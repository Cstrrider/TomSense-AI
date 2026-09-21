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
import { encryptKey, decryptKey } from "./crypto";
import { CF_MODELS } from "./cf_catalog";
import { CF_BUILTIN_ID } from "./providers";
import { isReasoningModel, isVisionModel } from "./capabilities";

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

/** The chat-capable Cloudflare catalogue, as a ModelEntry list. */
function cfCatalogModels(): ModelEntry[] {
  return CF_MODELS.filter((m) => m.roles.includes("chat")).map((m) => ({
    id: m.id,
    vision: m.vision,
    reasoning: m.reasoning,
    context: m.context,
  }));
}

/**
 * The Cloudflare entry.
 *
 * Synthesised rather than stored, so it exists for a brand-new user with no
 * setup at all — the app has to work before you have any keys.
 *
 * Its model list is curatable like any other provider's. An EMPTY stored list
 * means "the whole catalogue", which is what keeps a fresh account working
 * with no configuration; a non-empty one is an explicit selection and is
 * honoured exactly. The consequence worth knowing: removing every Cloudflare
 * model returns you to the full catalogue rather than to none. "None" is what
 * the enabled switch is for, and a provider that silently offers nothing is a
 * worse thing to build than a slightly surprising reset.
 *
 * Capabilities are merged FROM the catalogue by id, so a curated list keeps
 * its vision/reasoning/context data. An id the catalogue does not know —
 * Cloudflare ships new models faster than the generated catalogue is
 * regenerated — is kept with whatever was stored for it, so a new model can be
 * added by hand and still work.
 */
function cloudflareView(row: Row | null): ProviderView {
  const selected = row ? parseJson<ModelEntry[]>(row.models, []) : [];

  const models = selected.length === 0
    ? cfCatalogModels()
    : selected.map((sel) => {
        const known = CF_MODELS.find((m) => m.id === sel.id);
        return known
          ? { id: known.id, vision: known.vision, reasoning: known.reasoning, context: known.context }
          : sel;
      });

  return {
    id: CF_BUILTIN_ID,
    name: "Cloudflare Workers AI",
    kind: "cf",
    baseUrl: "",
    hasKey: false,
    keyless: true,
    models,
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
  // Cloudflare is synthetic, so it gets a narrow path: only `enabled` and
  // `models` mean anything for it. There is no key to rotate and no base URL
  // to point somewhere else, and accepting either would let a request move
  // the built-in provider somewhere it was never meant to go.
  if (id === CF_BUILTIN_ID) {
    const now = Date.now();

    // Materialise the synthetic row on first write. Until something is
    // actually persisted about Cloudflare it exists only as a view, so there
    // may be nothing here to update yet.
    //
    // The conflict target is `(id, user_id)`, matching the composite primary
    // key. It was `(id)`, and since every user's Cloudflare row is literally
    // `'cf'`, that made the row global: the first account to write one owned
    // it, and everyone else's insert did nothing while their update — rightly
    // scoped by user_id — matched no rows. Settings appeared to save and
    // silently did not. See migration 0005.
    await env.DB.prepare(
      `INSERT INTO providers
         (id, user_id, name, kind, base_url, api_key_enc, models, extra_body,
          enabled, created_at, updated_at)
       VALUES (?, ?, 'Cloudflare Workers AI', 'cf', '', '', '[]', '{}', 1, ?, ?)
       ON CONFLICT(id, user_id) DO NOTHING`,
    )
      .bind(CF_BUILTIN_ID, who.userId, now, now)
      .run();

    const cfSets: string[] = [];
    const cfArgs: unknown[] = [];
    if (body.enabled !== undefined) {
      cfSets.push("enabled = ?");
      cfArgs.push(body.enabled ? 1 : 0);
    }
    if (body.models !== undefined) {
      cfSets.push("models = ?");
      cfArgs.push(JSON.stringify(body.models));
    }
    if (!cfSets.length) return { ok: true };

    cfSets.push("updated_at = ?");
    cfArgs.push(now, CF_BUILTIN_ID, who.userId);
    await env.DB.prepare(
      `UPDATE providers SET ${cfSets.join(", ")} WHERE id = ? AND user_id = ?`,
    )
      .bind(...cfArgs)
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
      // Declared capabilities win; otherwise fall back to the heuristics in
      // capabilities.ts. This list used to read the declared value ONLY, so a
      // model with nothing declared was reported as having no capabilities at
      // all — which became visible the moment Cloudflare discovery started
      // returning models the bundled catalogue has no entry for. A reasoning
      // model shown as non-reasoning is worse than an unknown one: it is a
      // confident wrong answer, and the picker is where people decide.
      out.push({
        value: `${p.id}::${m.id}`,
        label: m.id,
        provider: p.name,
        vision: m.vision ?? isVisionModel(m.id),
        reasoning: m.reasoning ?? isReasoningModel(m.id),
        context: m.context ?? null,
      });
    }
  }
  return out;
}

/** Is this provider currently usable — enabled, and keyed unless keyless? */
async function usableProviderIds(env: Env, who: Principal): Promise<Set<string>> {
  const list = await listProviders(env, who);
  return new Set(
    list.filter((p) => p.enabled && (p.keyless || p.hasKey)).map((p) => p.id),
  );
}

/**
 * Decide which model a chat request actually runs on.
 *
 * This exists because "Cloudflare is optional" was previously cosmetic: the
 * toggle hid CF from the picker while TIER2_MODEL (a CF model) stayed the
 * hardcoded default and TIER1_MODEL (also CF) stayed the hardcoded stall
 * fallback. Disabling Cloudflare hid it from the UI and kept sending traffic
 * there — the worst kind of setting, one that lies.
 *
 * Order: explicit request → saved default → first usable model → null.
 * A saved default whose provider has since been disabled or had its key
 * removed is ignored rather than attempted.
 */
export async function resolveChatModel(
  env: Env,
  who: Principal,
  requested?: string | null,
): Promise<{ model: string } | { error: string }> {
  const usable = await usableProviderIds(env, who);

  const isUsable = (spec: string): boolean => {
    const sep = spec.indexOf("::");
    const pid = sep === -1 ? CF_BUILTIN_ID : spec.slice(0, sep) || CF_BUILTIN_ID;
    return usable.has(pid);
  };

  // An explicit request from the client is honoured only if it is usable;
  // silently substituting a different model would be worse than failing.
  if (requested) {
    return isUsable(requested)
      ? { model: requested }
      : { error: `model ${requested} is not available (provider disabled or missing key)` };
  }

  const saved = await getDefaultModel(env, who);
  if (saved && isUsable(saved)) return { model: saved };

  const options = await listModels(env, who);
  const first = options[0];
  if (first) return { model: first.value };

  return {
    error:
      "no usable model — enable Cloudflare or add a provider with an API key in settings",
  };
}

/**
 * Stall fallback, but only when it is legitimately available.
 *
 * Previously this was TIER1_MODEL unconditionally, i.e. always Cloudflare.
 * Now it is skipped entirely if CF is disabled, and never returns the model
 * that just stalled.
 */
export async function resolveFallbackModel(
  env: Env,
  who: Principal,
  primary: string,
): Promise<string | null> {
  const usable = await usableProviderIds(env, who);

  if (usable.has(CF_BUILTIN_ID) && env.TIER1_MODEL !== primary) {
    return env.TIER1_MODEL;
  }
  const options = await listModels(env, who);
  return options.find((m) => m.value !== primary)?.value ?? null;
}

/**
 * Ask a provider what models it serves, via the OpenAI-shaped /models
 * endpoint. Ported from `providers.discover_models` on stable.
 *
 * Best-effort by design: returns [] on any failure rather than throwing, so a
 * provider without a /models endpoint degrades to manual entry instead of
 * blocking the add-provider form.
 */
export async function discoverModels(
  env: Env,
  who: Principal,
  body: { providerId?: string; baseUrl?: string; apiKey?: string },
): Promise<{ models: string[] }> {
  let baseUrl = "";
  let apiKey = "";
  let kind = "openai-compat";

  // Ask Workers AI what it ACTUALLY serves.
  //
  // This used to return the bundled catalogue, which was wrong in exactly the
  // way discovery exists to fix: `cf_catalog.ts` is generated and goes stale,
  // and it lists 10 models where the platform serves 31. Offering it as
  // "available" answered "what we already knew about" — so models that plainly
  // exist (glm-5.3, kimi-k2.7, gpt-oss-120b, the qwen line) were unreachable
  // and looked like they did not exist.
  //
  // The AI *binding* can list them, so this needs no account API token and no
  // new secret. That matters: the alternative was giving the Worker a token
  // far more powerful than the binding it already has.
  //
  // Everything the platform reports is returned, including LoRA and guard
  // models. Filtering by guesswork would recreate the original problem one
  // level down — the user is asking what is available, and curation is what
  // the checkboxes are for.
  if (body.providerId === CF_BUILTIN_ID) {
    try {
      const listed = await env.AI.models({
        task: "Text Generation",
        per_page: 200,
      });
      const ids = listed
        .map((m) => m.name)
        .filter((n): n is string => typeof n === "string" && n.startsWith("@cf/"));
      if (ids.length) return { models: [...new Set(ids)].sort() };
    } catch {
      // Fall through — a discovery outage should degrade to the catalogue,
      // not leave the user with an empty list and no way to pick anything.
    }
    return { models: cfCatalogModels().map((m) => m.id).sort() };
  }

  if (body.providerId) {
    const row = await env.DB.prepare(
      `SELECT kind, base_url, api_key_enc FROM providers WHERE id = ? AND user_id = ?`,
    )
      .bind(body.providerId, who.userId)
      .first<{ kind: string; base_url: string; api_key_enc: string }>();
    if (!row) return { models: [] };
    kind = row.kind;
    baseUrl = row.base_url;
    // A key supplied with the request means the form is ROTATING the key —
    // discover with the new one, not the stored one, or the user can't
    // validate a replacement before saving it.
    apiKey = body.apiKey || (row.api_key_enc ? await decryptKey(env, row.api_key_enc) : "");
  } else {
    baseUrl = (body.baseUrl ?? "").trim();
    apiKey = body.apiKey ?? "";
  }

  if (!/^https:\/\//i.test(baseUrl)) return { models: [] };

  // Tolerate a base URL that already points at the completions endpoint.
  let base = baseUrl.replace(/\/+$/, "");
  if (base.endsWith("/chat/completions")) {
    base = base.slice(0, -"/chat/completions".length);
  }

  const headers: Record<string, string> = {};
  if (apiKey) {
    if (kind === "anthropic") {
      headers["x-api-key"] = apiKey;
      headers["anthropic-version"] = "2023-06-01";
    } else {
      headers["authorization"] = `Bearer ${apiKey}`;
    }
  }

  try {
    const res = await fetch(`${base}/models`, {
      headers,
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) return { models: [] };

    const data = (await res.json()) as unknown;
    const rows = Array.isArray(data)
      ? data
      : ((data as { data?: unknown[] })?.data ?? []);
    if (!Array.isArray(rows)) return { models: [] };

    const ids = new Set<string>();
    for (const row of rows) {
      const id = typeof row === "string" ? row : (row as { id?: unknown })?.id;
      if (typeof id === "string" && id) ids.add(id);
    }
    return { models: [...ids].sort() };
  } catch {
    return { models: [] };
  }
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
