"""TomSense sandbox executor.

A tiny HTTP shim around file operations and shell execution, all confined to
/workspace. The TomSense backend calls this over the aistack network when a
code-mode chat invokes a coding tool — the model's bash never touches the
backend container.

Spike-grade isolation: a single shared workspace, no per-call workspace
separation, and only an optional shared-token check. The real protection is
the container itself (non-root, resource-capped, not host-published). Do not
expose this to untrusted users as-is.
"""

import asyncio
import difflib
import json
import os
import shutil
import signal
import tempfile
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

WORKSPACE = "/workspace"
TOKEN = os.getenv("SANDBOX_TOKEN", "")
MAX_OUTPUT = 40_000  # chars — clip tool output so a runaway command can't flood
MAX_DIFF = 6_000     # chars — cap the diff we hand back after a write/edit

app = FastAPI()


def _check(token: str) -> None:
    if TOKEN and token != TOKEN:
        raise HTTPException(status_code=401, detail="bad sandbox token")


def _clip_headtail(s: str) -> tuple[str, bool]:
    """Clip to MAX_OUTPUT but keep BOTH ends. Command output puts the thing
    that matters (the error summary, the test failure count, the build's final
    verdict) at the *end* — a head-only clip throws exactly that away and hands
    the model a passing-looking log. Keep 60% head + 40% tail."""
    if len(s) <= MAX_OUTPUT:
        return s, False
    head = (MAX_OUTPUT * 6) // 10
    tail = MAX_OUTPUT - head
    dropped = len(s) - MAX_OUTPUT
    return s[:head] + f"\n… [{dropped} chars truncated] …\n" + s[-tail:], True


def _make_diff(old: str, new: str, path: str) -> str:
    """Unified diff old→new, capped. So the model SEES what its write/edit did
    and can catch a wrong-region replacement without a follow-up read_file."""
    diff = "".join(difflib.unified_diff(
        old.splitlines(keepends=True),
        new.splitlines(keepends=True),
        fromfile=f"a/{path}", tofile=f"b/{path}", n=3,
    ))
    if len(diff) > MAX_DIFF:
        diff = diff[:MAX_DIFF] + "\n… [diff truncated] …"
    return diff


def _atomic_write(full: str, content: str) -> None:
    """Write via a temp file in the same dir + os.replace, so a kill/timeout
    mid-write can never leave a truncated or partially-written file. The old
    open(.., 'w') truncated the target immediately — a crash there destroyed
    the original with nothing written yet."""
    d = os.path.dirname(full) or WORKSPACE
    os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".tmp-write-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        os.replace(tmp, full)  # atomic on the same filesystem
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _flexible_spans(text: str, old: str) -> list[tuple[int, int]]:
    """Whitespace-tolerant fallback for edit_file: find contiguous line blocks
    in `text` whose lines equal `old`'s lines after stripping leading/trailing
    whitespace. Returns (start,end) char offsets. This is what lets an edit
    survive a one-space/tab-vs-spaces/trailing-whitespace mismatch instead of
    failing with a flat 'not found' — the single biggest edit-loop friction
    vs. Claude Code, which matches whitespace-flexibly."""
    file_lines = text.splitlines(keepends=True)
    old_lines = old.splitlines()
    while old_lines and old_lines[0].strip() == "":
        old_lines.pop(0)
    while old_lines and old_lines[-1].strip() == "":
        old_lines.pop()
    if not old_lines:
        return []
    n = len(old_lines)
    target = [ln.strip() for ln in old_lines]
    offsets = [0]
    for ln in file_lines:
        offsets.append(offsets[-1] + len(ln))
    spans: list[tuple[int, int]] = []
    i = 0
    while i <= len(file_lines) - n:
        if [file_lines[i + k].strip() for k in range(n)] == target:
            spans.append((offsets[i], offsets[i + n]))
            i += n
        else:
            i += 1
    return spans


def _resolve(path: str) -> str:
    """Resolve a path under /workspace, accepting either a workspace-relative
    form (`projects/foo`) or an absolute form already rooted at the workspace
    (`/workspace/projects/foo`). Anything that resolves outside /workspace
    (via .., a different absolute root, or a symlink) is refused — same as
    before. The dual form matters because the system prompt frames mount
    points as `/workspace/projects/<name>/` and models faithfully send the
    full path; silently rerooting it landed writes in the named volume
    instead of the bind mount (file vanished from the host)."""
    raw = (path or "").strip()
    if raw == WORKSPACE or raw.startswith(WORKSPACE + os.sep):
        rel = raw[len(WORKSPACE):].lstrip("/")
    else:
        rel = raw.lstrip("/")
    full = os.path.realpath(os.path.join(WORKSPACE, rel))
    if full != WORKSPACE and not full.startswith(WORKSPACE + os.sep):
        raise HTTPException(status_code=400, detail=f"path escapes workspace: {path}")
    return full


def _clip(s: str) -> tuple[str, bool]:
    if len(s) > MAX_OUTPUT:
        return s[:MAX_OUTPUT], True
    return s, False


class ExecBody(BaseModel):
    command: str
    timeout: int = 120
    # Extra env vars merged over the process environment. Used by the backend to
    # inject the user's decrypted secret-vault values (keyed by name) so a
    # command can reference $NAME without the plaintext ever touching the model.
    env: dict[str, str] | None = None


class ListBody(BaseModel):
    path: str = "."


class ReadBody(BaseModel):
    path: str
    offset: int = 0
    limit: int = 600


class WriteBody(BaseModel):
    path: str
    content: str
    dry_run: bool = False   # compute the diff but don't write (for edit approval)


class EditBody(BaseModel):
    path: str
    old_string: str
    new_string: str
    replace_all: bool = False
    dry_run: bool = False


class GrepBody(BaseModel):
    pattern: str
    path: str = "."
    ignore_case: bool = False
    context: int = 0           # lines of context around each match (rg -C)
    glob: str | None = None    # rg --glob filter, e.g. "*.ts" or "!*.test.*"


class GlobBody(BaseModel):
    pattern: str               # e.g. "**/*.ts" or "src/**/route.ts"
    path: str = "."            # base directory to glob from


class RenameBody(BaseModel):
    src: str
    dst: str


class DeleteBody(BaseModel):
    path: str
    recursive: bool = False


class MkdirBody(BaseModel):
    path: str


class ApplyPatchBody(BaseModel):
    patch: str
    dry_run: bool = False


class SymbolFindBody(BaseModel):
    name: str
    path: str = "."
    kind: str | None = None
    limit: int = 50


class OutlineBody(BaseModel):
    path: str


@app.get("/healthz")
async def healthz():
    return {"ok": True}


@app.post("/exec")
async def exec_cmd(body: ExecBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    timeout = max(1, min(body.timeout, 600))
    # Backend-injected secret-vault values are merged over the base environment
    # (str values only). The command references them as $NAME; the plaintext is
    # never sent by the model and is redacted from output on the backend side.
    proc_env = dict(os.environ)
    if body.env:
        proc_env.update({str(k): str(v) for k, v in body.env.items()})
    # Run under bash (not /bin/sh → dash), so the bashisms the system prompt
    # advertises — [[ ]], arrays, `set -o pipefail`, process substitution —
    # actually work instead of silently misbehaving.
    proc = await asyncio.create_subprocess_exec(
        "/bin/bash", "-c", body.command,
        cwd=WORKSPACE,
        env=proc_env,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        start_new_session=True,  # own process group, so a timeout kills the tree
    )

    # Drain stdout/stderr into buffers concurrently. The old code used
    # communicate() inside wait_for — on timeout that coroutine is cancelled
    # and ALL output produced before the hang is lost, so the model got
    # "(no output)" exactly when the partial logs would explain the hang.
    # Buffering as it streams means a timeout still returns what was printed.
    out_buf, err_buf = bytearray(), bytearray()

    async def _drain(stream, buf):
        try:
            while True:
                chunk = await stream.read(65536)
                if not chunk:
                    break
                buf.extend(chunk)
        except asyncio.CancelledError:
            pass

    t_out = asyncio.create_task(_drain(proc.stdout, out_buf))
    t_err = asyncio.create_task(_drain(proc.stderr, err_buf))
    timed_out = False
    try:
        await asyncio.wait_for(asyncio.gather(t_out, t_err), timeout=timeout)
        rc = await proc.wait()
    except asyncio.TimeoutError:
        timed_out = True
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except ProcessLookupError:
            pass
        t_out.cancel()
        t_err.cancel()
        rc = await proc.wait()
    stdout, t1 = _clip_headtail(bytes(out_buf).decode("utf-8", "replace"))
    stderr, t2 = _clip_headtail(bytes(err_buf).decode("utf-8", "replace"))
    return {
        "stdout": stdout,
        "stderr": stderr,
        "exit_code": rc,
        "timed_out": timed_out,
        "truncated": t1 or t2,
    }


@app.post("/fs/list")
async def fs_list(body: ListBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if not os.path.exists(full):
        raise HTTPException(status_code=404, detail=f"not found: {body.path}")
    if not os.path.isdir(full):
        raise HTTPException(status_code=400, detail=f"not a directory: {body.path}")
    entries = []
    for name in sorted(os.listdir(full)):
        fp = os.path.join(full, name)
        is_dir = os.path.isdir(fp)
        entries.append({
            "name": name,
            "type": "dir" if is_dir else "file",
            "size": os.path.getsize(fp) if (not is_dir and os.path.isfile(fp)) else 0,
        })
    return {"path": body.path, "entries": entries}


@app.post("/fs/read")
async def fs_read(body: ReadBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if not os.path.isfile(full):
        raise HTTPException(status_code=404, detail=f"not a file: {body.path}")
    try:
        with open(full, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError as e:
        raise HTTPException(status_code=400, detail=f"cannot read {body.path}: {e}")
    total = len(lines)
    offset = max(0, body.offset)
    limit = max(1, min(body.limit, 2000))
    chunk = lines[offset:offset + limit]
    content, clipped = _clip("".join(chunk))
    return {
        "path": body.path,
        "content": content,
        "total_lines": total,
        "offset": offset,
        "returned_lines": len(chunk),
        "truncated": clipped or (offset + len(chunk) < total),
    }


@app.post("/fs/write")
async def fs_write(body: WriteBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    full = _resolve(body.path)
    # Surface filesystem errors with actionable detail. Without this the
    # bare exception becomes a generic 500 "Internal Server Error", which
    # sends the model into a tailspin diagnosing the wrong cause (it
    # invents permission theories, path theories, etc., for what is just
    # "EACCES: this dir is owned by root, can't write here").
    existed = os.path.isfile(full)
    old_text = ""
    if existed:
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as f:
                old_text = f.read()
        except OSError:
            old_text = ""
    # Preview: compute the diff for approval without touching disk.
    if body.dry_run:
        return {
            "ok": True, "path": body.path, "dry_run": True,
            "bytes": len(body.content.encode("utf-8")), "created": not existed,
            "diff": _make_diff(old_text, body.content, body.path),
        }
    try:
        _atomic_write(full, body.content)
    except PermissionError as e:
        raise HTTPException(status_code=403, detail=f"permission denied: {e}")
    except OSError as e:
        raise HTTPException(status_code=400, detail=f"write failed: {e}")
    resp = {
        "ok": True,
        "path": body.path,
        "bytes": len(body.content.encode("utf-8")),
        "created": not existed,
    }
    if existed:
        # write_file replaces the WHOLE file. Surface that it clobbered an
        # existing file and exactly what changed, so a model rewriting a
        # multi-export file can SEE it dropped a handler instead of finding
        # out from a 405 later. (No silent whole-file overwrite.)
        resp["overwrote_lines"] = old_text.count("\n") + (1 if old_text and not old_text.endswith("\n") else 0)
        resp["diff"] = _make_diff(old_text, body.content, body.path)
    return resp


@app.post("/fs/edit")
async def fs_edit(body: EditBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if not os.path.isfile(full):
        raise HTTPException(status_code=404, detail=f"not a file: {body.path}")
    with open(full, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    old, new = body.old_string, body.new_string
    if old == new:
        raise HTTPException(
            status_code=400,
            detail="old_string and new_string are identical — nothing to change",
        )
    count = text.count(old)
    match_mode = "exact"
    if count >= 1:
        if count > 1 and not body.replace_all:
            raise HTTPException(
                status_code=400,
                detail=f"old_string is not unique ({count} matches) — "
                       "add surrounding context or set replace_all",
            )
        new_text = text.replace(old, new)
        replacements = count
    else:
        # Exact match failed — try a whitespace-flexible line-block match
        # before giving up, so a trivial indentation/trailing-space drift
        # doesn't dead-end the edit.
        spans = _flexible_spans(text, old)
        if not spans:
            raise HTTPException(
                status_code=400,
                detail="old_string not found in file (tried exact and "
                       "whitespace-flexible matching)",
            )
        if len(spans) > 1 and not body.replace_all:
            raise HTTPException(
                status_code=400,
                detail=f"old_string matches {len(spans)} places "
                       "(whitespace-flexible) — add surrounding context or "
                       "set replace_all",
            )
        match_mode = "whitespace-flexible"
        new_text = text
        for (s, e) in reversed(spans):  # back-to-front keeps earlier offsets valid
            new_text = new_text[:s] + new + new_text[e:]
        replacements = len(spans)
    diff = _make_diff(text, new_text, body.path)
    if body.dry_run:
        return {"ok": True, "path": body.path, "dry_run": True,
                "replacements": replacements, "match_mode": match_mode, "diff": diff}
    _atomic_write(full, new_text)
    return {
        "ok": True,
        "path": body.path,
        "replacements": replacements,
        "match_mode": match_mode,
        "diff": diff,
    }


@app.post("/fs/grep")
async def fs_grep(body: GrepBody, x_sandbox_token: str = Header(default="")):
    _check(x_sandbox_token)
    full = _resolve(body.path)
    args = ["rg", "--line-number", "--no-heading", "--color", "never",
            "--max-count", "200"]
    if body.ignore_case:
        args.append("-i")
    if body.context and body.context > 0:
        args += ["-C", str(min(body.context, 10))]
    # Skip the usual noise so a repo-wide search isn't drowned in vendored
    # code (rg already honors .gitignore, but a non-ignored node_modules used
    # to flood results).
    for noise in ("node_modules", ".next", ".git", "dist", "build", "__pycache__"):
        args += ["--glob", f"!**/{noise}/**"]
    if body.glob:
        args += ["--glob", body.glob]
    args += ["--", body.pattern, full]
    proc = await asyncio.create_subprocess_exec(
        *args, cwd=WORKSPACE,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    out, err = await proc.communicate()
    # rg exit 1 == no matches (not an error); >1 is a real failure.
    if proc.returncode and proc.returncode > 1:
        raise HTTPException(
            status_code=400,
            detail=err.decode("utf-8", "replace")[:300] or "grep failed",
        )
    text = out.decode("utf-8", "replace").replace(WORKSPACE + "/", "")
    # rg's --max-count is PER FILE; also cap total lines so a hot pattern
    # across many files can't flood.
    lines = text.splitlines()
    total_capped = len(lines) > 400
    if total_capped:
        lines = lines[:400]
    matches, clipped = _clip("\n".join(lines))
    return {"pattern": body.pattern, "matches": matches,
            "truncated": clipped or total_capped}


@app.post("/fs/glob")
async def fs_glob(body: GlobBody, x_sandbox_token: str = Header(default="")):
    """Find files by name/path pattern (e.g. `**/*.tsx`, `src/**/route.ts`).
    Recursive `**` supported. Skips vendored/build noise; caps results."""
    _check(x_sandbox_token)
    base = _resolve(body.path)
    if not os.path.isdir(base):
        raise HTTPException(status_code=404, detail=f"not a directory: {body.path}")
    NOISE = {"node_modules", ".next", ".git", "__pycache__", "dist", "build", ".venv"}
    CAP = 300
    results: list[str] = []
    try:
        for p in sorted(Path(base).glob(body.pattern)):
            if not p.is_file():
                continue
            if set(p.relative_to(base).parts) & NOISE:
                continue
            results.append(str(p).replace(WORKSPACE + "/", ""))
            if len(results) >= CAP:
                break
    except (ValueError, OSError) as e:
        raise HTTPException(status_code=400, detail=f"glob failed: {e}")
    return {"pattern": body.pattern, "matches": results,
            "truncated": len(results) >= CAP}


@app.post("/fs/rename")
async def fs_rename(body: RenameBody, x_sandbox_token: str = Header(default="")):
    """Rename / move a file or directory inside the workspace."""
    _check(x_sandbox_token)
    src = _resolve(body.src)
    dst = _resolve(body.dst)
    if not os.path.exists(src):
        raise HTTPException(status_code=404, detail=f"not found: {body.src}")
    if os.path.exists(dst):
        raise HTTPException(status_code=409, detail=f"destination exists: {body.dst}")
    os.makedirs(os.path.dirname(dst) or WORKSPACE, exist_ok=True)
    os.rename(src, dst)
    return {"ok": True, "src": body.src, "dst": body.dst}


@app.post("/fs/delete")
async def fs_delete(body: DeleteBody, x_sandbox_token: str = Header(default="")):
    """Delete a file or directory. Non-empty dirs require `recursive: true`
    to avoid an accidental wipe."""
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if full == WORKSPACE:
        raise HTTPException(status_code=400, detail="cannot delete workspace root")
    if not os.path.exists(full):
        raise HTTPException(status_code=404, detail=f"not found: {body.path}")
    if os.path.isdir(full):
        if body.recursive:
            shutil.rmtree(full)
        else:
            try:
                os.rmdir(full)
            except OSError:
                raise HTTPException(
                    status_code=400,
                    detail="directory not empty — pass recursive=true to force",
                )
    else:
        os.remove(full)
    return {"ok": True, "path": body.path}


@app.post("/fs/mkdir")
async def fs_mkdir(body: MkdirBody, x_sandbox_token: str = Header(default="")):
    """Create a directory (and any missing parents). Idempotent."""
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if os.path.exists(full) and not os.path.isdir(full):
        raise HTTPException(status_code=409, detail=f"exists and is not a dir: {body.path}")
    os.makedirs(full, exist_ok=True)
    return {"ok": True, "path": body.path}


# ─────────────────────────────────────────────────────────────────────────────
# apply_patch — context-based (V4A-style) multi-file patching
# ─────────────────────────────────────────────────────────────────────────────
#
# A single tool that applies an entire multi-file, multi-hunk change atomically.
# Unlike a classic unified diff it does NOT trust line numbers — each hunk is
# located by its surrounding context lines (whitespace-tolerant, reusing the
# same forgiveness as edit_file). That's what makes it usable by a model that
# can't count lines reliably. Format:
#
#   *** Begin Patch
#   *** Update File: path/to/file
#   @@ optional section header (ignored for matching)
#    unchanged context line
#   -removed line
#   +added line
#   *** Add File: path/to/new
#   +first line of new file
#   *** Delete File: path/to/old
#   *** End Patch
#
# Move/rename: put `*** Move to: new/path` on the line after `*** Update File:`.

def _split_patch_sections(patch: str) -> list[dict]:
    """Parse a V4A-style patch into [{action, path, lines, move_to}]. Tolerant
    of missing Begin/End sentinels and of stray git-diff noise lines."""
    sections: list[dict] = []
    cur: dict | None = None
    for raw in patch.splitlines():
        st = raw.strip()
        if st in ("*** Begin Patch", "*** End Patch"):
            continue
        if st.startswith("*** Add File:"):
            if cur:
                sections.append(cur)
            cur = {"action": "add", "path": st[len("*** Add File:"):].strip(),
                   "lines": [], "move_to": None}
            continue
        if st.startswith("*** Update File:"):
            if cur:
                sections.append(cur)
            cur = {"action": "update", "path": st[len("*** Update File:"):].strip(),
                   "lines": [], "move_to": None}
            continue
        if st.startswith("*** Delete File:"):
            if cur:
                sections.append(cur)
            cur = {"action": "delete", "path": st[len("*** Delete File:"):].strip(),
                   "lines": [], "move_to": None}
            continue
        if st.startswith("*** Move to:") and cur:
            cur["move_to"] = st[len("*** Move to:"):].strip()
            continue
        # Skip git-diff cruft a model may emit out of habit; it carries no
        # information our context matcher needs.
        if st.startswith(("diff --git ", "index ", "new file mode",
                          "deleted file mode", "--- ", "+++ ")):
            continue
        if cur is not None:
            cur["lines"].append(raw)
    if cur:
        sections.append(cur)
    return sections


def _find_block(file_lines: list[str], block: list[str], start: int) -> int:
    """Find `block` in `file_lines` at or after `start` (wrapping to the top if
    not found ahead). Exact match first, then whitespace-flexible. Returns the
    start index, or -1."""
    n = len(block)
    if n == 0:
        return min(start, len(file_lines))
    end = len(file_lines) - n + 1
    if end <= 0:
        return -1
    order = list(range(start, end)) + list(range(0, min(start, end)))
    for i in order:                                   # exact
        if file_lines[i:i + n] == block:
            return i
    tb = [b.strip() for b in block]                   # whitespace-flexible
    for i in order:
        if [x.strip() for x in file_lines[i:i + n]] == tb:
            return i
    return -1


def _apply_update(text: str, payload_lines: list[str]) -> tuple[str, int]:
    """Apply the hunks of one Update-File section to `text`. Raises ValueError
    with the failing context if a hunk can't be located."""
    hunks: list[list[str]] = []
    cur: list[str] = []
    for ln in payload_lines:
        if ln.startswith("@@"):
            if cur:
                hunks.append(cur)
                cur = []
            continue
        cur.append(ln)
    if cur:
        hunks.append(cur)
    if not hunks or all(not h for h in hunks):
        raise ValueError("update section has no hunks")

    had_final_nl = text.endswith("\n")
    file_lines = text.split("\n")
    if had_final_nl:
        file_lines = file_lines[:-1]

    search = 0
    applied = 0
    for hunk in hunks:
        old_block: list[str] = []
        new_block: list[str] = []
        for ln in hunk:
            if ln == "":
                old_block.append("")
                new_block.append("")
                continue
            tag, rest = ln[0], ln[1:]
            if tag == " ":
                old_block.append(rest)
                new_block.append(rest)
            elif tag == "-":
                old_block.append(rest)
            elif tag == "+":
                new_block.append(rest)
            else:                      # unprefixed → treat as context (lenient)
                old_block.append(ln)
                new_block.append(ln)
        if not old_block and not new_block:
            continue
        idx = _find_block(file_lines, old_block, search)
        if idx < 0:
            ctx = "\n".join(old_block[:8]) or "(no context lines in hunk)"
            raise ValueError(
                "could not locate this hunk's context in the file — re-read the "
                "file and copy the surrounding lines exactly:\n" + ctx
            )
        file_lines[idx:idx + len(old_block)] = new_block
        search = idx + len(new_block)
        applied += 1

    out = "\n".join(file_lines)
    if had_final_nl and not out.endswith("\n"):
        out += "\n"
    return out, applied


def _read_text(full: str) -> str:
    with open(full, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


@app.post("/fs/apply_patch")
async def fs_apply_patch(body: ApplyPatchBody, x_sandbox_token: str = Header(default="")):
    """Apply a context-based multi-file patch atomically. Validates every
    section first (locating all hunks, checking add/delete preconditions) and
    only writes if the whole patch is applicable — so a bad hunk in file 3
    never leaves files 1-2 half-changed."""
    _check(x_sandbox_token)
    sections = _split_patch_sections(body.patch)
    if not sections:
        raise HTTPException(
            status_code=400,
            detail="empty patch: expected *** Add/Update/Delete File: sections",
        )

    # Phase 1 — stage & validate everything in memory (no writes yet).
    staged: list[dict] = []
    for sec in sections:
        action, path = sec["action"], sec["path"]
        if not path:
            raise HTTPException(status_code=400, detail=f"{action} section missing a file path")
        full = _resolve(path)
        if action == "add":
            if os.path.exists(full):
                raise HTTPException(status_code=409,
                                    detail=f"Add File: {path} already exists — use Update File")
            content = "\n".join(l[1:] if l.startswith("+") else l for l in sec["lines"])
            # Trim a single trailing blank artifact, then normalize to one final newline.
            content = content.rstrip("\n")
            if content:
                content += "\n"
            staged.append({"action": "add", "path": path, "full": full,
                           "old": "", "new": content, "move_full": None, "move_path": None})
        elif action == "delete":
            if not os.path.isfile(full):
                raise HTTPException(status_code=404, detail=f"Delete File: {path} not found")
            staged.append({"action": "delete", "path": path, "full": full,
                           "old": _read_text(full), "new": "", "move_full": None, "move_path": None})
        elif action == "update":
            if not os.path.isfile(full):
                raise HTTPException(status_code=404,
                                    detail=f"Update File: {path} not found — use Add File for new files")
            old = _read_text(full)
            try:
                new_text, _ = _apply_update(old, sec["lines"])
            except ValueError as e:
                raise HTTPException(status_code=400, detail=f"Update File: {path}: {e}")
            move_full = move_path = None
            if sec["move_to"]:
                move_path = sec["move_to"]
                move_full = _resolve(move_path)
                if os.path.exists(move_full):
                    raise HTTPException(status_code=409,
                                        detail=f"Move to: {move_path} already exists")
            staged.append({"action": "update", "path": path, "full": full, "old": old,
                           "new": new_text, "move_full": move_full, "move_path": move_path})

    # Preview: validation passed; return the per-file diffs without writing.
    if body.dry_run:
        preview = []
        for s in staged:
            tgt = s["move_path"] or s["path"]
            act = ("update+move" if s["move_full"] else s["action"])
            preview.append({"path": s["path"], "action": act,
                            "moved_to": s["move_path"],
                            "diff": _make_diff(s["old"], s["new"], tgt)})
        return {"ok": True, "dry_run": True, "files": preview}

    # Phase 2 — commit. Validation passed, so failures here are filesystem-level
    # (permissions, disk). Surface them as a clean 4xx with the offending path —
    # never a bare 500 that sends the model diagnosing the wrong cause — and note
    # which files already landed (the all-or-nothing guarantee is best-effort
    # past this point, since writes aren't transactional across files).
    files: list[dict] = []

    def _fail(path: str, err: Exception):
        done = ", ".join(f["path"] for f in files) or "none"
        status = 403 if isinstance(err, PermissionError) else 400
        raise HTTPException(
            status_code=status,
            detail=f"apply_patch failed writing {path}: {err}. "
                   f"Files already written this patch: {done}.",
        )

    for s in staged:
        try:
            if s["action"] == "delete":
                os.remove(s["full"])
                files.append({"path": s["path"], "action": "delete",
                              "diff": _make_diff(s["old"], "", s["path"])})
            elif s["action"] == "add":
                _atomic_write(s["full"], s["new"])
                files.append({"path": s["path"], "action": "add",
                              "diff": _make_diff("", s["new"], s["path"])})
            elif s["move_full"]:  # update + move/rename
                _atomic_write(s["move_full"], s["new"])
                os.remove(s["full"])
                files.append({"path": s["path"], "action": "update+move",
                              "moved_to": s["move_path"],
                              "diff": _make_diff(s["old"], s["new"], s["move_path"])})
            else:                 # update in place
                _atomic_write(s["full"], s["new"])
                files.append({"path": s["path"], "action": "update",
                              "diff": _make_diff(s["old"], s["new"], s["path"])})
        except (PermissionError, OSError) as e:
            _fail(s["path"], e)
    return {"ok": True, "files": files}


# ─────────────────────────────────────────────────────────────────────────────
# Symbol index — go-to-definition / file outline via universal-ctags
# ─────────────────────────────────────────────────────────────────────────────
#
# Lets the agent locate WHERE a function/class/method is defined, or see a
# file's structure, without grepping + reading whole files. Backed by ctags
# (computed fresh per call — no stale index — and fast because vendored/build
# dirs are excluded). Multi-language: ctags covers ~150 languages out of the box.

_CTAGS_EXCLUDES = ("node_modules", ".next", ".git", "dist", "build",
                   "__pycache__", ".venv", "vendor", ".turbo")


async def _run_ctags(target: str, recursive: bool) -> list[dict]:
    """Run ctags over `target`, return parsed tag dicts. Raises HTTPException
    with actionable detail on failure (binary missing, bad path)."""
    # ctags doesn't map .tsx/.jsx to its TS/JS parsers by default — without
    # these maps a React/Next project (all .tsx) yields ZERO symbols.
    args = ["ctags", "--output-format=json", "--fields=+nKzSl",
            "--map-TypeScript=+.tsx", "--map-JavaScript=+.jsx",
            "--map-JavaScript=+.mjs", "--map-JavaScript=+.cjs",
            "-f", "-"]
    if recursive:
        args.append("-R")
        for ex in _CTAGS_EXCLUDES:
            args.append(f"--exclude={ex}")
    args.append(target)
    try:
        proc = await asyncio.create_subprocess_exec(
            *args, cwd=WORKSPACE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        out, err = await asyncio.wait_for(proc.communicate(), timeout=60)
    except FileNotFoundError:
        raise HTTPException(status_code=501, detail="ctags is not installed in the sandbox")
    except asyncio.TimeoutError:
        raise HTTPException(status_code=400, detail="symbol indexing timed out (repo too large)")
    if proc.returncode and proc.returncode != 0:
        # ctags warns on unknown languages but still exits 0 usually; a real
        # non-zero is a genuine error worth surfacing.
        detail = err.decode("utf-8", "replace")[:300] or "ctags failed"
        raise HTTPException(status_code=400, detail=detail)
    tags: list[dict] = []
    for line in out.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        if obj.get("_type") != "tag":
            continue
        tags.append({
            "name": obj.get("name", ""),
            "kind": obj.get("kind", ""),
            "path": (obj.get("path") or "").replace(WORKSPACE + "/", ""),
            "line": obj.get("line", 0),
            "scope": obj.get("scope", ""),
            "scopeKind": obj.get("scopeKind", ""),
            "signature": obj.get("signature", ""),
            "language": obj.get("language", ""),
            "pattern": obj.get("pattern", ""),
        })
    return tags


# Kinds that are usually local noise in an outline (a `const data = …` inside a
# function body). We drop them UNLESS the pattern shows a function definition —
# that keeps nested arrow-fn handlers (`const fetchX = async () => …`) while
# dropping throwaway locals.
_OUTLINE_LOCAL_KINDS = {"constant", "variable", "local"}
_OUTLINE_DROP_KINDS = {"parameter", "label"}


def _outline_keep(t: dict) -> bool:
    if t["kind"] in _OUTLINE_DROP_KINDS:
        return False
    if t["kind"] in _OUTLINE_LOCAL_KINDS and t.get("scopeKind") in ("function", "method"):
        p = t.get("pattern", "")
        return "=>" in p or "function" in p
    return True


@app.post("/code/find_symbol")
async def code_find_symbol(body: SymbolFindBody, x_sandbox_token: str = Header(default="")):
    """Find where a symbol is defined across a subtree. Exact match first, then
    case-insensitive exact, then case-insensitive substring — so a slightly-off
    name still resolves. Optionally filter by kind (function/class/method/…)."""
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if not os.path.exists(full):
        raise HTTPException(status_code=404, detail=f"not found: {body.path}")
    name = (body.name or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name is required")
    tags = await _run_ctags(full, recursive=os.path.isdir(full))
    if body.kind:
        k = body.kind.strip().lower()
        tags = [t for t in tags if t["kind"].lower() == k]

    exact = [t for t in tags if t["name"] == name]
    if exact:
        hits, mode = exact, "exact"
    else:
        ci = [t for t in tags if t["name"].lower() == name.lower()]
        if ci:
            hits, mode = ci, "case-insensitive"
        else:
            sub = [t for t in tags if name.lower() in t["name"].lower()]
            hits, mode = sub, "substring"
    hits.sort(key=lambda t: (t["path"], t["line"]))
    truncated = len(hits) > body.limit
    return {"name": name, "match_mode": mode, "count": len(hits),
            "truncated": truncated, "symbols": hits[: body.limit]}


@app.post("/code/outline")
async def code_outline(body: OutlineBody, x_sandbox_token: str = Header(default="")):
    """Return a single file's symbol outline (definitions in source order) so
    the agent can grasp its structure without reading every line."""
    _check(x_sandbox_token)
    full = _resolve(body.path)
    if not os.path.isfile(full):
        raise HTTPException(status_code=404, detail=f"not a file: {body.path}")
    tags = await _run_ctags(full, recursive=False)
    lang = tags[0]["language"] if tags else ""
    tags = [t for t in tags if _outline_keep(t)]
    tags.sort(key=lambda t: t["line"])
    return {"path": body.path, "count": len(tags), "language": lang, "symbols": tags}
