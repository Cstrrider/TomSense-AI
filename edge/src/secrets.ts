/**
 * Per-user secrets: third-party credentials a tool needs.
 *
 * Stable kept these in a "secret vault" plus per-provider builtin rows. Here
 * it is one table, encrypted at rest with the same key as provider API keys,
 * and — like those — write-only: the API reports which names are SET, never
 * a value. A settings screen that can display a credential can leak one.
 */

import type { Env } from "./types";
import { decryptKey, encryptKey } from "./crypto";

/** Names the app knows about, with what each one unlocks. Others are allowed. */
export const KNOWN_SECRETS: Record<string, string> = {
  GOOGLE_VISION_API_KEY: "Google Cloud Vision — reverse image lookup (1,000 free/month)",
  AUDD_API_KEY: "AudD — identify a song from an audio clip",
};

export async function getSecret(env: Env, userId: string, name: string): Promise<string> {
  const row = await env.DB.prepare(`SELECT value_enc FROM secrets WHERE user_id = ? AND name = ?`)
    .bind(userId, name)
    .first<{ value_enc: string }>();
  return row ? decryptKey(env, row.value_enc) : "";
}

export async function setSecret(env: Env, userId: string, name: string, value: string): Promise<void> {
  const n = name.trim().toUpperCase().replace(/[^A-Z0-9_]/g, "_");
  if (!n) throw new Error("name is required");
  if (!value.trim()) {
    await env.DB.prepare(`DELETE FROM secrets WHERE user_id = ? AND name = ?`).bind(userId, n).run();
    return;
  }
  await env.DB.prepare(
    `INSERT INTO secrets (user_id, name, value_enc, updated_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id, name) DO UPDATE SET value_enc = excluded.value_enc, updated_at = excluded.updated_at`,
  )
    .bind(userId, n, await encryptKey(env, value.trim()), Date.now())
    .run();
}

export async function listSecrets(
  env: Env,
  userId: string,
): Promise<{ name: string; set: boolean; description: string }[]> {
  const { results } = await env.DB.prepare(`SELECT name FROM secrets WHERE user_id = ? ORDER BY name`)
    .bind(userId)
    .all<{ name: string }>();
  const set = new Set((results ?? []).map((r) => r.name));
  const names = [...new Set([...Object.keys(KNOWN_SECRETS), ...set])];
  return names.map((name) => ({ name, set: set.has(name), description: KNOWN_SECRETS[name] ?? "" }));
}
