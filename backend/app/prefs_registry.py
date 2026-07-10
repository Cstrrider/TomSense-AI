"""Single source of truth for user preference fields (P5 of the frontend
re-architecture — docs/frontend-rearch-plan.md).

Each simple pref is declared ONCE here and that declaration drives all three
places that previously had to agree by hand (and drifted — the 2026-07-10
fallback-keys bug was exactly a whitelist that forgot new keys):

  1. the /me/prefs whitelist + validation (main.py derives both from here),
  2. the /info payload (`prefs_schema`) the frontend reads,
  3. the frontend's generic field renderer (SchemaFields.svelte) — fields
     with ui="auto" appear in Settings automatically.

Composite prefs (tool_models, cf_models) keep their bespoke validation in
main.py and bespoke UI; they are listed here only for the whitelist.

Field spec keys:
  key      pref name (JSONB key in users.prefs)
  type     bool | int | enum | string | object | array
  ui       auto   → generic renderer, needs `section` + `label` (+ `hint`)
           custom → bespoke UI exists (voice/embedding/model pickers)
           hidden → API-only (flags set by flows, not by a settings control)
  default  value the UI shows before the user ever saves (NOT written to db)
  section  which settings area renders it (memory | coder | general)
  nullable int fields: empty/0 clears the override (stored as null)
  min/max  int clamp; options: enum [{value,label}]
"""

PREFS_FIELDS: list[dict] = [
    # ── ui: auto — rendered generically from this schema ────────────────────
    {
        "key": "auto_memory", "type": "bool", "ui": "auto",
        "default": True, "section": "memory",
        "label": "Automatic memory",
        "hint": "Quietly save durable facts from your messages into Memory.",
    },
    {
        "key": "review_edits", "type": "bool", "ui": "auto",
        "default": False, "section": "coder",
        "label": "Review edits before applying",
        "hint": "When on, every file edit in a code chat pauses and shows you "
                "the diff — nothing is written until you tap Apply. Reject "
                "sends the agent back to revise. Off (default) applies edits "
                "immediately.",
    },
    {
        "key": "verify_edits", "type": "bool", "ui": "auto",
        "default": True, "section": "coder",
        "label": "Auto-verify edits",
        "hint": "After an edit, run the project's build / type-check (tsc, "
                "py_compile, go build) and push the agent to fix any errors "
                "before it finishes. On by default — turn off for speed, or "
                "if a pre-existing repo error causes false failures.",
    },
    {
        "key": "max_rounds_code", "type": "int", "ui": "auto",
        "default": None, "section": "coder", "nullable": True,
        "min": 1, "max": 100, "placeholder": "40",
        "label": "Max steps per turn",
        "hint": "How many tool rounds the agent may take before it must stop "
                "(1–100). Higher lets it grind through bigger tasks; lower "
                "reins it in. Blank uses the default (40).",
    },
    {
        "key": "max_tokens_coder", "type": "int", "ui": "auto",
        "default": None, "section": "coder", "nullable": True,
        "min": 512, "max": 32768, "step": 512, "placeholder": "default",
        "label": "Response length cap",
        "hint": "Max tokens the coder model may emit per round (512–32768). "
                "Lower it if the model rambles; raise it for long files. "
                "Blank uses the default.",
    },
    {
        "key": "usage_display", "type": "enum", "ui": "auto",
        "default": "auto", "section": "general",
        "options": [
            {"value": "auto", "label": "Auto (per provider)"},
            {"value": "neurons", "label": "Neurons (Cloudflare)"},
            {"value": "tokens", "label": "Tokens & cost"},
        ],
        "label": "Usage counter",
        "hint": "What the sidebar shows. Auto follows the active provider — "
                "Cloudflare neurons, or tokens + $ cost for OpenRouter etc.",
    },
    {
        "key": "auto_route", "type": "bool", "ui": "auto",
        "default": True, "section": "general",
        "label": "Smart routing",
        "hint": "Difficulty-route default-model turns: hard questions "
                "escalate to the heavy chat model automatically.",
    },

    # ── ui: custom — bespoke UIs (voice tab, embedding section) ─────────────
    {"key": "tts_provider", "type": "string", "ui": "custom", "default": None},
    {"key": "tts_voice", "type": "string", "ui": "custom", "default": None},
    {"key": "stt_provider", "type": "string", "ui": "custom", "default": None},
    {"key": "embed_model", "type": "string", "ui": "custom", "default": None},
    {
        "key": "embed_dim", "type": "int", "ui": "custom",
        "default": None, "nullable": True, "min": 8, "max": 8192,
    },

    # ── ui: hidden — API-only flags / composite objects ─────────────────────
    {"key": "setup_dismissed", "type": "bool", "ui": "hidden", "default": False},
    {
        "key": "export_format", "type": "enum", "ui": "hidden",
        "default": "md",
        "options": [{"value": "md", "label": "Markdown"},
                    {"value": "json", "label": "JSON"}],
    },
    # Composite — validated bespoke in main.py (deep-merge / shape checks).
    {"key": "tool_models", "type": "object", "ui": "custom", "default": None},
    {"key": "cf_models", "type": "array", "ui": "custom", "default": None},
]

PREFS_KEYS: set[str] = {f["key"] for f in PREFS_FIELDS}

# Fields main.py validates itself (composite shapes, deep-merge semantics).
_BESPOKE = {"tool_models", "cf_models"}

_BY_KEY = {f["key"]: f for f in PREFS_FIELDS}


def validate_patch(patch: dict) -> dict:
    """Coerce + validate simple fields in-place from their declarations.
    Raises ValueError with a user-facing message on bad input. Composite
    fields (_BESPOKE) pass through untouched."""
    out = dict(patch)
    for key, val in patch.items():
        spec = _BY_KEY.get(key)
        if spec is None or key in _BESPOKE:
            continue
        t = spec["type"]
        if t == "bool":
            out[key] = bool(val)
        elif t == "int":
            if spec.get("nullable") and val in (None, "", 0):
                out[key] = None
            else:
                try:
                    out[key] = max(spec["min"], min(int(val), spec["max"]))
                except (TypeError, ValueError):
                    raise ValueError(f"{key} must be an integer")
        elif t == "enum":
            allowed = {o["value"] for o in spec["options"]}
            if val not in allowed:
                raise ValueError(f"{key} must be one of {sorted(allowed)}")
        elif t == "string":
            out[key] = (str(val).strip() if val else "") or None
    return out


def schema_for_info() -> list[dict]:
    """The schema payload /info exposes — auto + custom fields (the frontend
    renders `auto` ones and may read defaults for custom ones); hidden fields
    stay server-side."""
    return [f for f in PREFS_FIELDS if f["ui"] != "hidden"]
