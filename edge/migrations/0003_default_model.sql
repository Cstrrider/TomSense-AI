-- Per-user default model, so the answer to "what model am I talking to?" is a
-- user setting rather than a Worker environment variable.
--
-- Empty string means "fall back to TIER2_MODEL", which keeps existing users
-- working without a data migration.
ALTER TABLE users ADD COLUMN default_model TEXT NOT NULL DEFAULT '';
