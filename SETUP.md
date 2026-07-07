# TomSense — install & operations detail

The [README quickstart](README.md#quickstart) covers the happy path. This
doc is the longer version: what setup.sh writes, what each service does,
and the security posture.

## 1. Configure

```bash
bash setup.sh
```

Asks for:

- **Cloudflare account ID + Workers AI API token** — powers the default
  models. Dashboard → any zone (account ID in the right column);
  My Profile → API Tokens → Create → *Workers AI* template.
- **Owner email** — chats/memories are attributed to it. Behind Cloudflare
  Access it must match the email you log in with.
- **localhost or domain** — sets `REQUIRE_CF_ACCESS` accordingly.

And generates strong secrets: `TOMSENSE_DB_PASSWORD`, `SANDBOX_TOKEN`,
`JUPYTER_TOKEN`, `MCP_SERVER_KEY`, `TOMSENSE_ENCRYPTION_KEY` (Fernet key
for at-rest encryption of stored API keys — **back it up**; losing it
means re-entering every key).

Everything lands in `./.env`, which docker compose reads automatically.
Re-run setup.sh any time; strong secrets are kept, answers can change.

Rotating the DB password against an already-initialized postgres:

```bash
docker exec tomsense-postgres \
  psql -U tomsense -d tomsense -c "ALTER USER tomsense WITH PASSWORD '<new>';"
```

Non-interactive install (CI):

```bash
CF_ACCOUNT_ID=… CF_API_TOKEN=… TOMSENSE_OWNER_EMAIL=you@example.com bash setup.sh --yes
```

## 2. Run

```bash
docker compose up -d --build                     # core
docker compose --profile voice --profile rag \
               --profile jupyter --profile docker up -d --build   # everything
```

| Service | Profile | What it does |
|---|---|---|
| tomsense-frontend | core | nginx + SvelteKit SPA — the only page you open |
| tomsense-backend | core | FastAPI: chat loop, tools, RAG, auth |
| tomsense-postgres | core | chats, users, prefs, encrypted API keys |
| tomsense-sandbox | core | code-mode executor (hardened; see sandbox/HARDENING.md) |
| tomsense-searxng | core | web_search meta-search engine |
| tomsense-whisper / -piper | voice | speech-to-text / text-to-speech |
| tomsense-qdrant | rag | vector store for uploaded-doc search |
| tomsense-jupyter | jupyter | code_interpreter kernel for regular chats |
| tomsense-docker-proxy | docker | scoped socket proxy: sandbox recreates + whitelisted restarts only |

First-start check: `docker compose logs tomsense-backend | head` — a clean
start prints **no** `SECURITY:` lines.

To reuse existing external instances instead of the bundled ones, set
`QDRANT_URL` / `SEARXNG_URL` / `WHISPER_URL` / `PIPER_URL` / `JUPYTER_URL`
in `.env`.

## 3. In the UI

- **/setup wizard** runs on first visit: paste API keys, pick models.
- **Settings → Providers** — builtin CF/Anthropic credentials (masked after
  entry), plus any OpenAI-compatible endpoint (OpenRouter, Groq, local
  Ollama…) and the models it exposes.
- **Settings → Files → Mounts** — bind your real source trees into the
  code-mode sandbox at `/workspace/projects/<name>`. With the `docker`
  profile running, Apply recreates the sandbox for you; otherwise the UI
  shows the one-line manual command.

## 4. Code-mode deploys (optional)

To let the agent rebuild-and-restart a project you host on the same
machine, create `deploy-targets.json` next to docker-compose.yml (see the
schema in `backend/app/deploy_targets.py`) and run the `docker` profile.
Every deploy requires explicit in-chat approval; the model can only pick
whitelisted keys, never raw containers or commands. No file = the tool is
not offered to the model at all.

## Security posture

Enforced:
- Random per-install secrets via setup.sh; backend warns loudly on weak ones
- Stored API keys Fernet-encrypted at rest; masked on every read API
- CF Access JWT required when `REQUIRE_CF_ACCESS=1`; CORS locked to
  `FRONTEND_ORIGIN` in domain mode
- Per-user rate limit (`RATE_LIMIT_PER_MIN`, default 120/min)
- Sandbox: non-root, read-only rootfs, all capabilities dropped (5 added
  back for the entrypoint), no docker socket, 1G/2cpu/512pids caps, not
  host-published
- SSRF guard on model-controlled fetches (private/LAN addresses blocked
  by default; see `netguard.py`)
- Docker socket proxy (when enabled) allows container ops only — no
  BUILD/EXEC/SWARM — and deploys are approval-gated against a whitelist

Known limitations:
- Localhost mode has **no auth** — everyone who can reach the port is the
  owner. Don't port-forward it; use the domain mode + Access instead.
- The sandbox has open egress by design (pip/npm/curl for the agent);
  isolate at your firewall if that's a concern.
- Chat/user data (not API keys) is stored unencrypted in postgres —
  encrypt your backups.
