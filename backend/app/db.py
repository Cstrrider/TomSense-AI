"""Async Postgres pool + chat/message persistence.

Tables (bootstrapped on first startup):
  chats(id UUID, title, model, created_at, updated_at)
  messages(id BIGSERIAL, chat_id UUID, role, content, tool_calls JSONB, created_at)
"""

import json
import os
import re
import uuid
from typing import Any, Optional

import asyncpg


_pool: Optional[asyncpg.Pool] = None


SCHEMA = """
-- Phase 5: users
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    monthly_token_limit BIGINT NOT NULL DEFAULT 5000000,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    usage_period_start TIMESTAMPTZ NOT NULL DEFAULT date_trunc('month', now())
);

-- Per-user preferences (TTS/STT provider + voice, etc.)
ALTER TABLE users ADD COLUMN IF NOT EXISTS prefs JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS chats (
    id UUID PRIMARY KEY,
    title TEXT,
    model TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Phase 4: per-chat persona / custom instructions, share token
ALTER TABLE chats ADD COLUMN IF NOT EXISTS system_prompt TEXT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS share_token TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_chats_share_token ON chats(share_token) WHERE share_token IS NOT NULL;

-- Phase 5: chat + upload ownership. Nullable initially so the backfill below
-- can run; the backend treats NULL ownership as "legacy / default user".
ALTER TABLE chats   ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_chats_user_updated ON chats(user_id, updated_at DESC);

-- Phase B-C: organization
ALTER TABLE chats ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS folder TEXT;
CREATE INDEX IF NOT EXISTS idx_chats_user_pinned ON chats(user_id, is_pinned DESC, updated_at DESC);

-- Code mode: chats whose agent runs file/bash tools in the sandbox.
ALTER TABLE chats ADD COLUMN IF NOT EXISTS is_code BOOLEAN NOT NULL DEFAULT false;

-- Incremental summary cache. summary_up_to_idx is the count of leading
-- messages (by ascending id) that are folded into summary_text. NULL/0 means
-- no summary cached yet.
ALTER TABLE chats ADD COLUMN IF NOT EXISTS summary_text TEXT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS summary_up_to_idx INT NOT NULL DEFAULT 0;

-- Code-mode working set: the file paths this chat's agent has read/edited, most
-- recent first. On each new code turn the backend re-reads their CURRENT
-- contents from the sandbox and injects them, so the agent doesn't re-read what
-- it already opened (tool results aren't otherwise carried across turns). Just
-- a path list — contents are always pulled fresh, never cached, so it can't go
-- stale. Capped when written.
ALTER TABLE chats ADD COLUMN IF NOT EXISTS code_working_set JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Round A: per-user external providers (OpenAI-compatible APIs). The built-in
-- Cloudflare provider is synthetic (not a row) and is sourced from env vars.
-- Provider api_key column is transparently encrypted at rest via Fernet
-- (TOMSENSE_ENCRYPTION_KEY env). See crypto.py — pre-encryption plaintext rows
-- keep working until they're next written.
CREATE TABLE IF NOT EXISTS providers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,                    -- display label, e.g. "OpenAI"
    base_url TEXT NOT NULL,                -- e.g. https://api.openai.com/v1
    api_key TEXT NOT NULL,                 -- "fernet:<ciphertext>" or legacy plaintext
    kind TEXT NOT NULL DEFAULT 'openai-compat',
    models JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{id, label, note?, tools: [chat|code|research|image]}]
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- I1 (2026-05-23): builtin providers (cf, anthropic) live in this table too,
-- one row per user, so credentials have a single storage model. The synthetic
-- builtin objects in providers.py become a fallback for users without rows.
ALTER TABLE providers ADD COLUMN IF NOT EXISTS is_builtin BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE providers ADD COLUMN IF NOT EXISTS builtin_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uniq_user_builtin
    ON providers(user_id, builtin_id) WHERE is_builtin = true;
CREATE INDEX IF NOT EXISTS idx_providers_user ON providers(user_id, name);

-- Per-provider daily usage: tokens (always) + cost (when the provider
-- reports it, e.g. OpenRouter). Powers the sidebar counter's tokens/cost
-- mode. Keyed per user+day+provider so "auto" can show the active
-- provider's row. Day is UTC to line up with the CF neuron window.
CREATE TABLE IF NOT EXISTS usage_daily (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    provider_id TEXT NOT NULL,
    provider_name TEXT,
    tokens_in BIGINT NOT NULL DEFAULT 0,
    tokens_out BIGINT NOT NULL DEFAULT 0,
    cost DOUBLE PRECISION NOT NULL DEFAULT 0,
    requests INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, provider_id)
);

-- Projects: replace the old free-text `folder` grouping. A project groups
-- chats and carries a shared system prompt prepended on every chat in it.
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    system_prompt TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_projects_user ON projects(user_id, name);
ALTER TABLE chats ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_chats_project ON chats(project_id);

-- One-time migration: fold each distinct legacy chat.folder value into a
-- project. Idempotent — the project_id IS NULL guard means already-migrated
-- chats are skipped on subsequent boots.
DO $migrate_folders$
DECLARE r RECORD; pid UUID;
BEGIN
  FOR r IN
    SELECT DISTINCT user_id, folder FROM chats
    WHERE folder IS NOT NULL AND folder <> '' AND project_id IS NULL
  LOOP
    INSERT INTO projects (id, user_id, name)
      VALUES (gen_random_uuid(), r.user_id, r.folder)
      RETURNING id INTO pid;
    UPDATE chats SET project_id = pid
      WHERE user_id = r.user_id AND folder = r.folder AND project_id IS NULL;
  END LOOP;
END $migrate_folders$;

-- Personas library
CREATE TABLE IF NOT EXISTS personas (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    system_prompt TEXT,
    model TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_personas_user ON personas(user_id, name);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    tool_calls JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS uploads (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL,                    -- 'image' | 'text' | 'pdf'
    filename TEXT NOT NULL,
    mime TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    text_excerpt TEXT,                     -- extracted body for text/pdf, truncated
    storage_path TEXT,                     -- absolute path on the uploads volume (images only)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE uploads ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_uploads_user ON uploads(user_id);
-- Project knowledge files: uploads attached to a project are RAG-scoped for
-- every chat inside that project (Claude Projects parity).
ALTER TABLE uploads ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_uploads_project ON uploads(project_id) WHERE project_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS message_uploads (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    upload_id UUID NOT NULL REFERENCES uploads(id) ON DELETE CASCADE,
    ordinal SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, upload_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_chat_id_id ON messages(chat_id, id);
CREATE INDEX IF NOT EXISTS idx_chats_updated_at ON chats(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_message_uploads_message ON message_uploads(message_id);

CREATE TABLE IF NOT EXISTS artifacts (
    id BIGSERIAL PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    message_id BIGINT REFERENCES messages(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,                    -- 'image' | 'code'
    title TEXT,
    url TEXT,
    content TEXT,
    language TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_artifacts_chat ON artifacts(chat_id, created_at DESC);

-- Phase 6: per-user general memory (tool-managed)
CREATE TABLE IF NOT EXISTS user_memories (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_user_memories_user ON user_memories(user_id, id DESC);

-- Per-user secret vault. `value` is encrypted at rest via Fernet
-- ("fernet:<ciphertext>", same as providers.api_key — see crypto.py). Plaintext
-- is NEVER returned to the client; it is decrypted server-side only to inject
-- into the sandbox shell env at exec time, keyed by `name`.
CREATE TABLE IF NOT EXISTS user_secrets (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, name)
);

-- In-app notification queue. notify.py writes here on every event; the
-- Capacitor app drains unseen rows on open/resume and shows them as local
-- notifications (ntfy stays an optional secondary transport for closed-app
-- delivery of async events).
CREATE TABLE IF NOT EXISTS app_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT,
    click_path TEXT,
    seen BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_notifications_user ON app_notifications(user_id, id DESC);

-- Remote MCP servers (Settings → MCP). auth_header is encrypted at rest via
-- crypto.py (same scheme as provider api keys).
CREATE TABLE IF NOT EXISTS mcp_servers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    auth_header TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mcp_servers_user ON mcp_servers(user_id);

-- Code-mode turn checkpoints: git snapshot per touched project taken before
-- each turn, so an agent run can be rewound. stash_sha NULL = tree was clean
-- (restore is just reset --hard head_sha).
CREATE TABLE IF NOT EXISTS code_checkpoints (
    id BIGSERIAL PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    project_root TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    stash_sha TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_code_checkpoints_chat ON code_checkpoints(chat_id, id DESC);

-- Scheduled prompts: server-side "run this prompt every day at 07:00".
-- weekdays is a bitmask, Mon=1 … Sun=64 (127 = every day). run_at is "HH:MM"
-- in the backend's SCHEDULE_TZ. last_run_date guards double-fires.
CREATE TABLE IF NOT EXISTS scheduled_prompts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    prompt TEXT NOT NULL,
    run_at TEXT NOT NULL,
    weekdays SMALLINT NOT NULL DEFAULT 127,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    model TEXT,
    last_run_date TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_scheduled_prompts_user ON scheduled_prompts(user_id);
"""


async def init_pool() -> None:
    global _pool
    if _pool is not None:
        return
    dsn = os.getenv("DATABASE_URL")
    if not dsn:
        raise RuntimeError("DATABASE_URL not set")
    _pool = await asyncpg.create_pool(dsn, min_size=1, max_size=8)
    async with _pool.acquire() as conn:
        await conn.execute(SCHEMA)
        await _backfill_default_user(conn)


async def _backfill_default_user(conn) -> None:
    """Ensure a default user exists and own any pre-Phase-5 rows."""
    default_email = os.getenv("LOCAL_DEV_EMAIL", "local@dev")
    uid = await conn.fetchval(
        "SELECT id FROM users WHERE email = $1", default_email
    )
    if uid is None:
        uid = uuid.uuid4()
        await conn.execute(
            "INSERT INTO users (id, email, name) VALUES ($1, $2, $3) "
            "ON CONFLICT (email) DO NOTHING",
            uid, default_email, "Default User",
        )
    # Backfill ownership for legacy rows
    await conn.execute("UPDATE chats   SET user_id = $1 WHERE user_id IS NULL", uid)
    await conn.execute("UPDATE uploads SET user_id = $1 WHERE user_id IS NULL", uid)


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def _pool_or_raise() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("DB pool not initialized")
    return _pool


def _row_to_chat(r: asyncpg.Record) -> dict:
    out = {
        "id": str(r["id"]),
        "title": r["title"],
        "model": r["model"],
        "created_at": r["created_at"].isoformat(),
        "updated_at": r["updated_at"].isoformat(),
    }
    for col in ("system_prompt", "share_token", "is_pinned", "folder", "is_code"):
        try:
            out[col] = r[col]
        except (KeyError, IndexError):
            pass
    try:
        out["project_id"] = str(r["project_id"]) if r["project_id"] else None
    except (KeyError, IndexError):
        pass
    return out


def _row_to_message(r: asyncpg.Record) -> dict:
    tool_calls = r["tool_calls"]
    if isinstance(tool_calls, str):
        try:
            tool_calls = json.loads(tool_calls)
        except json.JSONDecodeError:
            tool_calls = None
    return {
        "id": r["id"],
        "role": r["role"],
        "content": r["content"] or "",
        "tool_calls": tool_calls,
        "created_at": r["created_at"].isoformat(),
    }


# ─── chats ──────────────────────────────────────────────────────────────────

async def list_chats(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, title, model, system_prompt, share_token, is_pinned, folder, is_code, project_id, created_at, updated_at "
            "FROM chats WHERE user_id = $1 "
            "ORDER BY is_pinned DESC, updated_at DESC LIMIT 200",
            uid,
        )
    return [_row_to_chat(r) for r in rows]


_REASONING_RE = re.compile(r"<details[^>]*>.*?</details>", re.DOTALL | re.IGNORECASE)


def _search_snippet(content: str, query: str, radius: int = 90) -> str:
    """A short excerpt of `content` centred on the first match of `query`,
    with whitespace collapsed and ellipses marking where it was trimmed.
    Collapsible reasoning blocks are dropped so snippets show answer text."""
    text = " ".join(_REASONING_RE.sub(" ", content or "").split())
    if not text:
        return ""
    idx = text.lower().find(query.lower())
    if idx < 0:  # matched elsewhere (shouldn't happen) — fall back to the head
        return text[: radius * 2] + ("…" if len(text) > radius * 2 else "")
    start = max(0, idx - radius)
    end = min(len(text), idx + len(query) + radius)
    snip = text[start:end]
    if start > 0:
        snip = "…" + snip
    if end < len(text):
        snip = snip + "…"
    return snip


async def search_chats(user_id: str, query: str) -> list[dict]:
    """Chats whose title or message content matches `query` (case-insensitive
    substring). Each result carries `title_match` plus up to 3 message `hits`
    — {message_id, role, snippet} — for the most recent matching messages,
    ordered most-recently-updated first."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    q = (query or "").strip()
    if not q:
        return []
    # Escape LIKE wildcards so the query matches as a literal substring.
    like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT c.id, c.title, c.model, c.system_prompt, c.share_token, "
            "       c.is_pinned, c.folder, c.is_code, c.project_id, c.created_at, c.updated_at, "
            "       (c.title ILIKE $2) AS title_match, "
            "       h.id AS hit_id, h.role AS hit_role, h.content AS hit_content "
            "FROM chats c "
            "LEFT JOIN LATERAL ("
            "    SELECT id, role, content FROM messages "
            "    WHERE chat_id = c.id AND role IN ('user', 'assistant') "
            "      AND content ILIKE $2 "
            "    ORDER BY id DESC LIMIT 3"
            ") h ON true "
            "WHERE c.user_id = $1 "
            "  AND (c.title ILIKE $2 OR EXISTS ("
            "      SELECT 1 FROM messages "
            "      WHERE chat_id = c.id AND content ILIKE $2)) "
            "ORDER BY c.updated_at DESC LIMIT 300",
            uid, like,
        )
    by_id: dict[str, dict] = {}
    order: list[str] = []
    for r in rows:
        cid = str(r["id"])
        chat = by_id.get(cid)
        if chat is None:
            chat = _row_to_chat(r)
            chat["title_match"] = bool(r["title_match"])
            chat["hits"] = []
            by_id[cid] = chat
            order.append(cid)
        if r["hit_id"] is not None:
            chat["hits"].append({
                "message_id": r["hit_id"],
                "role": r["hit_role"],
                "snippet": _search_snippet(r["hit_content"], q),
            })
    return [by_id[c] for c in order]


async def delete_chats(chat_ids: list[str], user_id: str) -> int:
    """Bulk-delete user-owned chats. Returns the number actually deleted."""
    try:
        uid = uuid.UUID(user_id)
        cids = [uuid.UUID(c) for c in chat_ids]
    except (ValueError, TypeError):
        return 0
    if not cids:
        return 0
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM chats WHERE user_id = $1 AND id = ANY($2::uuid[])",
            uid, cids,
        )
    try:
        return int(result.split(" ")[-1])
    except (ValueError, IndexError):
        return 0


async def create_chat(
    user_id: str, model: str, title: Optional[str] = None, is_code: bool = False,
) -> dict:
    chat_id = uuid.uuid4()
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        raise ValueError("invalid user_id")
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO chats (id, user_id, title, model, is_code) "
            "VALUES ($1, $2, $3, $4, $5) "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, "
            "folder, is_code, project_id, created_at, updated_at",
            chat_id, uid, title, model, is_code,
        )
    return _row_to_chat(row)


async def get_chat(chat_id: str, user_id: Optional[str] = None) -> Optional[dict]:
    """Returns chat only if user_id owns it (or if user_id is None — internal use)."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return None
    uid = None
    if user_id is not None:
        try:
            uid = uuid.UUID(user_id)
        except (ValueError, TypeError):
            return None
    async with _pool_or_raise().acquire() as conn:
        if uid is None:
            chat_row = await conn.fetchrow(
                "SELECT id, title, model, system_prompt, share_token, is_pinned, folder, is_code, project_id, created_at, updated_at FROM chats WHERE id = $1",
                cid,
            )
        else:
            chat_row = await conn.fetchrow(
                "SELECT id, title, model, system_prompt, share_token, is_pinned, folder, is_code, project_id, created_at, updated_at "
                "FROM chats WHERE id = $1 AND user_id = $2",
                cid, uid,
            )
        if chat_row is None:
            return None
        msg_rows = await conn.fetch(
            "SELECT id, role, content, tool_calls, created_at "
            "FROM messages WHERE chat_id = $1 ORDER BY id ASC",
            cid,
        )
        upload_rows = await conn.fetch(
            "SELECT mu.message_id, mu.ordinal, u.id, u.kind, u.filename, u.mime, u.size_bytes "
            "FROM message_uploads mu "
            "JOIN uploads u ON u.id = mu.upload_id "
            "JOIN messages m ON m.id = mu.message_id "
            "WHERE m.chat_id = $1 "
            "ORDER BY mu.message_id, mu.ordinal",
            cid,
        )
    uploads_by_msg: dict[int, list[dict]] = {}
    for r in upload_rows:
        uploads_by_msg.setdefault(r["message_id"], []).append({
            "id": str(r["id"]),
            "kind": r["kind"],
            "filename": r["filename"],
            "mime": r["mime"],
            "size_bytes": r["size_bytes"],
        })
    messages = []
    for r in msg_rows:
        m = _row_to_message(r)
        m["uploads"] = uploads_by_msg.get(m["id"], [])
        messages.append(m)
    return {
        **_row_to_chat(chat_row),
        "messages": messages,
    }


WORKING_SET_CAP = 12  # most-recent files kept per code chat


async def get_code_working_set(chat_id: str) -> list[str]:
    """The file paths this code chat has touched, most-recent-first."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow("SELECT code_working_set FROM chats WHERE id = $1", cid)
    if not row:
        return []
    val = row["code_working_set"]
    if isinstance(val, str):
        try:
            val = json.loads(val)
        except (ValueError, TypeError):
            return []
    if not isinstance(val, list):
        return []
    return [str(p) for p in val if isinstance(p, str)][:WORKING_SET_CAP]


async def update_code_working_set(chat_id: str, touched: list[str]) -> None:
    """Merge newly-touched paths to the front of the chat's working set, dedup,
    and cap. No-op on bad input."""
    if not touched:
        return
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    existing = await get_code_working_set(chat_id)
    merged: list[str] = []
    for p in list(touched) + existing:
        p = (p or "").strip()
        if p and p not in merged:
            merged.append(p)
    merged = merged[:WORKING_SET_CAP]
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE chats SET code_working_set = $2::jsonb WHERE id = $1",
            cid, json.dumps(merged),
        )


async def delete_chat(chat_id: str, user_id: str) -> bool:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM chats WHERE id = $1 AND user_id = $2", cid, uid
        )
    return result.endswith(" 1")


async def set_chat_pinned(chat_id: str, user_id: str, pinned: bool) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET is_pinned = $1, updated_at = updated_at WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            bool(pinned), cid, uid,
        )
    return _row_to_chat(row) if row else None


async def set_chat_folder(chat_id: str, user_id: str, folder: Optional[str]) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    folder_val = folder.strip() if folder else None
    if folder_val == "":
        folder_val = None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET folder = $1, updated_at = updated_at WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            folder_val, cid, uid,
        )
    return _row_to_chat(row) if row else None


async def branch_chat(chat_id: str, user_id: str, up_to_message_id: int) -> Optional[dict]:
    """Clone messages [0..up_to_message_id] of an owned chat into a new chat
    (same model + system_prompt). Returns the new chat dict."""
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    new_id = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        async with conn.transaction():
            src = await conn.fetchrow(
                "SELECT title, model, system_prompt, is_code FROM chats WHERE id = $1 AND user_id = $2",
                cid, uid,
            )
            if src is None:
                return None
            branched_title = (src["title"] or "Chat") + " (branch)"
            # is_code must carry over — a branched code-mode chat would
            # otherwise silently continue as a normal chat (wrong tools,
            # wrong system prompt).
            await conn.execute(
                "INSERT INTO chats (id, user_id, title, model, system_prompt, is_code) "
                "VALUES ($1, $2, $3, $4, $5, $6)",
                new_id, uid, branched_title[:200], src["model"], src["system_prompt"],
                bool(src["is_code"]),
            )
            # Copy messages with id <= up_to_message_id, preserving order
            await conn.execute(
                "INSERT INTO messages (chat_id, role, content, tool_calls) "
                "SELECT $1, role, content, tool_calls FROM messages "
                "WHERE chat_id = $2 AND id <= $3 ORDER BY id ASC",
                new_id, cid, int(up_to_message_id),
            )
            # Copy upload associations. New message ids are auto-generated,
            # so map old→new by row rank (the copy above preserves order).
            await conn.execute(
                "WITH src AS ("
                "    SELECT id, row_number() OVER (ORDER BY id) AS rn "
                "    FROM messages WHERE chat_id = $2 AND id <= $3"
                "), dst AS ("
                "    SELECT id, row_number() OVER (ORDER BY id) AS rn "
                "    FROM messages WHERE chat_id = $1"
                ") "
                "INSERT INTO message_uploads (message_id, upload_id, ordinal) "
                "SELECT dst.id, mu.upload_id, mu.ordinal "
                "FROM message_uploads mu "
                "JOIN src ON src.id = mu.message_id "
                "JOIN dst ON dst.rn = src.rn",
                new_id, cid, int(up_to_message_id),
            )
            row = await conn.fetchrow(
                "SELECT id, title, model, system_prompt, share_token, is_pinned, folder, is_code, project_id, created_at, updated_at "
                "FROM chats WHERE id = $1",
                new_id,
            )
    return _row_to_chat(row) if row else None


# ─── projects ───────────────────────────────────────────────────────────────

def _row_to_project(r: asyncpg.Record) -> dict:
    return {
        "id": str(r["id"]),
        "name": r["name"],
        "system_prompt": r["system_prompt"],
        "created_at": r["created_at"].isoformat(),
        "updated_at": r["updated_at"].isoformat(),
    }


async def list_projects(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, system_prompt, created_at, updated_at "
            "FROM projects WHERE user_id = $1 ORDER BY name ASC",
            uid,
        )
    return [_row_to_project(r) for r in rows]


async def get_project(project_id: str, user_id: Optional[str] = None) -> Optional[dict]:
    try:
        pid = uuid.UUID(project_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        if user_id is not None:
            try:
                uid = uuid.UUID(user_id)
            except (ValueError, TypeError):
                return None
            row = await conn.fetchrow(
                "SELECT id, name, system_prompt, created_at, updated_at "
                "FROM projects WHERE id = $1 AND user_id = $2",
                pid, uid,
            )
        else:
            row = await conn.fetchrow(
                "SELECT id, name, system_prompt, created_at, updated_at "
                "FROM projects WHERE id = $1",
                pid,
            )
    return _row_to_project(row) if row else None


async def create_project(user_id: str, name: str, system_prompt: Optional[str] = None) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    pid = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO projects (id, user_id, name, system_prompt) VALUES ($1, $2, $3, $4) "
            "RETURNING id, name, system_prompt, created_at, updated_at",
            pid, uid, name, system_prompt,
        )
    return _row_to_project(row) if row else None


async def update_project(
    project_id: str, user_id: str,
    name: Optional[str] = None, system_prompt: Optional[str] = None,
    set_system_prompt: bool = False,
) -> Optional[dict]:
    """Patch a project. `name` updates the name when non-None. The system
    prompt is updated only when `set_system_prompt` is True (so callers can
    explicitly clear it to NULL by passing system_prompt=None)."""
    try:
        pid = uuid.UUID(project_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    sets, args = [], []
    i = 1
    if name is not None:
        sets.append(f"name = ${i}")
        args.append(name)
        i += 1
    if set_system_prompt:
        sets.append(f"system_prompt = ${i}")
        args.append(system_prompt)
        i += 1
    if not sets:
        return await get_project(project_id, user_id)
    sets.append("updated_at = now()")
    args.extend([pid, uid])
    sql = (
        f"UPDATE projects SET {', '.join(sets)} "
        f"WHERE id = ${i} AND user_id = ${i+1} "
        "RETURNING id, name, system_prompt, created_at, updated_at"
    )
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(sql, *args)
    return _row_to_project(row) if row else None


async def delete_project(project_id: str, user_id: str) -> bool:
    """Delete a project. Chats in it survive — their project_id goes NULL
    (ON DELETE SET NULL), so they fall back to the ungrouped section."""
    try:
        pid = uuid.UUID(project_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM projects WHERE id = $1 AND user_id = $2", pid, uid,
        )
    return result.endswith(" 1")


async def set_chat_project(chat_id: str, user_id: str, project_id: Optional[str]) -> Optional[dict]:
    """Assign a chat to a project (or NULL to ungroup it). The project must
    belong to the same user."""
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
        pid = uuid.UUID(project_id) if project_id else None
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        if pid is not None:
            owned = await conn.fetchval(
                "SELECT 1 FROM projects WHERE id = $1 AND user_id = $2", pid, uid,
            )
            if not owned:
                return None
        row = await conn.fetchrow(
            "UPDATE chats SET project_id = $1 WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            pid, cid, uid,
        )
    return _row_to_chat(row) if row else None


# ─── personas (Phase C) ─────────────────────────────────────────────────────

def _row_to_persona(r: asyncpg.Record) -> dict:
    return {
        "id": str(r["id"]),
        "name": r["name"],
        "system_prompt": r["system_prompt"],
        "model": r["model"],
        "created_at": r["created_at"].isoformat(),
        "updated_at": r["updated_at"].isoformat(),
    }


async def list_personas(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, system_prompt, model, created_at, updated_at "
            "FROM personas WHERE user_id = $1 ORDER BY name ASC",
            uid,
        )
    return [_row_to_persona(r) for r in rows]


async def get_persona(persona_id: str, user_id: str) -> Optional[dict]:
    try:
        pid = uuid.UUID(persona_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "SELECT id, name, system_prompt, model, created_at, updated_at "
            "FROM personas WHERE id = $1 AND user_id = $2",
            pid, uid,
        )
    return _row_to_persona(row) if row else None


async def create_persona(user_id: str, name: str, system_prompt: Optional[str], model: Optional[str]) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    pid = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO personas (id, user_id, name, system_prompt, model) VALUES ($1, $2, $3, $4, $5) "
            "RETURNING id, name, system_prompt, model, created_at, updated_at",
            pid, uid, name.strip()[:120], (system_prompt or None), (model or None),
        )
    return _row_to_persona(row) if row else None


async def update_persona(persona_id: str, user_id: str, name: Optional[str], system_prompt: Optional[str], model: Optional[str]) -> Optional[dict]:
    try:
        pid = uuid.UUID(persona_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE personas SET "
            "  name = COALESCE($1, name), "
            "  system_prompt = COALESCE($2, system_prompt), "
            "  model = COALESCE($3, model), "
            "  updated_at = now() "
            "WHERE id = $4 AND user_id = $5 "
            "RETURNING id, name, system_prompt, model, created_at, updated_at",
            (name.strip()[:120] if name else None),
            system_prompt,
            model,
            pid, uid,
        )
    return _row_to_persona(row) if row else None


async def delete_persona(persona_id: str, user_id: str) -> bool:
    try:
        pid = uuid.UUID(persona_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM personas WHERE id = $1 AND user_id = $2", pid, uid
        )
    return result.endswith(" 1")


async def update_chat_model(chat_id: str, user_id: str, model: str) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET model = $1, updated_at = now() WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            model, cid, uid,
        )
    return _row_to_chat(row) if row else None


async def update_chat_system_prompt(chat_id: str, user_id: str, system_prompt: Optional[str]) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    cleaned = (system_prompt or "").strip() or None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET system_prompt = $1, updated_at = now() WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            cleaned, cid, uid,
        )
    return _row_to_chat(row) if row else None


async def set_share_token(chat_id: str, user_id: str, token: Optional[str]) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET share_token = $1, updated_at = now() WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            token, cid, uid,
        )
    return _row_to_chat(row) if row else None


async def get_chat_by_share_token(token: str) -> Optional[dict]:
    """Public read-only lookup. Returns chat with messages + upload metadata,
    without revealing the share_token in the response."""
    async with _pool_or_raise().acquire() as conn:
        chat_row = await conn.fetchrow(
            "SELECT id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at "
            "FROM chats WHERE share_token = $1",
            token,
        )
        if chat_row is None:
            return None
        msg_rows = await conn.fetch(
            "SELECT id, role, content, tool_calls, created_at "
            "FROM messages WHERE chat_id = $1 ORDER BY id ASC",
            chat_row["id"],
        )
        upload_rows = await conn.fetch(
            "SELECT mu.message_id, mu.ordinal, u.id, u.kind, u.filename, u.mime, u.size_bytes "
            "FROM message_uploads mu "
            "JOIN uploads u ON u.id = mu.upload_id "
            "JOIN messages m ON m.id = mu.message_id "
            "WHERE m.chat_id = $1 "
            "ORDER BY mu.message_id, mu.ordinal",
            chat_row["id"],
        )
    uploads_by_msg: dict[int, list[dict]] = {}
    for r in upload_rows:
        uploads_by_msg.setdefault(r["message_id"], []).append({
            "id": str(r["id"]),
            "kind": r["kind"],
            "filename": r["filename"],
            "mime": r["mime"],
            "size_bytes": r["size_bytes"],
        })
    messages = []
    for r in msg_rows:
        m = _row_to_message(r)
        m["uploads"] = uploads_by_msg.get(m["id"], [])
        messages.append(m)
    out = _row_to_chat(chat_row)
    out["messages"] = messages
    out.pop("share_token", None)  # don't expose token to viewer
    return out


async def rename_chat(chat_id: str, user_id: str, title: str) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE chats SET title = $1, updated_at = now() WHERE id = $2 AND user_id = $3 "
            "RETURNING id, title, model, system_prompt, share_token, is_pinned, folder, project_id, created_at, updated_at",
            title, cid, uid,
        )
    return _row_to_chat(row) if row else None


async def touch_chat(chat_id: str) -> None:
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute("UPDATE chats SET updated_at = now() WHERE id = $1", cid)


# ─── messages ───────────────────────────────────────────────────────────────

async def add_message(
    chat_id: str,
    role: str,
    content: str,
    tool_calls: Optional[list[dict[str, Any]]] = None,
    upload_ids: Optional[list[str]] = None,
) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return None
    tc_json = json.dumps(tool_calls) if tool_calls else None
    async with _pool_or_raise().acquire() as conn:
        async with conn.transaction():
            row = await conn.fetchrow(
                "INSERT INTO messages (chat_id, role, content, tool_calls) "
                "VALUES ($1, $2, $3, $4::jsonb) "
                "RETURNING id, role, content, tool_calls, created_at",
                cid, role, content or "", tc_json,
            )
            if upload_ids:
                for i, uid in enumerate(upload_ids):
                    try:
                        u = uuid.UUID(uid)
                    except (ValueError, TypeError):
                        continue
                    await conn.execute(
                        "INSERT INTO message_uploads (message_id, upload_id, ordinal) "
                        "VALUES ($1, $2, $3) ON CONFLICT DO NOTHING",
                        row["id"], u, i,
                    )
            await conn.execute("UPDATE chats SET updated_at = now() WHERE id = $1", cid)
    return _row_to_message(row) if row else None


# ─── users (Phase 5) ────────────────────────────────────────────────────────

async def get_or_create_user(email: str, name: Optional[str] = None) -> dict:
    """Upsert a user keyed by email. Touches last_seen on every lookup."""
    email = (email or "").strip().lower()
    if not email:
        raise ValueError("empty email")
    async with _pool_or_raise().acquire() as conn:
        # Fast path: existing user
        row = await conn.fetchrow(
            "UPDATE users SET last_seen_at = now() WHERE email = $1 "
            "RETURNING id, email, name, created_at, last_seen_at, "
            "          monthly_token_limit, tokens_used, usage_period_start",
            email,
        )
        if row is None:
            uid = uuid.uuid4()
            row = await conn.fetchrow(
                "INSERT INTO users (id, email, name) VALUES ($1, $2, $3) "
                "ON CONFLICT (email) DO UPDATE SET last_seen_at = now() "
                "RETURNING id, email, name, created_at, last_seen_at, "
                "          monthly_token_limit, tokens_used, usage_period_start",
                uid, email, name,
            )
    return _row_to_user(row)


def _row_to_user(r: asyncpg.Record) -> dict:
    return {
        "id": str(r["id"]),
        "email": r["email"],
        "name": r["name"],
        "created_at": r["created_at"].isoformat(),
        "last_seen_at": r["last_seen_at"].isoformat(),
        "monthly_token_limit": r["monthly_token_limit"],
        "tokens_used": r["tokens_used"],
        "usage_period_start": r["usage_period_start"].isoformat(),
    }


async def get_user_with_rollover(user_id: str) -> Optional[dict]:
    """Return the user, rolling tokens_used to zero if a new month started."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        # Atomic rollover: if usage_period_start is in a previous month,
        # zero the counter and bump the period to current month.
        await conn.execute(
            "UPDATE users "
            "SET tokens_used = 0, usage_period_start = date_trunc('month', now()) "
            "WHERE id = $1 AND date_trunc('month', usage_period_start) < date_trunc('month', now())",
            uid,
        )
        row = await conn.fetchrow(
            "SELECT id, email, name, created_at, last_seen_at, "
            "       monthly_token_limit, tokens_used, usage_period_start "
            "FROM users WHERE id = $1",
            uid,
        )
    return _row_to_user(row) if row else None


async def add_tokens(user_id: str, prompt_tokens: int, completion_tokens: int) -> None:
    """Add token usage to the user's current-month counter."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return
    delta = int(prompt_tokens or 0) + int(completion_tokens or 0)
    if delta <= 0:
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE users SET tokens_used = tokens_used + $1 WHERE id = $2",
            delta, uid,
        )


async def record_usage(user_id: str, provider_id: str, provider_name: str,
                       tokens_in: int, tokens_out: int, cost: float) -> None:
    """Increment today's per-provider usage row (UTC day). Best-effort."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return
    tin, tout = int(tokens_in or 0), int(tokens_out or 0)
    cost = float(cost or 0.0)
    if tin <= 0 and tout <= 0 and cost <= 0:
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            """
            INSERT INTO usage_daily (user_id, day, provider_id, provider_name,
                                     tokens_in, tokens_out, cost, requests)
            VALUES ($1, (now() at time zone 'utc')::date, $2, $3, $4, $5, $6, 1)
            ON CONFLICT (user_id, day, provider_id) DO UPDATE SET
                tokens_in = usage_daily.tokens_in + EXCLUDED.tokens_in,
                tokens_out = usage_daily.tokens_out + EXCLUDED.tokens_out,
                cost = usage_daily.cost + EXCLUDED.cost,
                requests = usage_daily.requests + 1,
                provider_name = EXCLUDED.provider_name
            """,
            uid, str(provider_id or "?"), provider_name or None, tin, tout, cost,
        )


async def usage_today(user_id: str) -> list[dict]:
    """Today's per-provider usage rows (UTC). [] on any error."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    try:
        async with _pool_or_raise().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT provider_id, provider_name, tokens_in, tokens_out, cost, requests
                FROM usage_daily
                WHERE user_id = $1 AND day = (now() at time zone 'utc')::date
                ORDER BY (tokens_in + tokens_out) DESC
                """,
                uid,
            )
        return [dict(r) for r in rows]
    except Exception:
        return []


async def get_user_prefs(user_id: str) -> dict:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return {}
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow("SELECT prefs FROM users WHERE id = $1", uid)
    if not row:
        return {}
    prefs = row["prefs"]
    if isinstance(prefs, str):
        try:
            return json.loads(prefs)
        except json.JSONDecodeError:
            return {}
    return prefs or {}


async def get_user_credentials(user_id: str) -> dict:
    """Read the raw credentials map from prefs.credentials. Caller is
    responsible for masking before sending over the wire."""
    prefs = await get_user_prefs(user_id) or {}
    creds = prefs.get("credentials") if isinstance(prefs, dict) else None
    return creds if isinstance(creds, dict) else {}


async def set_user_credentials(user_id: str, patch: dict) -> dict:
    """Merge `patch` (already validated) into prefs.credentials. Values that
    are empty strings or None are unset (removed from the map). Returns the
    updated raw credentials map."""
    current = await get_user_credentials(user_id)
    merged = dict(current)
    for k, v in patch.items():
        if v is None or (isinstance(v, str) and not v.strip()):
            merged.pop(k, None)
        else:
            merged[k] = str(v).strip()
    await update_user_prefs(user_id, {"credentials": merged})
    return merged


async def update_user_prefs(user_id: str, patch: dict) -> dict:
    """Shallow-merge `patch` into the user's prefs JSON."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return {}
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE users SET prefs = COALESCE(prefs, '{}'::jsonb) || $2::jsonb "
            "WHERE id = $1 RETURNING prefs",
            uid, json.dumps(patch),
        )
    if not row:
        return {}
    prefs = row["prefs"]
    if isinstance(prefs, str):
        try:
            return json.loads(prefs)
        except json.JSONDecodeError:
            return {}
    return prefs or {}


async def chat_count_for_user(user_id: str) -> int:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return 0
    async with _pool_or_raise().acquire() as conn:
        return await conn.fetchval(
            "SELECT count(*) FROM chats WHERE user_id = $1", uid
        ) or 0


# ─── memory (Phase 6) ───────────────────────────────────────────────────────

async def add_memory(user_id: str, content: str) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO user_memories (user_id, content) VALUES ($1, $2) "
            "RETURNING id, content, created_at",
            uid, content,
        )
    return {
        "id": row["id"],
        "content": row["content"],
        "created_at": row["created_at"].isoformat(),
    } if row else None


async def list_memories(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, content, created_at FROM user_memories "
            "WHERE user_id = $1 ORDER BY id ASC",
            uid,
        )
    return [
        {"id": r["id"], "content": r["content"], "created_at": r["created_at"].isoformat()}
        for r in rows
    ]


async def delete_memory(user_id: str, memory_id: int) -> bool:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM user_memories WHERE user_id = $1 AND id = $2",
            uid, int(memory_id),
        )
    return result.endswith(" 1")


async def delete_memories_matching(user_id: str, substring: str) -> int:
    """Case-insensitive substring delete. Returns the number of rows removed."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return 0
    sub = (substring or "").strip()
    if not sub:
        return 0
    # Escape LIKE wildcards so the substring matches literally (a bare "%"
    # would otherwise delete every memory).
    pattern = "%" + sub.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM user_memories WHERE user_id = $1 AND content ILIKE $2",
            uid, pattern,
        )
    # result is "DELETE N"
    try:
        return int(result.split(" ")[-1])
    except (ValueError, IndexError):
        return 0


async def touch_memories(user_id: str) -> None:
    """Bump last_used_at for the user's memories (called after a chat turn)."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE user_memories SET last_used_at = now() WHERE user_id = $1", uid
        )


# ─── artifacts ──────────────────────────────────────────────────────────────

async def insert_artifacts(chat_id: str, message_id: int, artifacts: list[dict]) -> None:
    if not artifacts:
        return
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        for a in artifacts:
            await conn.execute(
                "INSERT INTO artifacts (chat_id, message_id, kind, title, url, content, language) "
                "VALUES ($1, $2, $3, $4, $5, $6, $7)",
                cid, message_id, a["kind"], a.get("title"),
                a.get("url"), a.get("content"), a.get("language"),
            )


async def list_artifacts(chat_id: str) -> list[dict]:
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, message_id, kind, title, url, content, language, created_at "
            "FROM artifacts WHERE chat_id = $1 ORDER BY id DESC",
            cid,
        )
    return [
        {
            "id": r["id"],
            "message_id": r["message_id"],
            "kind": r["kind"],
            "title": r["title"],
            "url": r["url"],
            "content": r["content"],
            "language": r["language"],
            "created_at": r["created_at"].isoformat(),
        }
        for r in rows
    ]


async def list_user_artifacts(user_id: str, limit: int = 200) -> list[dict]:
    """All artifacts across the user's chats, newest first, with chat title
    joined so the file-manager UI can show where each came from. Code
    `content` is truncated to a preview; full body via `get_user_artifact`."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT a.id, a.chat_id, a.message_id, a.kind, a.title, a.url, "
            "       LEFT(a.content, 400) AS content_preview, "
            "       COALESCE(LENGTH(a.content), 0) AS content_len, "
            "       a.language, a.created_at, c.title AS chat_title "
            "FROM artifacts a JOIN chats c ON c.id = a.chat_id "
            "WHERE c.user_id = $1 ORDER BY a.id DESC LIMIT $2",
            uid, limit,
        )
    return [
        {
            "id": r["id"],
            "chat_id": str(r["chat_id"]),
            "chat_title": r["chat_title"],
            "message_id": r["message_id"],
            "kind": r["kind"],
            "title": r["title"],
            "url": r["url"],
            "content_preview": r["content_preview"],
            "content_len": r["content_len"],
            "language": r["language"],
            "created_at": r["created_at"].isoformat() if r["created_at"] else None,
        }
        for r in rows
    ]


async def get_user_artifact(user_id: str, artifact_id: int) -> dict | None:
    """One artifact with FULL content, ownership-checked via the chat."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        r = await conn.fetchrow(
            "SELECT a.id, a.chat_id, a.message_id, a.kind, a.title, a.url, "
            "       a.content, a.language, a.created_at, c.title AS chat_title "
            "FROM artifacts a JOIN chats c ON c.id = a.chat_id "
            "WHERE a.id = $1 AND c.user_id = $2",
            artifact_id, uid,
        )
    if not r:
        return None
    return {
        "id": r["id"],
        "chat_id": str(r["chat_id"]),
        "chat_title": r["chat_title"],
        "message_id": r["message_id"],
        "kind": r["kind"],
        "title": r["title"],
        "url": r["url"],
        "content": r["content"],
        "language": r["language"],
        "created_at": r["created_at"].isoformat() if r["created_at"] else None,
    }


async def delete_user_artifact(user_id: str, artifact_id: int) -> bool:
    """Delete iff the artifact's chat belongs to this user. Returns whether
    a row was actually removed."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "DELETE FROM artifacts a USING chats c "
            "WHERE a.id = $1 AND a.chat_id = c.id AND c.user_id = $2 "
            "RETURNING a.id",
            artifact_id, uid,
        )
    return row is not None


# ─── uploads ────────────────────────────────────────────────────────────────

async def insert_upload(
    upload_id: str,
    user_id: str,
    kind: str,
    filename: str,
    mime: str,
    size_bytes: int,
    text_excerpt: Optional[str],
    storage_path: Optional[str],
) -> dict:
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO uploads (id, user_id, kind, filename, mime, size_bytes, text_excerpt, storage_path) "
            "VALUES ($1::uuid, $2::uuid, $3, $4, $5, $6, $7, $8) "
            "RETURNING id, kind, filename, mime, size_bytes, text_excerpt, storage_path, created_at",
            upload_id, user_id, kind, filename, mime, size_bytes, text_excerpt, storage_path,
        )
    return {
        "id": str(row["id"]),
        "kind": row["kind"],
        "filename": row["filename"],
        "mime": row["mime"],
        "size_bytes": row["size_bytes"],
        "text_excerpt": row["text_excerpt"],
        "storage_path": row["storage_path"],
        "created_at": row["created_at"].isoformat(),
    }


async def get_upload(upload_id: str, user_id: Optional[str] = None) -> Optional[dict]:
    """Returns upload only if user_id owns it (or unrestricted when None)."""
    try:
        u = uuid.UUID(upload_id)
    except (ValueError, TypeError):
        return None
    uid = None
    if user_id is not None:
        try:
            uid = uuid.UUID(user_id)
        except (ValueError, TypeError):
            return None
    async with _pool_or_raise().acquire() as conn:
        if uid is None:
            row = await conn.fetchrow(
                "SELECT id, kind, filename, mime, size_bytes, text_excerpt, storage_path, created_at "
                "FROM uploads WHERE id = $1",
                u,
            )
        else:
            row = await conn.fetchrow(
                "SELECT id, kind, filename, mime, size_bytes, text_excerpt, storage_path, created_at "
                "FROM uploads WHERE id = $1 AND user_id = $2",
                u, uid,
            )
    if not row:
        return None
    return {
        "id": str(row["id"]),
        "kind": row["kind"],
        "filename": row["filename"],
        "mime": row["mime"],
        "size_bytes": row["size_bytes"],
        "text_excerpt": row["text_excerpt"],
        "storage_path": row["storage_path"],
        "created_at": row["created_at"].isoformat(),
    }


async def list_uploads_for_user(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, kind, filename, mime, size_bytes, created_at, project_id, "
            "       (text_excerpt IS NOT NULL) AS has_text "
            "FROM uploads WHERE user_id = $1 "
            "ORDER BY created_at DESC LIMIT 500",
            uid,
        )
    return [
        {
            "id": str(r["id"]),
            "kind": r["kind"],
            "filename": r["filename"],
            "mime": r["mime"],
            "size_bytes": r["size_bytes"],
            "created_at": r["created_at"].isoformat(),
            "indexed": bool(r["has_text"]),  # text/pdf get indexed
            "project_id": str(r["project_id"]) if r["project_id"] else None,
        }
        for r in rows
    ]


async def set_upload_project(upload_id: str, user_id: str, project_id: Optional[str]) -> bool:
    """Attach/detach an upload to a project (knowledge file). Ownership of
    both the upload and the project is enforced."""
    try:
        upid = uuid.UUID(upload_id)
        uid = uuid.UUID(user_id)
        pid = uuid.UUID(project_id) if project_id else None
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        if pid is not None:
            owns = await conn.fetchval(
                "SELECT 1 FROM projects WHERE id = $1 AND user_id = $2", pid, uid,
            )
            if not owns:
                return False
        res = await conn.execute(
            "UPDATE uploads SET project_id = $3 WHERE id = $1 AND user_id = $2",
            upid, uid, pid,
        )
    return res.endswith("1")


async def list_project_uploads(project_id: str, user_id: str) -> list[dict]:
    """Knowledge files of a project: [{id, filename}] — used to scope RAG."""
    try:
        pid = uuid.UUID(project_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, filename FROM uploads "
            "WHERE project_id = $1 AND user_id = $2 AND text_excerpt IS NOT NULL "
            "ORDER BY created_at DESC LIMIT 100",
            pid, uid,
        )
    return [{"id": str(r["id"]), "filename": r["filename"]} for r in rows]


async def delete_upload(upload_id: str, user_id: str) -> Optional[dict]:
    """Delete from DB. Returns the deleted row's metadata for callers that
    need to clean up the bytes on disk + qdrant chunks."""
    try:
        u = uuid.UUID(upload_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "DELETE FROM uploads WHERE id = $1 AND user_id = $2 "
            "RETURNING id, kind, filename, mime, size_bytes, storage_path",
            u, uid,
        )
    if not row:
        return None
    return {
        "id": str(row["id"]),
        "kind": row["kind"],
        "filename": row["filename"],
        "mime": row["mime"],
        "size_bytes": row["size_bytes"],
        "storage_path": row["storage_path"],
    }


async def get_uploads(upload_ids: list[str], user_id: str) -> list[dict]:
    """Fetch many uploads owned by user_id, preserving the order in upload_ids."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    valid = []
    order: dict[str, int] = {}
    for i, x in enumerate(upload_ids):
        try:
            valid.append(uuid.UUID(x))
            order[x] = i
        except (ValueError, TypeError):
            continue
    if not valid:
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, kind, filename, mime, size_bytes, text_excerpt, storage_path, created_at "
            "FROM uploads WHERE id = ANY($1::uuid[]) AND user_id = $2",
            valid, uid,
        )
    out = []
    for r in rows:
        out.append({
            "id": str(r["id"]),
            "kind": r["kind"],
            "filename": r["filename"],
            "mime": r["mime"],
            "size_bytes": r["size_bytes"],
            "text_excerpt": r["text_excerpt"],
            "storage_path": r["storage_path"],
            "created_at": r["created_at"].isoformat(),
        })
    out.sort(key=lambda x: order.get(x["id"], 0))
    return out


async def delete_last_assistant_message(chat_id: str, user_id: str) -> Optional[int]:
    """Remove the most recent assistant message in the chat (user-scoped).
    Returns its id, or None if no assistant message exists or the user
    doesn't own the chat."""
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        # Make sure user owns the chat
        owned = await conn.fetchval(
            "SELECT 1 FROM chats WHERE id = $1 AND user_id = $2",
            cid, uid,
        )
        if not owned:
            return None
        row = await conn.fetchrow(
            "DELETE FROM messages "
            "WHERE id = (SELECT id FROM messages "
            "            WHERE chat_id = $1 AND role = 'assistant' "
            "            ORDER BY id DESC LIMIT 1) "
            "RETURNING id",
            cid,
        )
    return int(row["id"]) if row else None


async def delete_messages_from(chat_id: str, user_id: str, message_id: int) -> int:
    """Delete the given message + every message in the chat with a higher id.
    User-scoped. Returns the number of rows deleted.

    Also invalidates the summary cache when the truncation point is at or
    before the cached cutoff — otherwise summary_up_to_idx would point past
    the surviving message count.
    """
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return 0
    async with _pool_or_raise().acquire() as conn:
        owned = await conn.fetchval(
            "SELECT 1 FROM chats WHERE id = $1 AND user_id = $2",
            cid, uid,
        )
        if not owned:
            return 0
        # Position (1-based ordinal) of the truncation point inside the chat.
        # If <= summary_up_to_idx the cache is stale and must be cleared.
        cutoff_pos = await conn.fetchval(
            "SELECT COUNT(*) FROM messages WHERE chat_id = $1 AND id <= $2",
            cid, int(message_id),
        )
        result = await conn.execute(
            "DELETE FROM messages WHERE chat_id = $1 AND id >= $2",
            cid, int(message_id),
        )
        await conn.execute(
            "UPDATE chats SET summary_text = NULL, summary_up_to_idx = 0 "
            "WHERE id = $1 AND summary_up_to_idx >= $2",
            cid, int(cutoff_pos or 0),
        )
    try:
        return int(result.split(" ")[-1])
    except (ValueError, IndexError):
        return 0


async def first_user_message(chat_id: str) -> Optional[str]:
    """Returns the content of the first user message in a chat, or None."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "SELECT content FROM messages "
            "WHERE chat_id = $1 AND role = 'user' ORDER BY id ASC LIMIT 1",
            cid,
        )
    return row["content"] if row else None


# ─── summary cache (incremental auto-summarization) ─────────────────────────

async def get_chat_summary(chat_id: str) -> tuple[Optional[str], int]:
    """Return (summary_text, summary_up_to_idx) for a chat. (None, 0) when
    nothing has been cached yet."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return None, 0
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "SELECT summary_text, summary_up_to_idx FROM chats WHERE id = $1",
            cid,
        )
    if row is None:
        return None, 0
    return row["summary_text"], int(row["summary_up_to_idx"] or 0)


async def set_chat_summary(chat_id: str, summary_text: str, up_to_idx: int) -> None:
    """Persist a new summary cache for the chat."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE chats SET summary_text = $2, summary_up_to_idx = $3 WHERE id = $1",
            cid, summary_text, int(up_to_idx),
        )


async def clear_chat_summary(chat_id: str) -> None:
    """Drop the cached summary — call when message history is mutated in a
    way that would make the cache stale (truncation inside the summarized
    range, message edit before the cutoff, etc.)."""
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE chats SET summary_text = NULL, summary_up_to_idx = 0 WHERE id = $1",
            cid,
        )


# ─── providers (Round A — provider abstraction) ─────────────────────────────

from . import crypto as _crypto


def _row_to_provider(r: asyncpg.Record) -> dict:
    models = r["models"]
    if isinstance(models, str):
        try:
            models = json.loads(models)
        except json.JSONDecodeError:
            models = []
    return {
        "id": str(r["id"]),
        "name": r["name"],
        "base_url": r["base_url"],
        # Transparently decrypts S5-encrypted values; pre-S5 plaintext rows
        # pass through unchanged so legacy data keeps working.
        "api_key": _crypto.decrypt(r["api_key"] or ""),
        "kind": r["kind"],
        "models": models or [],
        "created_at": r["created_at"].isoformat(),
        "updated_at": r["updated_at"].isoformat(),
    }


async def list_providers(user_id: str, include_builtin: bool = False) -> list[dict]:
    """User-added providers by default. include_builtin=True returns those
    plus the builtin (cf, anthropic) rows so the credentials UI can show
    "everything in one place"."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    where = "WHERE user_id = $1"
    if not include_builtin:
        where += " AND is_builtin = false"
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, base_url, api_key, kind, models, "
            "       is_builtin, builtin_id, created_at, updated_at "
            f"FROM providers {where} ORDER BY name ASC",
            uid,
        )
    return [_row_to_provider(r) for r in rows]


async def get_provider(provider_id: str, user_id: Optional[str] = None) -> Optional[dict]:
    try:
        pid = uuid.UUID(provider_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        if user_id is not None:
            try:
                uid = uuid.UUID(user_id)
            except (ValueError, TypeError):
                return None
            row = await conn.fetchrow(
                "SELECT id, name, base_url, api_key, kind, models, created_at, updated_at "
                "FROM providers WHERE id = $1 AND user_id = $2",
                pid, uid,
            )
        else:
            row = await conn.fetchrow(
                "SELECT id, name, base_url, api_key, kind, models, created_at, updated_at "
                "FROM providers WHERE id = $1",
                pid,
            )
    return _row_to_provider(row) if row else None


async def create_provider(
    user_id: str, name: str, base_url: str, api_key: str,
    kind: str = "openai-compat", models: Optional[list[dict]] = None,
) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    pid = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO providers (id, user_id, name, base_url, api_key, kind, models) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb) "
            "RETURNING id, name, base_url, api_key, kind, models, created_at, updated_at",
            pid, uid, name, base_url, _crypto.encrypt(api_key), kind, json.dumps(models or []),
        )
    return _row_to_provider(row) if row else None


async def get_builtin_provider(user_id: str, builtin_id: str) -> Optional[dict]:
    """Lookup a user's builtin provider row (e.g. their CF or Anthropic
    credential override). None if they haven't set one — caller falls back
    to env."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "SELECT id, name, base_url, api_key, kind, models, "
            "       is_builtin, builtin_id, created_at, updated_at "
            "FROM providers WHERE user_id = $1 AND builtin_id = $2 AND is_builtin = true",
            uid, builtin_id,
        )
    return _row_to_provider(row) if row else None


async def upsert_builtin_provider(
    user_id: str, builtin_id: str,
    *, name: str, base_url: str, api_key: str, kind: str,
) -> Optional[dict]:
    """Create or update the user's builtin-credential row. Used by
    /me/credentials when the user pastes/rotates a key. api_key is encrypted
    at the storage boundary by `_crypto.encrypt`."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    pid = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO providers (id, user_id, name, base_url, api_key, kind, "
            "                        models, is_builtin, builtin_id) "
            "VALUES ($1, $2, $3, $4, $5, $6, '[]'::jsonb, true, $7) "
            "ON CONFLICT (user_id, builtin_id) WHERE is_builtin "
            "DO UPDATE SET name=$3, base_url=$4, api_key=$5, kind=$6, updated_at=now() "
            "RETURNING id, name, base_url, api_key, kind, models, "
            "          is_builtin, builtin_id, created_at, updated_at",
            pid, uid, name, base_url, _crypto.encrypt(api_key), kind, builtin_id,
        )
    return _row_to_provider(row) if row else None


async def delete_builtin_provider(user_id: str, builtin_id: str) -> bool:
    """Clear the user's builtin credential. Returns True if a row was
    removed (so the caller knows whether to fall back to env)."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "DELETE FROM providers WHERE user_id = $1 AND builtin_id = $2 "
            "AND is_builtin = true RETURNING id",
            uid, builtin_id,
        )
    return row is not None


async def update_provider(
    provider_id: str, user_id: str,
    patch: dict,
) -> Optional[dict]:
    """Update one or more of: name, base_url, api_key, models. Empty/None
    values for a field skip that field (so callers can patch one column at
    a time without clearing others)."""
    try:
        pid = uuid.UUID(provider_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    sets, args = [], []
    i = 1
    for key in ("name", "base_url", "api_key"):
        if key in patch and patch[key] is not None:
            sets.append(f"{key} = ${i}")
            # Encrypt api_key at the storage boundary; other columns pass through.
            v = patch[key]
            if key == "api_key":
                v = _crypto.encrypt(v)
            args.append(v)
            i += 1
    if "models" in patch and patch["models"] is not None:
        sets.append(f"models = ${i}::jsonb")
        args.append(json.dumps(patch["models"]))
        i += 1
    if not sets:
        return await get_provider(provider_id, user_id)
    sets.append("updated_at = now()")
    args.extend([pid, uid])
    sql = (
        f"UPDATE providers SET {', '.join(sets)} "
        f"WHERE id = ${i} AND user_id = ${i+1} "
        "RETURNING id, name, base_url, api_key, kind, models, created_at, updated_at"
    )
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(sql, *args)
    return _row_to_provider(row) if row else None


async def delete_provider(provider_id: str, user_id: str) -> bool:
    try:
        pid = uuid.UUID(provider_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM providers WHERE id = $1 AND user_id = $2",
            pid, uid,
        )
    return result.endswith(" 1")


# ─── secret vault ───────────────────────────────────────────────────────────
# Encrypted, per-user named secrets. The plaintext value NEVER leaves the
# server except injected into the sandbox shell env at exec time. The API/UI
# only ever see names + timestamps.

async def list_secret_names(user_id: str) -> list[dict]:
    """Names + timestamps only — never the value."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT name, created_at, updated_at FROM user_secrets "
            "WHERE user_id = $1 ORDER BY name",
            uid,
        )
    return [
        {"name": r["name"],
         "created_at": r["created_at"].isoformat(),
         "updated_at": r["updated_at"].isoformat()}
        for r in rows
    ]


async def set_secret(user_id: str, name: str, value: str) -> bool:
    """Upsert an encrypted secret. Value is encrypted at the storage boundary."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    enc = _crypto.encrypt(value)
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "INSERT INTO user_secrets (user_id, name, value) VALUES ($1, $2, $3) "
            "ON CONFLICT (user_id, name) DO UPDATE SET value = EXCLUDED.value, "
            "updated_at = now()",
            uid, name, enc,
        )
    return True


async def delete_secret(user_id: str, name: str) -> bool:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        result = await conn.execute(
            "DELETE FROM user_secrets WHERE user_id = $1 AND name = $2", uid, name,
        )
    return result.endswith(" 1")


async def get_secrets_decrypted(user_id: str) -> dict:
    """SERVER-SIDE ONLY: {name: plaintext} for env injection. Never serialize
    this to a client response."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return {}
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT name, value FROM user_secrets WHERE user_id = $1", uid,
        )
    out: dict = {}
    for r in rows:
        try:
            out[r["name"]] = _crypto.decrypt(r["value"] or "")
        except Exception:
            continue
    return out


# ─── scheduled prompts ───────────────────────────────────────────────────────

def _row_to_schedule(r: asyncpg.Record) -> dict:
    return {
        "id": str(r["id"]),
        "title": r["title"],
        "prompt": r["prompt"],
        "run_at": r["run_at"],
        "weekdays": int(r["weekdays"]),
        "enabled": bool(r["enabled"]),
        "model": r["model"],
        "last_run_date": r["last_run_date"],
        "created_at": r["created_at"].isoformat(),
    }


_SCHEDULE_COLS = "id, title, prompt, run_at, weekdays, enabled, model, last_run_date, created_at"


async def list_schedules(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            f"SELECT {_SCHEDULE_COLS} FROM scheduled_prompts "
            "WHERE user_id = $1 ORDER BY run_at ASC", uid,
        )
    return [_row_to_schedule(r) for r in rows]


async def create_schedule(
    user_id: str, title: str, prompt: str, run_at: str,
    weekdays: int = 127, model: Optional[str] = None,
) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    sid = uuid.uuid4()
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO scheduled_prompts (id, user_id, title, prompt, run_at, weekdays, model) "
            f"VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING {_SCHEDULE_COLS}",
            sid, uid, title, prompt, run_at, weekdays, model,
        )
    return _row_to_schedule(row) if row else None


async def update_schedule(schedule_id: str, user_id: str, patch: dict) -> Optional[dict]:
    try:
        sid = uuid.UUID(schedule_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    allowed = {"title", "prompt", "run_at", "weekdays", "enabled", "model"}
    sets, vals = [], []
    for k, v in patch.items():
        if k in allowed:
            vals.append(v)
            sets.append(f"{k} = ${len(vals)}")
    if not sets:
        return None
    vals.extend([sid, uid])
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            f"UPDATE scheduled_prompts SET {', '.join(sets)} "
            f"WHERE id = ${len(vals)-1} AND user_id = ${len(vals)} "
            f"RETURNING {_SCHEDULE_COLS}",
            *vals,
        )
    return _row_to_schedule(row) if row else None


async def delete_schedule(schedule_id: str, user_id: str) -> bool:
    try:
        sid = uuid.UUID(schedule_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        res = await conn.execute(
            "DELETE FROM scheduled_prompts WHERE id = $1 AND user_id = $2", sid, uid,
        )
    return res.endswith("1")


async def due_schedules(weekday_bit: int, hhmm: str, today: str) -> list[dict]:
    """Enabled schedules whose run_at ≤ now (same day) and that haven't fired
    today. The scheduler loop calls this once a minute."""
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            f"SELECT user_id, {_SCHEDULE_COLS} FROM scheduled_prompts "
            "WHERE enabled AND (weekdays & $1) != 0 AND run_at <= $2 "
            "AND (last_run_date IS NULL OR last_run_date != $3)",
            weekday_bit, hhmm, today,
        )
    out = []
    for r in rows:
        d = _row_to_schedule(r)
        d["user_id"] = str(r["user_id"])
        out.append(d)
    return out


async def mark_schedule_ran(schedule_id: str, today: str) -> None:
    try:
        sid = uuid.UUID(schedule_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "UPDATE scheduled_prompts SET last_run_date = $2 WHERE id = $1",
            sid, today,
        )


async def update_user_artifact(user_id: str, artifact_id: int, patch: dict) -> Optional[dict]:
    """Update an artifact's content/title, ownership-checked via the chat.
    Canvas-style iterate-in-place editing (panel save or update_artifact tool)."""
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    allowed = {"content", "title"}
    sets, vals = [], []
    for k, v in patch.items():
        if k in allowed:
            vals.append(v)
            sets.append(f"{k} = ${len(vals)}")
    if not sets:
        return None
    vals.extend([artifact_id, uid])
    async with _pool_or_raise().acquire() as conn:
        r = await conn.fetchrow(
            f"UPDATE artifacts a SET {', '.join(sets)} "
            f"FROM chats c WHERE c.id = a.chat_id "
            f"AND a.id = ${len(vals)-1} AND c.user_id = ${len(vals)} "
            "RETURNING a.id, a.chat_id, a.message_id, a.kind, a.title, a.url, "
            "          a.content, a.language, a.created_at",
            *vals,
        )
    if not r:
        return None
    return {
        "id": r["id"],
        "chat_id": str(r["chat_id"]),
        "message_id": r["message_id"],
        "kind": r["kind"],
        "title": r["title"],
        "url": r["url"],
        "content": r["content"],
        "language": r["language"],
        "created_at": r["created_at"].isoformat(),
    }


# ─── code-mode checkpoints ───────────────────────────────────────────────────

async def add_checkpoint(chat_id: str, project_root: str, head_sha: str,
                         stash_sha: Optional[str]) -> None:
    try:
        cid = uuid.UUID(chat_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "INSERT INTO code_checkpoints (chat_id, project_root, head_sha, stash_sha) "
            "VALUES ($1, $2, $3, $4)",
            cid, project_root, head_sha, stash_sha,
        )
        # Retention: keep the last 30 per chat.
        await conn.execute(
            "DELETE FROM code_checkpoints WHERE chat_id = $1 AND id NOT IN ("
            "  SELECT id FROM code_checkpoints WHERE chat_id = $1 ORDER BY id DESC LIMIT 30)",
            cid,
        )


async def list_checkpoints(chat_id: str, user_id: str) -> list[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT cc.id, cc.project_root, cc.head_sha, cc.stash_sha, cc.created_at "
            "FROM code_checkpoints cc JOIN chats c ON c.id = cc.chat_id "
            "WHERE cc.chat_id = $1 AND c.user_id = $2 ORDER BY cc.id DESC",
            cid, uid,
        )
    return [{
        "id": r["id"],
        "project_root": r["project_root"],
        "head_sha": r["head_sha"],
        "stash_sha": r["stash_sha"],
        "created_at": r["created_at"].isoformat(),
    } for r in rows]


async def get_checkpoint(checkpoint_id: int, chat_id: str, user_id: str) -> Optional[dict]:
    try:
        cid = uuid.UUID(chat_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    async with _pool_or_raise().acquire() as conn:
        r = await conn.fetchrow(
            "SELECT cc.id, cc.project_root, cc.head_sha, cc.stash_sha "
            "FROM code_checkpoints cc JOIN chats c ON c.id = cc.chat_id "
            "WHERE cc.id = $1 AND cc.chat_id = $2 AND c.user_id = $3",
            checkpoint_id, cid, uid,
        )
    if not r:
        return None
    return {"id": r["id"], "project_root": r["project_root"],
            "head_sha": r["head_sha"], "stash_sha": r["stash_sha"]}


# ─── MCP servers ─────────────────────────────────────────────────────────────

def _row_to_mcp(r: asyncpg.Record) -> dict:
    return {
        "id": str(r["id"]),
        "name": r["name"],
        "url": r["url"],
        # Decrypted for internal use; endpoints must redact before returning.
        "auth_header": _crypto.decrypt(r["auth_header"]) if r["auth_header"] else None,
        "enabled": bool(r["enabled"]),
        "created_at": r["created_at"].isoformat(),
    }


async def list_mcp_servers(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, name, url, auth_header, enabled, created_at "
            "FROM mcp_servers WHERE user_id = $1 ORDER BY created_at",
            uid,
        )
    return [_row_to_mcp(r) for r in rows]


async def create_mcp_server(user_id: str, name: str, url: str,
                            auth_header: Optional[str]) -> Optional[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    sid = uuid.uuid4()
    enc = _crypto.encrypt(auth_header) if auth_header else None
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO mcp_servers (id, user_id, name, url, auth_header) "
            "VALUES ($1, $2, $3, $4, $5) "
            "RETURNING id, name, url, auth_header, enabled, created_at",
            sid, uid, name, url, enc,
        )
    return _row_to_mcp(row) if row else None


async def update_mcp_server(server_id: str, user_id: str, patch: dict) -> Optional[dict]:
    try:
        sid = uuid.UUID(server_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return None
    allowed = {"name", "url", "auth_header", "enabled"}
    sets, vals = [], []
    for k, v in patch.items():
        if k not in allowed:
            continue
        if k == "auth_header" and v:
            v = _crypto.encrypt(v)
        vals.append(v)
        sets.append(f"{k} = ${len(vals)}")
    if not sets:
        return None
    vals.extend([sid, uid])
    async with _pool_or_raise().acquire() as conn:
        row = await conn.fetchrow(
            f"UPDATE mcp_servers SET {', '.join(sets)} "
            f"WHERE id = ${len(vals)-1} AND user_id = ${len(vals)} "
            "RETURNING id, name, url, auth_header, enabled, created_at",
            *vals,
        )
    return _row_to_mcp(row) if row else None


async def delete_mcp_server(server_id: str, user_id: str) -> bool:
    try:
        sid = uuid.UUID(server_id)
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return False
    async with _pool_or_raise().acquire() as conn:
        res = await conn.execute(
            "DELETE FROM mcp_servers WHERE id = $1 AND user_id = $2", sid, uid,
        )
    return res.endswith("1")


# ─── in-app notifications ────────────────────────────────────────────────────

async def add_app_notification(user_id: str, title: str, body: str = "",
                               click_path: Optional[str] = None) -> None:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return
    async with _pool_or_raise().acquire() as conn:
        await conn.execute(
            "INSERT INTO app_notifications (user_id, title, body, click_path) "
            "VALUES ($1, $2, $3, $4)",
            uid, title[:200], (body or "")[:2000], click_path,
        )
        # Retention: last 100 per user.
        await conn.execute(
            "DELETE FROM app_notifications WHERE user_id = $1 AND id NOT IN ("
            "  SELECT id FROM app_notifications WHERE user_id = $1 ORDER BY id DESC LIMIT 100)",
            uid,
        )


async def list_unseen_notifications(user_id: str) -> list[dict]:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return []
    async with _pool_or_raise().acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, title, body, click_path, created_at "
            "FROM app_notifications WHERE user_id = $1 AND NOT seen "
            "ORDER BY id ASC LIMIT 25",
            uid,
        )
    return [{
        "id": r["id"],
        "title": r["title"],
        "body": r["body"],
        "click_path": r["click_path"],
        "created_at": r["created_at"].isoformat(),
    } for r in rows]


async def ack_notifications(user_id: str, up_to_id: int) -> int:
    try:
        uid = uuid.UUID(user_id)
    except (ValueError, TypeError):
        return 0
    async with _pool_or_raise().acquire() as conn:
        res = await conn.execute(
            "UPDATE app_notifications SET seen = TRUE "
            "WHERE user_id = $1 AND id <= $2 AND NOT seen",
            uid, int(up_to_id),
        )
    try:
        return int(res.split(" ")[-1])
    except (ValueError, IndexError):
        return 0
