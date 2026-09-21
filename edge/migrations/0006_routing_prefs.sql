-- Routing preferences: model slots, the auto-route toggle, and the optional
-- analytics key that budget mode needs.
--
-- Ported from stable, where routing is six layers deep (main.py ~3130-3235)
-- and almost every layer reads a per-user setting. The beta had exactly one:
-- `default_model`. Without somewhere to keep the rest, the router can only
-- ever be a server-wide policy, which is the thing stable deliberately moved
-- away from.

-- A JSON blob rather than a column per setting. These are small, read
-- together on every turn, and change shape as routing evolves — stable learned
-- this the same way, via `tool_models`. Shape:
--
--   {
--     "tool_models": {
--       "vision": "cf::@cf/...",            slot owns image turns
--       "research": "cf::@cf/...",          think mode
--       "title": "cf::@cf/...",             utility/task tier
--       "chat_fallback": "...",             used on stall AND budget downshift
--       "vision_fallback": "...",
--       "title_fallback": "..."
--     },
--     "auto_route": true                    EASY/HARD difficulty routing
--   }
--
-- Empty object means "every default applies", so existing users need no
-- backfill and the router behaves exactly as before until something is set.
ALTER TABLE users ADD COLUMN prefs TEXT NOT NULL DEFAULT '{}';

-- Cloudflare account API token, for reading neuron usage.
--
-- Encrypted with the same KEY_ENC_SECRET as provider keys, and never returned
-- by the API — only ever decrypted server-side for the analytics call.
--
-- It lives HERE, as a user setting, rather than as a Worker secret. Running
-- inference needs no token at all (the AI binding bills the account directly);
-- only READING usage does. Making it a per-user setting keeps the Worker
-- holding nothing but its bindings, makes budget mode opt-in, and means a
-- Worker compromise does not hand over an account credential by default.
ALTER TABLE users ADD COLUMN cf_analytics_key_enc TEXT NOT NULL DEFAULT '';

-- The account the analytics query is scoped to. Not a secret — it is an
-- identifier, not a credential, and it is useless without the token above —
-- so unlike the token it is stored in the clear and IS returned by the API,
-- which lets the settings screen show which account budget mode is watching.
ALTER TABLE users ADD COLUMN cf_account_id TEXT NOT NULL DEFAULT '';
