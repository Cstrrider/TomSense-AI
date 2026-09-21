-- Chat management (migration doc phase D).
--
-- Mirrors the columns added to the client's local schema in 1.sqm. Both sides
-- have to move together: sync copies a fixed column list (SYNCABLE in
-- sync.ts), so a column that exists on only one side is either dropped on push
-- or missing on pull, and in both cases the user's pin or rename just
-- evaporates on the other device.
--
-- `folder` is deliberately NOT ported. Stable carried both a free-text
-- `chats.folder` and `project_id`, and already ran a one-time migration
-- folding every distinct folder into a project (backend/app/db.py:140).
-- Re-importing it here would resurrect a grouping stable had retired.

ALTER TABLE conversations ADD COLUMN system_prompt TEXT;
ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;

-- Public share links (migration doc §3). Nullable and UNIQUE: a conversation
-- is unshared until a token is minted, and a token addresses exactly one
-- conversation.
ALTER TABLE conversations ADD COLUMN share_token TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_conv_share ON conversations(share_token);

-- The list order is (pinned, updated_at) on both sides.
CREATE INDEX IF NOT EXISTS idx_conv_user_pinned
  ON conversations(user_id, pinned DESC, updated_at DESC);
