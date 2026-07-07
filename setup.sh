#!/usr/bin/env bash
# TomSense setup — run once before `docker compose up`, from the repo root.
#
# Interactive: asks for your Cloudflare Workers AI account, owner email, and
# whether this instance is localhost-only or served on a domain. Generates
# strong random secrets. Writes everything to ./.env (which docker compose
# reads automatically). Idempotent — existing strong values are kept, and
# re-running lets you change the answers it asks for.
#
# Non-interactive (CI / scripted): pre-set the env vars and pass --yes:
#   CF_ACCOUNT_ID=… CF_API_TOKEN=… TOMSENSE_OWNER_EMAIL=… bash setup.sh --yes

set -euo pipefail

cd "$(dirname "$0")"
ENV_FILE=".env"
touch "$ENV_FILE"
ASSUME_YES="${1:-}"

get_kv() { grep -E "^${1}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }

set_kv() {
  local key="$1"; local value="$2"
  sed -i.bak "/^${key}=/d" "$ENV_FILE" && rm -f "${ENV_FILE}.bak"
  echo "${key}=${value}" >> "$ENV_FILE"
}

# A secret is "weak" if it's missing, empty, or matches a known dev default.
weak() {
  local cur; cur=$(get_kv "$1")
  [[ -z "$cur" || "$cur" == "${2:-}" ]]
}

ask() {  # ask VAR "Prompt" [default] — keeps existing .env value as default
  local var="$1"; local prompt="$2"; local def="${3:-}"
  local cur; cur=$(get_kv "$var")
  [[ -n "$cur" ]] && def="$cur"
  if [[ "$ASSUME_YES" == "--yes" ]]; then
    local envval="${!var:-$def}"
    [[ -z "$envval" ]] && { echo "[setup] --yes but $var is unset"; exit 1; }
    set_kv "$var" "$envval"
    return
  fi
  local input
  read -r -p "$prompt${def:+ [$def]}: " input
  set_kv "$var" "${input:-$def}"
}

echo "── TomSense setup ──────────────────────────────────────────────"
echo
echo "1. Cloudflare Workers AI powers the default models (free tier is"
echo "   10k neurons/day). Get credentials at dash.cloudflare.com:"
echo "   Account ID: any zone's overview page (right column)"
echo "   API token:  My Profile → API Tokens → Create → 'Workers AI' template"
echo
ask CF_ACCOUNT_ID "Cloudflare account ID"
ask CF_API_TOKEN  "Cloudflare API token (Workers AI scope)"
echo
echo "2. Owner identity — chats/memories are attributed to this email."
echo "   Behind Cloudflare Access it must match the email you log in with."
ask TOMSENSE_OWNER_EMAIL "Owner email" "admin@localhost"
echo
echo "3. Where will you open TomSense?"
echo "   - localhost:  http://localhost:8002, no auth gate (LAN-trusted)"
echo "   - domain:     e.g. https://tomsense.your-domain.com behind"
echo "                 Cloudflare Access (see CF_ACCESS_SETUP.md)"
if [[ "$ASSUME_YES" == "--yes" ]]; then
  MODE="${TOMSENSE_MODE:-localhost}"
else
  read -r -p "Serve on a domain? [y/N]: " REPLY_DOMAIN
  MODE=$([[ "${REPLY_DOMAIN,,}" == y* ]] && echo domain || echo localhost)
fi
if [[ "$MODE" == "domain" ]]; then
  ask TOMSENSE_DOMAIN "Public URL (https://…)"
  set_kv FRONTEND_ORIGIN "$(get_kv TOMSENSE_DOMAIN)"
  set_kv REQUIRE_CF_ACCESS "1"
  echo "[setup] REQUIRE_CF_ACCESS=1 — requests without the CF Access header"
  echo "        are refused. Complete CF_ACCESS_SETUP.md before going live."
else
  set_kv REQUIRE_CF_ACCESS "0"
  echo "[setup] localhost mode — all requests act as $(get_kv TOMSENSE_OWNER_EMAIL)."
  echo "        Do NOT port-forward this to the internet without an auth layer."
fi
echo

# ── generated secrets (kept if already strong) ──────────────────────────
NEW=0

if weak SANDBOX_TOKEN "tomsense-sandbox-dev"; then
  set_kv SANDBOX_TOKEN "$(openssl rand -hex 32)"
  echo "[setup] generated SANDBOX_TOKEN"
  NEW=$((NEW + 1))
fi

if weak JUPYTER_TOKEN ""; then
  set_kv JUPYTER_TOKEN "$(openssl rand -hex 32)"
  echo "[setup] generated JUPYTER_TOKEN (code_interpreter kernel auth)"
  NEW=$((NEW + 1))
fi

if weak MCP_SERVER_KEY ""; then
  set_kv MCP_SERVER_KEY "$(openssl rand -hex 24)"
  echo "[setup] generated MCP_SERVER_KEY (/mcp endpoint auth)"
  NEW=$((NEW + 1))
fi

if weak TOMSENSE_DB_PASSWORD "tomsense"; then
  set_kv TOMSENSE_DB_PASSWORD "$(openssl rand -hex 24)"
  echo "[setup] generated TOMSENSE_DB_PASSWORD"
  echo "  ⚠ If postgres was already initialized with an older password, run"
  echo "    ALTER USER tomsense WITH PASSWORD '<new>'; inside it — or wipe"
  echo "    the volume for a fresh start: docker volume rm tomsense_pgdata"
  NEW=$((NEW + 1))
fi

# Fernet master key for at-rest encryption of stored API keys.
if weak TOMSENSE_ENCRYPTION_KEY ""; then
  KEY=$(openssl rand 32 | base64 | tr -d '\n' | tr '+/' '-_')
  set_kv TOMSENSE_ENCRYPTION_KEY "$KEY"
  echo "[setup] generated TOMSENSE_ENCRYPTION_KEY (Fernet)"
  echo "  ⚠ Back this up — losing it means re-entering every stored API key."
  NEW=$((NEW + 1))
fi

echo
echo "── Done. Wrote $ENV_FILE ($NEW new secret(s)). Next:"
echo
echo "   docker compose up -d --build"
echo
echo "   optional extras:  docker compose --profile voice --profile rag \\"
echo "                       --profile jupyter --profile docker up -d --build"
echo
if [[ "$MODE" == "domain" ]]; then
  echo "   then follow CF_ACCESS_SETUP.md to put $(get_kv TOMSENSE_DOMAIN)"
  echo "   behind Cloudflare Access."
else
  echo "   then open http://localhost:8002"
fi
