"""FastAPI entrypoint.

Multi-user via Cloudflare Access: every request that reaches a non-public
endpoint must carry `Cf-Access-Authenticated-User-Email` (set by CF Access
at the edge) or — when REQUIRE_CF_ACCESS is unset — a LOCAL_DEV_EMAIL
fallback. Chats / uploads / memory / artifacts are scoped to that user.
Only /share/<token> is public.
"""

import asyncio
import json as _json
import logging
import re as _re
import os
import secrets
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import (
    Depends,
    FastAPI,
    File,
    HTTPException,
    Query,
    Request,
    UploadFile,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import cf_catalog, db, mcp as mcp_mod, providers, rag, stt as stt_mod, tts as tts_mod, uploads as up_mod
from .mcp_server import router as mcp_server_router
from .artifacts import detect_artifacts
from .auth import current_user
from .cf import close_client, fetch_gateway_spend_today, fetch_neurons_today
from .chat import run_chat, short_name, _strip_hallucinated_chips
from .clienttools import resolve_client_tool
from .config import settings
from .liverun import KEEPALIVE, LiveRun, create_run, get_run, live_run_for_chat, retire_run
from .schemas import (
    BranchRequest,
    ChatRequest,
    CreateChatRequest,
    CreateMemoryRequest,
    CreatePersonaRequest,
    FolderRequest,
    PinRequest,
    RenameChatRequest,
    ToolResultRequest,
    UpdateModelRequest,
    UpdatePersonaRequest,
    UpdateSystemPromptRequest,
)
from .tool_registry import ANTHROPIC_MODELS, CODE_MODELS_CATALOG, TOOL_MODELS_CATALOG, TOOL_MODELS_KEYS
from .tools import GENERATED_DIR, TOOL_SPECS
from . import mounts as _mounts


log = logging.getLogger("tomsense")

# Per-user rate-limit state (initialized here; middleware registered after
# `app = FastAPI(...)` below so the @app.middleware decorator can resolve).
from collections import deque
_RATE_HITS: dict[str, deque] = {}

# Strong refs to fire-and-forget background tasks (chat generation, RAG
# indexing, autotitle). asyncio only holds a WEAK reference to a task, so
# without this a GC pass can destroy one mid-run — the coroutine never
# resumes, nothing is persisted, and no exception surfaces (just a stderr
# "Task was destroyed but it is pending!"). Same hazard liverun._EVICT_TASKS
# guards; a generation task can sit suspended for 30+ min at the approval
# gate, so it needs the seatbelt even more.
_BG_TASKS: set = set()

# The scheduled-prompts loop task — module global so /me/schedules can report
# whether it's alive (a silently dead scheduler is otherwise invisible).
_SCHEDULER_TASK: "Optional[asyncio.Task]" = None


def _spawn(coro) -> "asyncio.Task":
    """create_task + keep a strong reference until the task finishes."""
    t = asyncio.create_task(coro)
    _BG_TASKS.add(t)
    t.add_done_callback(_BG_TASKS.discard)
    return t


def _security_warnings() -> None:
    """Print loud startup warnings for known-insecure defaults so an operator
    notices them in `docker compose logs` before exposing the app. Each one
    points at the env var to set."""
    require_cf = os.getenv("REQUIRE_CF_ACCESS", "").lower() in ("1", "true", "yes")
    # SANDBOX_TOKEN: a known-public value is no auth at all. Block it if the
    # app is meant for production (CF Access required).
    sb_tok = os.getenv("SANDBOX_TOKEN", "")
    if sb_tok in ("", "tomsense-sandbox-dev"):
        msg = ("SECURITY: SANDBOX_TOKEN is unset or uses the known-public dev "
               "default. Set a strong random token: "
               "openssl rand -hex 32  →  .env SANDBOX_TOKEN=...")
        log.warning(msg) if require_cf else log.warning(msg + " (dev mode)")
    # LOCAL_DEV_EMAIL: when CF Access is disabled, ANY caller becomes this user.
    if not require_cf:
        local_email = os.getenv("LOCAL_DEV_EMAIL", "local@dev")
        log.warning(
            "SECURITY: REQUIRE_CF_ACCESS is off — every request authenticates "
            "as %r. Only safe for local dev. Set REQUIRE_CF_ACCESS=1 before "
            "exposing this backend publicly.", local_email,
        )
    # Encryption-at-rest: missing key = plaintext API keys (dev-tolerable,
    # warn); INVALID key = operator thinks they're covered but isn't (error).
    from . import crypto as _crypto
    if _crypto.key_is_invalid():
        log.error("SECURITY: TOMSENSE_ENCRYPTION_KEY is invalid — stored API "
                  "keys are being written in PLAINTEXT. Regenerate via setup.sh.")
    elif not _crypto.is_configured():
        log.warning("SECURITY: TOMSENSE_ENCRYPTION_KEY not set — stored API keys "
                    "are saved in plaintext. Run setup.sh to generate one.")
    # ANTHROPIC api key: optional, but log so operator knows whether Claude
    # entries will work without user-side credential entry.
    if not settings.anthropic_api_key:
        log.info("ANTHROPIC_API_KEY env not set — Claude entries require a "
                 "per-user key via Settings → Builtin credentials.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    os.makedirs(GENERATED_DIR, exist_ok=True)
    up_mod.ensure_dir()
    _security_warnings()
    await db.init_pool()
    global _SCHEDULER_TASK
    _SCHEDULER_TASK = asyncio.create_task(_scheduler_loop())
    yield
    _SCHEDULER_TASK.cancel()
    await db.close_pool()
    await close_client()


app = FastAPI(title="tomsense-app", version="0.3.0", lifespan=lifespan)

# CORS: lock to FRONTEND_ORIGIN when set (production), fall back to "*" only
# when not configured (dev). Same-origin requests don't trigger CORS so the
# bundled SvelteKit frontend talking to the backend through nginx is unaffected
# either way — this only matters for direct API calls from a browser.
_frontend_origin = os.getenv("FRONTEND_ORIGIN", "").strip()
_cors_origins = [_frontend_origin] if _frontend_origin else ["*"]
if not _frontend_origin:
    log.warning(
        "FRONTEND_ORIGIN env not set — CORS is open to all origins. Set "
        "FRONTEND_ORIGIN=https://your-domain to lock down."
    )
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ─── basic in-memory rate limiter ───────────────────────────────────────────
# Sliding 60-second window keyed by CF Access email (post-auth) or remote IP.
# In-memory only — multi-instance deployments need an external store (redis),
# but the single-container deploy this targets is fine. Skips healthz/static.
@app.middleware("http")
async def _rate_limit(request: Request, call_next):
    limit = settings.rate_limit_per_min
    if limit <= 0:
        return await call_next(request)
    path = request.url.path
    if path in ("/healthz", "/", "/info") or path.startswith(("/generated/", "/assets/")):
        return await call_next(request)
    # Key by CF-verified email only when CF Access is enforced (the tunnel
    # validates the header). Without it the header is client-forgeable —
    # anyone could mint fresh buckets — so fall back to the client IP.
    _cf_on = os.getenv("REQUIRE_CF_ACCESS", "").lower() in ("1", "true", "yes")
    key = (
        (request.headers.get("Cf-Access-Authenticated-User-Email") if _cf_on else None)
        or (request.client.host if request.client else "anon")
    )
    now = time.monotonic()
    # Prune dead buckets so the dict can't grow unbounded (rotating IPs,
    # scans). Amortized: only sweeps when the dict gets large.
    if len(_RATE_HITS) > 512:
        stale = [k for k, w in _RATE_HITS.items() if not w or w[-1] < now - 60.0]
        for k in stale:
            _RATE_HITS.pop(k, None)
    window = _RATE_HITS.setdefault(key, deque())
    while window and window[0] < now - 60.0:
        window.popleft()
    if len(window) >= limit:
        log.warning("rate-limited key=%s hits=%s", key, len(window))
        raise HTTPException(
            status_code=429,
            detail=f"Too many requests — limit is {limit}/min. Try again shortly.",
        )
    window.append(now)
    return await call_next(request)

app.mount("/generated", StaticFiles(directory=GENERATED_DIR, check_dir=False), name="generated")

# TomSense-as-MCP-server: key-authenticated /mcp endpoint (own auth, no CF
# Access dependency) so Claude Code / claude.ai can reach TomSense's memory,
# docs and chat history. See mcp_server.py.
app.include_router(mcp_server_router)


def _info_payload() -> dict:
    return {
        "name": "tomsense-app",
        "version": app.version,
        "chat_model": settings.model_chat,
        "chat_model_short": short_name(settings.model_chat),
        "tools": [t["function"]["name"] for t in TOOL_SPECS],
        "auth_required": os.getenv("REQUIRE_CF_ACCESS", "").lower() in ("1", "true", "yes"),
        # Whether Cloudflare Workers AI is wired at all. When false this is a
        # pure bring-your-own-provider deployment: the frontend hides the
        # neuron counter (a CF-only metric) and falls back to token/cost usage.
        "cf_configured": bool(settings.cf_api_token),
        "tts_providers": [
            {"id": "piper",     "label": "Piper (local, CPU)",     "voices": tts_mod.PIPER_VOICES},
            {"id": "cf-aura",   "label": "Cloudflare Aura 2 (EN)", "voices": tts_mod.AURA_VOICES},
            {"id": "cf-melotts","label": "Cloudflare MeloTTS",     "voices": ["en", "es", "fr", "zh", "ja", "ko"]},
        ],
        "stt_providers": [
            {"id": "whisper",    "label": "Whisper (local, distil-medium.en)"},
            {"id": "cf-whisper", "label": "Cloudflare Whisper v3 turbo"},
        ],
        # Single source of truth for tool-model slots — frontend reads this
        # instead of maintaining its own TOOL_KEYS / TOOL_LABELS arrays.
        "tool_models_catalog": TOOL_MODELS_CATALOG,
        # Default code-mode model picker entries (I5). Adding a model is one
        # backend deploy — no frontend rebuild required.
        "code_models_catalog": CODE_MODELS_CATALOG,
        # Bundled Cloudflare models + declared capabilities/roles — the registry
        # the frontend sources its CF model lists from (Phase 4).
        "cf_models_catalog": cf_catalog.CF_MODELS,
        # Bundled Anthropic/Claude models (native Messages API), same shape.
        "anthropic_models_catalog": ANTHROPIC_MODELS,
        # Embedding default (Cloudflare) — the UI shows this as the fallback
        # option and pre-fills the dim field.
        "embed_default": {"model": settings.model_embed, "dim": settings.embed_dim},
    }


async def _json_body(request: Request):
    """Parse a JSON request body, returning 400 (not an uncaught 500) when the
    body is malformed JSON. Callers still validate the parsed shape."""
    try:
        return await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid JSON body")


@app.get("/")
async def root():
    return _info_payload()


@app.get("/info")
async def info():
    return _info_payload()


@app.get("/tools")
async def list_tools():
    return {"tools": TOOL_SPECS}


@app.get("/healthz")
async def healthz():
    return {"ok": True}


# ─── /me (current user) ─────────────────────────────────────────────────────

def _public_user(user: dict, chat_count: int = 0) -> dict:
    return {
        "email": user["email"],
        "name": user["name"],
        "tokens_used": user["tokens_used"],
        "monthly_token_limit": user["monthly_token_limit"],
        "usage_period_start": user["usage_period_start"],
        "chat_count": chat_count,
    }


@app.get("/me")
async def me(user: dict = Depends(current_user)):
    return _public_user(user)


@app.get("/me/usage")
async def me_usage(user: dict = Depends(current_user)):
    count = await db.chat_count_for_user(user["id"])
    return _public_user(user, chat_count=count)


# Budget mode — cached account-wide neuron reading so chat turns don't pay a
# GraphQL round-trip each time. 120s staleness is fine for a soft cap.
_NEURON_CACHE: dict = {"at": 0.0, "used": 0}


def _last_user_text(msgs: list[dict]) -> str:
    for m in reversed(msgs):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                return " ".join(
                    p.get("text", "") for p in c if isinstance(p, dict)
                ).strip()
            return str(c or "")
    return ""


async def _task_model(user_id: Optional[str], prefs: Optional[dict] = None) -> str:
    """Resolve the tiny 'utility' model used for chat titles, follow-ups,
    auto-memory, summaries and difficulty routing. Per-user override via
    `tool_models.title` (a full provider::model string, so it can point at any
    OpenAI-compatible endpoint); falls back to the MODEL_TITLE default."""
    if user_id:
        try:
            if prefs is None:
                prefs = await db.get_user_prefs(user_id)
            picked = ((prefs or {}).get("tool_models") or {}).get("title")
            if picked and str(picked).strip():
                return str(picked).strip()
        except Exception:
            pass
    return settings.model_title


async def _task_fallback(user_id: Optional[str], prefs: Optional[dict] = None) -> Optional[str]:
    """The user's `tool_models.title_fallback` — dispatch_chat_complete retries
    a failed/empty utility call on it. None when unset."""
    if not user_id:
        return None
    try:
        if prefs is None:
            prefs = await db.get_user_prefs(user_id)
        fb = ((prefs or {}).get("tool_models") or {}).get("title_fallback")
        return str(fb).strip() if fb and str(fb).strip() else None
    except Exception:
        return None


async def _route_model(msgs: list[dict], user_id: str) -> Optional[str]:
    """Difficulty-route a default-model turn: HARD → the heavy chat model.
    Returns the escalated model string, or None to stay on the default.
    Costs one tiny-model call (~1 neuron); obvious small talk short-circuits."""
    text = _last_user_text(msgs)
    if not text:
        return None
    # Short, code-free messages are near-always chitchat — skip the classifier.
    if len(text) < 60 and "```" not in text:
        return None
    verdict = ""
    try:
        r = await providers.dispatch_chat_complete(
            user_id=user_id,
            model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{
                "role": "user",
                "content": (
                    "Classify the difficulty of answering this message well. "
                    "Reply with exactly one word — EASY (casual chat, simple "
                    "facts, short rewrites) or HARD (multi-step reasoning, "
                    "math proofs, non-trivial code, nuanced analysis, long "
                    "structured writing).\n\nMessage:\n" + text[:2000]
                ),
            }],
            max_tokens=4,
        )
        verdict = (r.get("content") or "").strip().upper()
    except Exception as e:
        log.info("auto-route classifier failed (%s) — staying on default", e)
    if "HARD" not in verdict:
        return None
    heavy = settings.model_chat_heavy
    log.info("auto-route: HARD turn → %s", heavy)
    return f"{providers.CF_BUILTIN_ID}::{heavy}"


async def _budget_downshift(
    model_str: Optional[str], user_id: str = "", slot_fallback: Optional[str] = None
) -> tuple[Optional[str], Optional[str]]:
    """Swap heavy CF models when today's neuron use crosses the soft cap.
    Prefers the user's per-slot fallback (slot_fallback) over the server
    budget model when one is configured. Non-CF providers never downshift."""
    cap_pct = settings.neuron_soft_cap_pct
    if cap_pct <= 0:
        return model_str, None
    pid, mid = providers.parse_model_str(model_str)
    if pid != providers.CF_BUILTIN_ID:
        return model_str, None
    if not any(h in mid for h in settings.neuron_heavy_models):
        return model_str, None
    now_m = time.monotonic()
    if now_m - _NEURON_CACHE["at"] > 120:
        n = await fetch_neurons_today()
        _NEURON_CACHE["at"] = now_m
        _NEURON_CACHE["used"] = int(n.get("used") or 0)
    used = _NEURON_CACHE["used"]
    limit = settings.cf_daily_neuron_limit
    if used < limit * cap_pct / 100:
        return model_str, None
    target = (
        slot_fallback if slot_fallback
        else f"{providers.CF_BUILTIN_ID}::{settings.budget_model}"
    )
    target_name = short_name(target)
    log.warning("budget mode: %s → %s (neurons %s/%s)", mid, target_name, used, limit)
    # Push once per day when the cap first bites.
    from datetime import date
    today = date.today().isoformat()
    if _NEURON_CACHE.get("notified") != today:
        _NEURON_CACHE["notified"] = today
        from . import notify
        _spawn(notify.push(
            "🪫 TomSense budget mode",
            f"{used:,}/{limit:,} free neurons used today — heavy models "
            f"downshifted to {target_name} until midnight UTC.",
            tags="battery", priority="low",
            user_id=user_id,
        ))
    notice = (
        f"> 🪫 **Budget mode** — {used:,}/{limit:,} free neurons used today; "
        f"running `{target_name}` instead of `{mid.rsplit('/', 1)[-1]}`. "
        f"Resets at midnight UTC.\n\n"
    )
    return target, notice


@app.get("/me/neurons")
async def me_neurons(user: dict = Depends(current_user)):
    """Today's account-wide CF Workers AI neuron usage + estimated $ spend.
    The metrics are account-level — not per-user — but we surface them under
    /me because the frontend needs auth to fetch them.

    dollars.neurons is the Workers AI overage estimate: $0 until today's
    usage crosses the free daily allocation, then neuron_price_per_1k per
    1k neurons beyond it. dollars.gateway is real unified-billing spend on
    external providers (Imagen / gpt-image / Nano Banana …) via AI Gateway.
    """
    # Pure BYO deployment (no CF token) — neurons are a CF-only metric, so skip
    # the CF GraphQL calls entirely and return an "unconfigured" shape.
    if not settings.cf_api_token:
        return {"configured": False, "used": 0, "limit": 0,
                "dollars": {"neurons": 0.0, "gateway": 0.0, "total": 0.0}}
    neurons, gateway = await asyncio.gather(
        fetch_neurons_today(), fetch_gateway_spend_today()
    )
    used = int(neurons.get("used") or 0)
    limit = int(neurons.get("limit") or 0)
    overage = max(0, used - limit) / 1000.0 * settings.neuron_price_per_1k
    gw = float(gateway.get("external_cost") or 0.0)
    neurons["dollars"] = {
        "neurons": round(overage, 4),
        "gateway": round(gw, 4),
        "total": round(overage + gw, 4),
    }
    return neurons


@app.get("/me/usage/today")
async def me_usage_today(user: dict = Depends(current_user)):
    """Per-provider token (and, where reported, $ cost) usage for today —
    powers the sidebar counter's tokens/cost mode. `active_provider` is the
    provider of the user's default chat model, so the UI's 'auto' mode knows
    which row to surface."""
    rows = await db.usage_today(user["id"])
    prefs = await db.get_user_prefs(user["id"]) or {}
    chat_pref = ((prefs.get("tool_models") or {}).get("chat") or "").strip()
    active_pid, _ = providers.parse_model_str(chat_pref) if chat_pref else ("cf", "")
    tot_in = sum(int(r["tokens_in"]) for r in rows)
    tot_out = sum(int(r["tokens_out"]) for r in rows)
    tot_cost = sum(float(r["cost"]) for r in rows)
    return {
        "providers": rows,
        "active_provider": active_pid,
        "totals": {
            "tokens_in": tot_in,
            "tokens_out": tot_out,
            "tokens": tot_in + tot_out,
            "cost": round(tot_cost, 4),
        },
    }


def _mask_key(key: str) -> str:
    if not key:
        return ""
    if len(key) <= 10:
        return "•" * len(key)
    return f"{key[:4]}…{key[-4:]}"


CREDENTIAL_KEYS = {"cf_api_token", "anthropic_api_key"}

# Mapping from the credential field name (frontend / API contract) to the
# builtin_id it stores under in the providers table. Lets the API stay
# stable even if internal IDs change.
_CRED_TO_BUILTIN = {
    "cf_api_token": providers.CF_BUILTIN_ID,
    "anthropic_api_key": providers.ANTHROPIC_BUILTIN_ID,
}
_BUILTIN_DEFAULTS = {
    providers.CF_BUILTIN_ID: {
        "name": "Cloudflare Workers AI",
        "base_url": settings.cf_chat_url.rsplit("/chat/completions", 1)[0],
        "kind": "openai-compat",
    },
    providers.ANTHROPIC_BUILTIN_ID: {
        "name": "Anthropic Claude",
        "base_url": "https://api.anthropic.com/v1",
        "kind": "anthropic",
    },
}


async def _migrate_legacy_credentials(user_id: str) -> None:
    """One-shot lazy migration: copy any values that still live in
    prefs.credentials (pre-I1 storage) into the providers table, then
    clear them from prefs. Called from /me/credentials handlers so it
    happens on the user's first interaction post-deploy. Idempotent."""
    try:
        legacy = await db.get_user_credentials(user_id)
        if not legacy:
            return
        for cred_key, value in legacy.items():
            if cred_key not in _CRED_TO_BUILTIN or not value:
                continue
            builtin_id = _CRED_TO_BUILTIN[cred_key]
            existing = await db.get_builtin_provider(user_id, builtin_id)
            if existing and existing.get("api_key"):
                continue  # already migrated / user already set a new value
            await db.upsert_builtin_provider(
                user_id, builtin_id, api_key=value, **_BUILTIN_DEFAULTS[builtin_id],
            )
        # Clear the legacy field so we don't migrate again.
        await db.update_user_prefs(user_id, {"credentials": {}})
    except Exception as e:
        log.warning("legacy credentials migration failed for %s: %s", user_id, e)


@app.get("/me/prefs")
async def me_prefs(user: dict = Depends(current_user)):
    """Per-user preferences (TTS/STT provider + voice). The catalogue of
    valid options ships in /info so the UI can populate dropdowns. Credentials
    are stripped — the file manager UI fetches them masked via /me/credentials.
    """
    prefs = await db.get_user_prefs(user["id"])
    if isinstance(prefs, dict) and "credentials" in prefs:
        prefs = {k: v for k, v in prefs.items() if k != "credentials"}
    return {"prefs": prefs}


# ─── secret vault ────────────────────────────────────────────────────────────
# Encrypted, per-user named secrets the code-mode agent can USE (injected into
# its sandbox shell env) without the plaintext ever reaching the model or the
# client. The API exposes names only — values are write-only.

import re as _re_secrets

_SECRET_NAME_RE = _re_secrets.compile(r"^[A-Z][A-Z0-9_]{0,63}$")


@app.get("/me/secrets")
async def me_secrets(user: dict = Depends(current_user)):
    """List the user's secret NAMES + timestamps. Never returns values."""
    return {"secrets": await db.list_secret_names(user["id"])}


@app.put("/me/secrets/{name}")
async def me_secret_set(name: str, request: Request, user: dict = Depends(current_user)):
    """Create/update a secret. Name must be a shell-safe UPPER_SNAKE env name;
    value is encrypted at rest and never echoed back."""
    if not _SECRET_NAME_RE.match(name or ""):
        raise HTTPException(status_code=400, detail=(
            "Secret name must be UPPER_SNAKE_CASE (A-Z, 0-9, _), start with a "
            "letter, max 64 chars — it becomes a shell env var name."))
    body = await request.json()
    value = body.get("value")
    if not isinstance(value, str) or value == "":
        raise HTTPException(status_code=400, detail="value (non-empty string) is required")
    if len(value) > 8192:
        raise HTTPException(status_code=400, detail="value too long (max 8192 chars)")
    await db.set_secret(user["id"], name, value)
    return {"ok": True, "name": name}


@app.delete("/me/secrets/{name}")
async def me_secret_delete(name: str, user: dict = Depends(current_user)):
    ok = await db.delete_secret(user["id"], name)
    return {"ok": ok}


async def _credentials_snapshot(user_id: str) -> dict[str, dict]:
    """Build the masked {set, preview} response from the providers table."""
    out: dict[str, dict] = {}
    for cred_key, builtin_id in _CRED_TO_BUILTIN.items():
        row = await db.get_builtin_provider(user_id, builtin_id)
        api_key = (row or {}).get("api_key") or ""
        out[cred_key] = {"set": bool(api_key), "preview": _mask_key(api_key)}
    return out


@app.get("/me/credentials")
async def me_credentials(user: dict = Depends(current_user)):
    """Masked view of the user's builtin-provider credentials. Each entry is
    {set: bool, preview: "xxxx…yyyy"}. Sourced from the unified providers
    table (post-I1); legacy prefs.credentials values are migrated lazily on
    first call."""
    await _migrate_legacy_credentials(user["id"])
    return {"credentials": await _credentials_snapshot(user["id"])}


@app.put("/me/credentials")
async def me_credentials_update(request: Request, user: dict = Depends(current_user)):
    """Set / rotate / clear builtin-provider credentials. Body: dict whose
    keys are a subset of CREDENTIAL_KEYS. Values can be the new key (string)
    to set/rotate, or null / "" to clear (falls back to env). Unknown keys
    are ignored."""
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    patch = {k: v for k, v in body.items() if k in CREDENTIAL_KEYS}
    if not patch:
        raise HTTPException(status_code=400, detail="no valid credential keys")
    for k, v in patch.items():
        if v is not None and not isinstance(v, str):
            raise HTTPException(status_code=400, detail=f"{k} must be string or null")
    await _migrate_legacy_credentials(user["id"])
    for cred_key, value in patch.items():
        builtin_id = _CRED_TO_BUILTIN[cred_key]
        if value is None or not str(value).strip():
            await db.delete_builtin_provider(user["id"], builtin_id)
        else:
            await db.upsert_builtin_provider(
                user["id"], builtin_id,
                api_key=str(value).strip(),
                **_BUILTIN_DEFAULTS[builtin_id],
            )
    return {"credentials": await _credentials_snapshot(user["id"])}


@app.put("/me/prefs")
async def me_prefs_update(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    # Only accept a known whitelist of keys to avoid arbitrary growth
    allowed = {
        "tts_provider", "tts_voice", "stt_provider",
        "embed_model", "embed_dim",  # RAG embedding backend override
        "tool_models", "cf_models", "export_format",
        "setup_dismissed",  # first-run wizard "I've seen this" flag
        "review_edits",     # code mode: pause for Apply/Reject before each edit
        "verify_edits",     # code mode: run post-edit build/type-check (default on)
        "max_rounds_code",  # code mode: per-user agentic round cap override
        "max_tokens_coder", # code mode: per-user response length cap override
        "auto_route",       # chat: difficulty-route default turns to the heavy model
        "auto_memory",      # chat: auto-extract durable facts into memory
        "usage_display",    # sidebar counter: 'auto' | 'neurons' | 'tokens'
    }
    patch = {k: v for k, v in body.items() if k in allowed}
    if not patch:
        raise HTTPException(status_code=400, detail="no valid prefs keys")
    if "usage_display" in patch and patch["usage_display"] not in ("auto", "neurons", "tokens"):
        raise HTTPException(status_code=400, detail="usage_display must be auto|neurons|tokens")
    if "review_edits" in patch:
        patch["review_edits"] = bool(patch["review_edits"])
    if "verify_edits" in patch:
        patch["verify_edits"] = bool(patch["verify_edits"])
    if "auto_route" in patch:
        patch["auto_route"] = bool(patch["auto_route"])
    if "auto_memory" in patch:
        patch["auto_memory"] = bool(patch["auto_memory"])
    # Numeric code-mode overrides: clamp to sane ranges; null/empty clears the
    # override (falls back to the env/global default).
    for _key, _lo, _hi in (("max_rounds_code", 1, 100),
                           ("max_tokens_coder", 512, 32768)):
        if _key in patch:
            _v = patch[_key]
            if _v in (None, "", 0):
                patch[_key] = None
            else:
                try:
                    patch[_key] = max(_lo, min(int(_v), _hi))
                except (TypeError, ValueError):
                    raise HTTPException(status_code=400, detail=f"{_key} must be an integer")
    # tool_models is a nested dict — keys whitelisted via the canonical
    # TOOL_MODELS_KEYS set so adding a new slot is one entry in
    # tool_registry.py instead of touching this allowlist too.
    if "tool_models" in patch:
        tm = patch["tool_models"]
        if not isinstance(tm, dict):
            raise HTTPException(status_code=400, detail="tool_models must be an object")
        incoming = {
            k: (str(v).strip() if v else None) for k, v in tm.items() if k in TOOL_MODELS_KEYS
        }
        # DEEP-merge over the existing slots: the top-level prefs merge is
        # shallow (prefs || patch), so a partial tool_models patch would
        # otherwise REPLACE the whole object and wipe unlisted slots (vision,
        # code_mode). Merging here makes a partial update do what it says.
        existing = (await db.get_user_prefs(user["id"]) or {}).get("tool_models") or {}
        if isinstance(existing, dict):
            merged = {**existing, **incoming}
        else:
            merged = incoming
        patch["tool_models"] = merged
    if "export_format" in patch and patch["export_format"] not in ("md", "json"):
        raise HTTPException(status_code=400, detail="export_format must be 'md' or 'json'")
    # Embedding backend override: embed_model is a `provider::model` string
    # (empty clears back to the CF default); embed_dim must match the model's
    # output width — a wrong value silently breaks retrieval.
    if "embed_model" in patch:
        em = patch["embed_model"]
        patch["embed_model"] = (str(em).strip() if em else "") or None
    if "embed_dim" in patch:
        ed = patch["embed_dim"]
        if ed in (None, "", 0):
            patch["embed_dim"] = None
        else:
            try:
                patch["embed_dim"] = max(8, min(int(ed), 8192))
            except (TypeError, ValueError):
                raise HTTPException(status_code=400, detail="embed_dim must be an integer")
    # cf_models: when set, REPLACES the frontend's hardcoded CF model list.
    # Lets the user add/remove CF models without a redeploy. Validate shape:
    # array of {id, label, note?, tools[]}.
    if "cf_models" in patch:
        cm = patch["cf_models"]
        if cm is None:
            patch["cf_models"] = []
        elif not isinstance(cm, list):
            raise HTTPException(status_code=400, detail="cf_models must be a list")
        else:
            cleaned = []
            for entry in cm:
                if not isinstance(entry, dict):
                    continue
                eid = str(entry.get("id") or "").strip()
                if not eid:
                    continue
                tools = entry.get("tools") or []
                if not isinstance(tools, list):
                    tools = []
                # Optional steps override for Flux family — clamp 1..50.
                steps_raw = entry.get("steps")
                steps_val = None
                if steps_raw is not None and steps_raw != "":
                    try:
                        s = int(steps_raw)
                        if 1 <= s <= 50:
                            steps_val = s
                    except (TypeError, ValueError):
                        steps_val = None
                cleaned.append({
                    "id": eid,
                    "label": str(entry.get("label") or eid).strip(),
                    "note": (str(entry.get("note") or "").strip() or None),
                    "tools": [str(t) for t in tools if t],
                    "steps": steps_val,
                })
            patch["cf_models"] = cleaned
    return {"prefs": await db.update_user_prefs(user["id"], patch)}


@app.get("/me/rag-status")
async def me_rag_status(user: dict = Depends(current_user)):
    """Embedding config vs. indexed-collection state — powers the "change the
    embedding model" warning. `stale` = stored vectors no longer match the
    configured model's dimension (retrieval degraded until a reindex)."""
    return await rag.rag_status(user["id"])


@app.post("/me/reindex")
async def me_reindex(user: dict = Depends(current_user)):
    """Re-embed every indexed chunk with the current embedding backend — repairs
    the dimension mismatch after switching embedding models."""
    result = await rag.reindex_user(user["id"])
    if not result.get("ok"):
        raise HTTPException(status_code=502, detail=result.get("error") or "reindex failed")
    return result


@app.get("/me/providers")
async def me_providers_list(user: dict = Depends(current_user)):
    """List all providers visible to this user — builtin Cloudflare first, then
    user-defined entries. api_key is masked on every read."""
    out = await providers.list_user_providers_with_builtin(user["id"])
    return {"providers": [providers.redact_provider(p) for p in out]}


async def _check_provider_url(base_url: str) -> None:
    """SSRF guard for user-supplied provider URLs. Allowed to point at
    private/LAN hosts by default (local Ollama / LiteLLM are legitimate
    targets) — set PROVIDERS_ALLOW_PRIVATE=0 to lock down for multi-user."""
    if settings.providers_allow_private:
        return
    from . import netguard
    try:
        await netguard.assert_public_url(base_url)
    except netguard.PrivateAddressError as e:
        raise HTTPException(
            status_code=400,
            detail=f"base_url refused (private/internal address): {e}",
        )


@app.post("/me/providers")
async def me_providers_create(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    name = (body.get("name") or "").strip()
    base_url = (body.get("base_url") or "").strip()
    api_key = (body.get("api_key") or "").strip()
    if not name or not base_url or not api_key:
        raise HTTPException(status_code=400, detail="name, base_url, api_key required")
    if not (base_url.startswith("http://") or base_url.startswith("https://")):
        raise HTTPException(status_code=400, detail="base_url must be http(s)")
    await _check_provider_url(base_url)
    # Whitelist provider kinds — anything else is rejected so a typo doesn't
    # silently degrade to "openai-compat" and waste a request shape.
    kind = (body.get("kind") or "openai-compat").strip()
    ALLOWED_KINDS = {"openai-compat", "anthropic"}
    if kind not in ALLOWED_KINDS:
        raise HTTPException(status_code=400,
                            detail=f"kind must be one of {sorted(ALLOWED_KINDS)}")
    models = body.get("models") or []
    if not isinstance(models, list):
        raise HTTPException(status_code=400, detail="models must be a list")
    p = await db.create_provider(
        user["id"], name=name, base_url=base_url, api_key=api_key,
        kind=kind, models=models,
    )
    if not p:
        raise HTTPException(status_code=500, detail="could not create provider")
    return providers.redact_provider(p)


@app.patch("/me/providers/{provider_id}")
async def me_providers_update(
    provider_id: str,
    request: Request,
    user: dict = Depends(current_user),
):
    # Both synthetic builtins (cf, anthropic) are not in the providers DB.
    # Reject the patch explicitly so the user gets a clear "use Builtin
    # credentials" hint instead of a 404 chase.
    if provider_id in (providers.CF_BUILTIN_ID, providers.ANTHROPIC_BUILTIN_ID):
        raise HTTPException(
            status_code=400,
            detail="builtin provider is read-only — rotate its API key via "
                   "Settings → Builtin credentials",
        )
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    patch = {}
    for key in ("name", "base_url", "api_key"):
        if key in body:
            v = body[key]
            if v is not None:
                v = str(v).strip()
                if v == "":
                    continue  # ignore empty patches — don't wipe field
                if key == "base_url":
                    if not (v.startswith("http://") or v.startswith("https://")):
                        raise HTTPException(status_code=400, detail="base_url must be http(s)")
                    await _check_provider_url(v)
            patch[key] = v
    if "models" in body:
        if not isinstance(body["models"], list):
            raise HTTPException(status_code=400, detail="models must be a list")
        patch["models"] = body["models"]
    if not patch:
        raise HTTPException(status_code=400, detail="no valid fields to patch")
    p = await db.update_provider(provider_id, user["id"], patch)
    if not p:
        raise HTTPException(status_code=404, detail="provider not found")
    return providers.redact_provider(p)


@app.delete("/me/providers/{provider_id}")
async def me_providers_delete(provider_id: str, user: dict = Depends(current_user)):
    if provider_id == providers.CF_BUILTIN_ID:
        raise HTTPException(status_code=400, detail="builtin provider cannot be deleted")
    ok = await db.delete_provider(provider_id, user["id"])
    if not ok:
        raise HTTPException(status_code=404, detail="provider not found")
    return {"ok": True}


@app.post("/me/providers/{provider_id}/test")
async def me_providers_test(provider_id: str, user: dict = Depends(current_user)):
    """Ping {base_url}/models with the provider's auth to verify connectivity."""
    p = await providers.resolve_provider(provider_id, user_id=user["id"])
    if not p:
        raise HTTPException(status_code=404, detail="provider not found")
    return await providers.test_provider(p)


@app.post("/me/providers/discover")
async def me_providers_discover(request: Request, user: dict = Depends(current_user)):
    """List the model ids a provider advertises at /models. Works for a SAVED
    provider (body {provider_id}, uses its stored key) or an in-progress one
    (body {base_url, api_key}), so the add/edit form can offer a dropdown."""
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    pid = (body.get("provider_id") or "").strip()
    provider: Optional[dict] = None
    if pid:
        provider = await providers.resolve_provider(pid, user_id=user["id"])
        if not provider:
            raise HTTPException(status_code=404, detail="provider not found")
        key = (body.get("api_key") or "").strip()
        if key:  # form is rotating the key — use the new one for discovery
            provider = {**provider, "api_key": key}
    else:
        base_url = (body.get("base_url") or "").strip()
        if not base_url:
            raise HTTPException(status_code=400, detail="base_url or provider_id required")
        await _check_provider_url(base_url)
        provider = {"base_url": base_url, "api_key": (body.get("api_key") or "").strip()}
    return {"models": await providers.discover_models(provider)}


# ─── /me/projects ───────────────────────────────────────────────────────────

@app.get("/me/projects")
async def me_projects_list(user: dict = Depends(current_user)):
    return {"projects": await db.list_projects(user["id"])}


@app.post("/me/projects")
async def me_projects_create(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    name = (body.get("name") or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name required")
    sp = body.get("system_prompt")
    sp = str(sp).strip() if sp else None
    p = await db.create_project(user["id"], name=name[:120], system_prompt=sp)
    if not p:
        raise HTTPException(status_code=500, detail="could not create project")
    return p


@app.patch("/me/projects/{project_id}")
async def me_projects_update(
    project_id: str, request: Request, user: dict = Depends(current_user),
):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    name = None
    if "name" in body and body["name"] is not None:
        name = str(body["name"]).strip()[:120] or None
    set_sp = "system_prompt" in body
    sp = body.get("system_prompt")
    sp = (str(sp).strip() or None) if sp else None
    p = await db.update_project(
        project_id, user["id"], name=name, system_prompt=sp, set_system_prompt=set_sp,
    )
    if not p:
        raise HTTPException(status_code=404, detail="project not found")
    return p


@app.delete("/me/projects/{project_id}")
async def me_projects_delete(project_id: str, user: dict = Depends(current_user)):
    ok = await db.delete_project(project_id, user["id"])
    if not ok:
        raise HTTPException(status_code=404, detail="project not found")
    return {"ok": True}


@app.put("/chats/{chat_id}/project")
async def chats_set_project(
    chat_id: str, request: Request, user: dict = Depends(current_user),
):
    """Assign a chat to a project. Body: {project_id: <id> | null}."""
    body = await _json_body(request)
    project_id = body.get("project_id") if isinstance(body, dict) else None
    chat = await db.set_chat_project(chat_id, user["id"], project_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat or project not found")
    return chat


@app.get("/me/memories")
async def me_memories_list(user: dict = Depends(current_user)):
    return {"memories": await db.list_memories(user["id"])}


@app.post("/me/memories")
async def me_memories_create(
    body: CreateMemoryRequest,
    user: dict = Depends(current_user),
):
    content = body.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="memory content is empty")
    m = await db.add_memory(user["id"], content)
    if m is None:
        raise HTTPException(status_code=400, detail="could not save memory")
    return m


@app.delete("/me/memories/{memory_id}")
async def me_memories_delete(memory_id: int, user: dict = Depends(current_user)):
    ok = await db.delete_memory(user["id"], memory_id)
    if not ok:
        raise HTTPException(status_code=404, detail="memory not found")
    return {"ok": True}


@app.get("/me/starters")
async def me_starters(user: dict = Depends(current_user)):
    """Personalized welcome-screen starter prompts, generated fresh from the
    user's memories on each call."""
    return {"starters": await _generate_starters(user["id"])}


# ─── /me/notifications — in-app notification queue ─────────────────────────

@app.get("/me/notifications")
async def me_notifications(user: dict = Depends(current_user)):
    """Unseen queued notifications — the app drains these on open/resume and
    shows them as local notifications."""
    return {"notifications": await db.list_unseen_notifications(user["id"])}


@app.post("/me/notifications/ack")
async def me_notifications_ack(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict) or not isinstance(body.get("up_to_id"), int):
        raise HTTPException(status_code=400, detail="up_to_id (int) required")
    n = await db.ack_notifications(user["id"], body["up_to_id"])
    return {"acked": n}


# ─── /me/mcp — remote MCP servers CRUD ──────────────────────────────────────

def _redact_mcp(s: dict) -> dict:
    out = dict(s)
    out["auth_header"] = bool(s.get("auth_header"))  # never echo the secret
    return out


@app.get("/me/mcp")
async def me_mcp_list(user: dict = Depends(current_user)):
    return {"servers": [_redact_mcp(s) for s in await db.list_mcp_servers(user["id"])]}


@app.post("/me/mcp")
async def me_mcp_create(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    name = str(body.get("name") or "").strip()[:60]
    url = str(body.get("url") or "").strip()
    auth = str(body.get("auth_header") or "").strip() or None
    if not name or not url:
        raise HTTPException(status_code=400, detail="name and url required")
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="url must be http(s)")
    s = await db.create_mcp_server(user["id"], name, url, auth)
    if not s:
        raise HTTPException(status_code=500, detail="could not create server")
    return _redact_mcp(s)


@app.patch("/me/mcp/{server_id}")
async def me_mcp_update(server_id: str, request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    patch = {}
    if "name" in body and str(body["name"]).strip():
        patch["name"] = str(body["name"]).strip()[:60]
    if "url" in body and str(body["url"]).strip():
        u = str(body["url"]).strip()
        if not (u.startswith("http://") or u.startswith("https://")):
            raise HTTPException(status_code=400, detail="url must be http(s)")
        patch["url"] = u
    if "auth_header" in body:
        patch["auth_header"] = str(body["auth_header"] or "").strip() or None
    if "enabled" in body:
        patch["enabled"] = bool(body["enabled"])
    if not patch:
        raise HTTPException(status_code=400, detail="no valid fields")
    s = await db.update_mcp_server(server_id, user["id"], patch)
    if not s:
        raise HTTPException(status_code=404, detail="server not found")
    mcp_mod._CACHE.pop(server_id, None)  # config changed — drop cached session
    return _redact_mcp(s)


@app.delete("/me/mcp/{server_id}")
async def me_mcp_delete(server_id: str, user: dict = Depends(current_user)):
    if not await db.delete_mcp_server(server_id, user["id"]):
        raise HTTPException(status_code=404, detail="server not found")
    mcp_mod._CACHE.pop(server_id, None)
    return {"ok": True}


@app.post("/me/mcp/{server_id}/test")
async def me_mcp_test(server_id: str, user: dict = Depends(current_user)):
    """Connectivity check: handshake + tools/list, returns the tool names."""
    servers = await db.list_mcp_servers(user["id"])
    server = next((s for s in servers if s["id"] == server_id), None)
    if server is None:
        raise HTTPException(status_code=404, detail="server not found")
    mcp_mod._CACHE.pop(server_id, None)  # force a fresh handshake
    specs = await mcp_mod.get_tool_specs(server)
    if not specs:
        raise HTTPException(status_code=502, detail="handshake or tools/list failed (see backend logs)")
    return {"ok": True, "tools": [s["function"]["name"] for s in specs]}


# ─── /me/schedules — scheduled prompts CRUD ─────────────────────────────────

_RUN_AT_RE = _re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def _validate_schedule_fields(body: dict, *, partial: bool) -> dict:
    out: dict = {}
    if "title" in body or not partial:
        title = str(body.get("title") or "").strip()
        if not title or len(title) > 120:
            raise HTTPException(status_code=400, detail="title required (≤120 chars)")
        out["title"] = title
    if "prompt" in body or not partial:
        prompt = str(body.get("prompt") or "").strip()
        if not prompt or len(prompt) > 4000:
            raise HTTPException(status_code=400, detail="prompt required (≤4000 chars)")
        out["prompt"] = prompt
    if "run_at" in body or not partial:
        run_at = str(body.get("run_at") or "").strip()
        if not _RUN_AT_RE.match(run_at):
            raise HTTPException(status_code=400, detail="run_at must be HH:MM (24h)")
        out["run_at"] = run_at
    if "weekdays" in body:
        try:
            wd = int(body["weekdays"])
        except (TypeError, ValueError):
            raise HTTPException(status_code=400, detail="weekdays must be an int bitmask")
        if not (1 <= wd <= 127):
            raise HTTPException(status_code=400, detail="weekdays must be 1-127 (Mon=1 … Sun=64)")
        out["weekdays"] = wd
    if "enabled" in body:
        out["enabled"] = bool(body["enabled"])
    if "model" in body:
        m = (str(body["model"]).strip() or None) if body["model"] is not None else None
        out["model"] = m
    return out


@app.get("/me/schedules")
async def me_schedules_list(user: dict = Depends(current_user)):
    out = {"schedules": await db.list_schedules(user["id"]),
           "tz": settings.schedule_tz}
    # Scheduler health — surfaced so a dead loop is visible, not silent.
    t = _SCHEDULER_TASK
    if t is None:
        out["scheduler"] = "not started"
    elif t.done():
        exc = None
        try:
            exc = t.exception()
        except Exception:
            pass
        out["scheduler"] = f"DEAD: {type(exc).__name__}: {exc}" if exc else "DEAD: exited"
    else:
        out["scheduler"] = "running"
    return out


@app.post("/me/schedules")
async def me_schedules_create(request: Request, user: dict = Depends(current_user)):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    fields = _validate_schedule_fields(body, partial=False)
    s = await db.create_schedule(
        user["id"], title=fields["title"], prompt=fields["prompt"],
        run_at=fields["run_at"], weekdays=fields.get("weekdays", 127),
        model=fields.get("model"),
    )
    if not s:
        raise HTTPException(status_code=500, detail="could not create schedule")
    return s


@app.patch("/me/schedules/{schedule_id}")
async def me_schedules_update(
    schedule_id: str, request: Request, user: dict = Depends(current_user),
):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    patch = _validate_schedule_fields(body, partial=True)
    if not patch:
        raise HTTPException(status_code=400, detail="no valid fields to patch")
    s = await db.update_schedule(schedule_id, user["id"], patch)
    if not s:
        raise HTTPException(status_code=404, detail="schedule not found")
    return s


@app.delete("/me/schedules/{schedule_id}")
async def me_schedules_delete(schedule_id: str, user: dict = Depends(current_user)):
    ok = await db.delete_schedule(schedule_id, user["id"])
    if not ok:
        raise HTTPException(status_code=404, detail="schedule not found")
    return {"ok": True}


@app.get("/me/uploads")
async def me_uploads_list(user: dict = Depends(current_user)):
    return {"uploads": await db.list_uploads_for_user(user["id"])}


@app.put("/me/uploads/{upload_id}/project")
async def me_uploads_set_project(
    upload_id: str, request: Request, user: dict = Depends(current_user),
):
    """Attach an upload to a project as a knowledge file (null detaches)."""
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    project_id = body.get("project_id") or None
    ok = await db.set_upload_project(upload_id, user["id"], project_id)
    if not ok:
        raise HTTPException(status_code=404, detail="upload or project not found")
    return {"ok": True, "project_id": project_id}


@app.post("/me/uploads/{upload_id}/reindex")
async def me_uploads_reindex(upload_id: str, user: dict = Depends(current_user)):
    """Re-run chunking + embedding for an existing upload. Useful when the
    initial background indexing failed (e.g. embed batch too large)."""
    meta = await db.get_upload(upload_id, user_id=user["id"])
    if not meta:
        raise HTTPException(status_code=404, detail="upload not found")
    if meta.get("kind") not in up_mod.TEXTUAL_KINDS:
        raise HTTPException(status_code=400, detail="only text-bearing uploads are indexable")
    full_text = await up_mod.read_full_text(meta)
    if not full_text:
        raise HTTPException(status_code=400, detail="could not read upload contents")
    # Wipe any partial chunks first so we don't double-index
    try:
        await rag.delete_upload_chunks(user["id"], upload_id)
    except Exception:
        pass
    count = await rag.index_document(
        user_id=user["id"],
        upload_id=upload_id,
        filename=meta["filename"],
        kind=meta["kind"],
        text=full_text,
    )
    return {"upload_id": upload_id, "chunks": count}


@app.delete("/me/uploads/{upload_id}")
async def me_uploads_delete(upload_id: str, user: dict = Depends(current_user)):
    meta = await db.delete_upload(upload_id, user["id"])
    if meta is None:
        raise HTTPException(status_code=404, detail="upload not found")
    # Remove bytes from disk
    path = meta.get("storage_path")
    if path and os.path.exists(path):
        try:
            os.remove(path)
        except OSError as e:
            log.warning("could not remove %s: %s", path, e)
    # Remove qdrant chunks (text-bearing kinds only)
    if meta.get("kind") in up_mod.TEXTUAL_KINDS:
        try:
            await rag.delete_upload_chunks(user["id"], upload_id)
        except Exception as e:
            log.warning("could not delete rag chunks for %s: %s", upload_id, e)
    return {"ok": True}


# ─── Sandbox project mounts (UI-managed bind mounts) ───────────────────────
# The user manages a list of host directories that get bind-mounted into the
# tomsense-sandbox at /workspace/projects/<name>/. Config + override-file
# generation live in mounts.py; apply uses the scoped tomsense-docker-proxy
# sidecar to recreate the sandbox container.


@app.get("/me/mounts")
async def me_mounts(user: dict = Depends(current_user)):
    return {
        "mounts": _mounts.load_config(),
        "apply_supported": _mounts.is_apply_supported(),
    }


class _MountCreateBody(BaseModel):
    name: str
    host_path: str


@app.post("/me/mounts")
async def me_mounts_create(body: _MountCreateBody, user: dict = Depends(current_user)):
    try:
        return {"mounts": _mounts.add_mount(body.name, body.host_path)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.delete("/me/mounts/{name}")
async def me_mounts_delete(name: str, user: dict = Depends(current_user)):
    try:
        return {"mounts": _mounts.remove_mount(name)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except KeyError:
        raise HTTPException(status_code=404, detail=f"no mount named {name!r}")


@app.post("/me/mounts/apply")
async def me_mounts_apply(user: dict = Depends(current_user)):
    """Recreate tomsense-sandbox with the current mount config. The sandbox
    has a ~5s startup window during which any in-flight code-mode chat
    will fail with a connection error — that's expected, the chat client
    retries cleanly on the next message."""
    return await _mounts.apply()


# ─── /sandbox file manager (user-facing proxy to the executor shim) ─────────
# The model-side tools in tools_code.py call the sandbox directly with the
# shared SANDBOX_TOKEN. These mirror endpoints are auth'd via current_user
# (Cloudflare Access in prod) so the /files UI can drive the same volume.

from .tools_code import _call as _sandbox_call


class _SandboxWriteBody(BaseModel):
    path: str
    content: str


class _SandboxRenameBody(BaseModel):
    src: str
    dst: str


class _SandboxDeleteBody(BaseModel):
    path: str
    recursive: bool = False


class _SandboxPathBody(BaseModel):
    path: str


async def _proxy_sandbox(endpoint: str, payload: dict) -> dict:
    """Translate the sandbox's RuntimeError → HTTPException so the UI sees a
    real status code instead of a 500."""
    try:
        return await _sandbox_call(endpoint, payload)
    except RuntimeError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/sandbox/fs/list")
async def sandbox_fs_list(path: str = ".", user: dict = Depends(current_user)):
    return await _proxy_sandbox("/fs/list", {"path": path})


@app.get("/sandbox/fs/read")
async def sandbox_fs_read(
    path: str,
    offset: int = 0,
    limit: int = 2000,
    user: dict = Depends(current_user),
):
    return await _proxy_sandbox("/fs/read", {"path": path, "offset": offset, "limit": limit})


@app.post("/sandbox/fs/write")
async def sandbox_fs_write(body: _SandboxWriteBody, user: dict = Depends(current_user)):
    return await _proxy_sandbox("/fs/write", {"path": body.path, "content": body.content})


@app.post("/sandbox/fs/rename")
async def sandbox_fs_rename(body: _SandboxRenameBody, user: dict = Depends(current_user)):
    return await _proxy_sandbox("/fs/rename", {"src": body.src, "dst": body.dst})


@app.post("/sandbox/fs/delete")
async def sandbox_fs_delete(body: _SandboxDeleteBody, user: dict = Depends(current_user)):
    return await _proxy_sandbox("/fs/delete", {"path": body.path, "recursive": body.recursive})


@app.post("/sandbox/fs/mkdir")
async def sandbox_fs_mkdir(body: _SandboxPathBody, user: dict = Depends(current_user)):
    return await _proxy_sandbox("/fs/mkdir", {"path": body.path})


# ─── /chats CRUD (user-scoped) ──────────────────────────────────────────────

@app.get("/chats")
async def chats_list(user: dict = Depends(current_user)):
    return {"chats": await db.list_chats(user["id"])}


@app.post("/chats")
async def chats_create(
    body: CreateChatRequest,
    user: dict = Depends(current_user),
):
    # Precedence: explicit request override → user's pref → global default.
    # The user pref ensures a new chat picks up the model they've set as their
    # tool_models.<kind> default, instead of being permanently pinned to
    # whatever was in env at chat-creation time. Code chats read the
    # `code_mode` pref (the agentic coder); regular chats read `chat`.
    model = body.model
    if not model:
        try:
            prefs = await db.get_user_prefs(user["id"])
            tm = (prefs or {}).get("tool_models") or {}
            model = tm.get("code_mode" if body.code else "chat") or None
        except Exception:
            model = None
    # Fall back to the env default for the appropriate kind.
    model = model or (settings.model_coder if body.code else settings.model_chat)
    return await db.create_chat(
        user_id=user["id"], model=model, title=body.title, is_code=body.code,
    )


@app.get("/chats/search")
async def chats_search(q: str = "", user: dict = Depends(current_user)):
    """Search the user's chats by title OR message content. Defined before
    /chats/{chat_id} so the literal path wins the route match."""
    return {"chats": await db.search_chats(user["id"], q)}


@app.post("/chats/delete-batch")
async def chats_delete_batch(request: Request, user: dict = Depends(current_user)):
    """Bulk-delete chats. Body: {ids: [...]}. POST (not DELETE) so the id list
    travels in a body rather than the URL."""
    body = await _json_body(request)
    ids = body.get("ids") if isinstance(body, dict) else None
    if not isinstance(ids, list) or not ids:
        raise HTTPException(status_code=400, detail="ids must be a non-empty list")
    deleted = await db.delete_chats([str(i) for i in ids], user["id"])
    return {"deleted": deleted}


@app.get("/chats/{chat_id}")
async def chats_get(chat_id: str, user: dict = Depends(current_user)):
    chat = await db.get_chat(chat_id, user_id=user["id"])
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.patch("/chats/{chat_id}")
async def chats_rename(
    chat_id: str,
    body: RenameChatRequest,
    user: dict = Depends(current_user),
):
    chat = await db.rename_chat(chat_id, user["id"], body.title.strip())
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.delete("/chats/{chat_id}")
async def chats_delete(chat_id: str, user: dict = Depends(current_user)):
    ok = await db.delete_chat(chat_id, user["id"])
    if not ok:
        raise HTTPException(status_code=404, detail="chat not found")
    return {"ok": True}


@app.put("/chats/{chat_id}/system_prompt")
async def chats_set_system_prompt(
    chat_id: str,
    body: UpdateSystemPromptRequest,
    user: dict = Depends(current_user),
):
    chat = await db.update_chat_system_prompt(chat_id, user["id"], body.system_prompt)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.put("/chats/{chat_id}/model")
async def chats_set_model(
    chat_id: str,
    body: UpdateModelRequest,
    user: dict = Depends(current_user),
):
    chat = await db.update_chat_model(chat_id, user["id"], body.model.strip())
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.get("/chats/{chat_id}/export")
async def chats_export(
    chat_id: str,
    format: str = "md",
    user: dict = Depends(current_user),
):
    chat = await db.get_chat(chat_id, user_id=user["id"])
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")

    fmt = (format or "md").lower()
    if fmt not in ("md", "json"):
        raise HTTPException(status_code=400, detail="format must be 'md' or 'json'")

    # Whitelist filename chars — a title containing `"` or control chars
    # would otherwise break out of the quoted Content-Disposition value
    # (header injection). Everything else becomes "-".
    safe_title = _re.sub(r'[^\w \-.()\[\]]', "-", chat.get("title") or "chat").strip() or "chat"
    filename = f"{safe_title[:60]}.{fmt}"

    if fmt == "json":
        body = _json.dumps(chat, indent=2)
        media = "application/json"
    else:
        lines: list[str] = []
        lines.append(f"# {chat.get('title') or 'Chat'}\n")
        lines.append(f"_Model:_ `{chat.get('model')}`  ")
        lines.append(f"_Created:_ {chat.get('created_at')}  ")
        lines.append(f"_Updated:_ {chat.get('updated_at')}  \n")
        if chat.get("system_prompt"):
            lines.append("## Custom instructions\n")
            lines.append(f"> {chat['system_prompt']}\n")
        for m in chat.get("messages", []):
            role = m["role"]
            heading = "## You" if role == "user" else "## TomSense" if role == "assistant" else f"## {role}"
            lines.append(f"\n{heading} — {m.get('created_at','')}\n")
            for u in (m.get("uploads") or []):
                lines.append(f"_📎 {u['filename']}_ ({u['kind']}, {u['size_bytes']} B)\n")
            lines.append((m.get("content") or "").strip())
        body = "\n".join(lines) + "\n"
        media = "text/markdown; charset=utf-8"

    return StreamingResponse(
        iter([body.encode("utf-8")]),
        media_type=media,
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


@app.post("/chats/{chat_id}/share")
async def chats_create_share(chat_id: str, user: dict = Depends(current_user)):
    token = secrets.token_urlsafe(16)
    chat = await db.set_share_token(chat_id, user["id"], token)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return {"share_token": token, "chat_id": chat["id"]}


@app.delete("/chats/{chat_id}/share")
async def chats_revoke_share(chat_id: str, user: dict = Depends(current_user)):
    chat = await db.set_share_token(chat_id, user["id"], None)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return {"ok": True}


@app.get("/share/{token}")
async def share_view(token: str):
    """Public read-only chat view — no auth required."""
    chat = await db.get_chat_by_share_token(token)
    if chat is None:
        raise HTTPException(status_code=404, detail="share link not found")
    return chat


@app.get("/chats/{chat_id}/artifacts")
async def chats_artifacts(chat_id: str, user: dict = Depends(current_user)):
    chat = await db.get_chat(chat_id, user_id=user["id"])
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return {"artifacts": await db.list_artifacts(chat_id)}


# Cross-chat artifact listing for the /files file manager. Uploads have
# `/me/uploads`; this mirrors that pattern for chat-generated artifacts.
@app.get("/me/artifacts")
async def me_artifacts(user: dict = Depends(current_user)):
    return {"artifacts": await db.list_user_artifacts(user["id"])}


@app.get("/artifacts/{artifact_id}")
async def artifact_get(artifact_id: int, user: dict = Depends(current_user)):
    a = await db.get_user_artifact(user["id"], artifact_id)
    if a is None:
        raise HTTPException(status_code=404, detail="artifact not found")
    return a


@app.patch("/artifacts/{artifact_id}")
async def artifact_update(
    artifact_id: int, request: Request, user: dict = Depends(current_user),
):
    """Canvas editing: save panel edits to a code artifact's content/title."""
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be an object")
    patch = {}
    if "content" in body:
        c = str(body["content"] or "")
        if len(c) > 200_000:
            raise HTTPException(status_code=400, detail="content too large (200k max)")
        patch["content"] = c
    if "title" in body:
        patch["title"] = str(body["title"] or "").strip()[:200] or None
    if not patch:
        raise HTTPException(status_code=400, detail="nothing to update")
    a = await db.update_user_artifact(user["id"], artifact_id, patch)
    if a is None:
        raise HTTPException(status_code=404, detail="artifact not found")
    return a


@app.delete("/artifacts/{artifact_id}")
async def artifact_delete(artifact_id: int, user: dict = Depends(current_user)):
    if not await db.delete_user_artifact(user["id"], artifact_id):
        raise HTTPException(status_code=404, detail="artifact not found")
    return {"ok": True}


@app.put("/chats/{chat_id}/pin")
async def chats_pin(
    chat_id: str,
    body: PinRequest,
    user: dict = Depends(current_user),
):
    chat = await db.set_chat_pinned(chat_id, user["id"], body.is_pinned)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.put("/chats/{chat_id}/folder")
async def chats_folder(
    chat_id: str,
    body: FolderRequest,
    user: dict = Depends(current_user),
):
    chat = await db.set_chat_folder(chat_id, user["id"], body.folder)
    if chat is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return chat


@app.post("/chats/{chat_id}/branch")
async def chats_branch(
    chat_id: str,
    body: BranchRequest,
    user: dict = Depends(current_user),
):
    new_chat = await db.branch_chat(chat_id, user["id"], body.message_id)
    if new_chat is None:
        raise HTTPException(status_code=404, detail="chat not found or invalid message id")
    return new_chat


# ─── /me/personas (Phase C personas library) ────────────────────────────────

@app.get("/me/personas")
async def me_personas_list(user: dict = Depends(current_user)):
    return {"personas": await db.list_personas(user["id"])}


@app.post("/me/personas")
async def me_personas_create(
    body: CreatePersonaRequest,
    user: dict = Depends(current_user),
):
    p = await db.create_persona(user["id"], body.name, body.system_prompt, body.model)
    if p is None:
        raise HTTPException(status_code=400, detail="could not create persona")
    return p


@app.put("/me/personas/{persona_id}")
async def me_personas_update(
    persona_id: str,
    body: UpdatePersonaRequest,
    user: dict = Depends(current_user),
):
    p = await db.update_persona(persona_id, user["id"], body.name, body.system_prompt, body.model)
    if p is None:
        raise HTTPException(status_code=404, detail="persona not found")
    return p


@app.delete("/me/personas/{persona_id}")
async def me_personas_delete(
    persona_id: str,
    user: dict = Depends(current_user),
):
    ok = await db.delete_persona(persona_id, user["id"])
    if not ok:
        raise HTTPException(status_code=404, detail="persona not found")
    return {"ok": True}


@app.post("/chats/{chat_id}/regenerate")
async def chats_regenerate(chat_id: str, user: dict = Depends(current_user)):
    """Delete the most recent assistant message so the client can re-stream
    a fresh response off the user's last message."""
    deleted_id = await db.delete_last_assistant_message(chat_id, user["id"])
    if deleted_id is None:
        raise HTTPException(status_code=404, detail="no assistant message to regenerate")
    return {"deleted_message_id": deleted_id}


@app.delete("/chats/{chat_id}/messages/from/{message_id}")
async def chats_messages_truncate(
    chat_id: str,
    message_id: int,
    user: dict = Depends(current_user),
):
    """Delete the given message + every message after it in this chat. Used
    by the edit-and-resend flow."""
    removed = await db.delete_messages_from(chat_id, user["id"], message_id)
    if removed == 0:
        raise HTTPException(status_code=404, detail="nothing to delete (chat not found or id past end)")
    return {"removed": removed}


# ─── /uploads (user-scoped) ─────────────────────────────────────────────────

@app.post("/uploads")
async def uploads_create(
    file: UploadFile = File(...),
    user: dict = Depends(current_user),
):
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty file")
    try:
        meta = up_mod.process_upload(
            filename=file.filename or "upload",
            mime=file.content_type or "application/octet-stream",
            raw=raw,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    # Scanned PDFs: no text layer → render pages and OCR them with the vision
    # model, so the excerpt/RAG path works for photographed documents too.
    if meta["kind"] == "pdf" and not (meta.get("text_excerpt") or "").strip():
        pages = up_mod.render_pdf_pages(raw)
        ocr_parts: list[str] = []
        for i, jpeg in enumerate(pages, start=1):
            try:
                import base64 as _b64
                data_url = "data:image/jpeg;base64," + _b64.b64encode(jpeg).decode()
                r = await providers.dispatch_chat_complete(
                    user_id=user["id"],
                    model_str=settings.model_chat,
                    messages=[{
                        "role": "user",
                        "content": [
                            {"type": "text", "text":
                             "Transcribe ALL text visible in this document page, "
                             "verbatim, preserving reading order. Output only the "
                             "transcription, no commentary."},
                            {"type": "image_url", "image_url": {"url": data_url}},
                        ],
                    }],
                    max_tokens=1500,
                )
                t = (r.get("content") or "").strip()
                if t:
                    ocr_parts.append(f"[page {i}]\n{t}")
            except Exception as e:
                log.warning("PDF OCR page %d failed: %s", i, e)
        if ocr_parts:
            meta["text_excerpt"] = (
                "[OCR of scanned PDF]\n" + "\n\n".join(ocr_parts)
            )[: up_mod.MAX_EXCERPT_CHARS]

    # Audio: transcribe at ingest so the transcript rides the normal
    # text-excerpt path (chat prepend + RAG). Same provider pick as /stt.
    if meta["kind"] == "audio":
        prefs = await db.get_user_prefs(user["id"])
        provider = (prefs or {}).get("stt_provider") or os.getenv("STT_PROVIDER", "whisper")
        try:
            transcript = await stt_mod.transcribe(
                provider, raw, meta["filename"], file.content_type or "audio/mpeg",
                user_id=user["id"],
            )
            meta["text_excerpt"] = transcript[: up_mod.MAX_EXCERPT_CHARS] or None
        except Exception as e:
            log.warning("audio upload transcription failed: %s", e)
            meta["text_excerpt"] = f"[transcription failed: {str(e)[:200]}]"

    record = await db.insert_upload(
        upload_id=meta["upload_id"],
        user_id=user["id"],
        kind=meta["kind"],
        filename=meta["filename"],
        mime=meta["mime"],
        size_bytes=meta["size_bytes"],
        text_excerpt=meta["text_excerpt"],
        storage_path=meta["storage_path"],
    )

    # RAG: index text-bearing uploads for later retrieval. Run in the
    # background — the upload response shouldn't wait on embedding.
    if meta["kind"] in up_mod.TEXTUAL_KINDS:
        full_text = await up_mod.read_full_text(meta)
        if full_text:
            _spawn(rag.index_document(
                user_id=user["id"],
                upload_id=record["id"],
                filename=record["filename"],
                kind=record["kind"],
                text=full_text,
            ))

    return {
        "id": record["id"],
        "kind": record["kind"],
        "filename": record["filename"],
        "mime": record["mime"],
        "size_bytes": record["size_bytes"],
        "text_preview": (record["text_excerpt"] or "")[:500] if record["text_excerpt"] else None,
    }


@app.get("/uploads/{upload_id}/raw")
async def uploads_raw(
    upload_id: str,
    user: dict = Depends(current_user),
):
    meta = await db.get_upload(upload_id, user_id=user["id"])
    if not meta or not meta.get("storage_path") or not os.path.exists(meta["storage_path"]):
        raise HTTPException(status_code=404, detail="upload not found")
    return FileResponse(
        meta["storage_path"],
        media_type=meta["mime"] or "application/octet-stream",
        filename=meta["filename"],
        headers={"Cache-Control": "public, max-age=86400"},
    )


# ─── /transcribe (STT, provider-switchable) ────────────────────────────────

@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    user: dict = Depends(current_user),
):
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty audio")
    prefs = await db.get_user_prefs(user["id"])
    provider = prefs.get("stt_provider") or os.getenv("STT_PROVIDER", "whisper")
    try:
        text = await stt_mod.transcribe(
            provider,
            raw,
            file.filename or "audio.webm",
            file.content_type or "audio/webm",
            user_id=user["id"],
        )
    except stt_mod.STTInputError as e:
        raise HTTPException(status_code=400, detail=f"invalid audio: {e}")
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"{provider} failed: {e}")
    return {"text": text}


# ─── /tts (provider-switchable) ────────────────────────────────────────────

@app.post("/tts")
async def tts(
    request: Request,
    user: dict = Depends(current_user),
):
    body = await _json_body(request)
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be a JSON object")
    text = (body.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="empty text")
    fmt = (body.get("format") or "mp3").lower()

    prefs = await db.get_user_prefs(user["id"])
    provider = (
        body.get("provider")
        or prefs.get("tts_provider")
        or os.getenv("TTS_PROVIDER", "piper")
    )
    voice = (
        body.get("voice")
        or prefs.get("tts_voice")
        or settings.tts_voice
    )

    try:
        stream = await tts_mod.open_tts(provider, text, voice, fmt, user_id=user["id"])
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"{provider} failed: {e}")

    return StreamingResponse(
        stream.aiter(),
        media_type=stream.media_type,
        headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
    )


# ─── /chat/stream — agentic SSE chat with optional persistence ──────────────

def _msg_chars(m: dict) -> int:
    c = m.get("content")
    if isinstance(c, str):
        return len(c)
    if isinstance(c, list):
        return sum(len(p.get("text", "")) if isinstance(p, dict) else len(str(p)) for p in c)
    return 0


def _serialize_for_summary(messages: list[dict]) -> str:
    parts = []
    for m in messages:
        role = m.get("role", "?")
        c = m.get("content")
        if isinstance(c, list):
            c = " ".join(p.get("text", "[image]") if isinstance(p, dict) else str(p) for p in c)
        parts.append(f"[{role}] {str(c)[:1200]}")
    return "\n\n".join(parts)[:40000]


async def _summarize_to_text(transcript_blob: str, user_id: Optional[str] = None) -> Optional[str]:
    """Call the small task model to produce a summary. Returns None on error.
    Goes through the provider layer so users can later swap the summary model
    to a cheaper non-CF option."""
    prompt = (
        "Summarize the following conversation in 250-400 words. Capture "
        "concrete facts the user shared, decisions made, code/files referenced, "
        "and any unresolved threads. Use neutral third-person. NO markdown.\n\n"
        + transcript_blob
    )
    try:
        result = await providers.dispatch_chat_complete(
            user_id=user_id,
            model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{"role": "user", "content": prompt}],
            max_tokens=600,
        )
        return (result.get("content") or "").strip() or None
    except Exception as e:
        log.warning("auto-summary failed: %s — sending full history", e)
        return None


async def _maybe_summarize(
    messages: list[dict],
    chat_id: Optional[str],
    user_id: Optional[str] = None,
) -> tuple[list[dict], int]:
    """Apply incremental summarization to a request's message list.

    Returns (new_messages, summarized_count). summarized_count is the total
    number of older turns folded into the summary now in the prompt — 0 if
    no summary is being applied. Threaded through to the stats footer so the
    user can see retroactively when summary kicked in.

    Strategy: keep a per-chat cache of (summary_text, summary_up_to_idx) in
    the DB. On each request, splice the cached summary in as a system message
    and only forward msgs[K:]. Re-run the summarizer (folding cached + new
    older into a fresh summary) only when the cached-summary + tail crosses
    the threshold again. For unpersisted chats (chat_id=None) we fall back to
    the one-shot fresh-every-turn behavior so the call still gets bounded.
    """
    if not messages:
        return messages, 0

    keep = max(1, settings.auto_summary_keep_recent)
    threshold = settings.auto_summary_threshold

    # ─── unpersisted fast path (no chat_id → can't cache) ────────────────
    if not chat_id:
        total = sum(_msg_chars(m) for m in messages)
        if total <= threshold or len(messages) <= keep:
            return messages, 0
        older = messages[:-keep]
        recent = messages[-keep:]
        summary = await _summarize_to_text(_serialize_for_summary(older), user_id=user_id)
        if not summary:
            return messages, 0
        sys_msg = {
            "role": "system",
            "content": (
                f"# Summary of {len(older)} earlier messages "
                f"({_chars_short(total)} chars truncated)\n\n{summary}"
            ),
        }
        log.info(
            "[summary] (no cache, ephemeral) folded %d turns (%s chars) → ~%d-char summary",
            len(older), _chars_short(total), len(summary),
        )
        return [sys_msg, *recent], len(older)

    # ─── persisted chat: use / extend the cache ──────────────────────────
    cached_text, cached_up_to = await db.get_chat_summary(chat_id)
    # Guard against a stale cache that points past current msg count (e.g.
    # truncation invalidation race). Treat as no-cache in that case.
    if cached_up_to > len(messages):
        cached_text, cached_up_to = None, 0

    # Build the proposed prompt: [cached system msg if any] + msgs[K:]
    def _make_sys(text: str, covers: int) -> dict:
        return {
            "role": "system",
            "content": f"# Summary of {covers} earlier messages\n\n{text}",
        }

    tail = messages[cached_up_to:]
    sys_msg_chars = (len(cached_text) + 64) if cached_text else 0  # +64 for the heading
    proposed_chars = sys_msg_chars + sum(_msg_chars(m) for m in tail)

    if proposed_chars <= threshold:
        # Under threshold — use cache as-is (no LLM call this turn).
        if cached_text:
            return [_make_sys(cached_text, cached_up_to), *tail], cached_up_to
        return messages, 0

    # Over threshold AND we have at least `keep` recent to preserve.
    if len(tail) <= keep:
        # Nothing new to fold (rare — only possible when cached summary +
        # very few tail messages already exceed threshold, which means the
        # cache itself is bloated. Send as-is rather than infinitely re-fold).
        if cached_text:
            return [_make_sys(cached_text, cached_up_to), *tail], cached_up_to
        return messages, 0

    # Re-summarize: fold (cached summary, if any) + tail[:-keep] into new summary.
    new_older = tail[:-keep]
    new_recent = tail[-keep:]
    transcript_parts = []
    if cached_text:
        transcript_parts.append(
            f"[prior-summary covering {cached_up_to} earlier messages]\n{cached_text}"
        )
    transcript_parts.append(_serialize_for_summary(new_older))
    transcript_blob = "\n\n".join(transcript_parts)[:40000]

    new_summary = await _summarize_to_text(transcript_blob, user_id=user_id)
    if not new_summary:
        # Summarizer failed — keep cached version active and just send tail.
        if cached_text:
            return [_make_sys(cached_text, cached_up_to), *tail], cached_up_to
        return messages, 0

    new_up_to = cached_up_to + len(new_older)
    try:
        await db.set_chat_summary(chat_id, new_summary, new_up_to)
    except Exception as e:
        log.warning("set_chat_summary failed (%s) — still using new summary for this turn", e)
    log.info(
        "[summary] re-summarized: folded %d new turns (cumulative %d) → ~%d-char summary",
        len(new_older), new_up_to, len(new_summary),
    )
    return [_make_sys(new_summary, new_up_to), *new_recent], new_up_to


def _chars_short(n: int) -> str:
    if n < 1000:
        return f"{n}"
    if n < 1_000_000:
        return f"{n//1000}k"
    return f"{n/1_000_000:.1f}M"


async def _generate_title(first_user_msg: str, user_id: Optional[str] = None) -> Optional[str]:
    if not first_user_msg.strip():
        return None
    prompt = (
        "Generate a 3-6 word title for a chat that begins with the following "
        "user message. Return ONLY the title, no quotes, no punctuation at the "
        "end, no prefix.\n\nUser message:\n" + first_user_msg[:500]
    )
    try:
        result = await providers.dispatch_chat_complete(
            user_id=user_id,
            model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{"role": "user", "content": prompt}],
            max_tokens=24,
        )
        title = (result.get("content") or "").strip().strip('"').strip("'")
        title = title.split("\n")[0].strip().rstrip(".!?")
        if not title or len(title) > 80:
            return title[:80] if title else None
        return title
    except Exception as e:
        log.warning("title gen failed: %s", e)
        return None


def _msg_plain_text(content) -> str:
    """Flatten a message's content to plain text (handles multimodal lists)
    and drop tool-call/reasoning chips."""
    if isinstance(content, list):
        content = " ".join(
            p.get("text", "") for p in content
            if isinstance(p, dict) and p.get("type") == "text"
        )
    if not isinstance(content, str):
        return ""
    return _re.sub(r"<details[\s\S]*?</details>", "", content).strip()


async def _generate_followups(chat_id: str, user_id: str) -> list[str]:
    """Suggest a few follow-up questions for a chat, via the cheap title
    model. Best-effort — returns [] on any failure."""
    chat = await db.get_chat(chat_id, user_id=user_id)
    if not chat:
        return []
    msgs = [
        m for m in (chat.get("messages") or [])
        if m.get("role") in ("user", "assistant")
    ]
    if not msgs:
        return []
    convo = "\n\n".join(
        f"{m['role'].upper()}: {_msg_plain_text(m.get('content'))[:800]}"
        for m in msgs[-4:]
    )
    prompt = (
        "Based on this conversation, suggest 3 brief follow-up questions the "
        "user is likely to ask next. Each must be short (3-9 words), natural, "
        "phrased in the first person, and a sensible next step. Return ONLY "
        "the 3 questions, one per line — no numbering, bullets, or quotes.\n\n"
        + convo
    )
    try:
        result = await providers.dispatch_chat_complete(
            user_id=user_id,
            model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{"role": "user", "content": prompt}],
            max_tokens=90,
        )
        out: list[str] = []
        for line in (result.get("content") or "").splitlines():
            line = line.strip().lstrip("-*•0123456789.) ").strip().strip('"').strip()
            if line and len(line) <= 100:
                out.append(line)
        return out[:3]
    except Exception as e:
        log.warning("followup gen failed: %s", e)
        return []


_DEFAULT_STARTERS: list[dict] = [
    {"icon": "sparkles", "text": "Explain what zero-shot prompting is"},
    {"icon": "image",    "text": "Draw a cyberpunk skyline at golden hour"},
    {"icon": "file",     "text": "Search my docs for the key takeaways"},
    {"icon": "brain",    "text": "Remember that I prefer concise answers"},
]


def _starter_icon(text: str) -> str:
    t = text.lower()
    if any(k in t for k in ("draw", "image", "picture", "photo", "logo", "sketch",
                            "illustrat", "wallpaper", "paint", "render", "poster")):
        return "image"
    if t.startswith("remember") or "remember that" in t or "note that i" in t:
        return "brain"
    if any(k in t for k in ("my doc", "my note", "my file", "search my",
                            "in my document", "the manual", "uploaded")):
        return "file"
    return "sparkles"


async def _generate_starters(user_id: str) -> list[dict]:
    """Personalized welcome-screen starters, generated from the user's saved
    memories on the utility model. Falls back to the static defaults when
    there's nothing to personalize on or generation fails."""
    try:
        memories = await db.list_memories(user_id)
    except Exception:
        memories = []
    if not memories:
        return _DEFAULT_STARTERS
    mem_lines = "\n".join(f"- {m['content']}" for m in memories[-40:])
    has_docs = False
    try:
        has_docs = len(await db.list_uploads_for_user(user_id)) > 0
    except Exception:
        pass
    doc_rule = ("- Include ONE prompt that searches their uploaded documents.\n"
                if has_docs else "")
    prompt = (
        "You are writing starter prompts for the home screen of a personal AI "
        "assistant. Below are facts the assistant remembers about the user.\n"
        "Write EXACTLY 4 starter prompts this specific user would plausibly "
        "want to send right now. Rules:\n"
        "- First person, as if the user typed it.\n"
        "- Each 4-12 words, specific, genuinely useful to THIS user.\n"
        "- Varied: mix practical help, something creative/image, and something "
        "drawing on their interests or projects.\n"
        + doc_rule +
        "- Do NOT mention these instructions or that facts were provided.\n"
        "- Return ONLY the 4 prompts, one per line, no numbering/bullets/quotes.\n\n"
        f"Remembered facts:\n{mem_lines}"
    )
    try:
        result = await providers.dispatch_chat_complete(
            user_id=user_id, model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{"role": "user", "content": prompt}], max_tokens=160,
        )
        out: list[dict] = []
        seen: set = set()
        for line in (result.get("content") or "").splitlines():
            text = line.strip().lstrip("-*•0123456789.) ").strip().strip('"').strip()
            key = text.lower()
            if not text or len(text) > 90 or key in seen:
                continue
            seen.add(key)
            out.append({"icon": _starter_icon(text), "text": text})
            if len(out) >= 4:
                break
        return out or _DEFAULT_STARTERS
    except Exception as e:
        log.warning("starter gen failed: %s", e)
        return _DEFAULT_STARTERS


async def _auto_memory(user_id: str, user_text: str) -> None:
    """ChatGPT-style automatic memory: scan the user's message for durable
    personal facts (preferences, biography, ongoing projects) and save them.
    Tiny-model call (~1 neuron); dedupes against existing memories."""
    text = (user_text or "").strip()
    if len(text) < 25:
        return  # too short to contain a durable fact
    try:
        prefs = await db.get_user_prefs(user_id)
        if not (prefs or {}).get("auto_memory", True):
            return
        existing = await db.list_memories(user_id)
        existing_texts = [m["content"] for m in existing]
        listing = "\n".join(f"- {c}" for c in existing_texts[-50:]) or "- (none)"
        result = await providers.dispatch_chat_complete(
            user_id=user_id,
            model_str=await _task_model(user_id),
            fallback_model_str=await _task_fallback(user_id),
            messages=[{
                "role": "user",
                "content": (
                    "You maintain long-term memory about a user. Below is "
                    "their latest chat message and the facts already stored.\n"
                    "Extract at most 2 NEW durable facts about the user "
                    "worth remembering across conversations: stable "
                    "preferences, biography, relationships, possessions, "
                    "ongoing projects. NOT: one-off requests, questions, "
                    "moods, anything already stored below.\n"
                    "Output one fact per line, third person ('User …'), "
                    "max 120 chars each. If there is nothing new and "
                    "durable, output exactly: NONE\n\n"
                    f"Already stored:\n{listing}\n\n"
                    f"Message:\n{text[:2000]}"
                ),
            }],
            max_tokens=120,
        )
        raw = (result.get("content") or "").strip()
        if not raw or raw.upper().startswith("NONE"):
            return
        saved = 0
        for line in raw.splitlines():
            fact = _re.sub(r"^[\s\-*•\d.)]+", "", line).strip()
            if not (15 <= len(fact) <= 200) or saved >= 2:
                continue
            if not fact.lower().startswith("user"):
                continue  # off-format chatter from the small model
            # Dedupe: substring either way against existing memories.
            fl = fact.lower().rstrip(".")
            if any(fl in e.lower() or e.lower().rstrip(".") in fl for e in existing_texts):
                continue
            if await db.add_memory(user_id, fact):
                existing_texts.append(fact)
                saved += 1
                log.info("auto-memory saved: %s", fact[:80])
    except Exception as e:
        log.info("auto-memory skipped: %s", e)


async def _autotitle_if_needed(chat_id: str, user_id: str) -> None:
    chat = await db.get_chat(chat_id, user_id=user_id)
    if not chat or chat.get("title"):
        return
    first = await db.first_user_message(chat_id)
    if not first:
        return
    title = await _generate_title(first, user_id=user_id)
    if title:
        await db.rename_chat(chat_id, user_id, title)


# Image ACTION intent — the user wants to modify/create an image, so the turn
# should call edit_image/generate_image (which get the image directly) rather
# than route to a vision model to "read" it. Kept deliberately narrow to
# action verbs so questions ("what is this?", "describe…") still get vision.
_IMAGE_ACTION_RE = _re.compile(
    r"\b(edit|add|remove|erase|delete|change|replace|swap|make (?:it|this|the)|"
    r"turn (?:it|this)|put (?:a|an|some)|give (?:it|the|them)|restyle|redraw|"
    r"recolou?r|colou?r|paint|draw|generate|create|render|upscale|enhance|"
    r"blur|crop|rotate|flip|combine|merge|overlay|convert (?:it|this)|"
    r"cartoon|anime|photoreal|background)\b",
    _re.I,
)


def _has_image_input(msgs: list[dict]) -> bool:
    """True when any message carries multimodal image parts — this turn's
    attachment or an earlier one still in context (follow-up questions about
    a photo need the vision model too)."""
    for m in msgs:
        c = m.get("content")
        if isinstance(c, list) and any(
            isinstance(p, dict) and p.get("type") == "image_url" for p in c
        ):
            return True
    return False


def _augment_last_user_with_uploads(msgs: list[dict], upload_metas: list[dict]) -> str:
    if not msgs or not upload_metas:
        return msgs[-1].get("content", "") if msgs else ""
    last = msgs[-1]
    if last.get("role") != "user":
        return last.get("content", "")

    user_text = last.get("content") or ""
    if isinstance(user_text, list):
        parts = []
        for p in user_text:
            if isinstance(p, dict) and p.get("type") == "text":
                parts.append(p.get("text", ""))
        user_text = " ".join(parts).strip()

    excerpts = []
    image_parts = []
    for meta in upload_metas:
        kind = meta.get("kind")
        if kind in up_mod.TEXTUAL_KINDS and meta.get("text_excerpt"):
            excerpts.append(
                f"<file name=\"{meta['filename']}\" kind=\"{kind}\">\n"
                f"{meta['text_excerpt']}\n"
                f"</file>"
            )
        elif kind == "image":
            data_url = up_mod.image_data_url(meta)
            if data_url:
                image_parts.append({
                    "type": "image_url",
                    "image_url": {"url": data_url},
                })

    augmented_text = user_text
    if excerpts:
        augmented_text = "\n\n".join(excerpts) + ("\n\n" + user_text if user_text else "")

    if image_parts:
        last["content"] = (
            [{"type": "text", "text": augmented_text or " "}] + image_parts
        )
    else:
        last["content"] = augmented_text

    return augmented_text


async def _sse_from_run(run: LiveRun, from_index: int = 0):
    """Wrap a LiveRun's chunk log as a Server-Sent Events stream. The opening
    `run` event hands the client the run id so it can reconnect later."""
    yield f"data: {_json.dumps({'type': 'run', 'run_id': run.run_id})}\n\n"
    async for chunk in run.stream(from_index=from_index):
        if chunk is KEEPALIVE:
            yield ": keepalive\n\n"
        elif isinstance(chunk, dict):
            # Structured control event (client_tool) — forwarded verbatim.
            yield f"data: {_json.dumps(chunk)}\n\n"
        else:
            yield f"data: {_json.dumps({'type': 'text', 'text': chunk})}\n\n"
    yield "data: {\"type\":\"done\"}\n\n"


def _project_roots(paths: list) -> list[str]:
    """Derive `projects/<Name>` roots from working-set paths."""
    roots: list[str] = []
    for p in paths:
        parts = (p or "").split("/")
        if "projects" in parts:
            i = parts.index("projects")
            if i + 1 < len(parts):
                root = "/".join(parts[: i + 2])
                if root not in roots:
                    roots.append(root)
    return roots


async def _snapshot_projects(chat_id: str, ws_paths: list) -> None:
    """Turn checkpoint: record HEAD + a `git stash create` snapshot for each
    active project BEFORE the agent edits, so the turn can be rewound via
    POST /chats/{id}/checkpoints/{cp}/restore. Non-invasive (stash create
    doesn't touch the working tree) and best-effort."""
    from .tools_code import _call as _sandbox_call
    for root in _project_roots(ws_paths)[:2]:
        # Working-set paths are relative to the sandbox workspace; /exec's
        # cwd is not — make it absolute.
        abs_root = root if root.startswith("/") else f"/workspace/{root}"
        try:
            r = await _sandbox_call("/exec", {
                "command": f"cd '{abs_root}' && git rev-parse HEAD && git stash create 2>/dev/null",
                "timeout": 20,
            })
            lines = [l.strip() for l in (r.get("stdout") or "").splitlines() if l.strip()]
            if not lines:
                continue
            head = lines[0]
            stash = lines[1] if len(lines) > 1 else None
            if len(head) == 40:
                await db.add_checkpoint(chat_id, root, head, stash)
        except Exception as e:
            log.info("checkpoint skipped for %s: %s", root, e)


@app.get("/chats/{chat_id}/checkpoints")
async def chats_checkpoints(chat_id: str, user: dict = Depends(current_user)):
    if await db.get_chat(chat_id, user_id=user["id"]) is None:
        raise HTTPException(status_code=404, detail="chat not found")
    return {"checkpoints": await db.list_checkpoints(chat_id, user["id"])}


@app.post("/chats/{chat_id}/checkpoints/{checkpoint_id}/restore")
async def chats_checkpoint_restore(
    chat_id: str, checkpoint_id: int, user: dict = Depends(current_user),
):
    """Rewind a project to a turn checkpoint: reset tracked files to the
    recorded HEAD, then re-apply the stashed uncommitted changes (if any).
    Untracked files created after the checkpoint are left in place."""
    cp = await db.get_checkpoint(checkpoint_id, chat_id, user["id"])
    if cp is None:
        raise HTTPException(status_code=404, detail="checkpoint not found")
    from .tools_code import _call as _sandbox_call
    root, head, stash = cp["project_root"], cp["head_sha"], cp["stash_sha"]
    abs_root = root if root.startswith("/") else f"/workspace/{root}"
    cmd = f"cd '{abs_root}' && git reset --hard {head}"
    if stash:
        cmd += f" && git stash apply {stash}"
    try:
        r = await _sandbox_call("/exec", {"command": cmd, "timeout": 30})
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"restore failed: {e}")
    if r.get("exit_code") not in (0, None):
        raise HTTPException(
            status_code=502,
            detail=f"restore failed: {(r.get('stderr') or r.get('stdout') or '')[:300]}",
        )
    return {"ok": True, "project_root": root, "head_sha": head, "reapplied_stash": bool(stash)}


async def _build_working_set_block(paths: list) -> Optional[str]:
    """Read the CURRENT contents of files this code chat has already opened and
    format them as a system-prompt block, so the agent doesn't waste rounds
    re-reading them. Contents are pulled fresh here every turn (never cached),
    so they can't go stale. Defensive: skips files that error or are gone;
    caps file count and total size."""
    if not paths:
        return None
    from .tools_code import tool_read_file  # local import avoids import cycle
    MAX_FILES = 8
    MAX_TOTAL = 32_000
    sections: list[str] = []
    total = 0
    for p in paths[:MAX_FILES]:
        try:
            body = await tool_read_file({"path": p, "limit": 800})
        except Exception:
            continue
        head = (body or "")[:80].lower()
        if not body or head.lstrip().startswith("error") or "not a file" in head:
            continue
        section = f"### {p}\n{body.strip()}"
        if total + len(section) > MAX_TOTAL:
            break
        sections.append(section)
        total += len(section)
    if not sections:
        return None
    return (
        "<files-already-in-context>\n"
        "These files are already open in your working set from earlier in THIS "
        "chat; their CURRENT on-disk contents are below. Do NOT call read_file "
        "on them again — you already have them. Read other files normally.\n\n"
        + "\n\n".join(sections)
        + "\n</files-already-in-context>"
    )


async def _build_project_context_block(paths: list) -> Optional[str]:
    """Load the active project's convention docs (AGENTS.md / CLAUDE.md /
    .cursorrules / README.md) and inject them as authoritative context, so the
    agent honors project rules without being told. The active project(s) are
    derived from the working-set paths (`projects/<Name>/...`). The JS/TS hint
    already SAYS to treat AGENTS.md as authoritative — this is what actually
    puts it in front of the model."""
    roots: list[str] = []
    for p in paths:
        parts = (p or "").split("/")
        if "projects" in parts:
            i = parts.index("projects")
            if i + 1 < len(parts):
                root = "/".join(parts[: i + 2])
                if root not in roots:
                    roots.append(root)
    if not roots:
        return None
    from .tools_code import _call
    # TOMSENSE.md first — the TomSense-specific instructions file wins over
    # generic convention docs when both exist.
    CONTEXT_FILES = ["TOMSENSE.md", "AGENTS.md", "CLAUDE.md", ".cursorrules", "README.md"]
    MAX_TOTAL = 10_000
    sections: list[str] = []
    total = 0
    for root in roots[:2]:
        for fname in CONTEXT_FILES:
            try:
                data = await _call("/fs/read", {"path": f"{root}/{fname}", "limit": 120})
            except Exception:
                continue
            body = (data.get("content") or "").strip()
            # skip empties and trivial @import shims (e.g. CLAUDE.md = "@AGENTS.md")
            if not body or len(body) < 12:
                continue
            section = f"### {root}/{fname}\n{body}"
            if total + len(section) > MAX_TOTAL:
                break
            sections.append(section)
            total += len(section)
    if not sections:
        return None
    return (
        "<project-conventions>\n"
        "Convention docs for the project you're working in. Treat these as "
        "AUTHORITATIVE over your training-data priors — follow them.\n\n"
        + "\n\n".join(sections)
        + "\n</project-conventions>"
    )


async def _run_generation(
    run: LiveRun,
    msgs: list,
    *,
    model,
    max_tokens,
    start_time,
    tool_context: dict,
    persona,
    summarized: int,
    chat_id,
    needs_autotitle: bool,
    user: dict,
    code_mode: bool = False,
    working_set_block: Optional[str] = None,
    reasoning_effort: Optional[str] = None,
):
    touched_paths: list = []
    """Drive run_chat to completion, buffering output into `run`. Runs as a
    detached task: it persists the assistant message + token usage when done
    regardless of whether any client is still streaming it — so a swipe-away,
    a network drop, or an app restart can no longer lose the reply."""
    accumulated = ""
    stats: dict = {}
    err: Optional[str] = None
    try:
        agen = run_chat(
            msgs,
            model=model,
            max_tokens=max_tokens,
            start_time=start_time,
            tool_context=tool_context,
            persona=persona,
            stats_out=stats,
            summarized=summarized,
            code_mode=code_mode,
            working_set_block=working_set_block,
            touched_out=touched_paths,
            reasoning_effort=reasoning_effort,
        )
        try:
            async for chunk in agen:
                run.append(chunk)
                if isinstance(chunk, str):
                    accumulated += chunk
        except Exception as e:
            err = str(e)
            marker = f"\n\n*[error: {err}]*"
            run.append(marker)
            accumulated += marker
        finally:
            try:
                await agen.aclose()
            except Exception:
                pass

        try:
            await db.add_tokens(
                user["id"],
                int(stats.get("prompt_tokens") or 0),
                int(stats.get("completion_tokens") or 0),
            )
        except Exception as e:
            log.warning("token accounting failed: %s", e)

        # Per-provider daily usage (tokens always; cost when the provider
        # reports it — OpenRouter does). Powers the sidebar tokens/cost mode.
        try:
            _pid, _ = providers.parse_model_str(model)
            _prov = await providers.resolve_provider(_pid, user_id=user["id"])
            _pname = (_prov or {}).get("name") or _pid
            await db.record_usage(
                user["id"], _pid, _pname,
                int(stats.get("prompt_tokens") or 0),
                int(stats.get("completion_tokens") or 0),
                float(stats.get("cost") or 0.0),
            )
        except Exception as e:
            log.warning("usage recording failed: %s", e)

        # Persist BEFORE marking the run done — so the moment `done` is
        # observable, a client falling back to GET /chats/{id} sees the reply.
        if chat_id and accumulated:
            try:
                # Strip only HALLUCINATED chip markup; keep the real tool-call /
                # reasoning chips (marked data-step="1") so the run's steps stay
                # visible after completion and on reload.
                saved = await db.add_message(chat_id, "assistant", _strip_hallucinated_chips(accumulated))
                if saved and saved.get("id") is not None:
                    # Code-mode replies put files into the sandbox directly; the
                    # tool chips already show what was written. Auto-extracting
                    # fenced-code artifacts on top of that produces a flood of
                    # near-duplicates (the same code in /files Sandbox AND
                    # Artifacts), so we skip extraction for code chats.
                    if not code_mode:
                        artifacts = detect_artifacts(accumulated)
                        if artifacts:
                            try:
                                await db.insert_artifacts(chat_id, saved["id"], artifacts)
                            except Exception as e:
                                log.warning("artifact persist failed: %s", e)
            except Exception as e:
                # The chat's FK vanishing means the user deleted the chat while
                # this run was in flight (chat existence IS validated at request
                # start) — dropping the reply is correct, not an error.
                if "messages_chat_id_fkey" in str(e):
                    log.info("chat %s deleted mid-run — reply not persisted", chat_id)
                else:
                    log.warning("persist assistant msg failed: %s", e)

        # Update the code-mode working set with files touched this turn, so the
        # next turn pre-loads them instead of re-reading.
        if code_mode and chat_id and touched_paths:
            try:
                await db.update_code_working_set(chat_id, touched_paths)
            except Exception as e:
                log.warning("working-set update failed: %s", e)
    finally:
        run.finish(error=err)
        retire_run(run)

    if chat_id and needs_autotitle:
        _spawn(_autotitle_if_needed(chat_id, user["id"]))
    # Auto-memory: persisted, non-code turns only (code chats are about the
    # repo, not the person).
    if chat_id and not code_mode and settings.auto_memory:
        _spawn(_auto_memory(user["id"], _last_user_text(msgs)))
    # Long code runs finish while the user is elsewhere — push a completion.
    if code_mode and start_time and (time.monotonic() - start_time) > 180:
        from . import notify
        mins = (time.monotonic() - start_time) / 60
        _spawn(notify.push(
            "🤖 Code run finished",
            f"{mins:.0f} min run {'failed: ' + err[:200] if err else 'completed'} "
            f"— {len(touched_paths)} file(s) touched.",
            tags="robot",
            user_id=user["id"],
            click_path=f"/c/{chat_id}" if chat_id else None,
        ))


# ─── scheduled prompts — server-side "run this every morning" ───────────────

async def _run_scheduled(s: dict) -> None:
    """Execute one due scheduled prompt: fresh chat + detached generation.
    The result lands in the sidebar like any other chat."""
    user = await db.get_user_with_rollover(s["user_id"])
    if not user:
        return
    if user["tokens_used"] >= user["monthly_token_limit"]:
        log.warning("schedule %r skipped — monthly token budget exhausted", s["title"])
        return
    from datetime import datetime
    from zoneinfo import ZoneInfo
    stamp = datetime.now(ZoneInfo(settings.schedule_tz)).strftime("%b %-d")
    chat = await db.create_chat(
        user["id"], model=s.get("model"), title=f"⏰ {s['title']} — {stamp}",
    )
    chat_id = chat["id"]
    await db.add_message(chat_id, "user", s["prompt"])

    try:
        prefs = await db.get_user_prefs(user["id"])
    except Exception:
        prefs = {}
    tool_models = (prefs or {}).get("tool_models") or {}
    try:
        mem_rows = await db.list_memories(user["id"])
        memories = [m["content"] for m in mem_rows][-50:]
    except Exception:
        memories = []

    from . import notify
    _tm_dict = tool_models if isinstance(tool_models, dict) else {}
    _sched_fb = (_tm_dict.get("chat_fallback") or "").strip() or None
    model, _notice = await _budget_downshift(
        s.get("model") or None, user_id=user["id"], slot_fallback=_sched_fb
    )
    run = create_run(chat_id, user_id=user["id"])
    log.info("schedule %r firing → chat %s", s["title"], chat_id)
    await _run_generation(
        run,
        [{"role": "user", "content": s["prompt"]}],
        model=model,
        max_tokens=None,
        start_time=time.monotonic(),
        tool_context={
            "uploads": [],
            "user_id": user["id"],
            "chat_id": chat_id,
            "memories": memories,
            "tool_models": _tm_dict,
            "cf_models": (prefs or {}).get("cf_models") or [],
            "stall_fallback": _sched_fb,
            "secret_env": {},
        },
        persona=None,
        summarized=0,
        chat_id=chat_id,
        needs_autotitle=False,
        user=user,
        code_mode=False,
        working_set_block=None,
    )
    # Push the result to the phone — a scheduled run nobody hears about
    # might as well not have run. Body = reply preview minus chip markup.
    reply = "".join(c for c in run.chunks if isinstance(c, str))
    preview = _re.sub(r"<details.*?</details>", "", reply, flags=_re.DOTALL)
    preview = _re.sub(r"\n?---\n\*.*?\*\s*$", "", preview.strip())  # stats footer
    await notify.push(
        f"⏰ {s['title']}",
        preview[:1500] or "(no reply)",
        tags="alarm_clock",
        user_id=user["id"],
        click_path=f"/c/{chat_id}",
    )


async def _scheduler_loop() -> None:
    """Once a minute, fire any scheduled prompts that are due. Runs for the
    app's lifetime (single-worker deploy, so no cross-instance locking)."""
    from datetime import datetime
    from zoneinfo import ZoneInfo
    log.info("scheduler running (tz=%s)", settings.schedule_tz)
    while True:
        try:
            now = datetime.now(ZoneInfo(settings.schedule_tz))
            due = await db.due_schedules(
                1 << now.weekday(), now.strftime("%H:%M"), now.strftime("%Y-%m-%d"),
            )
            for s in due:
                # Mark BEFORE running — an error mid-run must not double-fire.
                await db.mark_schedule_ran(s["id"], now.strftime("%Y-%m-%d"))
                _spawn(_run_scheduled(s))
        except asyncio.CancelledError:
            return
        except Exception as e:
            log.warning("scheduler tick failed: %s", e)
        await asyncio.sleep(60)


@app.post("/chat/stream")
async def chat_stream(
    req: ChatRequest,
    user: dict = Depends(current_user),
):
    start_time = time.monotonic()

    # Enforce monthly budget BEFORE doing any expensive work.
    if user["tokens_used"] >= user["monthly_token_limit"]:
        raise HTTPException(
            status_code=429,
            detail=(
                f"Monthly token budget exhausted "
                f"({user['tokens_used']:,} / {user['monthly_token_limit']:,}). "
                f"Resets at the start of next month."
            ),
        )

    if not req.messages:
        raise HTTPException(status_code=400, detail="messages cannot be empty")

    msgs = [m.model_dump(exclude_none=True) for m in req.messages]

    # Auto-summary: if the rolling history blows past the threshold, collapse
    # the older slice into one summary message via the small task model.
    # For persisted chats this consults / updates a per-chat summary cache so
    # we don't re-run the summarizer on every turn.
    msgs, summarized = await _maybe_summarize(msgs, req.chat_id, user_id=user["id"])

    upload_metas: list[dict] = []
    if req.upload_ids:
        upload_metas = await db.get_uploads(req.upload_ids, user["id"])

    persisted_user_text = _augment_last_user_with_uploads(msgs, upload_metas)

    chat_id = req.chat_id
    needs_autotitle = False
    persona: Optional[str] = None
    chat_model_override: Optional[str] = None
    code_mode = False
    project_knowledge: list = []  # project knowledge files, if any
    if chat_id:
        existing = await db.get_chat(chat_id, user_id=user["id"])
        if existing is None:
            raise HTTPException(status_code=404, detail="chat not found")
        code_mode = bool(existing.get("is_code"))
        last = msgs[-1] if msgs else None
        if last and last.get("role") == "user":
            await db.add_message(
                chat_id, "user", persisted_user_text,
                upload_ids=[m["id"] for m in upload_metas] or None,
            )
        needs_autotitle = not existing.get("title")
        persona = existing.get("system_prompt")
        chat_model_override = existing.get("model")
        # If the chat belongs to a project, prepend the project's shared
        # system prompt ahead of the chat's own persona.
        proj_id = existing.get("project_id")
        if proj_id:
            try:
                project = await db.get_project(proj_id, user_id=user["id"])
            except Exception:
                project = None
            proj_prompt = (project or {}).get("system_prompt")
            if proj_prompt and proj_prompt.strip():
                persona = (
                    proj_prompt.strip() + "\n\n" + persona
                    if persona and persona.strip()
                    else proj_prompt.strip()
                )
            # Project knowledge files: scope search_docs to the project's
            # uploads and tell the model they exist.
            try:
                project_knowledge = await db.list_project_uploads(proj_id, user["id"])
            except Exception:
                project_knowledge = []
            if project_knowledge:
                names = ", ".join(u["filename"] for u in project_knowledge[:20])
                hint = (
                    f"This project has knowledge files: {names}. When the "
                    "user's question could be answered from them, call "
                    "search_docs FIRST and ground your answer in the results."
                )
                persona = f"{persona}\n\n{hint}" if persona else hint

    # Code-mode working set: pre-load the current contents of files this chat
    # has already opened so the agent doesn't re-read them (tool results aren't
    # carried across turns). Best-effort — a failure here just means no
    # pre-load, never a failed chat.
    working_set_block: Optional[str] = None
    if code_mode and chat_id:
        try:
            ws_paths = await db.get_code_working_set(chat_id)
            # First turn has an empty working set, so convention docs would
            # only load from turn 2 — also derive project roots from mount
            # names mentioned in the user's message ("fix X in kitchensync").
            _text = _last_user_text(msgs).lower()
            for m in _mounts.load_config():
                if m["name"].lower() in _text:
                    _hint_path = f"projects/{m['name']}/."
                    if _hint_path not in ws_paths:
                        ws_paths = list(ws_paths) + [_hint_path]
            # Project conventions first (foundational), then the file working set.
            blocks = [
                await _build_project_context_block(ws_paths),
                await _build_working_set_block(ws_paths),
            ]
            blocks = [b for b in blocks if b]
            working_set_block = "\n\n".join(blocks) if blocks else None
            # Turn checkpoint BEFORE the agent edits anything (awaited so the
            # snapshot can't race the first edit).
            await _snapshot_projects(chat_id, ws_paths)
        except Exception as e:
            log.warning("code-context load failed: %s", e)

    # Pull this user's memories once for system-prompt injection.
    try:
        mem_rows = await db.list_memories(user["id"])
        memories = [m["content"] for m in mem_rows][-50:]
    except Exception:
        memories = []

    # Per-user model overrides for chat + tools (consult_coder / deep_research /
    # generate_image). Tools fall back to settings.model_* when a key is unset.
    try:
        user_prefs = await db.get_user_prefs(user["id"])
    except Exception:
        user_prefs = {}
    tool_models = (user_prefs or {}).get("tool_models") or {}
    if not isinstance(tool_models, dict):
        tool_models = {}
    cf_models = (user_prefs or {}).get("cf_models") or []
    if not isinstance(cf_models, list):
        cf_models = []
    user_chat_default = (tool_models.get("chat") or "").strip() or None
    user_code_default = (tool_models.get("code_mode") or "").strip() or None
    # Pick the right pref kind for this chat (code vs. regular). Without this
    # split, code chats fall back to `tool_models.chat`, silently running the
    # chat model when chat_model_override is null — observed 2026-05-26 when
    # a code chat's followup ran Gemma 4 (the chat default) instead of the
    # user's picker-selected coder model.
    user_pref_default = user_code_default if code_mode else user_chat_default

    # Decrypt the user's secret vault for code mode only. These are injected
    # into the sandbox shell env (never shown to the model) — see chat.py /
    # tools_code.set_secret_env. Empty {} for normal chats.
    secret_env = await db.get_secrets_decrypted(user["id"]) if code_mode else {}

    # Remote MCP tools: merge the user's enabled servers' tools into this
    # turn's toolset (tool lists are cached 5 min per server in mcp.py).
    mcp_specs: list = []
    try:
        for _srv in await db.list_mcp_servers(user["id"]):
            if _srv.get("enabled"):
                mcp_specs.extend(await mcp_mod.get_tool_specs(_srv))
    except Exception as e:
        log.warning("MCP spec fetch failed: %s", e)

    # Generation runs as a detached background task — it streams into a
    # LiveRun, persists itself on completion, and keeps going even if this
    # response's client disconnects. The client streams the LiveRun and can
    # reconnect to it (see GET /chat/stream/{run_id}).
    # Precedence: explicit request override → per-chat stored model
    #            → user's prefs.tool_models.{code_mode|chat} → env default.
    requested_model = req.model or chat_model_override or user_pref_default

    # "Think" toggle: high reasoning effort on the user's chosen Research model
    # (a deliberate, heavier model) — falling back to MODEL_THINK only when no
    # Research model is configured. Previously this always forced CF gpt-oss
    # 120B, ignoring the user's picks. Only an explicit per-request model pick
    # keeps its model (effort still bumps).
    reasoning_effort: Optional[str] = "high" if req.think else None
    if req.think and not req.model:
        _think_model = (tool_models.get("research") or "").strip()
        requested_model = _think_model or f"{providers.CF_BUILTIN_ID}::{settings.model_think}"

    # Vision routing: image turns MUST run on a vision-capable model — a
    # text-only model 400s on multimodal content. So when the conversation
    # carries images and the model that WOULD run can't see them, override to
    # a vision model. Capability is a hard requirement, not a preference, so
    # this wins over the per-chat pin, the Think swap, AND an explicit
    # per-request pick — but only when that pick is itself non-vision. The
    # target is: the user's Vision slot if set, else the chat model if it's
    # already vision-capable, else the vision default (Llama 4 Scout). A
    # notice is streamed so the swap is transparent.
    from .cf import flatten_for_text_model
    from . import capabilities
    vision_notice: Optional[str] = None
    if not code_mode and (req.vision or _has_image_input(msgs)):
        # Image EDIT/GENERATE intent: the model doesn't need to SEE the image —
        # generate_image/edit_image get it directly, and a text-only model
        # writes the instruction fine (logs: gpt-oss-20b calls edit_image in
        # 0.6s). So skip the vision detour (extra latency/cost) and instead
        # flatten the image parts to a text note so a text-only model doesn't
        # choke on multimodal content. Vision routing stays for QUESTIONS about
        # an image, where the model must actually see it to answer.
        _asked_action = bool(_IMAGE_ACTION_RE.search(_last_user_text(msgs)))
        if _asked_action:
            # Steer the (possibly text-only) model to call the image tool
            # rather than say it can't see the image — edit_image/generate_image
            # receive the actual image bytes directly.
            msgs[:] = flatten_for_text_model(
                msgs,
                "{n} image(s) attached. To modify them, call edit_image with the "
                "requested change — the tool receives the images directly, so you "
                "do NOT need to see them yourself. Do not reply that you can't see "
                "the image; just call the tool",
            )
        else:
            _would_run = requested_model or f"{providers.CF_BUILTIN_ID}::{settings.model_chat}"
            # Capability via the registry (declared caps → CF catalogue →
            # demoted heuristics), so a vision model on ANY provider — including
            # one the user tagged vision on their own OpenRouter/OpenAI entry —
            # is recognised and NOT needlessly overridden.
            _wr_pid, _wr_mid = providers.parse_model_str(_would_run)
            _wr_provider = await providers.resolve_provider(_wr_pid, user_id=user["id"])
            if not capabilities.model_sees_images(_wr_provider, _wr_mid):
                _vision_model = (
                    (tool_models.get("vision") or "").strip()
                    or f"{providers.CF_BUILTIN_ID}::{settings.model_vision}"
                )
                if _vision_model != requested_model:
                    requested_model = _vision_model
                    reasoning_effort = None  # vision default isn't the think model
                    vision_notice = (
                        f"*[image attached — answering with {short_name(_vision_model)} "
                        "(vision). Set a Vision model in Settings → Models to "
                        "choose which.]*\n\n"
                    )

    # Smart routing: only when the turn would run the env-default chat model
    # (no explicit pick anywhere) — HARD turns escalate to the heavy model.
    if (
        settings.auto_route
        and not code_mode
        and requested_model is None
        and (user_prefs or {}).get("auto_route", True)
    ):
        requested_model = await _route_model(msgs, user["id"])

    # Per-slot fallback: if the primary model stalls or hits the neuron cap,
    # switch to the user's configured fallback for this slot rather than the
    # server-wide defaults.
    if vision_notice:
        _slot_fb_key = "vision_fallback"
    elif code_mode:
        _slot_fb_key = "code_mode_fallback"
    else:
        _slot_fb_key = "chat_fallback"
    slot_fallback = (tool_models.get(_slot_fb_key) or "").strip() or None

    # Budget mode may then swap a heavy CF model for the budget one (this
    # also reins the router back in when the neuron cap is near).
    resolved_model, budget_notice = await _budget_downshift(
        requested_model, user_id=user["id"], slot_fallback=slot_fallback
    )

    run = create_run(chat_id, user_id=user["id"])
    if vision_notice:
        run.append(vision_notice)
    if budget_notice:
        # Streamed as the first chunk — visible live and on reconnect (it is
        # part of run.chunks), though not persisted with the reply.
        run.append(budget_notice)
    _spawn(
        _run_generation(
            run,
            msgs,
            model=resolved_model,
            # Code mode: per-user response-length override (Coder settings),
            # else fall through to run_chat's env/global default.
            max_tokens=req.max_tokens or (
                (user_prefs or {}).get("max_tokens_coder") if code_mode else None
            ),
            start_time=start_time,
            tool_context={
                "uploads": upload_metas,
                "user_id": user["id"],
                "chat_id": chat_id,
                # This turn's user text — image tools derive the HD decision
                # from it (an explicit /HD, "4K", "best quality", …) rather
                # than trusting the model's hd flag, which small models set
                # spuriously and silently upgrade to the pricier HD model.
                "user_text": _last_user_text(msgs),
                "memories": memories,
                "project_upload_ids": [u["id"] for u in project_knowledge] or None,
                "mcp_specs": mcp_specs or None,
                "tool_models": tool_models,
                "cf_models": cf_models,
                "stall_fallback": slot_fallback,
                "review_edits": bool((user_prefs or {}).get("review_edits")),
                "verify_edits": (user_prefs or {}).get("verify_edits", True),
                "max_rounds_code": (user_prefs or {}).get("max_rounds_code"),
                "secret_env": secret_env,
            },
            persona=persona,
            summarized=summarized,
            chat_id=chat_id,
            needs_autotitle=needs_autotitle,
            user=user,
            code_mode=code_mode,
            working_set_block=working_set_block,
            reasoning_effort=reasoning_effort,
        )
    )
    return StreamingResponse(
        _sse_from_run(run),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/chat/stream/{run_id}")
async def chat_stream_reconnect(
    run_id: str,
    user: dict = Depends(current_user),
    from_index: int = Query(0, alias="from", ge=0),
):
    """Re-attach to an in-progress (or recently finished) generation — used
    after a swipe-to-app handoff, a network drop, or an app restart. `from`
    is the chunk count the client already has, so the replay has no dupes."""
    run = get_run(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="run not found or expired")
    # Ownership: every run records its creator (ephemeral chats have no
    # chat_id, so the chat-ownership check alone would let any authenticated
    # user attach to them by run_id).
    if run.user_id != user["id"]:
        raise HTTPException(status_code=404, detail="run not found")
    if run.chat_id and await db.get_chat(run.chat_id, user_id=user["id"]) is None:
        raise HTTPException(status_code=404, detail="run not found")
    return StreamingResponse(
        _sse_from_run(run, from_index=from_index),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/chat/{chat_id}/live")
async def chat_live(chat_id: str, user: dict = Depends(current_user)):
    """Whether `chat_id` has a generation in progress — lets a freshly loaded
    chat view reconnect to a reply that's still being produced."""
    if await db.get_chat(chat_id, user_id=user["id"]) is None:
        raise HTTPException(status_code=404, detail="chat not found")
    run = live_run_for_chat(chat_id)
    return {"run_id": run.run_id if run else None}


@app.post("/chat/{chat_id}/followups")
async def chat_followups(chat_id: str, user: dict = Depends(current_user)):
    """Suggested follow-up questions for the chat, generated by the cheap
    title model. Best-effort — empty list on any failure."""
    return {"followups": await _generate_followups(chat_id, user["id"])}


@app.post("/chat/tool_result")
async def chat_tool_result(
    req: ToolResultRequest,
    user: dict = Depends(current_user),
):
    """The device's answer to a `client_tool` SSE event — wakes the awaiting
    run_chat so the in-flight streaming turn can continue. See clienttools.py.
    `ok` is False when no call was pending (already resolved or timed out)."""
    delivered = resolve_client_tool(req.call_id, req.result)
    return {"ok": delivered}
