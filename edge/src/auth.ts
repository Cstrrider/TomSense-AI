/**
 * Authentication at the edge.
 *
 * ── A SECURITY DIFFERENCE FROM main, STATED LOUDLY ──────────────────────────
 *
 * backend/app/auth.py TRUSTS the `Cf-Access-Authenticated-User-Email` header.
 * That is correct there and ONLY there: the FastAPI app is not exposed to the
 * internet, and the single path in is through cloudflared, which sets the
 * header only after CF Access has validated the user.
 *
 * A Worker has no such property. It sits directly on the public internet, and
 * anyone can send any header they like. Trusting that header here would be a
 * complete authentication bypass — `curl -H 'Cf-Access-Authenticated-User-Email:
 * you@example.com'` would be a full account takeover.
 *
 * So at the edge the Access JWT is VERIFIED: signature checked against the
 * team's JWKS, plus issuer, audience, and expiry. The header is never trusted
 * on its own, and there is no LOCAL_DEV_EMAIL fallback in deployed code.
 *
 * Native clients don't run a browser Access flow per request, so they exchange
 * an Access login once for a long-lived device token (see /auth/device).
 */

import type { Env, Principal } from "./types";

const ACCESS_JWT_HEADER = "cf-access-jwt-assertion";
const ACCESS_EMAIL_HEADER = "cf-access-authenticated-user-email";

/** CF Access team domain and application audience (AUD) tag. */
interface AccessConfig {
  teamDomain: string; // e.g. "cstrrider.cloudflareaccess.com"
  aud: string;
}

interface Jwk {
  kid: string;
  kty: string;
  alg: string;
  use?: string;
  n: string;
  e: string;
}

// JWKS rarely rotates; cache per isolate with a TTL so we aren't fetching on
// every request, but still pick up a rotation within the hour.
const JWKS_TTL_MS = 60 * 60 * 1000;
let jwksCache: { at: number; keys: Map<string, CryptoKey> } | null = null;

function b64urlToBytes(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function loadJwks(cfg: AccessConfig): Promise<Map<string, CryptoKey>> {
  const now = Date.now();
  if (jwksCache && now - jwksCache.at < JWKS_TTL_MS) return jwksCache.keys;

  const res = await fetch(`https://${cfg.teamDomain}/cdn-cgi/access/certs`);
  if (!res.ok) throw new Error(`JWKS fetch failed: ${res.status}`);
  const body = (await res.json()) as { keys: Jwk[] };

  const keys = new Map<string, CryptoKey>();
  for (const jwk of body.keys ?? []) {
    if (jwk.kty !== "RSA") continue;
    const key = await crypto.subtle.importKey(
      "jwk",
      { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    keys.set(jwk.kid, key);
  }
  jwksCache = { at: now, keys };
  return keys;
}

interface AccessClaims {
  email?: string;
  sub?: string;
  aud?: string | string[];
  iss?: string;
  exp?: number;
  nbf?: number;
}

/** Verify an Access JWT. Returns claims, or null if the token is not valid. */
export async function verifyAccessJwt(
  token: string,
  cfg: AccessConfig,
): Promise<AccessClaims | null> {
  const parts = token.split(".");
  const [headB64, payloadB64, sigB64] = parts;
  if (parts.length !== 3 || !headB64 || !payloadB64 || !sigB64) return null;

  let header: { kid?: string; alg?: string };
  let claims: AccessClaims;
  try {
    header = JSON.parse(new TextDecoder().decode(b64urlToBytes(headB64)));
    claims = JSON.parse(new TextDecoder().decode(b64urlToBytes(payloadB64)));
  } catch {
    return null;
  }

  // Pin the algorithm. Accepting whatever `alg` the token names is the classic
  // JWT confusion bug (alg:none, or HS256 verified against the public key).
  if (header.alg !== "RS256" || !header.kid) return null;

  const keys = await loadJwks(cfg);
  const key = keys.get(header.kid);
  if (!key) return null;

  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlToBytes(sigB64),
    new TextEncoder().encode(`${headB64}.${payloadB64}`),
  );
  if (!ok) return null;

  const now = Math.floor(Date.now() / 1000);
  if (typeof claims.exp === "number" && claims.exp < now) return null;
  if (typeof claims.nbf === "number" && claims.nbf > now + 60) return null;
  if (claims.iss !== `https://${cfg.teamDomain}`) return null;

  const auds = Array.isArray(claims.aud) ? claims.aud : claims.aud ? [claims.aud] : [];
  if (!auds.includes(cfg.aud)) return null;

  return claims;
}

/** Constant-time string compare, so token checks don't leak length/prefix. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Resolve the caller.
 *
 * Two accepted credentials:
 *   1. `Authorization: Bearer <device-token>` — native clients. Only the
 *      SHA-256 of the token is stored, so a D1 dump does not yield live
 *      credentials.
 *   2. A verified CF Access JWT — browser client.
 */
export async function authenticate(
  req: Request,
  env: Env,
  /**
   * Null when Access is not configured. Only the JWT path depends on it —
   * device tokens are issued and verified entirely by us, so gating them on
   * Access config would be an unrelated coupling that makes the app
   * un-testable before Access exists.
   */
  cfg: AccessConfig | null,
): Promise<Principal | null> {
  const authz = req.headers.get("authorization") ?? "";
  if (authz.toLowerCase().startsWith("bearer ")) {
    const token = authz.slice(7).trim();
    if (!token) return null;
    const row = await env.DB.prepare(
      `SELECT d.id AS device_id, d.user_id, u.email
         FROM device_tokens t
         JOIN devices d ON d.id = t.device_id
         JOIN users   u ON u.id = d.user_id
        WHERE t.token_sha256 = ? AND (t.revoked_at IS NULL)`,
    )
      .bind(await sha256Hex(token))
      .first<{ device_id: string; user_id: string; email: string }>();
    if (!row) return null;
    return { userId: row.user_id, email: row.email, deviceId: row.device_id };
  }

  // Beyond this point only the Access-JWT path remains. With no Access
  // config there is nothing to verify against, and accepting the request
  // would mean trusting an unverified header — so refuse.
  if (!cfg) return null;

  const jwt = req.headers.get(ACCESS_JWT_HEADER);
  if (!jwt) return null;

  const claims = await verifyAccessJwt(jwt, cfg);
  if (!claims?.email) return null;

  // Only AFTER the signature verifies is the email header worth anything, and
  // even then we use the value from the signed payload, not the header.
  const email = claims.email.trim().toLowerCase();
  const headerEmail = (req.headers.get(ACCESS_EMAIL_HEADER) ?? "").trim().toLowerCase();
  if (headerEmail && headerEmail !== email) return null; // mismatched → reject

  const user = await getOrCreateUser(env, email);
  return { userId: user.id, email, deviceId: "web" };
}

export async function getOrCreateUser(
  env: Env,
  email: string,
): Promise<{ id: string; email: string }> {
  const existing = await env.DB.prepare(`SELECT id, email FROM users WHERE email = ?`)
    .bind(email)
    .first<{ id: string; email: string }>();
  if (existing) return existing;

  const id = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO users (id, email, created_at) VALUES (?, ?, ?)
     ON CONFLICT(email) DO NOTHING`,
  )
    .bind(id, email, Date.now())
    .run();

  const row = await env.DB.prepare(`SELECT id, email FROM users WHERE email = ?`)
    .bind(email)
    .first<{ id: string; email: string }>();
  return row ?? { id, email };
}

/** Mint a device token. Returns the plaintext ONCE; only its hash is stored. */
export async function issueDeviceToken(
  env: Env,
  userId: string,
  deviceId: string,
): Promise<string> {
  const raw = crypto.randomUUID().replace(/-/g, "") + crypto.randomUUID().replace(/-/g, "");
  await env.DB.prepare(
    `INSERT INTO device_tokens (token_sha256, device_id, user_id, created_at)
     VALUES (?, ?, ?, ?)`,
  )
    .bind(await sha256Hex(raw), deviceId, userId, Date.now())
    .run();
  return raw;
}
