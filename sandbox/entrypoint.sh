#!/bin/sh
# Sandbox entrypoint.
#
# (1) Run as root briefly to clean up empty mount-point directories left
#     over from project mounts that were removed in the UI. Docker creates
#     /workspace/projects/<name>/ in the named volume when a bind mount is
#     added; removing the bind in compose leaves an empty owner-root dir
#     behind. Live bind mounts (with real content) are not empty, so
#     find -empty skips them; a bind mount that happens to be empty on the
#     host also stays safe because rmdir on a mount point fails with EBUSY.
# (2) chown any mount-point top-level dir that isn't owned by coder. When a
#     bind mount points at a host path that didn't pre-exist, Docker creates
#     it root:root and the agent can't write to it (silent 500s in fs_write).
#     We only chown the directory itself, not its contents — existing repos
#     keep their original file ownership; freshly-empty mounts become writable.
# (3) Drop to the coder user (uid 1000) and exec the FastAPI shim — the
#     agent's `run_bash` and file ops run unprivileged, same as before.
set -e

if [ -d /workspace/projects ]; then
    find /workspace/projects -mindepth 1 -maxdepth 1 -type d -empty \
        -exec rmdir {} \; 2>/dev/null || true
    find /workspace/projects -mindepth 1 -maxdepth 1 -type d \
        ! -user coder -exec chown coder:coder {} \; 2>/dev/null || true
fi

exec gosu coder "$@"
