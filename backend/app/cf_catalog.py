"""Catalogue of the bundled Cloudflare Workers AI models — the single source
of truth for their CAPABILITIES.

Historically, capability was inferred from model-name substrings scattered in
cf.py (`_is_vision_model` / `_is_reasoning_model`) and duplicated again as
hardcoded lists in the frontend. That guessing only works for `@cf/...` names
and silently misclassifies models on other providers.

Here we declare capability as DATA, per model:

    vision    — accepts image_url content parts (multimodal)
    reasoning — emits a hidden reasoning channel; needs reasoning_effort capped
    context   — advertised context window (tokens), best-effort / informational

`capabilities.py` reads this for `cf::` models and falls back to the demoted
name-substring heuristics only for CF models not listed here. Custom-provider
models declare their own capabilities on the provider `models[]` entry, so the
same id (e.g. gemma-4 on Cloudflare vs. OpenRouter) can differ per provider.

`roles` lists which tool-model pickers a model belongs in — this lets the
frontend source its option lists from the backend instead of hardcoding them.
"""

# Each entry: id, label, note, caps (vision/reasoning/context), roles.
# The vision/reasoning flags intentionally MIRROR the pre-refactor
# _VISION_MODEL_HINTS / _REASONING_MODEL_HINTS so behaviour is unchanged — the
# win is that it's now declared data with a per-model override path.
CF_MODELS: list[dict] = [
    {
        "id": "@cf/google/gemma-4-26b-a4b-it", "label": "Gemma 4 (26B)",
        "note": "default · vision + tools", "vision": True, "reasoning": True,
        "context": 16000, "roles": ["chat", "vision", "code"],
    },
    {
        "id": "@cf/moonshotai/kimi-k2.7-code", "label": "Kimi K2.7 (1T)",
        "note": "frontier · 262k ctx · vision + tools · neuron-hungry",
        "vision": True, "reasoning": True, "context": 262000,
        "roles": ["chat", "vision", "code"],
    },
    {
        "id": "@cf/moonshotai/kimi-k2.6", "label": "Kimi K2.6",
        "note": "tight reasoning when warm", "vision": True, "reasoning": True,
        "context": 262000, "roles": ["chat", "code"],
    },
    {
        "id": "@cf/zai-org/glm-5.2", "label": "GLM-5.2",
        "note": "flagship agentic · 262k ctx · neuron-hungry",
        "vision": False, "reasoning": True, "context": 262000,
        "roles": ["chat", "code"],
    },
    {
        "id": "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "label": "Llama 3.3 70B",
        "note": "fastest · text-only", "vision": False, "reasoning": False,
        "context": 24000, "roles": ["chat", "research"],
    },
    {
        "id": "@cf/openai/gpt-oss-120b", "label": "GPT-OSS 120B",
        "note": "reasoning · expensive", "vision": False, "reasoning": True,
        "context": 128000, "roles": ["chat", "research", "code"],
    },
    {
        "id": "@cf/openai/gpt-oss-20b", "label": "GPT-OSS 20B",
        "note": "reasoning · cheap", "vision": False, "reasoning": True,
        "context": 128000, "roles": ["chat", "research", "code"],
    },
    {
        "id": "@cf/nvidia/nemotron-3-120b-a12b", "label": "Nemotron 3 120B",
        "note": "agentic 120B (12B active) · 256k ctx", "vision": False,
        "reasoning": True, "context": 256000, "roles": ["code"],
    },
    {
        "id": "@cf/qwen/qwen3-30b-a3b-fp8", "label": "Qwen3 30B",
        "note": "fast & cheap MoE · 3B active FP8", "vision": False,
        "reasoning": True, "context": 32000, "roles": ["code"],
    },
    {
        "id": "@cf/meta/llama-4-scout-17b-16e-instruct", "label": "Llama 4 Scout 17B",
        "note": "fast multimodal MoE", "vision": True, "reasoning": False,
        "context": 128000, "roles": ["chat", "vision"],
    },
    {
        "id": "@cf/meta/llama-3.1-8b-instruct", "label": "Llama 3.1 8B",
        "note": "", "vision": False, "reasoning": False, "context": 16000,
        "roles": ["chat", "title"],
    },
    {
        "id": "@cf/meta/llama-3.2-3b-instruct", "label": "Llama 3.2 3B",
        "note": "tiny", "vision": False, "reasoning": False, "context": 16000,
        "roles": ["chat", "title"],
    },
    # ── image generation / edit (modality: image; vision/reasoning N/A). The
    # google/openai ids are AI-Gateway partner models routed through CF. ──
    {
        "id": "@cf/black-forest-labs/flux-2-klein-4b", "label": "Flux 2 Klein 4B",
        "note": "default · ~$0.001/img", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
    {
        "id": "@cf/runwayml/stable-diffusion-v1-5-img2img", "label": "SD v1.5",
        "note": "beta · FREE · CF", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
    {
        "id": "@cf/black-forest-labs/flux-2-klein-9b", "label": "Flux 2 Klein 9B",
        "note": "~$0.015/img · better quality", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
    {
        "id": "google/imagen-4", "label": "Imagen 4",
        "note": "~$0.04/img · photorealistic", "vision": False, "reasoning": False,
        "roles": ["image"],
    },
    {
        "id": "@cf/black-forest-labs/flux-2-dev", "label": "Flux 2 Dev",
        "note": "premium · ~$0.04/img · multi-ref", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
    {
        "id": "openai/gpt-image-2", "label": "gpt-image-2",
        "note": "$0.055/img medium · OpenAI", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
    {
        "id": "google/nano-banana-2", "label": "Nano Banana 2",
        "note": "~$0.08/img · best all-rounder", "vision": False, "reasoning": False,
        "roles": ["image", "image_edit"],
    },
]

# Fast lookup by bare `@cf/...` id.
CF_MODELS_BY_ID: dict[str, dict] = {m["id"]: m for m in CF_MODELS}
