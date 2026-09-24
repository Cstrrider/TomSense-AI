-- 0007 — the long tail of the stable → edge migration (EDGE-NATIVE-MIGRATION §§3,4b,6,8).
--
-- Everything here is USER-level configuration and server-owned state, reached
-- through REST rather than the local-first sync protocol. The sync tables are
-- for what a phone creates offline (chats, messages); personas, schedules and
-- MCP servers are edited rarely, from Settings, always online — putting them
-- through lamport/LWW would buy conflict handling nobody needs.

-- Personas: a named system prompt. The active one is users.prefs.persona_id.
CREATE TABLE IF NOT EXISTS personas (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  prompt      TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_personas_user ON personas(user_id);

-- Per-user secrets: third-party keys a tool needs (Google Vision, AudD…).
-- Encrypted with KEY_ENC_SECRET exactly like provider keys; never readable back.
CREATE TABLE IF NOT EXISTS secrets (
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  value_enc   TEXT NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, name)
);

-- Scheduled prompts. next_run_at is precomputed so the cron tick is one
-- indexed range scan, not a parse of every schedule every 15 minutes.
CREATE TABLE IF NOT EXISTS schedules (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title        TEXT NOT NULL,
  prompt       TEXT NOT NULL,
  kind         TEXT NOT NULL,             -- daily | weekly | once
  time_local   TEXT NOT NULL DEFAULT '08:00',  -- HH:MM in tz
  days         TEXT NOT NULL DEFAULT '',  -- weekly: "1,3,5" (1 = Monday)
  run_at       INTEGER,                   -- once: epoch ms
  tz           TEXT NOT NULL DEFAULT 'UTC',
  conv_id      TEXT,                      -- results accumulate in one chat
  enabled      INTEGER NOT NULL DEFAULT 1,
  next_run_at  INTEGER,
  last_run_at  INTEGER,
  last_error   TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sched_due ON schedules(enabled, next_run_at);
CREATE INDEX IF NOT EXISTS idx_sched_user ON schedules(user_id);

-- Things to tell the user when the app is not open: a schedule ran, etc.
-- Polled by the app (WorkManager) — see notifications in features.ts for why
-- polling rather than FCM.
CREATE TABLE IF NOT EXISTS notifications (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title       TEXT NOT NULL,
  body        TEXT NOT NULL DEFAULT '',
  conv_id     TEXT,
  created_at  INTEGER NOT NULL,
  delivered   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id, delivered, created_at);

-- Remote MCP servers this user has connected (Streamable HTTP transport).
CREATE TABLE IF NOT EXISTS mcp_servers (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  url         TEXT NOT NULL,
  auth_enc    TEXT NOT NULL DEFAULT '',  -- optional bearer token, encrypted
  enabled     INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mcp_user ON mcp_servers(user_id);

-- Artifacts: a document or program the model writes and then revises in
-- place, instead of re-printing a whole file inside every reply.
CREATE TABLE IF NOT EXISTS artifacts (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  conv_id     TEXT,
  title       TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'markdown', -- markdown | code | html | text
  language    TEXT,
  content     TEXT NOT NULL,
  version     INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_artifacts_conv ON artifacts(user_id, conv_id);

-- Uploaded documents for search_docs. Text lives in doc_chunks; vectors in
-- Vectorize (id = chunk id). status: indexing | ready | error.
CREATE TABLE IF NOT EXISTS documents (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  mime        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  r2_key      TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'indexing',
  chunks      INTEGER NOT NULL DEFAULT 0,
  error       TEXT,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_docs_user ON documents(user_id);

CREATE TABLE IF NOT EXISTS doc_chunks (
  id          TEXT PRIMARY KEY,
  doc_id      TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  user_id     TEXT NOT NULL,
  ord         INTEGER NOT NULL,
  text        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON doc_chunks(doc_id, ord);

-- Keyword fallback for search when Vectorize is unavailable, and a second
-- signal alongside it: exact names and numbers are what embeddings miss.
CREATE VIRTUAL TABLE IF NOT EXISTS doc_chunks_fts USING fts5(
  text, chunk_id UNINDEXED, user_id UNINDEXED, tokenize = 'porter'
);
