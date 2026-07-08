"""Coding-agent tools — file operations and shell execution, proxied to the
tomsense-sandbox container over the aistack network.

These are plain server-side tools (entries in DISPATCH, not client tools).
They are only *offered* to code-mode chats — see chat.py's `code_mode` — so a
normal chat's model never sees `run_bash`. The actual file ops + bash run
inside the sandbox container, never in the backend.
"""

import contextvars
import shlex

import httpx

from .config import settings

# Per-request secret vault, set by run_chat for the current user. Values are
# injected into every /exec shell env (so the model uses $NAME, never the
# plaintext) and redacted out of command output before it reaches the model.
# A ContextVar keeps it task-local — no threading it through every signature.
_secret_env: contextvars.ContextVar[dict] = contextvars.ContextVar("secret_env", default={})


def set_secret_env(env: dict | None) -> None:
    _secret_env.set(dict(env or {}))


def get_secret_env() -> dict:
    return _secret_env.get()


def redact_secrets(text: str) -> str:
    """Replace any secret VALUE with «SECRET:NAME» so it can't leak into the
    model context / transcript / chips, even if a command echoes it."""
    if not text:
        return text
    for name, val in get_secret_env().items():
        if val and len(val) >= 4:
            text = text.replace(val, f"«SECRET:{name}»")
    return text

# Read timeout outlives the longest a sandbox command may run.
_TIMEOUT = httpx.Timeout(
    connect=5.0,
    read=settings.sandbox_exec_timeout + 30.0,
    write=30.0,
    pool=5.0,
)


def _headers() -> dict:
    h = {"Content-Type": "application/json"}
    if settings.sandbox_token:
        h["X-Sandbox-Token"] = settings.sandbox_token
    return h


async def _call(path: str, payload: dict) -> dict:
    """POST to the sandbox shim; raise RuntimeError with the shim's detail
    message on any 4xx/5xx so the tool wrappers can relay it to the model.

    For /exec, the user's decrypted secrets are injected as shell env (so the
    model can reference $NAME) and any secret value is redacted out of the
    returned stdout/stderr before it can reach the model."""
    if path == "/exec":
        env = get_secret_env()
        if env:
            payload = {**payload, "env": {**env, **(payload.get("env") or {})}}
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        r = await client.post(
            f"{settings.sandbox_url}{path}", json=payload, headers=_headers()
        )
    if r.status_code >= 400:
        try:
            detail = r.json().get("detail")
        except Exception:
            detail = (r.text or "")[:300]
        raise RuntimeError(redact_secrets(detail or f"sandbox returned {r.status_code}"))
    data = r.json()
    if path == "/exec":
        for k in ("stdout", "stderr", "output"):
            if isinstance(data.get(k), str):
                data[k] = redact_secrets(data[k])
    return data


# ─────────────────────────────────────────────────────────────────────────────
# Tool implementations
# ─────────────────────────────────────────────────────────────────────────────

async def tool_list_dir(args: dict) -> str:
    path = (args.get("path") or ".").strip() or "."
    try:
        data = await _call("/fs/list", {"path": path})
    except Exception as e:
        return f"Error listing {path}: {e}"
    entries = data.get("entries") or []
    if not entries:
        return f"{path}: (empty directory)"
    header = "(workspace root)" if path in (".", "") else f"{path}/"
    lines = [header]
    for e in entries:
        if e["type"] == "dir":
            lines.append(f"  {e['name']}/")
        else:
            lines.append(f"  {e['name']}  ({e['size']} bytes)")
    return "\n".join(lines)


async def tool_read_file(args: dict) -> str:
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    payload: dict = {"path": path}
    if isinstance(args.get("offset"), int):
        payload["offset"] = args["offset"]
    if isinstance(args.get("limit"), int):
        payload["limit"] = args["limit"]
    try:
        data = await _call("/fs/read", payload)
    except Exception as e:
        return f"Error reading {path}: {e}"
    total = data.get("total_lines", 0)
    offset = data.get("offset", 0)
    returned = data.get("returned_lines", 0)
    header = f"{path} — {total} lines"
    if data.get("truncated"):
        header += f" (showing lines {offset + 1}-{offset + returned}; " \
                  "pass offset/limit to read more)"
    content = data.get("content") or ""
    # cat -n-style line numbers: anchors edits/patches and lets the model cite
    # file:line (pairs with find_symbol/diagnostics output). The `NNN→` prefix
    # is PRESENTATION ONLY — the system prompt tells the model to never copy
    # it into edit_file old_string/new_string or apply_patch hunks.
    numbered = "".join(
        f"{offset + i + 1:>5}→{line}"
        for i, line in enumerate(content.splitlines(keepends=True))
    )
    return header + "\n" + numbered


async def tool_write_file(args: dict) -> str:
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    content = args.get("content")
    if content is None:
        return "Error: content is required"
    try:
        data = await _call("/fs/write", {"path": path, "content": str(content)})
    except Exception as e:
        return f"Error writing {path}: {e}"
    if data.get("created", True):
        msg = f"Wrote new file {path} ({data.get('bytes', 0)} bytes)."
    else:
        ow = data.get("overwrote_lines")
        msg = (f"OVERWROTE existing {path} — replaced its entire contents "
               f"({ow} lines) with {data.get('bytes', 0)} bytes. Check the diff "
               "below to confirm you didn't drop anything you meant to keep:")
    diff = data.get("diff")
    if diff and diff.strip():
        msg += "\n\n```diff\n" + diff.rstrip() + "\n```"
    return msg


async def tool_edit_file(args: dict) -> str:
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    old = args.get("old_string")
    new = args.get("new_string")
    if old is None or new is None:
        return "Error: old_string and new_string are required"
    try:
        data = await _call("/fs/edit", {
            "path": path,
            "old_string": str(old),
            "new_string": str(new),
            "replace_all": bool(args.get("replace_all", False)),
        })
    except Exception as e:
        return f"Error editing {path}: {e}"
    n = data.get("replacements", 1)
    note = " via whitespace-flexible match" if data.get("match_mode") == "whitespace-flexible" else ""
    msg = f"Edited {path} ({n} replacement{'' if n == 1 else 's'}{note})."
    diff = data.get("diff")
    if diff and diff.strip():
        msg += "\n\n```diff\n" + diff.rstrip() + "\n```"
    return msg


async def tool_grep(args: dict) -> str:
    pattern = (args.get("pattern") or "").strip()
    if not pattern:
        return "Error: pattern is required"
    path = (args.get("path") or ".").strip() or "."
    payload: dict = {"pattern": pattern, "path": path}
    if args.get("ignore_case"):
        payload["ignore_case"] = True
    if isinstance(args.get("context"), int) and args["context"] > 0:
        payload["context"] = args["context"]
    if args.get("glob"):
        payload["glob"] = str(args["glob"])
    try:
        data = await _call("/fs/grep", payload)
    except Exception as e:
        return f"Error searching for {pattern!r}: {e}"
    matches = data.get("matches") or ""
    if not matches.strip():
        return f"No matches for {pattern!r} in {path}."
    out = matches.rstrip()
    if data.get("truncated"):
        out += "\n… (results truncated)"
    return out


async def tool_run_bash(args: dict) -> str:
    command = (args.get("command") or "").strip()
    if not command:
        return "Error: command is required"
    payload: dict = {"command": command}
    cwd = (args.get("cwd") or "").strip()
    if cwd:
        payload["cwd"] = cwd
    timeout = args.get("timeout")
    if isinstance(timeout, int) and timeout > 0:
        payload["timeout"] = min(timeout, 600)
    try:
        data = await _call("/exec", payload)
    except Exception as e:
        return f"Error running command: {e}"
    parts = []
    if (data.get("stdout") or "").strip():
        parts.append(data["stdout"].rstrip())
    if (data.get("stderr") or "").strip():
        parts.append("[stderr]\n" + data["stderr"].rstrip())
    body = "\n".join(parts) if parts else "(no output)"
    if data.get("timed_out"):
        status = "[TIMED OUT — process killed; any output captured before the timeout is below]"
    else:
        status = f"[exit {data.get('exit_code')}]"
    if data.get("truncated"):
        body += "\n… (output truncated)"
    return f"{status}\n{body}"


def _bg_line(d: dict) -> str:
    state = "running" if d.get("running") else f"exited {d.get('exit_code')}"
    return (f"{d.get('id')} ({d.get('name')}) — {state}, "
            f"up {d.get('uptime_s', 0)}s: {d.get('command', '')}")


async def tool_run_background(args: dict) -> str:
    command = (args.get("command") or "").strip()
    if not command:
        return "Error: command is required"
    payload: dict = {"command": command}
    if (args.get("name") or "").strip():
        payload["name"] = str(args["name"]).strip()
    if (args.get("cwd") or "").strip():
        payload["cwd"] = str(args["cwd"]).strip()
    try:
        d = await _call("/proc/start", payload)
    except Exception as e:
        return f"Error starting background process: {e}"
    tail = (d.get("log_tail") or "").strip()
    out = f"Started: {_bg_line(d)}"
    if not d.get("running"):
        out = f"⚠ Process exited immediately: {_bg_line(d)}"
    if tail:
        out += f"\n[output so far]\n{tail}"
    return out + ("\nIt keeps running between your tool calls — reach it at "
                  "localhost from run_bash, watch it with background_logs, and "
                  "background_stop it when done (auto-killed after 45 min).")


async def tool_background_logs(args: dict) -> str:
    payload: dict = {}
    if (args.get("id") or "").strip():
        payload["id"] = str(args["id"]).strip()
    if isinstance(args.get("tail_chars"), int):
        payload["tail_chars"] = args["tail_chars"]
    try:
        d = await _call("/proc/logs", payload)
    except Exception as e:
        return f"Error: {e}"
    if "processes" in d:
        procs = d["processes"]
        if not procs:
            return "No background processes."
        return "Background processes:\n" + "\n".join(_bg_line(p) for p in procs)
    return _bg_line(d) + "\n[log tail]\n" + (d.get("log_tail") or "(no output yet)")


async def tool_background_stop(args: dict) -> str:
    proc_id = (args.get("id") or "").strip()
    if not proc_id:
        return "Error: id is required (see background_logs for the list)"
    try:
        d = await _call("/proc/stop", {"id": proc_id})
    except Exception as e:
        return f"Error: {e}"
    return f"Stopped: {_bg_line(d)}"


async def tool_glob(args: dict) -> str:
    pattern = (args.get("pattern") or "").strip()
    if not pattern:
        return "Error: pattern is required"
    path = (args.get("path") or ".").strip() or "."
    try:
        data = await _call("/fs/glob", {"pattern": pattern, "path": path})
    except Exception as e:
        return f"Error globbing {pattern!r}: {e}"
    matches = data.get("matches") or []
    if not matches:
        return f"No files match {pattern!r} under {path}."
    out = "\n".join(matches)
    if data.get("truncated"):
        out += "\n… (more matches; narrow the pattern)"
    return out


async def tool_rename_file(args: dict) -> str:
    src = (args.get("src") or "").strip()
    dst = (args.get("dst") or "").strip()
    if not src or not dst:
        return "Error: src and dst are required"
    try:
        await _call("/fs/rename", {"src": src, "dst": dst})
    except Exception as e:
        return f"Error renaming {src} -> {dst}: {e}"
    return f"Renamed {src} -> {dst}."


async def tool_delete_file(args: dict) -> str:
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    try:
        await _call("/fs/delete", {"path": path, "recursive": bool(args.get("recursive", False))})
    except Exception as e:
        return f"Error deleting {path}: {e}"
    return f"Deleted {path}."


async def tool_make_dir(args: dict) -> str:
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    try:
        await _call("/fs/mkdir", {"path": path})
    except Exception as e:
        return f"Error creating directory {path}: {e}"
    return f"Created directory {path}."


_PLAN_GLYPH = {
    "done": "✓", "completed": "✓", "complete": "✓",
    "in_progress": "▸", "doing": "▸", "active": "▸",
    "pending": "○", "todo": "○", "": "○",
}


async def tool_update_plan(args: dict) -> str:
    """Render the model's task plan as a checklist. Pure bookkeeping — no
    sandbox call. The result is shown to the user as a chip and stays in the
    loop context, so the model can track multi-step work across rounds."""
    steps = args.get("steps")
    if not isinstance(steps, list) or not steps:
        return "Error: steps must be a non-empty list of {step, status}."
    lines = []
    for s in steps:
        if isinstance(s, str):
            text, status = s, "pending"
        elif isinstance(s, dict):
            text = (s.get("step") or s.get("content") or s.get("task") or "").strip()
            status = str(s.get("status") or "pending").strip().lower()
        else:
            continue
        if not text:
            continue
        lines.append(f"{_PLAN_GLYPH.get(status, '○')} {text}")
    if not lines:
        return "Error: no valid steps (each needs a 'step' and a 'status')."
    return "Plan:\n" + "\n".join(lines)


async def tool_apply_patch(args: dict) -> str:
    """Apply a context-based multi-file patch in one shot. This is the preferred
    way to make several edits at once — instead of N edit_file round-trips, the
    model emits one patch describing every change. Hunks are located by context
    (whitespace-tolerant), not line numbers, and the whole patch applies
    atomically."""
    patch = args.get("patch")
    if not patch or not str(patch).strip():
        return "Error: patch is required (V4A format — see the tool description)."
    try:
        data = await _call("/fs/apply_patch", {"patch": str(patch)})
    except Exception as e:
        return f"Error applying patch: {e}"
    files = data.get("files") or []
    if not files:
        return "Patch parsed but changed nothing."
    parts = [f"Applied patch to {len(files)} file(s):"]
    for f in files:
        line = f"{f.get('action', 'update')}: {f['path']}"
        if f.get("moved_to"):
            line += f" → {f['moved_to']}"
        parts.append(line)
        diff = f.get("diff")
        if diff and diff.strip():
            parts.append("```diff\n" + diff.rstrip() + "\n```")
    return "\n".join(parts)


# Edit tools that the diff-approval gate intercepts. Each can be run in
# dry-run mode (compute the diff, don't write) so the user can approve first.
EDIT_TOOL_NAMES = frozenset({"write_file", "edit_file", "apply_patch"})


async def preview_edit(name: str, args: dict) -> tuple[str | None, str | None, list[str]]:
    """Dry-run an edit tool: return (combined_diff, error, paths) WITHOUT
    writing. diff=None+error=None means there's nothing to preview (skip the
    gate). A non-None error means the edit itself would fail — also skip the
    gate so the real call surfaces the error to the model."""
    args = args or {}
    try:
        if name == "write_file":
            path = (args.get("path") or "").strip()
            if not path or args.get("content") is None:
                return None, None, []
            data = await _call("/fs/write", {"path": path, "content": str(args["content"]), "dry_run": True})
            return data.get("diff"), None, [path]
        if name == "edit_file":
            path = (args.get("path") or "").strip()
            if not path or args.get("old_string") is None or args.get("new_string") is None:
                return None, None, []
            data = await _call("/fs/edit", {
                "path": path, "old_string": str(args["old_string"]),
                "new_string": str(args["new_string"]),
                "replace_all": bool(args.get("replace_all", False)), "dry_run": True,
            })
            return data.get("diff"), None, [path]
        if name == "apply_patch":
            patch = args.get("patch")
            if not patch or not str(patch).strip():
                return None, None, []
            data = await _call("/fs/apply_patch", {"patch": str(patch), "dry_run": True})
            files = data.get("files") or []
            paths = [f["path"] for f in files]
            parts = []
            for f in files:
                hdr = f"{f.get('action', 'update')}: {f['path']}"
                if f.get("moved_to"):
                    hdr += f" → {f['moved_to']}"
                parts.append(hdr + "\n" + (f.get("diff") or ""))
            return "\n".join(parts), None, paths
    except Exception as e:
        return None, str(e), []
    return None, None, []


# git ops we expose. Anything mutating-and-dangerous (push, reset --hard,
# clean -f, rebase) is deliberately NOT here — those stay opt-in via run_bash
# so the model can't casually nuke uncommitted work or publish to a remote.
_GIT_BUILDERS = {
    "status":   lambda a: ["status", "--short", "--branch"],
    "diff":     lambda a: ["diff"] + (["--staged"] if a.get("staged") else [])
                          + (["--", str(a["path"])] if a.get("path") else []),
    "log":      lambda a: ["log", "--oneline", "-n",
                           str(min(int(a.get("n", 15) or 15), 100))],
    "branch":   lambda a: ["branch", "-vv"],
    "show":     lambda a: ["show", "--stat", str(a.get("ref") or "HEAD")],
    "add":      lambda a: ["add", "--"] + _git_paths(a),
    "commit":   lambda a: ["commit", "-m", str(a.get("message") or "")],
    "checkout": lambda a: ["checkout"] + (["-b"] if a.get("create") else [])
                          + [str(a.get("branch") or "")],
}


def _git_paths(a: dict) -> list[str]:
    p = a.get("paths") or a.get("path") or ["."]
    if isinstance(p, str):
        p = [p]
    return [str(x) for x in p]


async def tool_git(args: dict) -> str:
    """Structured git, scoped to a project dir. Keeps the model from forgetting
    `git -C`, wandering into raw shell, or running destructive ops by mistake.
    Falls through to run_bash output formatting."""
    op = (args.get("op") or "").strip().lower()
    builder = _GIT_BUILDERS.get(op)
    if not builder:
        return (f"Error: unknown git op {op!r}. Supported: "
                f"{', '.join(sorted(_GIT_BUILDERS))}. For anything else "
                "(push, reset, rebase, …) use run_bash explicitly.")
    if op == "commit" and not str(args.get("message") or "").strip():
        return "Error: commit requires a message."
    if op == "checkout" and not str(args.get("branch") or "").strip():
        return "Error: checkout requires a branch name."
    cwd = (args.get("cwd") or ".").strip() or "."
    try:
        argv = builder(args)
    except (ValueError, TypeError) as e:
        return f"Error building git command: {e}"
    cmd = f"git -C {shlex.quote(cwd)} " + " ".join(shlex.quote(a) for a in argv)
    try:
        data = await _call("/exec", {"command": cmd, "timeout": 120})
    except Exception as e:
        return f"Error running git {op}: {e}"
    parts = []
    if (data.get("stdout") or "").strip():
        parts.append(data["stdout"].rstrip())
    if (data.get("stderr") or "").strip():
        parts.append("[stderr]\n" + data["stderr"].rstrip())
    body = "\n".join(parts) if parts else "(no output)"
    exit_code = data.get("exit_code")
    if exit_code not in (0, None):
        return f"git {op} failed [exit {exit_code}]:\n{body}"
    if data.get("truncated"):
        body += "\n… (output truncated)"
    return f"git {op} [exit {exit_code}]\n{body}"


def _fmt_symbol(s: dict) -> str:
    """One symbol as a single line: path:line  kind name(signature)  [scope]."""
    head = f"{s['path']}:{s['line']}"
    label = f"{s.get('kind', '')} {s['name']}".strip()
    sig = s.get("signature") or ""
    scope = f"  [{s['scope']}]" if s.get("scope") else ""
    return f"{head}  {label}{sig}{scope}"


def _diagnostics_cmd(path: str) -> str:
    """One bash command that detects the project type around `path` and runs the
    matching checker: tsc (TS/JS), pyright (Python), or `go vet` (Go). Prints
    `DIAG_SKIP <reason>` (exit 0) when nothing is checkable."""
    p = shlex.quote(path)
    return (
        "cd /workspace; p=" + p + "; "
        'if [ -d "$p" ]; then start="$p"; else start=$(dirname "$p"); fi; '
        # Walk up for the nearest tsconfig.json / go.mod.
        'd="$start"; tsroot=""; goroot=""; '
        'while [ -n "$d" ] && [ "$d" != "." ] && [ "$d" != "/" ]; do '
        '  [ -z "$tsroot" ] && [ -f "$d/tsconfig.json" ] && tsroot="$d"; '
        '  [ -z "$goroot" ] && [ -f "$d/go.mod" ] && goroot="$d"; '
        '  d=$(dirname "$d"); done; '
        'ext="${p##*.}"; '
        'if [ -n "$tsroot" ] && { [ "$ext" = ts ] || [ "$ext" = tsx ] || [ "$ext" = js ] || [ "$ext" = jsx ] || [ "$ext" = mjs ] || [ "$ext" = cjs ] || [ -d "$p" ]; }; then '
        '  if [ -x "$tsroot/node_modules/.bin/tsc" ]; then '
        '    echo "# tsc --noEmit  ($tsroot)"; cd "$tsroot" && ./node_modules/.bin/tsc --noEmit --pretty false; '
        '  else echo "DIAG_SKIP tsc-not-installed-in-project"; fi; '
        'elif [ "$ext" = py ] || ls "$p"/*.py >/dev/null 2>&1; then '
        '  if command -v pyright >/dev/null 2>&1; then echo "# pyright  ($p)"; pyright --outputjson "$p" 2>/dev/null; '
        '  else echo "# python -m py_compile"; python -m py_compile "$p" 2>&1 || python -m compileall -q "$p"; fi; '
        'elif [ -n "$goroot" ]; then echo "# go vet  ($goroot)"; cd "$goroot" && go vet ./...; '
        'else echo "DIAG_SKIP no-checker-for-path"; fi'
    )


def _format_pyright_json(raw: str) -> str | None:
    """If pyright produced --outputjson, render its diagnostics as
    file:line:col severity: message lines. Returns None if not JSON."""
    import json as _json
    raw = raw.strip()
    start = raw.find("{")
    if start < 0:
        return None
    try:
        obj = _json.loads(raw[start:])
    except ValueError:
        return None
    diags = obj.get("generalDiagnostics") or []
    if not diags:
        return "No diagnostics (clean)."
    lines = []
    for d in diags:
        f = (d.get("file") or "").replace("/workspace/", "")
        rng = (d.get("range") or {}).get("start") or {}
        ln = (rng.get("line", 0) or 0) + 1
        col = (rng.get("character", 0) or 0) + 1
        sev = d.get("severity", "error")
        rule = f" [{d['rule']}]" if d.get("rule") else ""
        lines.append(f"{f}:{ln}:{col}  {sev}: {d.get('message', '').splitlines()[0]}{rule}")
    summ = obj.get("summary") or {}
    head = f"{summ.get('errorCount', 0)} error(s), {summ.get('warningCount', 0)} warning(s)"
    return head + ":\n" + "\n".join(lines[:100])


async def tool_diagnostics(args: dict) -> str:
    """Run the project's type-checker / linter on a path and report errors with
    file:line — on-demand 'what's broken and where', without waiting for the
    post-edit verify pass."""
    path = (args.get("path") or ".").strip() or "."
    try:
        data = await _call("/exec", {"command": _diagnostics_cmd(path), "timeout": 180})
    except Exception as e:
        return f"Error running diagnostics on {path}: {e}"
    out = (data.get("stdout") or "").strip()
    err = (data.get("stderr") or "").strip()
    if "DIAG_SKIP" in out:
        reason = out.split("DIAG_SKIP", 1)[1].strip() or "no checker"
        return (f"No diagnostics provider for {path} ({reason}). Supported: "
                "TypeScript/JS (tsc), Python (pyright), Go (go vet).")
    # Split off the leading "# tool (root)" header we echo.
    header = ""
    body = out
    if out.startswith("#"):
        header, _, body = out.partition("\n")
        header = header.lstrip("# ").strip()
    pretty = _format_pyright_json(body)
    if pretty is not None:
        body = pretty
    combined = (body + ("\n" + err if err else "")).strip()
    exit_code = data.get("exit_code")
    label = header or "diagnostics"
    if data.get("timed_out"):
        return f"Diagnostics ({label}) timed out on {path} — try a narrower path."
    if exit_code == 0 and (not combined or "No diagnostics" in combined):
        return f"✓ {label}: no problems found in {path}."
    if len(combined) > 6000:
        combined = "… (truncated)\n" + combined[-6000:]
    return f"{label} — problems in {path}:\n{combined}"


async def tool_find_symbol(args: dict) -> str:
    """Locate where a symbol (function/class/method/…) is DEFINED, across a
    subtree — go-to-definition without grepping and reading whole files."""
    name = (args.get("name") or "").strip()
    if not name:
        return "Error: name is required"
    payload: dict = {"name": name, "path": (args.get("path") or ".").strip() or "."}
    if args.get("kind"):
        payload["kind"] = str(args["kind"]).strip()
    if isinstance(args.get("limit"), int) and args["limit"] > 0:
        payload["limit"] = min(args["limit"], 200)
    try:
        data = await _call("/code/find_symbol", payload)
    except Exception as e:
        return f"Error finding symbol {name!r}: {e}"
    syms = data.get("symbols") or []
    if not syms:
        return (f"No definition of {name!r} found in {payload['path']}. "
                "Try grep for usages, or check the spelling.")
    note = "" if data.get("match_mode") == "exact" else f" ({data['match_mode']} match)"
    lines = [f"{data.get('count', len(syms))} definition(s) for {name!r}{note}:"]
    lines += [_fmt_symbol(s) for s in syms]
    if data.get("truncated"):
        lines.append("… (more; narrow with kind= or a more specific name)")
    return "\n".join(lines)


async def tool_outline(args: dict) -> str:
    """Show a file's symbol outline (definitions in source order) so you can
    grasp its shape without reading every line."""
    path = (args.get("path") or "").strip()
    if not path:
        return "Error: path is required"
    try:
        data = await _call("/code/outline", {"path": path})
    except Exception as e:
        return f"Error outlining {path}: {e}"
    syms = data.get("symbols") or []
    if not syms:
        return (f"{path}: no symbols found (empty, unsupported language, or "
                "all top-level statements). Read the file directly.")
    lang = data.get("language") or ""
    header = f"{path} — {data.get('count', len(syms))} symbols" + (f" ({lang})" if lang else "")
    lines = [header]
    for s in syms:
        # Indent members (anything with a scope) under their container.
        indent = "  " if s.get("scope") else ""
        label = f"{s.get('kind', '')} {s['name']}".strip()
        sig = s.get("signature") or ""
        lines.append(f"{indent}L{s['line']}  {label}{sig}")
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────────────────────
# Specs + dispatch
# ─────────────────────────────────────────────────────────────────────────────

CODE_TOOL_SPECS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "list_dir",
            "description": (
                "List files and subdirectories at a path inside the workspace. "
                "Use '.' for the workspace root. Call this first to orient yourself."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Workspace-relative path. Default '.'."},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": (
                "Read a text file from the workspace. Returns the content with a "
                "line count. For large files, pass offset (0-based line number) "
                "and limit to page through it."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Workspace-relative file path."},
                    "offset": {"type": "integer", "description": "First line to return (0-based)."},
                    "limit": {"type": "integer", "description": "Max lines to return."},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": (
                "Create a new file, or completely overwrite an existing one, with "
                "the given content. To change part of an existing file, prefer "
                "edit_file — write_file replaces the whole file."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Workspace-relative file path."},
                    "content": {"type": "string", "description": "Full file content."},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "edit_file",
            "description": (
                "Replace an exact string in an existing file. old_string must "
                "match the file exactly (including whitespace) and be unique — "
                "include enough surrounding context to disambiguate, or set "
                "replace_all to replace every occurrence."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Workspace-relative file path."},
                    "old_string": {"type": "string", "description": "Exact text to find."},
                    "new_string": {"type": "string", "description": "Text to replace it with."},
                    "replace_all": {"type": "boolean", "description": "Replace every occurrence."},
                },
                "required": ["path", "old_string", "new_string"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "grep",
            "description": (
                "Search file contents in the workspace with a regular expression "
                "(ripgrep). Returns matching lines with file paths and line "
                "numbers. node_modules/.next/.git/build are skipped automatically."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": {"type": "string", "description": "Regular expression to search for."},
                    "path": {"type": "string", "description": "Subtree to search. Default '.' (whole workspace)."},
                    "ignore_case": {"type": "boolean", "description": "Case-insensitive search."},
                    "context": {"type": "integer", "description": "Lines of context to show around each match (like grep -C, max 10)."},
                    "glob": {"type": "string", "description": "Restrict to matching files, e.g. '*.ts' or '!*.test.*' (ripgrep --glob)."},
                },
                "required": ["pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_bash",
            "description": (
                "Run a shell command in the workspace (bash). Use for git, "
                "installing dependencies, running builds and tests, etc. Returns "
                "stdout, stderr, and the exit code. Long output is truncated; "
                "default timeout is 120s. For dev servers / watchers use "
                "run_background instead — `&` here orphans the process."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command to run."},
                    "cwd": {"type": "string", "description": "Working directory, e.g. 'projects/kitchensync'. Default: workspace root."},
                    "timeout": {"type": "integer", "description": "Timeout in seconds (max 600)."},
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_background",
            "description": (
                "Start a LONG-RUNNING process (dev server, watcher, long build) "
                "that keeps running between your tool calls — unlike run_bash, "
                "which kills its process when the call returns. The server is "
                "reachable from run_bash at localhost:<port> (same container). "
                "Watch output with background_logs; stop with background_stop. "
                "Max 4 running; auto-killed after 45 minutes."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Command to run, e.g. 'npm run dev'."},
                    "name": {"type": "string", "description": "Short label, e.g. 'dev-server'."},
                    "cwd": {"type": "string", "description": "Working directory, e.g. 'projects/kitchensync/frontend'."},
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "background_logs",
            "description": (
                "Recent output + status of a background process started with "
                "run_background. Without an id, lists all background processes."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "Process id from run_background (e.g. 'bg1'). Omit to list all."},
                    "tail_chars": {"type": "integer", "description": "How much recent output to return (default 4000)."},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "background_stop",
            "description": "Stop a background process started with run_background.",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "Process id (e.g. 'bg1')."},
                },
                "required": ["id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "glob",
            "description": (
                "Find files by name/path pattern (e.g. '**/*.tsx', "
                "'src/**/route.ts'). Recursive '**' supported. Use this to "
                "locate files instead of `run_bash find`. Skips "
                "node_modules/.next/.git/build."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": {"type": "string", "description": "Glob pattern, e.g. '**/*.ts'."},
                    "path": {"type": "string", "description": "Base directory. Default '.' (whole workspace)."},
                },
                "required": ["pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "rename_file",
            "description": "Rename or move a file/directory within the workspace.",
            "parameters": {
                "type": "object",
                "properties": {
                    "src": {"type": "string", "description": "Existing path."},
                    "dst": {"type": "string", "description": "New path (must not already exist)."},
                },
                "required": ["src", "dst"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_file",
            "description": (
                "Delete a file or directory in the workspace. Non-empty "
                "directories require recursive=true."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path to delete."},
                    "recursive": {"type": "boolean", "description": "Required to delete a non-empty directory."},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "make_dir",
            "description": "Create a directory (and any missing parents) in the workspace. Idempotent.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory path to create."},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_plan",
            "description": (
                "Record or update your task plan as a checklist. Call this at "
                "the START of any multi-step task, then again each time a step's "
                "status changes. It's shown to the user and keeps you on track. "
                "Statuses: pending, in_progress, done."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "steps": {
                        "type": "array",
                        "description": "Ordered list of plan steps.",
                        "items": {
                            "type": "object",
                            "properties": {
                                "step": {"type": "string", "description": "What the step does."},
                                "status": {"type": "string", "enum": ["pending", "in_progress", "done"]},
                            },
                            "required": ["step", "status"],
                        },
                    },
                },
                "required": ["steps"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "apply_patch",
            "description": (
                "Apply a multi-file, multi-hunk patch in ONE call — the "
                "preferred way to make several edits at once instead of many "
                "edit_file calls. Hunks are matched by CONTEXT, not line "
                "numbers, so you never count lines. The whole patch applies "
                "atomically (all or nothing).\n"
                "Format:\n"
                "*** Begin Patch\n"
                "*** Update File: path/to/file.ts\n"
                "@@ optional section label\n"
                " unchanged context line\n"
                "-line to remove\n"
                "+line to add\n"
                "*** Add File: path/to/new.ts\n"
                "+first line of the new file\n"
                "+second line\n"
                "*** Delete File: path/to/old.ts\n"
                "*** End Patch\n"
                "Rules: prefix every line — ' ' (space) for context, '-' to "
                "remove, '+' to add. Include 2-3 unchanged context lines around "
                "each change so the hunk can be located. For a rename, put "
                "'*** Move to: new/path' right under '*** Update File:'. Paths "
                "are workspace-relative."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "patch": {"type": "string", "description": "The full patch text in the format above."},
                },
                "required": ["patch"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "git",
            "description": (
                "Run a git operation scoped to a project directory (no need to "
                "remember `git -C`). Supported ops: status, diff, log, branch, "
                "show, add, commit, checkout. Destructive/remote ops (push, "
                "reset, rebase, clean) are intentionally not available here — "
                "use run_bash for those. Do NOT commit unless the user asked."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "op": {"type": "string", "enum": ["status", "diff", "log", "branch", "show", "add", "commit", "checkout"]},
                    "cwd": {"type": "string", "description": "Project dir to run in, e.g. 'projects/baby-tracker'. Default '.'."},
                    "message": {"type": "string", "description": "Commit message (op=commit)."},
                    "paths": {"type": "array", "items": {"type": "string"}, "description": "Files to stage (op=add). Default ['.']."},
                    "branch": {"type": "string", "description": "Branch name (op=checkout)."},
                    "create": {"type": "boolean", "description": "Create the branch (op=checkout, i.e. -b)."},
                    "staged": {"type": "boolean", "description": "Show staged diff (op=diff)."},
                    "path": {"type": "string", "description": "Limit diff to a path (op=diff)."},
                    "ref": {"type": "string", "description": "Commit/ref to show (op=show). Default HEAD."},
                    "n": {"type": "integer", "description": "Number of commits (op=log). Default 15, max 100."},
                },
                "required": ["op"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "diagnostics",
            "description": (
                "Run the project's type-checker / linter and report problems with "
                "file:line — go-to-error without waiting for the automatic post-"
                "edit check. Auto-detects: TypeScript/JS → tsc --noEmit, Python → "
                "pyright, Go → go vet. Pass a file to focus, or a directory / '.' "
                "for the whole project. Use it after editing to confirm the code "
                "type-checks, or to find what's broken before you start."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File or directory to check. Default '.' (nearest project)."},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_symbol",
            "description": (
                "Find WHERE a symbol is defined (function, class, method, type, "
                "constant) across a directory tree — go-to-definition without "
                "grepping and reading whole files. Returns each definition's "
                "file:line, kind, and signature. Use this to locate code instead "
                "of reading files hunting for a definition. Matches exact name "
                "first, then case-insensitive, then substring."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Symbol name to locate, e.g. 'createBaby' or 'BabyCard'."},
                    "path": {"type": "string", "description": "Subtree to search. Default '.' (whole workspace). Narrow to a project for speed, e.g. 'projects/baby-tracker'."},
                    "kind": {"type": "string", "description": "Optional filter: function, class, method, variable, etc."},
                    "limit": {"type": "integer", "description": "Max results (default 50, max 200)."},
                },
                "required": ["name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "outline",
            "description": (
                "Show a single file's symbol outline — every function/class/"
                "method definition in source order with line numbers and "
                "signatures — so you understand its structure WITHOUT reading "
                "the whole file. Then read_file with offset/limit to pull just "
                "the region you need."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Workspace-relative file path."},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "spawn_subagent",
            "description": (
                "Delegate a focused, self-contained subtask to a fresh agent "
                "that works in the same workspace with the same tools, then "
                "reports back. Use it to parallelize/offload well-scoped work — "
                "e.g. 'implement the DELETE /api/babies/[id] route and its "
                "handler', 'add unit tests for utils/date.ts', 'investigate why "
                "the build fails and summarize the cause'. The subagent has NO "
                "memory of this conversation, so write a COMPLETE, standalone "
                "task: what to do, which files/dirs, and what 'done' looks like. "
                "It returns a summary of what it changed/found. Don't use it for "
                "trivial one-step edits you can just do yourself."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "task": {"type": "string", "description": "Complete, standalone instructions for the subtask (include file paths + acceptance criteria)."},
                },
                "required": ["task"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ask_user",
            "description": (
                "Ask the user a question and STOP — use this when you genuinely "
                "need a decision or clarification before continuing: an ambiguous "
                "requirement, a real fork in the approach, or a destructive/"
                "irreversible action to confirm. Provide 2-4 concrete options when "
                "the answer is a choice. This ENDS your turn; the user's reply (a "
                "tapped option or free text) arrives as the next message and you "
                "continue from there with full context. Use sparingly — do NOT ask "
                "about things you can reasonably decide yourself or discover with a "
                "tool (read a file, grep, check the project docs first)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "question": {"type": "string", "description": "The single, focused question to ask."},
                    "options": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "2-4 short, concrete choices, when the answer is a selection.",
                    },
                },
                "required": ["question"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "deploy_project",
            "description": (
                "Ship your changes: rebuild a project and restart its running "
                "container so your edits go live. You edit files in the sandbox, "
                "but the running app serves a pre-built bundle — so after making "
                "changes, call this to actually deploy them. EVERY deploy requires "
                "the user's explicit approval (they see the plan and tap "
                "Approve/Reject) — this is ALWAYS on, regardless of other settings, "
                "and a no-response cancels the deploy. Only whitelisted projects "
                "(from the server's deploy-targets.json) can be deployed. Build "
                "failures cancel the restart and report the error, so the old "
                "build keeps serving."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "project": {
                        "type": "string",
                        "description": "Whitelisted project key to deploy.",
                        # enum filled in dynamically below from deploy_targets.json
                    },
                },
                "required": ["project"],
            },
        },
    },
    # search_docs is dispatched through the regular DISPATCH table (defined
    # in tools.py); we only re-expose its spec to the code-mode tool list so
    # the agent knows it can call it. Critical for niche-language work where
    # the model's training-data knowledge has gaps (Monkey C, Solidity, etc.)
    # — call this first against uploaded SDK docs before generating code.
    {
        "type": "function",
        "function": {
            "name": "search_docs",
            "description": (
                "Semantic search over the user's uploaded text/PDF documents "
                "(API references, SDK docs, manuals). Call this BEFORE writing "
                "code in any language you don't fully know — the user may have "
                "uploaded the reference for it. Returns top-k chunks with "
                "filenames and similarity scores."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Question or API name to retrieve relevant chunks for."},
                    "k": {"type": "integer", "description": "Number of chunks to retrieve (1-10, default 5)."},
                },
                "required": ["query"],
            },
        },
    },
    # web_search / fetch_page — same re-exposure pattern as search_docs (they
    # dispatch through tools.py). Real docs lookup beats guessing URLs with
    # curl: search for the upstream doc, then fetch the winning page.
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": (
                "Web search (SearXNG). Use to find current docs, error messages, "
                "library versions, or upstream READMEs — instead of guessing "
                "URLs for curl. Follow up with fetch_page on the best result."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "3-8 word query."},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_page",
            "description": (
                "Fetch a web page and return its readable text. Use after "
                "web_search, or on a known docs URL."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "Full URL."},
                },
                "required": ["url"],
            },
        },
    },
]

# deploy_project is only offered when the user configured targets in
# deploy-targets.json — an empty whitelist means the tool doesn't exist as
# far as the model is concerned. When present, the enum pins the model to
# the configured keys.
from .deploy_targets import DEPLOY_TARGETS as _DEPLOY_TARGETS  # noqa: E402

if _DEPLOY_TARGETS:
    for _spec in CODE_TOOL_SPECS:
        if _spec["function"]["name"] == "deploy_project":
            _p = _spec["function"]["parameters"]["properties"]["project"]
            _p["enum"] = sorted(_DEPLOY_TARGETS)
            _p["description"] = (
                "Whitelisted project key to deploy. One of: "
                + ", ".join(sorted(_DEPLOY_TARGETS)) + "."
            )
else:
    CODE_TOOL_SPECS = [s for s in CODE_TOOL_SPECS
                       if s["function"]["name"] != "deploy_project"]

CODE_DISPATCH = {
    "list_dir":    tool_list_dir,
    "read_file":   tool_read_file,
    "write_file":  tool_write_file,
    "edit_file":   tool_edit_file,
    "grep":        tool_grep,
    "glob":        tool_glob,
    "run_bash":    tool_run_bash,
    "rename_file": tool_rename_file,
    "delete_file": tool_delete_file,
    "make_dir":    tool_make_dir,
    "update_plan": tool_update_plan,
    "apply_patch": tool_apply_patch,
    "git":         tool_git,
    "find_symbol": tool_find_symbol,
    "outline":     tool_outline,
    "diagnostics": tool_diagnostics,
    "run_background":  tool_run_background,
    "background_logs": tool_background_logs,
    "background_stop": tool_background_stop,
}
