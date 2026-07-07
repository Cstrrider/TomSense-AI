# TomSense sandbox — runs the coding agent's file ops + shell commands in an
# isolated container, well away from the backend. Joins the tomsense network
# so the backend reaches it by hostname; it is NOT published to the host.

# Docker CLI is staged here for `docker compose config` syntax validation
# only — the sandbox has NO docker socket and cannot start/stop containers.
# The agent uses it to verify compose files it authors at project mounts
# before telling the user to `docker compose up -d` on the host.
FROM docker:27-cli AS docker-cli

FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    git ripgrep curl ca-certificates nodejs npm build-essential \
    default-jdk-headless openssl unzip \
    pandoc \
    universal-ctags \
    gosu \
    && rm -rf /var/lib/apt/lists/*

# Docker CLI + compose plugin — validation only, no daemon access.
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins

# Garmin Connect IQ SDK lives here when the user installs it. We can't bake
# it into the image (requires accepting Garmin's developer agreement at
# download time), so we just stage the directory and add it to PATH. To
# activate: drop the unzipped SDK into /workspace/.connectiq-sdk/ then
# symlink:  ln -s /workspace/.connectiq-sdk/bin/* /usr/local/bin/  (or call
# tools by their absolute path).
ENV CONNECTIQ_SDK=/workspace/.connectiq-sdk
ENV PATH="/workspace/.connectiq-sdk/bin:${PATH}"

RUN pip install --no-cache-dir fastapi==0.136.1 uvicorn==0.47.0

# pyright — real type diagnostics for Python (the `diagnostics` tool). TS/JS
# uses each project's own node_modules/.bin/tsc; Go uses `go vet`.
RUN npm install -g pyright@1.1.390 && rm -rf /root/.npm

# Non-root: the agent's bash runs as this user, never as root.
RUN useradd -m -u 1000 coder \
    && mkdir -p /workspace \
    && chown coder:coder /workspace

COPY app.py /srv/app.py
COPY entrypoint.sh /srv/entrypoint.sh
RUN chmod +x /srv/entrypoint.sh

# Container starts as root so entrypoint can rmdir stale mount points; the
# entrypoint then drops to `coder` via gosu before exec'ing uvicorn. The
# agent itself never runs as root — same security posture as before, just
# with a tiny root-owned cleanup step at startup.
WORKDIR /workspace
ENV PYTHONUNBUFFERED=1
EXPOSE 8000

ENTRYPOINT ["/srv/entrypoint.sh"]
CMD ["uvicorn", "app:app", "--app-dir", "/srv", "--host", "0.0.0.0", "--port", "8000"]
