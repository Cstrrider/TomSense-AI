"""Settings loaded from environment with sensible defaults."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    cf_account_id: str = os.getenv("CF_ACCOUNT_ID", "")
    cf_api_token: str = os.getenv("CF_API_TOKEN", "")
    # AI Gateway routing for partner / passthrough models (Imagen, Nano Banana,
    # gpt-image-2). Separate token scope: needs `AI Gateway · Run` permission.
    # Falls back to cf_api_token when only one token is configured AND it has
    # both Workers AI + AI Gateway scopes.
    cf_aig_token: str = os.getenv("CF_AIG_TOKEN", "")
    cf_gateway_id: str = os.getenv("CF_GATEWAY_ID", "default")

    # Anthropic Messages API — enables the "Claude *" entries in the code-mode
    # picker via the native /v1/messages endpoint (with prompt caching). When
    # empty, those models are listed but resolve to an "unknown provider" error.
    anthropic_api_key: str = os.getenv("ANTHROPIC_API_KEY", "")

    # Master key for at-rest encryption of stored API keys (see crypto.py).
    # Generate with `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`
    # — setup.sh does this automatically. Empty = no encryption (dev).
    encryption_key: str = os.getenv("TOMSENSE_ENCRYPTION_KEY", "")

    # Sidecar defaults match the bundled tomsense-* services in
    # docker-compose.yml; override via env to point at external instances.
    searxng_url: str = os.getenv("SEARXNG_URL", "http://tomsense-searxng:8080")
    jupyter_url: str = os.getenv("JUPYTER_URL", "http://tomsense-jupyter:8888")
    # Kernel-API token shared with the jupyter container (setup.sh generates
    # it). Empty = unauthenticated Jupyter (pre-token deploys).
    jupyter_token: str = os.getenv("JUPYTER_TOKEN", "")
    whisper_url: str = os.getenv("WHISPER_URL", "http://tomsense-whisper:8000")
    piper_url:   str = os.getenv("PIPER_URL",   "http://tomsense-piper:8000")
    qdrant_url:  str = os.getenv("QDRANT_URL",  "http://tomsense-qdrant:6333")
    tts_voice:   str = os.getenv("TTS_VOICE", "alloy")
    model_embed: str = os.getenv("MODEL_EMBED", "@cf/baai/bge-base-en-v1.5")
    embed_dim:   int = int(os.getenv("EMBED_DIM", "768"))

    # CF Workers AI free tier is 10,000 neurons / day. Override via env if
    # you're on a paid plan.
    cf_daily_neuron_limit: int = int(os.getenv("CF_DAILY_NEURON_LIMIT", "10000"))
    # Budget mode: when today's account-wide neuron use crosses this % of the
    # free daily limit, heavy CF models downshift to budget_model for new
    # turns (a notice is streamed). 0 disables the downshift entirely.
    neuron_soft_cap_pct: int = int(os.getenv("NEURON_SOFT_CAP_PCT", "80"))
    # Smart routing: default-model chat turns get a difficulty check on the
    # tiny task model; HARD turns escalate to model_chat_heavy. Master switch
    # (per-user pref `auto_route` can also disable). Never fires in budget
    # mode, for explicit model picks, or in code mode.
    auto_route: bool = os.getenv("AUTO_ROUTE", "1") == "1"
    # IANA tz for scheduled prompts ("run at 07:00" is interpreted here).
    schedule_tz: str = os.getenv("SCHEDULE_TZ", os.getenv("TZ", "UTC"))
    model_chat_heavy: str = os.getenv("MODEL_CHAT_HEAVY", "@cf/moonshotai/kimi-k2.7-code")
    # "Think" toggle: reasoning model + high effort for turns the user marks.
    model_think: str = os.getenv("MODEL_THINK", "@cf/openai/gpt-oss-120b")
    # Auto-memory: after each persisted chat turn, the tiny task model scans
    # the user's message for durable personal facts and saves them (deduped).
    # Per-user pref `auto_memory` can also disable.
    auto_memory: bool = os.getenv("AUTO_MEMORY", "1") == "1"
    budget_model: str = os.getenv("BUDGET_MODEL", "@cf/google/gemma-4-26b-a4b-it")
    # Stall guard: a streamed round that produces ZERO bytes (no reasoning, no
    # text, no tool deltas) for this many seconds is aborted and retried once
    # on stall_fallback_model, with a visible note in the reply. Time-to-FIRST-
    # byte only — reasoning models stream their thinking, so a long think is
    # never cut off. Motivated by the 2026-07-07 CF gemma-4 brownout (rounds
    # hung 180-295s with zero bytes). 0 disables the guard.
    first_token_timeout_s: float = float(os.getenv("FIRST_TOKEN_TIMEOUT_S", "45"))
    # Mid-stream idle watchdog: after the first byte, a gap of this many
    # seconds with NO streamed events (reasoning, text, or tool deltas) aborts
    # the round instead of riding out the 180s httpx read timeout. Catches the
    # "streams its thinking, then dies" failure (2026-07-10: 4771 reasoning
    # chars then 300s of silence → empty reply). Healthy models never pause
    # this long between deltas. 0 disables.
    stream_idle_timeout_s: float = float(os.getenv("STREAM_IDLE_TIMEOUT_S", "90"))
    stall_fallback_model: str = os.getenv("STALL_FALLBACK_MODEL", "@cf/openai/gpt-oss-120b")
    # Google Cloud Vision API key — powers the reverse_image_lookup tool
    # (Lens-style landmark + web detection; 1k lookups/mo free tier). Users
    # can instead store a per-user GOOGLE_VISION_API_KEY in the secret vault,
    # which takes precedence; this env var is the instance-wide fallback.
    google_vision_api_key: str = os.getenv("GOOGLE_VISION_API_KEY", "")
    # AudD music-recognition token — powers the identify_song tool. Per-user
    # keys on the Providers page take precedence; this is the instance-wide
    # fallback.
    audd_api_key: str = os.getenv("AUDD_API_KEY", "")
    # Workers AI paid-tier price per 1k neurons past the free daily allocation
    # — used for the $ spend estimate in the sidebar.
    neuron_price_per_1k: float = float(os.getenv("NEURON_PRICE_PER_1K", "0.011"))
    # Substring matches against CF model ids that count as "heavy" — only
    # these get downshifted (downshifting a 3B model would be a no-op).
    neuron_heavy_models: tuple = tuple(
        m.strip() for m in os.getenv(
            "NEURON_HEAVY_MODELS",
            "kimi,glm-5.2,gpt-oss-120b,nemotron,llama-3.3-70b,deepseek,qwq,qwen2.5-coder",
        ).split(",") if m.strip()
    )

    # When the rendered message history exceeds this many characters, the
    # backend collapses older messages into a one-paragraph summary so we
    # don't blow the model's context window.
    auto_summary_threshold: int = int(os.getenv("AUTO_SUMMARY_THRESHOLD", "20000"))
    auto_summary_keep_recent: int = int(os.getenv("AUTO_SUMMARY_KEEP_RECENT", "4"))

    model_chat: str = os.getenv("MODEL_CHAT", "@cf/google/gemma-4-26b-a4b-it")
    # Fallback vision model: image turns auto-route here when the model that
    # would otherwise run can't see images and the user set no Vision slot.
    model_vision: str = os.getenv("MODEL_VISION", "@cf/meta/llama-4-scout-17b-16e-instruct")
    model_coder: str = os.getenv("MODEL_CODER", "@cf/google/gemma-4-26b-a4b-it")
    model_writer: str = os.getenv("MODEL_WRITER", "@cf/openai/gpt-oss-120b")
    model_research: str = os.getenv("MODEL_RESEARCH", "@cf/openai/gpt-oss-120b")
    model_title: str = os.getenv("MODEL_TITLE", "@cf/meta/llama-3.2-3b-instruct")
    model_image_sd: str = os.getenv("MODEL_IMAGE_SD", "@cf/black-forest-labs/flux-2-klein-4b")
    model_image_hd: str = os.getenv("MODEL_IMAGE_HD", "@cf/black-forest-labs/flux-2-klein-9b")

    # In-memory per-user rate limit. Keeps a runaway client from draining the
    # CF neuron budget in seconds. Tuned generously — interactive use is
    # ~1 req/sec; this allows bursts. Set to 0 to disable.
    rate_limit_per_min: int = int(os.getenv("RATE_LIMIT_PER_MIN", "120"))

    max_tokens_chat: int = int(os.getenv("MAX_TOKENS_CHAT", "4096"))
    max_tokens_coder: int = int(os.getenv("MAX_TOKENS_CODER", "16384"))
    max_tokens_research: int = int(os.getenv("MAX_TOKENS_RESEARCH", "8192"))

    # SSRF guard (netguard.py). fetch_page is model-controlled, so private /
    # docker-network addresses are blocked by default. Custom provider
    # base_urls are user-entered config and legitimately point at LAN LLM
    # hosts (LiteLLM, Ollama), so those stay allowed unless locked down.
    fetch_allow_private: bool = os.getenv("FETCH_ALLOW_PRIVATE", "0") == "1"
    providers_allow_private: bool = os.getenv("PROVIDERS_ALLOW_PRIVATE", "1") == "1"

    max_tool_rounds: int = int(os.getenv("MAX_TOOL_ROUNDS", "8"))
    code_interp_timeout: int = int(os.getenv("CODE_INTERP_TIMEOUT", "45"))
    searxng_max_results: int = int(os.getenv("SEARXNG_MAX_RESULTS", "5"))
    max_page_chars: int = int(os.getenv("MAX_PAGE_CHARS", "4000"))

    # Code mode — the coding-agent sandbox + a longer agentic loop.
    sandbox_url: str = os.getenv("SANDBOX_URL", "http://tomsense-sandbox:8000")
    sandbox_token: str = os.getenv("SANDBOX_TOKEN", "")
    sandbox_exec_timeout: int = int(os.getenv("SANDBOX_EXEC_TIMEOUT", "120"))
    max_tool_rounds_code: int = int(os.getenv("MAX_TOOL_ROUNDS_CODE", "40"))
    # Diff-approval gate master switch. Held OPEN (default 1) so the per-user
    # "Review edits" toggle in Settings → Coder is the SOLE control of edit
    # pausing; CODE_REVIEW_GATE=0 is a kill-switch that hard-disables the
    # gate regardless of anyone's pref. Default matches docker-compose.yml so
    # non-compose runs (local dev, tests) behave the same.
    code_review_gate: bool = os.getenv("CODE_REVIEW_GATE", "1") == "1"
    # How long an edit waits for the user's Apply/Reject before the fail-safe
    # auto-applies it. Default 30 min — effectively "wait for me" for any active
    # session, while still self-healing if a run is genuinely abandoned (so a
    # blocked background task can't leak forever).
    code_review_timeout: int = int(os.getenv("CODE_REVIEW_TIMEOUT", "1800"))

    api_key: str = os.getenv("API_KEY", "")

    @property
    def cf_chat_url(self) -> str:
        return (
            f"https://api.cloudflare.com/client/v4/accounts/"
            f"{self.cf_account_id}/ai/v1/chat/completions"
        )

    @property
    def cf_run_url(self) -> str:
        return (
            f"https://api.cloudflare.com/client/v4/accounts/"
            f"{self.cf_account_id}/ai/run"
        )

    @property
    def cf_gateway_url(self) -> str:
        """AI Gateway base — no suffix. Append `/workers-ai/run/{model}` for
        Unified-Billing partner models, or `/compat/chat/completions` etc.
        for OpenAI-shape calls."""
        return (
            f"https://gateway.ai.cloudflare.com/v1/"
            f"{self.cf_account_id}/{self.cf_gateway_id}"
        )

    @property
    def cf_gateway_compat_url(self) -> str:
        return f"{self.cf_gateway_url}/compat"

    @property
    def cf_gateway_headers(self) -> dict:
        # Prefer the gateway-scoped token; fall back to the Workers AI token
        # when only one is set (assumes it has both scopes).
        token = self.cf_aig_token or self.cf_api_token
        return {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            # CF gateway's bot manager 1010-blocks default python-httpx UA.
            "User-Agent": "tomsense-backend/0.4 (+tomsense)",
        }

    @property
    def cf_headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.cf_api_token}",
            "Content-Type": "application/json",
        }


settings = Settings()
