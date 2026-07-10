"""Document RAG: chunk → embed → Qdrant.

All external dependencies are reached via env-configurable URLs:
- QDRANT_URL    (default http://qdrant:6333)
- CF Workers AI (settings.cf_run_url) for the bge embedding model

The vector store is sharded per-user via one Qdrant collection per user
named `tomsense_u_<user_id>`. Chunks carry payload metadata so retrieval can
surface filename + a snippet without a second DB round-trip.

If Qdrant is unreachable, every entrypoint logs and returns safely empty —
the chat path still works, just without retrieval.
"""

import logging
import re
import uuid as uuidlib
from typing import Optional

from .cf import get_client
from .config import settings


log = logging.getLogger("tomsense.rag")


# ─── chunking ──────────────────────────────────────────────────────────────

CHUNK_CHARS = 1800       # ~450 tokens, fits well under bge's 512 cap
CHUNK_OVERLAP = 200
MIN_CHUNK_CHARS = 60

_PARAGRAPH_RE = re.compile(r"\n\s*\n")
_WHITESPACE_RE = re.compile(r"[ \t]+")


def chunk_text(text: str) -> list[str]:
    """Split text into overlapping chunks, biased to paragraph boundaries."""
    if not text:
        return []
    text = _WHITESPACE_RE.sub(" ", text).strip()
    if len(text) <= CHUNK_CHARS:
        return [text] if len(text) >= MIN_CHUNK_CHARS else []

    paragraphs = [p.strip() for p in _PARAGRAPH_RE.split(text) if p.strip()]

    chunks: list[str] = []
    buf = ""
    for p in paragraphs:
        if len(buf) + len(p) + 2 <= CHUNK_CHARS:
            buf = (buf + "\n\n" + p) if buf else p
            continue
        if buf:
            chunks.append(buf)
            # Carry overlap from end of previous chunk
            tail = buf[-CHUNK_OVERLAP:] if len(buf) > CHUNK_OVERLAP else ""
            buf = (tail + " " + p) if tail else p
            # Paragraph itself bigger than CHUNK_CHARS — break it further
            while len(buf) > CHUNK_CHARS:
                chunks.append(buf[:CHUNK_CHARS])
                buf = buf[CHUNK_CHARS - CHUNK_OVERLAP:]
        else:
            # Lone paragraph longer than CHUNK_CHARS
            for i in range(0, len(p), CHUNK_CHARS - CHUNK_OVERLAP):
                piece = p[i : i + CHUNK_CHARS]
                if piece:
                    chunks.append(piece)
            buf = ""
    if buf and len(buf) >= MIN_CHUNK_CHARS:
        chunks.append(buf)
    return chunks


# ─── embedding (provider-agnostic — see operations/embed.py) ───────────────

from .operations import embed as _embed_op


async def embed_batch(
    texts: list[str], user_id=None, cfg=None,
) -> list[list[float]]:
    """Embed via the user's configured embedding backend (CF default, or any
    OpenAI-compatible /embeddings provider). Thin delegate to the adapter."""
    return await _embed_op.embed_batch(texts, user_id=user_id, cfg=cfg)


# ─── qdrant client (lazy + safe) ───────────────────────────────────────────

_qdrant = None


def _client():
    """Lazy import + lazy connect so the backend boots even if qdrant is down."""
    global _qdrant
    if _qdrant is not None:
        return _qdrant
    try:
        from qdrant_client import AsyncQdrantClient
    except Exception as e:
        log.warning("qdrant-client not installed: %s", e)
        return None
    try:
        _qdrant = AsyncQdrantClient(url=settings.qdrant_url, prefer_grpc=False, timeout=10.0)
        return _qdrant
    except Exception as e:
        log.warning("qdrant connect failed: %s", e)
        return None


def _collection_for(user_id: str) -> str:
    # Underscore-cleaned UUID — qdrant collection names disallow some chars
    return f"tomsense_u_{user_id.replace('-', '')}"


async def ensure_collection(user_id: str, dim=None) -> bool:
    cli = _client()
    if cli is None:
        return False
    coll = _collection_for(user_id)
    try:
        existing = await cli.collection_exists(coll)
        if existing:
            return True
        from qdrant_client.models import Distance, VectorParams
        await cli.create_collection(
            collection_name=coll,
            vectors_config=VectorParams(
                size=dim or settings.embed_dim, distance=Distance.COSINE
            ),
        )
        return True
    except Exception as e:
        log.warning("ensure_collection failed: %s", e)
        return False


# ─── indexing ──────────────────────────────────────────────────────────────

async def index_document(
    user_id: str,
    upload_id: str,
    filename: str,
    kind: str,
    text: str,
) -> int:
    """Chunk, embed, upsert. Returns the chunk count. Safe to call from a
    background task — failures are logged and swallowed."""
    if not text or not text.strip():
        return 0
    cli = _client()
    if cli is None:
        return 0
    cfg = await _embed_op.resolve_embed(user_id)
    if not await ensure_collection(user_id, cfg["dim"]):
        return 0

    chunks = chunk_text(text)
    if not chunks:
        return 0
    try:
        vectors = await embed_batch(chunks, cfg=cfg)
    except Exception as e:
        log.warning("embed failed for %s: %s", upload_id, e)
        return 0
    if len(vectors) != len(chunks):
        log.warning("embed mismatch: %d chunks vs %d vectors", len(chunks), len(vectors))
        return 0

    from qdrant_client.models import PointStruct
    points = []
    for i, (chunk, vec) in enumerate(zip(chunks, vectors)):
        # Deterministic point id derived from upload_id + chunk index
        pid = str(uuidlib.uuid5(uuidlib.NAMESPACE_URL, f"{upload_id}#{i}"))
        points.append(PointStruct(
            id=pid,
            vector=vec,
            payload={
                "upload_id": upload_id,
                "filename": filename,
                "kind": kind,
                "ordinal": i,
                "text": chunk,
            },
        ))
    coll = _collection_for(user_id)
    UPSERT_BATCH = 200
    try:
        for start in range(0, len(points), UPSERT_BATCH):
            sub = points[start : start + UPSERT_BATCH]
            await cli.upsert(collection_name=coll, points=sub, wait=False)
    except Exception as e:
        log.warning("qdrant upsert failed: %s", e)
        return 0
    log.info("indexed %d chunks for upload %s (%s)", len(chunks), upload_id, filename)
    return len(chunks)


# ─── retrieval ─────────────────────────────────────────────────────────────

async def search(
    user_id: str,
    query: str,
    k: int = 5,
    upload_ids: Optional[list[str]] = None,
) -> list[dict]:
    """Top-k chunk results. Each dict: {filename, score, text, upload_id}.
    `upload_ids` restricts hits to those documents — used to scope a chat
    to its project's knowledge files."""
    if not query or not query.strip():
        return []
    cli = _client()
    if cli is None:
        return []
    coll = _collection_for(user_id)
    try:
        if not await cli.collection_exists(coll):
            return []
    except Exception:
        return []
    try:
        vectors = await embed_batch([query.strip()], user_id=user_id)
    except Exception as e:
        log.warning("query embed failed: %s", e)
        return []
    if not vectors:
        return []
    qfilter = None
    if upload_ids:
        from qdrant_client import models as qmodels
        qfilter = qmodels.Filter(must=[
            qmodels.FieldCondition(
                key="upload_id", match=qmodels.MatchAny(any=[str(u) for u in upload_ids]),
            )
        ])
    try:
        # query_points is the v1.10+ API; fall back to search() if older
        try:
            result = await cli.query_points(
                collection_name=coll,
                query=vectors[0],
                limit=k,
                with_payload=True,
                query_filter=qfilter,
            )
            hits = getattr(result, "points", result) or []
        except AttributeError:
            hits = await cli.search(
                collection_name=coll,
                query_vector=vectors[0],
                limit=k,
                with_payload=True,
                query_filter=qfilter,
            )
    except Exception as e:
        log.warning("qdrant search failed: %s", e)
        return []

    out = []
    for h in hits:
        payload = getattr(h, "payload", None) or {}
        out.append({
            "filename": payload.get("filename") or "unknown",
            "kind": payload.get("kind") or "text",
            "upload_id": payload.get("upload_id"),
            "text": payload.get("text") or "",
            "score": float(getattr(h, "score", 0.0)),
        })
    return out


async def delete_upload_chunks(user_id: str, upload_id: str) -> int:
    """Delete every chunk in the user's collection whose payload.upload_id
    matches. Returns the number of points removed (best-effort; some qdrant
    versions don't return an exact count)."""
    cli = _client()
    if cli is None:
        return 0
    coll = _collection_for(user_id)
    try:
        if not await cli.collection_exists(coll):
            return 0
    except Exception:
        return 0
    from qdrant_client.models import Filter, FieldCondition, MatchValue, FilterSelector
    flt = Filter(must=[FieldCondition(key="upload_id", match=MatchValue(value=upload_id))])
    try:
        await cli.delete(collection_name=coll, points_selector=FilterSelector(filter=flt), wait=False)
    except Exception as e:
        log.warning("qdrant chunk delete failed for %s: %s", upload_id, e)
        return 0
    return 1


async def delete_user_collection(user_id: str) -> None:
    cli = _client()
    if cli is None:
        return
    try:
        await cli.delete_collection(_collection_for(user_id))
    except Exception as e:
        log.warning("collection delete failed: %s", e)


# ─── embedding status + reindex ────────────────────────────────────────────

def _collection_dim(info) -> Optional[int]:
    """Pull the vector size out of a qdrant get_collection() response, tolerant
    of the named/unnamed vector shapes across client versions."""
    try:
        vectors = info.config.params.vectors
    except Exception:
        return None
    size = getattr(vectors, "size", None)
    if isinstance(size, int):
        return size
    if isinstance(vectors, dict):
        for v in vectors.values():
            s = getattr(v, "size", None)
            if isinstance(s, int):
                return s
    return None


async def rag_status(user_id: str) -> dict:
    """Report the user's embedding config vs. the state of their vector
    collection so the UI can warn when a model/dim change has orphaned the
    already-indexed documents (`stale` = collection dim != configured dim)."""
    cfg = await _embed_op.resolve_embed(user_id)
    out = {
        "model": cfg["model"], "kind": cfg["kind"], "configured_dim": cfg["dim"],
        "collection_dim": None, "indexed_chunks": 0, "stale": False,
    }
    cli = _client()
    if cli is None:
        return out
    coll = _collection_for(user_id)
    try:
        if not await cli.collection_exists(coll):
            return out
        info = await cli.get_collection(coll)
    except Exception as e:
        log.warning("rag_status failed: %s", e)
        return out
    out["collection_dim"] = _collection_dim(info)
    out["indexed_chunks"] = int(getattr(info, "points_count", 0) or 0)
    if (out["indexed_chunks"] > 0 and out["collection_dim"] is not None
            and out["collection_dim"] != cfg["dim"]):
        out["stale"] = True
    return out


async def reindex_user(user_id: str) -> dict:
    """Re-embed every indexed chunk with the user's current embedding config.
    Chunk text lives in the qdrant payloads, so this needs no original files:
    scroll existing points → recreate the collection at the configured dim →
    re-embed + re-upsert. Returns {ok, reindexed, error?}."""
    cli = _client()
    if cli is None:
        return {"ok": False, "error": "vector store unavailable"}
    coll = _collection_for(user_id)
    cfg = await _embed_op.resolve_embed(user_id)
    try:
        exists = await cli.collection_exists(coll)
    except Exception as e:
        return {"ok": False, "error": f"collection check failed: {e}"}

    records = []
    if exists:
        offset = None
        try:
            while True:
                points, offset = await cli.scroll(
                    collection_name=coll, limit=256, offset=offset,
                    with_payload=True, with_vectors=False,
                )
                records.extend(points)
                if offset is None:
                    break
        except Exception as e:
            return {"ok": False, "error": f"scroll failed: {e}"}

    from qdrant_client.models import Distance, VectorParams, PointStruct
    try:
        if exists:
            await cli.delete_collection(coll)
        await cli.create_collection(
            collection_name=coll,
            vectors_config=VectorParams(size=cfg["dim"], distance=Distance.COSINE),
        )
    except Exception as e:
        return {"ok": False, "error": f"recreate failed: {e}"}

    keep = [r for r in records if (getattr(r, "payload", None) or {}).get("text")]
    if not keep:
        return {"ok": True, "reindexed": 0}
    texts = [(r.payload or {}).get("text") or "" for r in keep]
    try:
        vectors = await embed_batch(texts, cfg=cfg)
    except Exception as e:
        return {"ok": False, "error": f"embed failed: {e}"}
    if len(vectors) != len(keep):
        return {"ok": False, "error": "embed count mismatch — collection reset but not repopulated"}

    points = [
        PointStruct(id=keep[i].id, vector=vectors[i], payload=keep[i].payload or {})
        for i in range(len(keep))
    ]
    try:
        for start in range(0, len(points), 200):
            await cli.upsert(collection_name=coll, points=points[start : start + 200], wait=False)
    except Exception as e:
        return {"ok": False, "error": f"upsert failed: {e}"}
    log.info("reindexed %d chunks for user %s → dim %d", len(points), user_id, cfg["dim"])
    return {"ok": True, "reindexed": len(points)}
