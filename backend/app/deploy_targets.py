"""Deploy-target whitelist, loaded from user config.

Targets live in `deploy-targets.json` next to docker-compose.yml (mounted
into the backend at /host-compose). Each entry maps a key the model may
pick to a fixed recipe — the model NEVER supplies container names or
commands, only a key from this file. No file = deploying is disabled and
the deploy_project tool is not offered to the model at all.

Example deploy-targets.json:

    {
      "my-blog": {
        "label": "Blog — Next.js (http://<host>:3000)",
        "cwd": "/workspace/projects/my-blog",
        "build": "npm run build",
        "build_timeout": 420,
        "container": "my-blog"
      }
    }

`cwd`/`build` run inside the SANDBOX via /exec; `container` is restarted on
the host through the scoped docker-proxy (ALLOW_RESTARTS only). See
deploy.py for the safety model. Kept import-light: this module is imported
by both deploy.py and tools_code.py, so it must not import either.
"""

import json
import os

CONFIG_PATH = os.getenv("DEPLOY_TARGETS_PATH", "/host-compose/deploy-targets.json")

_REQUIRED = ("label", "cwd", "build", "container")


def _load() -> dict[str, dict]:
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        print(f"[deploy_targets] ignoring unreadable {CONFIG_PATH}: {e}")
        return {}
    if not isinstance(raw, dict):
        return {}
    out: dict[str, dict] = {}
    for key, t in raw.items():
        if not isinstance(t, dict) or not all(t.get(k) for k in _REQUIRED):
            print(f"[deploy_targets] skipping malformed target {key!r} "
                  f"(needs {', '.join(_REQUIRED)})")
            continue
        t.setdefault("build_timeout", 420)
        out[str(key)] = t
    return out


# Loaded once at import — edit deploy-targets.json + restart the backend to
# change the whitelist (deliberate: a static whitelist is the safety property).
DEPLOY_TARGETS: dict[str, dict] = _load()


def target_keys() -> str:
    return ", ".join(sorted(DEPLOY_TARGETS)) or "(none configured)"
