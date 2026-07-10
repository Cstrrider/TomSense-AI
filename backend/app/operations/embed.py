"""Provider-agnostic embeddings.

- CF builtin  → Workers AI native: POST {cf_run_url}/{model}, body {text:[...]}.
- openai-compat → standard: POST {base_url}/embeddings, body {model, input:[...]}.

Backend is chosen by the user's `embed_model` pref (a `provider::model` string);
empty falls back to the bundled CF default (settings.model_embed / EMBED_DIM).
The output width MUST match the vector store, so it's carried as `dim`
(pref `embed_dim`, default EMBED_DIM) — declared, not guessed.
"""

import logging
from typing import Optional

from .. import db
from .. import providers as _prov
from ..cf import get_client
from ..config import settings


log = logging.getLogger("tomsense.embed")

# CF bge times out on huge single-shot batches; both shapes are fine sub-batched.
EMBED_SUBBATCH = 64


async def resolve_embed(user_id: Optional[str]) -> dict:
    """Resolve the embedding backend for a user → {kind, provider, model, dim}.
    `kind` is 'cf' (native run URL) or 'openai' (standard /embeddings)."""
    model_str = ""
    dim = settings.embed_dim
    if user_id:
        try:
            prefs = await db.get_user_prefs(user_id) or {}
            model_str = (prefs.get("embed_model") or "").strip()
            if model_str:
                d = prefs.get("embed_dim")
                if d:
                    dim = int(d)
        except Exception as e:
            log.warning("embed pref read failed for %s: %s", user_id, e)

    if not model_str:
        return {"kind": "cf", "provider": None, "model": settings.model_embed,
                "dim": settings.embed_dim}

    pid, mid = _prov.parse_model_str(model_str)
    if pid == _prov.CF_BUILTIN_ID:
        return {"kind": "cf", "provider": None, "model": mid, "dim": dim}

    provider = await _prov.resolve_provider(pid, user_id=user_id)
    if not provider:
        log.warning("embed provider %r not found — falling back to CF default", pid)
        return {"kind": "cf", "provider": None, "model": settings.model_embed,
                "dim": settings.embed_dim}
    return {"kind": "openai", "provider": provider, "model": mid, "dim": dim}


async def embed_batch(
    texts: list[str], user_id: Optional[str] = None, cfg: Optional[dict] = None,
) -> list[list[float]]:
    """Embed a batch, auto sub-batching. `cfg` (from resolve_embed) is reused
    when a caller embeds several times in one operation."""
    if not texts:
        return []
    if cfg is None:
        cfg = await resolve_embed(user_id)
    out: list[list[float]] = []
    for start in range(0, len(texts), EMBED_SUBBATCH):
        out.extend(await _embed_one(texts[start : start + EMBED_SUBBATCH], cfg))
    return out


async def _embed_one(texts: list[str], cfg: dict) -> list[list[float]]:
    if cfg["kind"] == "openai":
        provider = cfg["provider"] or {}
        base = (provider.get("base_url") or "").rstrip("/")
        if base.endswith("/chat/completions"):
            base = base[: -len("/chat/completions")]
        url = f"{base}/embeddings"
        headers = {
            "Authorization": f"Bearer {provider.get('api_key') or ''}",
            "Content-Type": "application/json",
        }
        r = await get_client().post(
            url, headers=headers, json={"model": cfg["model"], "input": texts}, timeout=90.0
        )
        if not r.is_success:
            raise RuntimeError(f"embed {cfg['model']} {r.status_code}: {r.text[:300]}")
        data = r.json()
        items = data.get("data")
        if not isinstance(items, list):
            raise RuntimeError(f"unexpected embed response: {str(data)[:200]}")
        try:  # OpenAI returns an `index` per item — order by it, don't trust position
            items = sorted(items, key=lambda e: e.get("index", 0))
        except Exception:
            pass
        return [
            (e.get("embedding") or e.get("vector")) if isinstance(e, dict) else e
            for e in items
        ]

    # Cloudflare Workers AI native shape.
    url = f"{settings.cf_run_url}/{cfg['model']}"
    headers = {
        "Authorization": f"Bearer {settings.cf_api_token}",
        "Content-Type": "application/json",
    }
    r = await get_client().post(url, headers=headers, json={"text": texts}, timeout=90.0)
    if not r.is_success:
        raise RuntimeError(f"CF embed {r.status_code}: {r.text[:300]}")
    data = r.json()
    result = data.get("result") or {}
    if isinstance(result, dict) and "data" in result:
        return [
            (e if isinstance(e, list) else (e.get("embedding") or e.get("vector")))
            for e in result["data"]
        ]
    if isinstance(result, list):
        return [
            (e if isinstance(e, list) else (e.get("embedding") if isinstance(e, dict) else e))
            for e in result
        ]
    raise RuntimeError(f"unexpected embed response: {str(data)[:200]}")
