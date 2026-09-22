/**
 * Per-user routing preferences.
 *
 * Stable keeps these in a `tool_models` map plus a couple of toggles, and
 * almost every routing layer reads one. Porting the router without porting
 * this would reduce it to a server-wide policy — which is exactly what stable
 * moved away from.
 */

import type { Env, Principal } from "./types";
import { encryptKey } from "./crypto";

/**
 * Model slots. Each is a full `provider::model` string, so a slot can point at
 * ANY configured provider rather than being limited to Cloudflare.
 */
export interface ToolModels {
  /** Owns image turns outright — see routing.ts for why it beats capability. */
  vision?: string;
  /** Think mode. */
  research?: string;
  /** Utility tier: titles, follow-ups, the auto-route classifier. */
  title?: string;
  /** Text-to-image model used by generate_image. */
  image?: string;
  /** Used when the primary stalls, and as the budget-mode target. */
  chat_fallback?: string;
  vision_fallback?: string;
  title_fallback?: string;
}

export interface UserPrefs {
  tool_models: ToolModels;
  /** aura-2 speaker name. Empty means the device TTS is used instead. */
  tts_voice: string;
  /** Difficulty routing. Defaults ON, matching stable. */
  auto_route: boolean;
}

// Empty tts_voice on purpose: speech defaults to the DEVICE engine, which
// costs nothing and works offline. aura-2 is opt-in.
const DEFAULTS: UserPrefs = { tool_models: {}, auto_route: true, tts_voice: "" };

export async function getPrefs(env: Env, userId: string): Promise<UserPrefs> {
  const row = await env.DB.prepare(`SELECT prefs FROM users WHERE id = ?`)
    .bind(userId)
    .first<{ prefs: string }>();

  // A malformed blob must not break routing for every future turn, so it
  // degrades to defaults rather than throwing.
  let parsed: Partial<UserPrefs> = {};
  try {
    parsed = JSON.parse(row?.prefs || "{}") as Partial<UserPrefs>;
  } catch {
    parsed = {};
  }

  return {
    tool_models: parsed.tool_models ?? {},
    auto_route: parsed.auto_route ?? DEFAULTS.auto_route,
    tts_voice: parsed.tts_voice ?? DEFAULTS.tts_voice,
  };
}

/**
 * Merge a partial update.
 *
 * Slots merge key by key so a client that only knows about `vision` cannot
 * wipe `research` by omitting it — the same reasoning that makes an omitted
 * `apiKey` mean "leave it alone" in updateProvider. Setting a slot to an
 * empty string clears it.
 */
export async function setPrefs(
  env: Env,
  who: Principal,
  patch: { tool_models?: ToolModels; auto_route?: boolean; tts_voice?: string },
): Promise<UserPrefs> {
  const current = await getPrefs(env, who.userId);

  const tool_models: ToolModels = { ...current.tool_models };
  for (const [slot, value] of Object.entries(patch.tool_models ?? {})) {
    if (typeof value !== "string") continue;
    const v = value.trim();
    if (v) tool_models[slot as keyof ToolModels] = v;
    else delete tool_models[slot as keyof ToolModels];
  }

  const next: UserPrefs = {
    tool_models,
    auto_route: patch.auto_route ?? current.auto_route,
    tts_voice: patch.tts_voice ?? current.tts_voice,
  };

  await env.DB.prepare(`UPDATE users SET prefs = ? WHERE id = ?`)
    .bind(JSON.stringify(next), who.userId)
    .run();

  return next;
}

/**
 * Store the Cloudflare analytics token, or clear it with an empty string.
 *
 * Write-only by design: there is no getter that returns the plaintext, and
 * the prefs API reports only whether one is set. A settings screen that can
 * display a credential is a settings screen that can leak one.
 */
export async function setAnalyticsKey(
  env: Env,
  who: Principal,
  token: string,
): Promise<void> {
  const enc = token.trim() ? await encryptKey(env, token.trim()) : "";
  await env.DB.prepare(`UPDATE users SET cf_analytics_key_enc = ? WHERE id = ?`)
    .bind(enc, who.userId)
    .run();
}

export async function hasAnalyticsKey(env: Env, userId: string): Promise<boolean> {
  const row = await env.DB.prepare(
    `SELECT cf_analytics_key_enc FROM users WHERE id = ?`,
  )
    .bind(userId)
    .first<{ cf_analytics_key_enc: string }>();
  return Boolean(row?.cf_analytics_key_enc);
}
