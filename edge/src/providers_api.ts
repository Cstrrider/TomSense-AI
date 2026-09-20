/**
 * Provider CRUD and model listing — BYO keys.
 *
 * The design goal is that Cloudflare stops being privileged. Previously `cf`
 * was hardcoded as the default and every other provider was second-class.
 * Now it is one entry in the same list, and the user picks a default model.
 *
 * Cloudflare keeps ONE genuine difference, and it is a security property
 * rather than favouritism: it needs no API key, because it is reached through
 * the Workers AI binding. Every other provider requires a key, which is
 * encrypted at rest with KEY_ENC_SECRET.
 *
 * INVARIANT: an API key, once stored, is never readable through this API.
 * Responses carry `hasKey: boolean` and nothing more. A settings screen that
 * can display your OpenAI key is a settings screen that can leak it.
 */

import type { Env, Principal, ModelEntry } from "./types";
import { encryptKey } from "./crypto";
import { CF_MODELS } from "./cf_catalog";
import { CF_BUILTIN_ID } from "./providers";

export interface ProviderView {
  id: string;
  name: string;
  kind: string;
  baseUrl: string;
  /** Never the key itself — only whether one is set. */
  hasKey: boolean;
  /** True for Cloudflare, which authenticates via the binding. */
  keyless: boolean;
  models: ModelEntry[];
  enabled: boolean;
  builtin: boolean;
}

interface Row {
  id: string;
  name: string;
  kind: string;
  base_url: string;
  api_key_enc: string;
  models: string;
  extra_body: string;
  enabled: number;
}

function parseJson<T>(s: string, fallback: T): T {
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
}

/** Presets so the user doesn't have to remember base URLs. */
export const PROVIDER_PRESETS = [
  { kind: "openai-compat", name: "OpenAI", baseUrl: "https://api.openai.com/v1" },
  { kind: "anthropic", name: "Anthropic", baseUrl: "https://api.anthropic.com" },
  { kind: "openai-compat", name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1" },
  { kind: "openai-compat", name: "DeepInfra", baseUrl: "https://api.deepinfra.com/v1/openai" },
  { kind: "openai-compat", name: "Groq", baseUrl: "https://api.groq.com/openai/v1" },
] as const;

/**
 * The Cloudflare entry.
 *
 * Synthesised rather than stored, so it exists for a brand-new user with no
 * setup at all — the app has to work before you have any keys. Its `models`
 * come from the bundled catalogue, which is also where their declared
 * capabilities live.
 */
function cloudflareView(row: Row | null): ProviderView {
  return {
    id: CF_BUILTIN_ID,
    name: "Cloudflare Workers AI",
    kind: "cf",
    baseUrl: "",
    hasKey: false,
    keyless: true,
    models: CF_MODELS.filter((m) => m.roles.includes("chat")).map((m) => ({
      id: m.id,
      vision: m.vision,
      reasoning: m.reasoning,
      context: m.context,
    })),
    enabled: row ? Boolean(row.enabled) : true,
    builtin: true,
  };
}

export async function listProviders(env: Env, who: Principal): Promise<ProviderView[]> {
  const res = await env.DB.prepare(
    `SELECT id, name, kind, base_url, api_key_enc, models, extra_body, enabled
       FROM providers WHERE user_id = ? ORDER BY name`,
  )
    .bind(who.userId)
    .all<Row>();

  const rows = res.results ?? [];
  const cfRow = rows.find((r) => r.id === CF_BUILTIN_ID) ?? null;

  const custom = rows
    .filter((r) => r.id !== CF_BUILTIN_ID)
    .map<ProviderView>((r) => ({
      id: r.id,
      name: r.name,
      kind: r.kind,
      baseUrl: r.base_url,
      hasKey: r.api_key_enc.length > 0,
      keyless: false,
      models: parseJson<ModelEntry[]>(r.models, []),
      enabled: Boolean(r.enabled),
      builtin: false,
    }));

  // Cloudflare listed alongside the rest, not above it.
  return [cloudflareView(cfRow), ...custom];
}

export async function createProvider(
  env: Env,
  who: Principal,
  body: {
    name?: string;
    kind?: string;
    baseUrl?: string;
    apiKey?: string;
    models?: ModelEntry[];
    extraBody?: Record<string, unknown>;
  },
): Promise<{ id: string } | { error: string }> {
  const name = (body.name ?? "").trim();
  const kind = body.kind ?? "openai-compat";
  const baseUrl = (body.baseUrl ?? "").trim().replace(/\/+$/, "");

  if (!name) return { error: "name is required" };
  if (kind !== "openai-compat" && kind !== "anthropic") {
    // `cf` is synthesised, not user-created — allowing it would let someone
    // shadow the built-in entry with a row that has a key.
    return { error: "kind must be openai-compat or anthropic" };
  }
  if (!/^https:\/\//i.test(baseUrl)) {
    // Refuse plaintext: this URL receives the API key on every request.
    return { error: "baseUrl must be https" };
  }

  const id = crypto.randomUUID();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO providers
       (id, user_id, name, kind, base_url, api_key_enc, models, extra_body,
        enabled, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
  )
    .bind(
      id,
      who.userId,
      name,
      kind,
      baseUrl,
      body.apiKey ? await encryptKey(env, body.apiKey) : "",
      JSON.stringify(body.models ?? []),
      JSON.stringify(body.extraBody ?? {}),
      now,
      now,
    )
    .run();

  return { id };
}

export async function updateProvider(
  env: Env,
  who: Principal,
  id: string,
  body: {
    name?: string;
    baseUrl?: string;
    apiKey?: string;
    models?: ModelEntry[];
    extraBody?: Record<string, unknown>;
    enabled?: boolean;
  },
): Promise<{ ok: true } | { error: string }> {
  // Cloudflare is synthetic; the only thing that can be persisted about it is
  // whether it is enabled, so it gets its own narrow path.
  if (id === CF_BUILTIN_ID) {
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO providers
         (id, user_id, name, kind, base_url, api_key_enc, models, extra_body,
          enabled, created_at, updated_at)
       VALUES (?, ?, 'Cloudflare Workers AI', 'cf', '', '', '[]', '{}', ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET enabled = excluded.enabled, updated_at = excluded.updated_at`,
    )
      .bind(CF_BUILTIN_ID, who.userId, body.enabled === false ? 0 : 1, now, now)
      .run();
    return { ok: true };
  }

  const existing = await env.DB.prepare(
    `SELECT id FROM providers WHERE id = ? AND user_id = ?`,
  )
    .bind(id, who.userId)
    .first();
  if (!existing) return { error: "not found" };

  const sets: string[] = [];
  const args: unknown[] = [];

  if (body.name !== undefined) {
    sets.push("name = ?");
    args.push(body.name.trim());
  }
  if (body.baseUrl !== undefined) {
    const url = body.baseUrl.trim().replace(/\/+$/, "");
    if (!/^https:\/\//i.test(url)) return { error: "baseUrl must be https" };
    sets.push("base_url = ?");
    args.push(url);
  }
  // An omitted apiKey means "leave it alone"; an empty string means "clear
  // it". Conflating those would silently wipe the key on every settings save.
  if (body.apiKey !== undefined) {
    sets.push("api_key_enc = ?");
    args.push(body.apiKey ? await encryptKey(env, body.apiKey) : "");
  }
  if (body.models !== undefined) {
    sets.push("models = ?");
    args.push(JSON.stringify(body.models));
  }
  if (body.extraBody !== undefined) {
    sets.push("extra_body = ?");
    args.push(JSON.stringify(body.extraBody));
  }
  if (body.enabled !== undefined) {
    sets.push("enabled = ?");
    args.push(body.enabled ? 1 : 0);
  }

  if (!sets.length) return { ok: true };

  sets.push("updated_at = ?");
  args.push(Date.now(), id, who.userId);

  await env.DB.prepare(
    `UPDATE providers SET ${sets.join(", ")} WHERE id = ? AND user_id = ?`,
  )
    .bind(...args)
    .run();

  return { ok: true };
}

export async function deleteProvider(
  env: Env,
  who: Principal,
  id: string,
): Promise<{ ok: true } | { error: string }> {
  if (id === CF_BUILTIN_ID) {
    return { error: "Cloudflare is built in; disable it instead of deleting" };
  }
  await env.DB.prepare(`DELETE FROM providers WHERE id = ? AND user_id = ?`)
    .bind(id, who.userId)
    .run();
  return { ok: true };
}

export interface ModelOption {
  /** `provider_id::model_id` — exactly what /chat accepts. */
  value: string;
  label: string;
  provider: string;
  vision: boolean;
  reasoning: boolean;
  context: number | null;
}

/** Every selectable model across enabled providers, for the client picker. */
export async function listModels(env: Env, who: Principal): Promise<ModelOption[]> {
  const providers = await listProviders(env, who);
  const out: ModelOption[] = [];

  for (const p of providers) {
    if (!p.enabled) continue;
    // A provider with no key and no binding can't serve anything; listing it
    // would just produce options that fail on first use.
    if (!p.keyless && !p.hasKey) continue;

    for (const m of p.models) {
      out.push({
        value: `${p.id}::${m.id}`,
        label: m.id,
        provider: p.name,
        vision: Boolean(m.vision),
        reasoning: Boolean(m.reasoning),
        context: m.context ?? null,
      });
    }
  }
  return out;
}

export async function getDefaultModel(env: Env, who: Principal): Promise<string> {
  const row = await env.DB.prepare(`SELECT default_model FROM users WHERE id = ?`)
    .bind(who.userId)
    .first<{ default_model: string }>();
  return row?.default_model ?? "";
}

export async function setDefaultModel(
  env: Env,
  who: Principal,
  model: string,
): Promise<void> {
  await env.DB.prepare(`UPDATE users SET default_model = ? WHERE id = ?`)
    .bind(model, who.userId)
    .run();
}
