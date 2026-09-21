-- providers: primary key becomes (id, user_id).
--
-- `id` alone was the primary key, and the built-in Cloudflare provider uses
-- the constant `'cf'` for everyone. So the first account to persist anything
-- about Cloudflare owned that row outright: every other user's insert hit
-- `ON CONFLICT DO NOTHING` and their update matched zero rows, because it is
-- correctly scoped by `user_id`. The result was a settings screen that
-- accepted changes and silently discarded them.
--
-- A composite key is the right shape regardless of the bug: a provider row is
-- only ever meaningful within one account, and every query in the Worker
-- already scopes by `user_id`.
--
-- SQLite cannot alter a primary key, so this is the standard table rebuild.
-- It is safe to drop the old table because NOTHING references `providers` by
-- foreign key — verified. `usage_daily.provider_id` is a plain column, not a
-- reference, and already carries `user_id` in its own composite key.

CREATE TABLE providers_new (
  -- 'cf' or a uuid. Unique per user, not globally.
  id            TEXT NOT NULL,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL,              -- openai-compat | anthropic | cf
  base_url      TEXT NOT NULL DEFAULT '',
  api_key_enc   TEXT NOT NULL DEFAULT '',
  -- Declared capabilities live here, per §4 — the SAME model id can differ
  -- per provider, which is exactly why capabilities resolve declared data
  -- before falling back to heuristics.
  models        TEXT NOT NULL DEFAULT '[]', -- JSON [{id,vision,reasoning,context,tools[]}]
  extra_body    TEXT NOT NULL DEFAULT '{}', -- JSON, OpenRouter routing etc.
  enabled       INTEGER NOT NULL DEFAULT 1,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (id, user_id)
);

-- Columns listed explicitly rather than `SELECT *`: a rebuild that relies on
-- column order is a rebuild that silently shuffles data the first time the
-- two definitions drift.
INSERT INTO providers_new
  (id, user_id, name, kind, base_url, api_key_enc, models, extra_body,
   enabled, created_at, updated_at)
SELECT
   id, user_id, name, kind, base_url, api_key_enc, models, extra_body,
   enabled, created_at, updated_at
FROM providers;

DROP TABLE providers;
ALTER TABLE providers_new RENAME TO providers;

CREATE INDEX IF NOT EXISTS idx_prov_user ON providers(user_id);
