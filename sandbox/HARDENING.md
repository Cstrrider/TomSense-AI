# Sandbox hardening (#5)

`run_bash` (`/exec` in `app.py`) is an **unconfined shell** for the `coder`
user: the per-tool `_resolve` workspace jail only guards the `/fs/*` file
endpoints, not the shell. So the agent can read `/etc/passwd`, write anywhere
`coder` can (`/tmp`, `$HOME`), and reach the network (`curl`). The real boundary
is the container, not the app.

## Already in place
- **Non-root**: the agent runs as `coder` (uid 1000); the entrypoint drops via `gosu`.
- **Resource caps**: `mem 1G / cpus 2.0 / pids 512` (`deploy.resources.limits`).
- **No host port**: the sandbox is only reachable on the internal `aistack` network.
- **`no-new-privileges:true`** (applied in `docker-compose.yml`): blocks setuid-root
  privilege regain. Zero functional cost.

## Next tier — APPLIED 2026-06-04 (live in docker-compose.yml)
Verified: container boots, token matches backend, agent runs as `coder`,
rootfs read-only, and a full `npm run build` in Baby-Tracker passes under it.
This block is in the `tomsense-sandbox` service now (kept here as reference +
rollback target — remove the block and re-run the recreate to revert):

```yaml
    # Drop all caps, re-add only what the entrypoint needs:
    #   SETUID/SETGID → gosu drop to coder
    #   CHOWN/FOWNER/DAC_OVERRIDE → entrypoint chowns root-owned mount dirs
    cap_drop: ["ALL"]
    cap_add: ["SETUID", "SETGID", "CHOWN", "FOWNER", "DAC_OVERRIDE"]
    # Read-only root FS. Everything writable is redirected to /workspace (the
    # volume) or tmpfs. Without the cache redirects below, npm/pip/build break.
    read_only: true
    tmpfs:
      - /tmp:size=512m,mode=1777
      - /var/tmp:size=128m,mode=1777
      - /run:size=16m
      - /home/coder:size=128m,uid=1000,gid=1000,mode=0755
    environment:
      - SANDBOX_TOKEN=${SANDBOX_TOKEN:-tomsense-sandbox-dev}
      - NPM_CONFIG_CACHE=/workspace/.cache/npm
      - PIP_CACHE_DIR=/workspace/.cache/pip
      - HOME=/home/coder
      - PYTHONDONTWRITEBYTECODE=1
```

### Recreate + smoke-test
Run from the repo root (compose reads `.env` there — the `SANDBOX_TOKEN` must
match what the backend was started with, or the backend 401s on every sandbox
call). `--no-deps` keeps postgres/backend untouched.
```bash
cd <your tomsense-ai directory>
docker compose up -d --no-deps --force-recreate tomsense-sandbox
docker logs --tail 20 tomsense-sandbox          # must reach "Uvicorn running"
# token must still match the backend (else auth breaks):
[ "$(docker exec tomsense-sandbox printenv SANDBOX_TOKEN)" = \
  "$(docker exec tomsense-backend printenv SANDBOX_TOKEN)" ] && echo "token OK" || echo "TOKEN MISMATCH"
# shell still works, can't escalate, rootfs read-only:
docker exec tomsense-sandbox sh -c 'whoami; node -v; touch /etc/x 2>&1 || echo "rootfs read-only OK"'
# real build in a project mount still passes under the hardening:
docker exec tomsense-sandbox sh -c 'cd /workspace/projects/Baby-Tracker && npm run build 2>&1 | tail -3'
```
If the container fails to boot, the cap set is the usual culprit — re-add the
missing one (watch `docker logs` for the gosu/permission error). If a build
fails on a cache write, point that tool's cache into `/workspace/.cache/...`.
Roll back instantly by removing the hardening block and re-running the same
recreate command.

## Not done by design
- **Egress filtering.** Blocking outbound network would break `curl`, `pip
  install`, and `npm install`, which are advertised sandbox features. The
  correct version is a registry-only allow-list via an HTTP proxy — a separate
  piece of infra, out of scope here.
- **Per-session workspace isolation.** Today all code chats share one
  `/workspace`. True per-chat isolation is the prerequisite for parallel
  subagents (audit item #13) and is a larger change.
