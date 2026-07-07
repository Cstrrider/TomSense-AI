"""Project-mount management.

The user manages a list of host directories that get bind-mounted into
`tomsense-sandbox` at `/workspace/projects/<name>/`, giving the code-mode
agent live read/write access to their real source trees.

Storage:
  - Mount config:        /host-compose/.mounts.json     (one source of truth)
  - Generated override:  /host-compose/docker-compose.override.yml
                         (auto-loaded by `docker compose` next time the sandbox
                          is recreated; backend regenerates on every config
                          change)

Apply path:
  - DOCKER_HOST points at tomsense-docker-proxy (scoped Docker API). Backend
    calls `docker compose ... up -d --no-deps --force-recreate tomsense-sandbox`
    via subprocess — proxy permits CONTAINERS / NETWORKS / VOLUMES / SERVICES
    so the recreate succeeds without giving away the rest of the API surface.

Security: host paths come from the authenticated user via the API. The
backend doesn't validate that they exist or are safe — a malicious user
behind CF Access could bind-mount any host directory (including /etc) into
the sandbox. Acceptable for the single-user spike target; revisit before
multi-tenant.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path

log = logging.getLogger("tomsense.mounts")


COMPOSE_DIR = Path("/host-compose")
CONFIG_PATH = COMPOSE_DIR / ".mounts.json"
OVERRIDE_PATH = COMPOSE_DIR / "docker-compose.override.yml"

# A fresh install starts with no project mounts — the user adds their own
# source trees via Settings → Files → Mounts (or by editing .mounts.json).
DEFAULT_MOUNTS: list[dict] = []


def _safe_name(name: str) -> str:
    """Project mount name must be a single path segment (used in the mount
    target path and the override filename). Forbid separators and shell
    metacharacters to keep the generated YAML predictable."""
    name = (name or "").strip()
    if not name:
        raise ValueError("name required")
    if any(c in name for c in "/\\\t\n\r \"'`$<>|;&"):
        raise ValueError("name must be a single path segment (alnum + - _ .)")
    if name.startswith("."):
        # Also excludes "." and ".." — dot-prefixed mounts (".git", ".env")
        # create hidden/confusing paths under /workspace/projects/.
        raise ValueError("name cannot start with '.'")
    if len(name) > 64:
        raise ValueError("name too long (max 64)")
    return name


def _safe_host_path(path: str) -> str:
    """Host paths are required to be absolute; we don't try to validate
    existence here because they only need to resolve on the docker daemon's
    host, not inside the backend container."""
    path = (path or "").strip()
    if not path.startswith("/"):
        raise ValueError("host_path must be absolute (start with /)")
    # Reject obvious nasties — a colon in a bind mount string is the field
    # separator in compose's short-form syntax.
    if ":" in path:
        raise ValueError("host_path must not contain ':'")
    return path


def load_config() -> list[dict]:
    """Read .mounts.json from /host-compose. Returns [] if missing.
    Seeds the file with DEFAULT_MOUNTS on first call so subsequent reads
    are deterministic."""
    try:
        if not CONFIG_PATH.exists():
            save_config(DEFAULT_MOUNTS)
            return list(DEFAULT_MOUNTS)
        raw = CONFIG_PATH.read_text(encoding="utf-8")
        data = json.loads(raw) if raw.strip() else []
        # save_config writes {"mounts": [...]} (dict form for future-proofing —
        # leaves room for extra top-level fields like default_branch, etc.).
        # Older versions may have written a bare list. Accept both.
        if isinstance(data, dict):
            data = data.get("mounts") or []
        if not isinstance(data, list):
            return []
        # Validate each entry; drop malformed ones rather than failing the read.
        out = []
        for e in data:
            if not isinstance(e, dict):
                continue
            try:
                out.append({
                    "name": _safe_name(e.get("name", "")),
                    "host_path": _safe_host_path(e.get("host_path", "")),
                })
            except ValueError:
                continue
        return out
    except Exception as e:
        log.warning("load_config failed: %s", e)
        return []


def save_config(mounts: list[dict]) -> None:
    """Write the canonical config + regenerate the override file in one shot.
    Atomic-ish: write to a temp file then rename."""
    COMPOSE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps({"mounts": mounts}, indent=2) + "\n", encoding="utf-8")
    tmp.replace(CONFIG_PATH)
    _write_override(mounts)


def _write_override(mounts: list[dict]) -> None:
    """Generate docker-compose.override.yml. Compose merges volume LISTS by
    concatenation (not replacement), so our additional bind mounts here
    join the named-volume entry in the base file rather than replacing it."""
    lines = [
        "# Auto-generated by tomsense-backend (backend/app/mounts.py).",
        "# Do NOT edit by hand — changes will be overwritten on next /me/mounts",
        "# call. Manage via Settings → Files → Mounts in the UI, or by",
        "# editing /host-compose/.mounts.json and re-applying.",
        "services:",
        "  tomsense-sandbox:",
        "    volumes:",
    ]
    for m in mounts:
        # `<host-path>:<container-path>:rw,z` short form. The `z` flag tells
        # Docker to relabel the host directory to `container_file_t` so the
        # SELinux-confined sandbox can actually read/write/chown it. Without
        # `z`, any freshly-created host dir keeps the default `user_home_t`
        # label and the container hits silent EACCES on every operation
        # (even container-root chown). Lowercase `z` = shared label, safe
        # for dirs other containers may also mount.
        lines.append(
            f"      - {m['host_path']}:/workspace/projects/{m['name']}:rw,z"
        )
    if not mounts:
        # Empty list still needs valid YAML — give an explicit empty list.
        lines[-1] = "    volumes: []"
    lines.append("")  # trailing newline
    tmp = OVERRIDE_PATH.with_suffix(".yml.tmp")
    tmp.write_text("\n".join(lines), encoding="utf-8")
    tmp.replace(OVERRIDE_PATH)


def add_mount(name: str, host_path: str) -> list[dict]:
    name = _safe_name(name)
    host_path = _safe_host_path(host_path)
    mounts = load_config()
    if any(m["name"] == name for m in mounts):
        raise ValueError(f"a mount named {name!r} already exists")
    if any(m["host_path"] == host_path for m in mounts):
        raise ValueError(f"host path {host_path!r} is already mounted")
    mounts.append({"name": name, "host_path": host_path})
    save_config(mounts)
    return mounts


def remove_mount(name: str) -> list[dict]:
    name = _safe_name(name)
    mounts = load_config()
    new = [m for m in mounts if m["name"] != name]
    if len(new) == len(mounts):
        raise KeyError(name)
    save_config(new)
    return new


def is_apply_supported() -> bool:
    """True when the backend has the docker CLI + a reachable DOCKER_HOST.
    When False, the UI surfaces the manual shell command instead."""
    if not os.getenv("DOCKER_HOST"):
        return False
    # Check `docker` binary is on PATH — added in the backend Dockerfile.
    from shutil import which
    return which("docker") is not None


async def apply() -> dict:
    """Recreate the sandbox container so it picks up the new mounts list.
    Returns {ok: bool, log: str} so the UI can show whatever the user needs
    to see (success message or the docker compose stderr)."""
    if not is_apply_supported():
        return {
            "ok": False,
            "log": (
                "Backend can't auto-apply (DOCKER_HOST unset or docker CLI "
                "missing). Run this on the host, from your tomsense-ai "
                "directory:\n\n"
                "  docker compose up -d --no-deps --force-recreate "
                "tomsense-sandbox"
            ),
        }
    cmd = [
        "docker", "compose",
        # Project name must match the existing containers' "com.docker.compose.project"
        # label — pinned by `name: tomsense` in docker-compose.yml. Without
        # --project-name, compose defaults to the project-directory basename
        # ("host-compose") and tries to spin up a fresh stack alongside,
        # hitting a name conflict.
        "--project-name", "tomsense",
        "--project-directory", "/host-compose",
        "-f", "/host-compose/docker-compose.yml",
        "-f", "/host-compose/docker-compose.override.yml",
        "up", "-d", "--no-deps", "--force-recreate", "tomsense-sandbox",
    ]
    log.info("mounts.apply: %s", " ".join(cmd))
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        out_bytes, _ = await asyncio.wait_for(proc.communicate(), timeout=120)
    except asyncio.TimeoutError:
        proc.kill()
        return {"ok": False, "log": "docker compose apply timed out after 120s"}
    out = out_bytes.decode("utf-8", "replace")
    ok = proc.returncode == 0
    if not ok and ("docker-proxy" in out.lower() or "cannot connect" in out.lower()):
        out += (
            "\n\nHint: auto-apply needs the `docker` compose profile "
            "(tomsense-docker-proxy) running. Either start it — "
            "docker compose --profile docker up -d — or apply manually: "
            "docker compose up -d --no-deps --force-recreate tomsense-sandbox"
        )
    return {"ok": ok, "log": out[-4000:]}  # tail in case compose was chatty
