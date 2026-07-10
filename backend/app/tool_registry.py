"""Canonical registry of tool-model slots — the *only* place these are
defined. Used by:

  - main.py for the PUT /me/prefs allowlist + the /info catalog
  - the frontend (consumes the /info catalog via the existing infoStore)

Adding a new tool model — say "translate" — means one entry here + the
optional UI label, and the rest of the stack picks it up automatically.

Each entry:
    key:         identifier; matches a `tool_models.<key>` pref column.
    label:       human label shown in Settings → Models row header.
    chip:        short label for the per-row tool chips in the model editor.
    hint:        one-line description rendered under the row header.
"""

TOOL_MODELS_CATALOG: list[dict] = [
    {
        "key": "chat",
        "label": "Chat",
        "chip": "chat",
        "hint": "Main model for conversation and tool dispatch.",
    },
    {
        "key": "vision",
        "label": "Vision",
        "chip": "vision",
        "hint": "Handles turns with image attachments. Unset = the Chat model "
                "answers them (it must be vision-capable).",
    },
    {
        "key": "code",
        "label": "Code Writer",
        "chip": "writer",
        "hint": "🪄 Single-shot — used by /code → consult_coder. One prompt, one code block.",
    },
    {
        "key": "code_mode",
        "label": "Code Mode",
        "chip": "agent",
        "hint": "🤖 Agentic — used by code chats. Reads, edits, runs files in a sandbox loop.",
    },
    {
        "key": "research",
        "label": "Research",
        "chip": "research",
        "hint": "Used by /research → deep_research synthesis.",
    },
    {
        "key": "title",
        "label": "Utility / Title",
        "chip": "utility",
        "hint": "Tiny background model — chat titles, follow-up suggestions, "
                "auto-memory extraction, history summaries, difficulty routing. "
                "Unset = the built-in Cloudflare task model.",
    },
    {
        "key": "image",
        "label": "Image",
        "chip": "img",
        "hint": "Text-to-image, default (SD) quality.",
    },
    {
        "key": "image_hd",
        "label": "Image (HD)",
        "chip": "img HD",
        "hint": "Text-to-image, picked when you type /HD or ask for HD / 4K.",
    },
    {
        "key": "image_edit",
        "label": "Image edit",
        "chip": "edit",
        "hint": "Image-to-image (img2img), default quality.",
    },
    {
        "key": "image_edit_hd",
        "label": "Image edit (HD)",
        "chip": "edit HD",
        "hint": "Image-to-image, picked when you type /HD on an edit request.",
    },
]

TOOL_MODELS_KEYS: set[str] = (
    {t["key"] for t in TOOL_MODELS_CATALOG}
    | {"chat_fallback", "vision_fallback", "code_mode_fallback"}
)


# ─── Code-mode model catalog (I5) ───────────────────────────────────────────
# Default models offered in the code-mode picker. The frontend reads this via
# /info so adding a model is one backend deploy, not a frontend rebuild. Each
# entry maps a provider-qualified model ID to UI labels/hints.
#
# Order matters — index 0 is the default-default when a user has no saved
# pref. Spike findings: Qwen3 wins on tokens + rounds; reach for Claude on
# niche-domain tasks.
CODE_MODELS_CATALOG: list[dict] = [
    {
        "id": "@cf/google/gemma-4-26b-a4b-it",
        "label": "Gemma 4 26B",
        "hint": "Recommended default — passed checklist test, 5× cheaper than Nemotron",
    },
    {
        "id": "@cf/nvidia/nemotron-3-120b-a12b",
        "label": "Nemotron 3 120B",
        "hint": "Agentic 120B (12B active) — 256k ctx; reach for it on harder tasks",
    },
    {
        "id": "@cf/qwen/qwen3-30b-a3b-fp8",
        "label": "Qwen3 30B",
        "hint": "Cheapest — fewest tokens; weaker at multi-step validation",
    },
    {
        "id": "@cf/moonshotai/kimi-k2.7-code",
        "label": "Kimi K2.7 Code",
        "hint": "Frontier 1T coder — 262k ctx, vision, structured tool calls; neuron-hungry",
    },
    {
        "id": "@cf/zai-org/glm-5.2",
        "label": "GLM-5.2",
        "hint": "Z.ai flagship agentic coder — 262k ctx; neuron-hungry",
    },
    {
        "id": "@cf/moonshotai/kimi-k2.6",
        "label": "Kimi K2.6",
        "hint": "Tight reasoning when warm; CF latency can spike",
    },
    {
        "id": "anthropic::claude-haiku-4-5",
        "label": "Claude Haiku",
        "hint": "Frontier fast tier (~5× Qwen3 cost) — capable & quick",
    },
    {
        "id": "anthropic::claude-sonnet-4-6",
        "label": "Claude Sonnet",
        "hint": "Frontier workhorse (~25× Qwen3 cost) — best general coding",
    },
    {
        "id": "anthropic::claude-opus-4-7",
        "label": "Claude Opus",
        "hint": "Frontier top tier (~125× Qwen3 cost) — hardest tasks only",
    },
]


# ─── Anthropic (native Messages API) model catalogue ────────────────────────
# The bundled Claude models, surfaced like cf_catalog so the Anthropic builtin
# provider card lists them AND they appear in the per-tool pickers. Ids are
# bare (the `anthropic::` provider prefix is added by the picker). Claude 3+ is
# multimodal; none are separate reasoning-channel models.
ANTHROPIC_MODELS: list[dict] = [
    {
        "id": "claude-opus-4-7", "label": "Claude Opus 4.7",
        "note": "frontier top tier", "vision": True, "reasoning": False,
        "roles": ["chat", "vision", "code", "code_mode", "research"],
    },
    {
        "id": "claude-sonnet-4-6", "label": "Claude Sonnet 4.6",
        "note": "frontier workhorse", "vision": True, "reasoning": False,
        "roles": ["chat", "vision", "code", "code_mode", "research"],
    },
    {
        "id": "claude-haiku-4-5", "label": "Claude Haiku 4.5",
        "note": "frontier fast tier", "vision": True, "reasoning": False,
        "roles": ["chat", "vision", "code", "code_mode", "research"],
    },
]
