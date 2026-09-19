-- One-time authorization codes for the native login flow (PKCE-style).
--
-- Why this exists rather than redirecting the device token straight back to
-- the app: the redirect target is a custom scheme (`tomsense://`), and
-- Android does not guarantee custom-scheme uniqueness — any installed app can
-- register the same scheme and intercept the redirect. Shipping a long-lived
-- bearer token through that channel would hand it to whichever app wins.
--
-- So the redirect carries only a short-lived CODE. Redeeming it additionally
-- requires the verifier, which never leaves the originating app, so an
-- intercepted code on its own is useless.

CREATE TABLE IF NOT EXISTS auth_codes (
  code              TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- SHA-256 of the app-generated verifier, base64url. Stored hashed so a
  -- database read cannot be replayed into a token.
  challenge         TEXT NOT NULL,
  device_name       TEXT NOT NULL DEFAULT '',
  device_platform   TEXT NOT NULL DEFAULT 'android',
  created_at        INTEGER NOT NULL,
  -- Deliberately short. This code lives only for the seconds between the
  -- browser redirect and the app's exchange call.
  expires_at        INTEGER NOT NULL,
  -- Set on redemption; a second attempt with the same code must fail.
  redeemed_at       INTEGER
);

CREATE INDEX IF NOT EXISTS idx_auth_codes_expiry ON auth_codes(expires_at);
