-- TomSense edge-native — D1 schema (M1)
--
-- D1 is a SYNC TARGET, not a master. The device's local SQLite is the source
-- of truth. Every syncable row therefore carries the convergence triple:
--
--   lamport    monotonic logical counter, per device
--   device_id  tie-breaker for equal lamport values
--   seq        server-assigned monotonic sequence, the PULL cursor
--
-- `seq` is deliberately NOT wall-clock: device clocks are untrusted and a
-- cursor that can move backwards silently drops rows.

-- ─── server sequence ────────────────────────────────────────────────────────
-- D1 is single-threaded per database, so a counter row is safe here without
-- any locking ceremony. One row, id = 0.
CREATE TABLE IF NOT EXISTS seq_counter (
  id      INTEGER PRIMARY KEY CHECK (id = 0),
  next    INTEGER NOT NULL
);
INSERT OR IGNORE INTO seq_counter (id, next) VALUES (0, 1);

-- ─── identity ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  -- UNIQUE is load-bearing: getOrCreateUser relies on ON CONFLICT(email) to
  -- make concurrent first-sight requests idempotent instead of duplicating.
  email         TEXT NOT NULL UNIQUE,
  created_at    INTEGER NOT NULL,
  -- Layer 1 of memory (§9): small, user-editable, always in context.
  profile       TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS devices (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  platform      TEXT NOT NULL,              -- android | desktop | web
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  -- Highest lamport this device has pushed; lets the server reject replays.
  last_lamport  INTEGER NOT NULL DEFAULT 0,
  -- Push target for detached-run completion, NULL until registered.
  push_token    TEXT
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- Native clients exchange one CF Access login for a long-lived device token.
-- Only the SHA-256 is stored: a D1 dump then yields no usable credentials.
CREATE TABLE IF NOT EXISTS device_tokens (
  token_sha256  TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER,
  revoked_at    INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tok_device ON device_tokens(device_id);
CREATE INDEX IF NOT EXISTS idx_tok_user   ON device_tokens(user_id);

-- ─── conversations ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversations (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project_id    TEXT,
  title         TEXT NOT NULL DEFAULT '',
  model         TEXT NOT NULL DEFAULT '',   -- provider_id::model_id, see §4
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  -- Tombstone rather than DELETE, so removal propagates to other devices.
  deleted       INTEGER NOT NULL DEFAULT 0,
  lamport       INTEGER NOT NULL,
  device_id     TEXT NOT NULL,
  seq           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conv_user_seq ON conversations(user_id, seq);
CREATE INDEX IF NOT EXISTS idx_conv_project  ON conversations(project_id);

CREATE TABLE IF NOT EXISTS messages (
  id            TEXT PRIMARY KEY,
  conv_id       TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id       TEXT NOT NULL,
  role          TEXT NOT NULL,              -- user | assistant | system | tool
  -- Plaintext at the edge by default (§10). When the conversation's project
  -- has E2EE on, this holds ciphertext and `encrypted` is 1 — the Worker then
  -- cannot read it, and server-side automation skips the thread.
  content       TEXT NOT NULL,
  encrypted     INTEGER NOT NULL DEFAULT 0,
  reasoning     TEXT,
  tool_calls    TEXT,                       -- JSON
  attachments   TEXT,                       -- JSON, R2 keys
  usage         TEXT,                       -- JSON {in,out,cache_read,...}
  created_at    INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  lamport       INTEGER NOT NULL,
  device_id     TEXT NOT NULL,
  seq           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_conv     ON messages(conv_id, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_user_seq ON messages(user_id, seq);

CREATE TABLE IF NOT EXISTS projects (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  instructions  TEXT NOT NULL DEFAULT '',
  -- Per-project E2EE toggle — the deliberate fork in §10.
  e2ee          INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  lamport       INTEGER NOT NULL,
  device_id     TEXT NOT NULL,
  seq           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_proj_user_seq ON projects(user_id, seq);

-- ─── providers (BYO keys) ───────────────────────────────────────────────────
-- Ported from the unified providers table on main. API keys are encrypted
-- with a Worker secret before storage; D1 never holds them in the clear.
CREATE TABLE IF NOT EXISTS providers (
  id            TEXT PRIMARY KEY,           -- 'cf' | 'anthropic' | uuid
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL,              -- openai-compat | anthropic | cf
  base_url      TEXT NOT NULL DEFAULT '',
  api_key_enc   TEXT NOT NULL DEFAULT '',
  -- Declared capabilities live here, per §4 — the SAME model id can differ
  -- per provider, which is exactly why capabilities.py resolves declared
  -- data before falling back to heuristics.
  models        TEXT NOT NULL DEFAULT '[]', -- JSON [{id,vision,reasoning,context,tools[]}]
  extra_body    TEXT NOT NULL DEFAULT '{}', -- JSON, OpenRouter routing etc.
  enabled       INTEGER NOT NULL DEFAULT 1,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_prov_user ON providers(user_id);

-- ─── memory (§9 layer 2) ────────────────────────────────────────────────────
-- Layer 3 (semantic search over full history) lives in Vectorize; this table
-- is the extracted-facts layer. `source_msg_id` is what makes a memory write
-- VISIBLE AND EDITABLE — the user can always trace a fact to its origin.
CREATE TABLE IF NOT EXISTS memories (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  text          TEXT NOT NULL,
  source_msg_id TEXT,
  confidence    REAL NOT NULL DEFAULT 1.0,
  -- Decay: last time this fact was actually used in a prompt. Facts that stop
  -- being retrieved age out instead of accumulating forever.
  last_used_at  INTEGER,
  use_count     INTEGER NOT NULL DEFAULT 0,
  pinned        INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  lamport       INTEGER NOT NULL,
  device_id     TEXT NOT NULL,
  seq           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mem_user_seq ON memories(user_id, seq);

-- ─── detached runs ──────────────────────────────────────────────────────────
-- The run itself lives in a DetachedRun DO; this is the durable index so a
-- run is reconnectable by run_id after the DO evicts.
CREATE TABLE IF NOT EXISTS runs (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  conv_id       TEXT NOT NULL,
  status        TEXT NOT NULL,              -- running | done | error | cancelled
  model         TEXT NOT NULL DEFAULT '',
  started_at    INTEGER NOT NULL,
  ended_at      INTEGER,
  error         TEXT
);
CREATE INDEX IF NOT EXISTS idx_runs_user ON runs(user_id, started_at);

-- ─── usage ──────────────────────────────────────────────────────────────────
-- Carries over the prompt-cache instrumentation from main: cache_read is a
-- first-class column, not buried in a JSON blob, because it is the number
-- that tells you whether caching is actually working.
CREATE TABLE IF NOT EXISTS usage_daily (
  user_id       TEXT NOT NULL,
  day           TEXT NOT NULL,              -- YYYY-MM-DD UTC
  provider_id   TEXT NOT NULL,
  model_id      TEXT NOT NULL,
  tokens_in     INTEGER NOT NULL DEFAULT 0,
  tokens_out    INTEGER NOT NULL DEFAULT 0,
  cache_read    INTEGER NOT NULL DEFAULT 0,
  cache_write   INTEGER NOT NULL DEFAULT 0,
  requests      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day, provider_id, model_id)
);
