"""Post-edit verification (#3).

After a code-mode turn finishes editing, run a project-appropriate check
(typecheck / syntax / build) on the files that changed and feed any failures
back into the loop, so the agent can't end a turn on a broken build — the
"compiles-but-actually-it-doesn't" gap that advisory hints kept missing.

Design:
- Detection is by changed-file extension + an upward walk to the project root,
  encoded as ONE bash command so a single /exec call yields the verdict.
- The command prints a `VERIFY_SKIP <reason>` marker (and exits 0) when there's
  nothing it can safely check — e.g. no tsconfig, or the type checker isn't
  installed. That's distinct from a clean pass, so "can't verify" never blocks.
- The caller (chat.run_chat) bounds how many times a failure forces another
  round, so a genuinely-stuck or pre-existing error can't loop forever.
"""

from __future__ import annotations

import os
from typing import Awaitable, Callable, Optional

_TS_EXT = {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"}
_VERIFY_TIMEOUT = 150  # seconds — tsc/build on a real project


def _q(s: str) -> str:
    """Single-quote a string for safe embedding in a bash command."""
    return "'" + s.replace("'", "'\\''") + "'"


def build_check_command(changed: list[str]) -> Optional[tuple[str, str]]:
    """(label, bash_command) for the most relevant check given the files changed
    this turn, or None if nothing is checkable. Commands assume cwd=/workspace.

    A `VERIFY_SKIP` line on stdout (with exit 0) means "couldn't check" — the
    caller treats that as neither pass nor fail.
    """
    ts = [f for f in changed if os.path.splitext(f)[1].lower() in _TS_EXT]
    py = [f for f in changed if f.lower().endswith(".py")]
    go = [f for f in changed if f.lower().endswith(".go")]

    if ts:
        f = ts[0]
        # Walk up to the nearest package.json; typecheck with the project's OWN
        # tsc (node_modules/.bin/tsc) so we never trigger an npx download and a
        # missing checker is a clean skip, not a false failure.
        cmd = (
            "cd /workspace && f=" + _q(f) + "; d=$(dirname \"$f\"); root=\"\"; "
            "while [ \"$d\" != \".\" ] && [ \"$d\" != \"/\" ]; do "
            "if [ -f \"$d/package.json\" ]; then root=\"$d\"; break; fi; "
            "d=$(dirname \"$d\"); done; "
            "if [ -z \"$root\" ]; then echo VERIFY_SKIP no-package-json; exit 0; fi; "
            "if [ ! -f \"$root/tsconfig.json\" ]; then echo VERIFY_SKIP no-tsconfig; exit 0; fi; "
            "if [ ! -x \"$root/node_modules/.bin/tsc\" ]; then echo VERIFY_SKIP tsc-not-installed; exit 0; fi; "
            "cd \"$root\" && ./node_modules/.bin/tsc --noEmit --pretty false"
        )
        return ("tsc --noEmit", cmd)

    if py:
        files = " ".join(_q(f) for f in py)
        # py_compile is always available and catches syntax/indentation errors.
        cmd = f"cd /workspace && python -m py_compile {files}"
        return ("python -m py_compile", cmd)

    if go:
        f = go[0]
        cmd = (
            "cd /workspace && f=" + _q(f) + "; d=$(dirname \"$f\"); root=\"\"; "
            "while [ \"$d\" != \".\" ] && [ \"$d\" != \"/\" ]; do "
            "if [ -f \"$d/go.mod\" ]; then root=\"$d\"; break; fi; "
            "d=$(dirname \"$d\"); done; "
            "if [ -z \"$root\" ]; then echo VERIFY_SKIP no-go-mod; exit 0; fi; "
            "cd \"$root\" && go build ./..."
        )
        return ("go build ./...", cmd)

    return None


async def run_verification(
    changed: list[str],
    exec_call: Callable[[dict], Awaitable[dict]],
) -> Optional[dict]:
    """Run the detected check via `exec_call` (an /exec POST). Returns:
        {"status": "pass"|"fail"|"skip", "label": str, "output": str}
    or None when there's nothing to check. Never raises — a sandbox error
    degrades to "skip" so verification can't break a chat.
    """
    detected = build_check_command(changed)
    if not detected:
        return None
    label, cmd = detected
    try:
        res = await exec_call({"command": cmd, "timeout": _VERIFY_TIMEOUT})
    except Exception:
        return {"status": "skip", "label": label, "output": ""}

    stdout = (res.get("stdout") or "")
    stderr = (res.get("stderr") or "")
    if "VERIFY_SKIP" in stdout:
        return {"status": "skip", "label": label, "output": ""}
    if res.get("timed_out"):
        # An inconclusive check shouldn't block; surface it as a skip.
        return {"status": "skip", "label": label, "output": "(check timed out)"}

    combined = (stdout + ("\n" + stderr if stderr.strip() else "")).strip()
    if (res.get("exit_code") or 0) == 0:
        return {"status": "pass", "label": label, "output": combined}
    # Keep the TAIL — compiler error summaries live at the end.
    if len(combined) > 4000:
        combined = "… (truncated)\n" + combined[-4000:]
    return {"status": "fail", "label": label, "output": combined}
