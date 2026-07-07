"""Narrow, whitelisted deploy capability for code mode.

TomSense can EDIT files in the sandbox but is intentionally docker-blind (no
DOCKER_HOST, no socket — verified), so it cannot ship its own changes. This
module gives the BACKEND a tightly-scoped way to deploy a SMALL whitelist of
projects: rebuild the project (in the sandbox, via /exec) and restart its host
container (via the scoped docker-proxy, which only needs ALLOW_RESTARTS — not
START/BUILD/EXEC).

Safety rests on three independent layers, not on the proxy alone:
  1. The sandbox cannot reach host docker at all, so the ONLY path from TomSense
     to a restart is this tool's dispatch.
  2. DEPLOY_TARGETS is a fixed server-side whitelist — the model picks a key,
     never a container name or a command.
  3. chat.py requires an explicit, MANDATORY per-deploy approval before this
     runs — independent of the diff-approval ("review edits") setting — and a
     no-response ABORTS (a deploy is higher-consequence than an edit).
"""
import asyncio

from .tools_code import _call  # POSTs to the sandbox /exec endpoint

# key -> deploy recipe, loaded from the user's deploy-targets.json (see
# deploy_targets.py). `build` runs in the SANDBOX at `cwd`; `container` is
# the HOST container restarted via the scoped docker-proxy after a
# successful build. Re-exported here so chat.py keeps one import site.
from .deploy_targets import DEPLOY_TARGETS, target_keys  # noqa: F401


def deploy_plan(name: str) -> str:
    """Human-readable plan rendered in the approval card."""
    t = DEPLOY_TARGETS[name]
    return (
        f"{t['label']}\n"
        f"\n"
        f"Step 1 — build (in sandbox):\n"
        f"  cd {t['cwd']} && {t['build']}\n"
        f"Step 2 — restart host container:\n"
        f"  docker restart {t['container']}\n"
    )


async def _build(name: str) -> tuple[bool, str]:
    t = DEPLOY_TARGETS[name]
    cmd = f"cd {t['cwd']} && {t['build']}"
    try:
        data = await _call("/exec", {"command": cmd, "timeout": int(t.get("build_timeout", 300))})
    except Exception as e:
        return False, f"build call to sandbox failed: {e}"
    if data.get("timed_out"):
        return False, "build TIMED OUT"
    tail = ((data.get("stdout") or "") + ("\n" + data["stderr"] if data.get("stderr") else "")).strip()
    return data.get("exit_code") == 0, tail[-3000:]


async def _restart(name: str) -> tuple[bool, str]:
    container = DEPLOY_TARGETS[name]["container"]
    try:
        proc = await asyncio.create_subprocess_exec(
            "docker", "restart", container,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        )
        out_b, _ = await asyncio.wait_for(proc.communicate(), timeout=90)
    except asyncio.TimeoutError:
        return False, "docker restart timed out after 90s"
    except Exception as e:
        return False, f"docker restart failed to launch: {e}"
    return proc.returncode == 0, (out_b or b"").decode(errors="replace").strip()[-1500:]


async def deploy(name: str) -> tuple[bool, str]:
    """Build, then (only on success) restart. Returns (ok, log)."""
    if name not in DEPLOY_TARGETS:
        return False, f"Unknown deploy target '{name}'. Allowed: {target_keys()}."
    ok, build_log = await _build(name)
    if not ok:
        return False, f"Build FAILED — container NOT restarted (old build still serving).\n{build_log}"
    ok, restart_log = await _restart(name)
    if not ok:
        return False, f"Build succeeded but RESTART FAILED:\n{restart_log}"
    return True, f"✓ Deployed '{name}': rebuilt and restarted.\n{restart_log}".strip()
