# Docker CLI (client only — no daemon) for /me/mounts/apply which calls
# `docker compose ... up --force-recreate tomsense-sandbox` through the
# scoped tomsense-docker-proxy sidecar. Multi-stage copy keeps the runtime
# image lean (~20MB of binaries vs ~150MB if we apt-get installed docker.io).
FROM docker:27-cli AS docker-cli

FROM python:3.12-slim

WORKDIR /app

# System deps for Pillow + httpx[http2]
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Pull just the docker client binary + compose plugin from the docker:cli stage.
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV PYTHONUNBUFFERED=1
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--loop", "uvloop"]
