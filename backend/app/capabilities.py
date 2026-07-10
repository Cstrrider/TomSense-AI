"""Single source of truth for model capabilities — "what can this model do?".

Replaces the scattered name-substring guessing (cf._is_vision_model /
_is_reasoning_model) with a lookup that prefers DECLARED capability data and
only falls back to heuristics for models nobody has annotated.

Resolution order for `model_capabilities(provider, model_id)`:

  1. Capability fields declared on the provider's `models[]` entry
     (vision/reasoning/context) — lets the SAME model id differ per provider
     (e.g. gemma-4 on Cloudflare vs. OpenRouter behave differently).
  2. The bundled Cloudflare catalogue (cf_catalog) for `@cf/...` ids.
  3. Demoted name-substring heuristics (cf._is_vision_model /
     _is_reasoning_model) — last resort for un-annotated custom models.

Callers should hold the resolved `provider` dict (from resolve_provider) and
the bare `model_id` (from parse_model_str). Both the CF builtin and custom
providers carry a `models` list; the CF builtin's is empty, so CF ids resolve
via the catalogue in step 2.
"""

from typing import Optional

from . import cf as _cf
from .cf_catalog import CF_MODELS_BY_ID


_CAP_KEYS = ("vision", "reasoning", "context")


def _declared(provider: Optional[dict], model_id: str) -> Optional[dict]:
    """Capabilities explicitly declared on the provider's model entry, or None
    when the model isn't listed / carries no capability fields."""
    for m in ((provider or {}).get("models") or []):
        if m.get("id") != model_id:
            continue
        if any(k in m for k in _CAP_KEYS):
            return {
                "vision": bool(m.get("vision")),
                "reasoning": bool(m.get("reasoning")),
                "context": m.get("context"),
            }
        return None
    return None


def model_capabilities(provider: Optional[dict], model_id: str) -> dict:
    """Return {vision, reasoning, context} for a model."""
    declared = _declared(provider, model_id)
    if declared is not None:
        return declared

    entry = CF_MODELS_BY_ID.get(model_id)
    if entry is not None:
        return {
            "vision": bool(entry.get("vision")),
            "reasoning": bool(entry.get("reasoning")),
            "context": entry.get("context"),
        }

    # Last resort: the demoted name-substring heuristics.
    return {
        "vision": _cf._is_vision_model(model_id),
        "reasoning": _cf._is_reasoning_model(model_id),
        "context": None,
    }


def model_sees_images(provider: Optional[dict], model_id: str) -> bool:
    """True when the model accepts image_url content parts."""
    return model_capabilities(provider, model_id)["vision"]


def model_reasons(provider: Optional[dict], model_id: str) -> bool:
    """True when the model emits a hidden reasoning channel (so reasoning_effort
    should be capped)."""
    return model_capabilities(provider, model_id)["reasoning"]
