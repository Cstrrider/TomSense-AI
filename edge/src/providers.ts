/**
 * Provider abstraction — port of backend/app/providers.py.
 *
 * Wraps any OpenAI-compatible chat-completions endpoint behind one interface
 * so nothing downstream cares whether the model lives on Cloudflare, OpenAI,
 * OpenRouter, DeepInfra, or Anthropic.
 *
 * Model strings carry their provider as a prefix:
 *
 *     <provider_id>::<model_id>
 *
 *     cf::@cf/google/gemma-4-26b-a4b-it        built-in Cloudflare
 *     08fab…d3::gpt-4o                         user-defined OpenAI
 *     08fab…d3::anthropic/claude-opus-4-8      user-defined OpenRouter
 *
 * Backward compat is load-bearing: a string WITHOUT `::` is treated as a
 * Cloudflare model, so conversation rows written by the current backend keep
 * resolving after the migration.
 */

import type { Env, Provider, ModelEntry } from "./types";
import { decryptKey } from "./crypto";

export const SEP = "::";
export const CF_BUILTIN_ID = "cf";
export const ANTHROPIC_BUILTIN_ID = "anthropic";

/** Split `provider_id::model_id`. No separator → Cloudflare. */
export function parseModelStr(
  modelStr: string | null | undefined,
  defaultModel: string,
): { providerId: string; modelId: string } {
  if (!modelStr) {
    return parseModelStr(defaultModel, "");
  }
  const i = modelStr.indexOf(SEP);
  if (i === -1) return { providerId: CF_BUILTIN_ID, modelId: modelStr };
  return {
    providerId: modelStr.slice(0, i) || CF_BUILTIN_ID,
    modelId: modelStr.slice(i + SEP.length),
  };
}

const CF_ACCOUNT_BASE = "https://api.cloudflare.com/client/v4/accounts";

interface ProviderRow {
  id: string;
  name: string;
  kind: string;
  base_url: string;
  api_key_enc: string;
  models: string;
  extra_body: string;
  enabled: number;
}

function safeJson<T>(s: string, fallback: T): T {
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
}

/**
 * Resolve a provider id to a usable Provider.
 *
 * `cf` is synthetic: it isn't a row the user created, but it still honours a
 * user-supplied key row if one exists, falling back to the Worker's own AI
 * binding path. Same precedence as the Python `_cf_builtin_provider`.
 */
export async function resolveProvider(
  env: Env,
  userId: string,
  providerId: string,
): Promise<Provider | null> {
  const row = await env.DB.prepare(
    `SELECT id, name, kind, base_url, api_key_enc, models, extra_body, enabled
       FROM providers WHERE user_id = ? AND id = ?`,
  )
    .bind(userId, providerId)
    .first<ProviderRow>();

  if (row && row.enabled) {
    return {
      id: row.id,
      name: row.name,
      kind: (row.kind as Provider["kind"]) ?? "openai-compat",
      baseUrl: row.base_url.replace(/\/+$/, ""),
      apiKey: row.api_key_enc ? await decryptKey(env, row.api_key_enc) : "",
      models: safeJson<ModelEntry[]>(row.models, []),
      extraBody: safeJson<Record<string, unknown>>(row.extra_body, {}),
    };
  }

  // Synthetic built-ins, for when the user has no explicit row.
  if (providerId === CF_BUILTIN_ID) {
    return {
      id: CF_BUILTIN_ID,
      name: "Cloudflare Workers AI",
      kind: "cf",
      // Both empty by design: cf-kind never does an HTTP round trip. It is
      // dispatched through the Workers AI binding, which carries its own
      // authorization, so there is no base URL and no key to hold.
      baseUrl: "",
      apiKey: "",
      models: [], // capability comes from the bundled catalogue
      extraBody: {},
    };
  }

  if (providerId === ANTHROPIC_BUILTIN_ID) {
    return {
      id: ANTHROPIC_BUILTIN_ID,
      name: "Anthropic",
      kind: "anthropic",
      baseUrl: "https://api.anthropic.com",
      apiKey: "",
      models: [],
      extraBody: {},
    };
  }

  return null;
}

export function chatCompletionsUrl(provider: Provider, accountId?: string): string {
  if (provider.kind === "cf") {
    // Unused: cf-kind is served by the AI binding in streamWorkersAi, not by
    // fetch. Kept only so a caller that reaches here gets a URL that fails
    // loudly rather than a silently malformed one.
    if (!accountId) return "cf-binding://unrouted";
    return `${CF_ACCOUNT_BASE}/${accountId}/ai/v1/chat/completions`;
  }
  if (provider.kind === "anthropic") {
    return `${provider.baseUrl}/v1/messages`;
  }
  // OpenAI-compatible: base_url already points at the /v1 root.
  return `${provider.baseUrl}/chat/completions`;
}

/**
 * Flatten multimodal turns to plain text for models that only accept a string
 * `content`. Non-vision models 400 on a content ARRAY rather than ignoring
 * it, so this is required, not cosmetic.
 *
 * Only `content` is flattened. The tool-linkage fields are carried through
 * untouched: strip `tool_calls` off an assistant turn, or `tool_call_id` off a
 * tool result, and the provider can no longer match a result to the call that
 * asked for it — which fails as a 400 at best and a silently mismatched tool
 * result at worst.
 */
interface FlattenableMessage {
  role: string;
  content: unknown;
  tool_calls?: unknown[];
  tool_call_id?: string;
  name?: string;
}

export function flattenForTextModel(
  messages: FlattenableMessage[],
  imageNote = "[image omitted — this model cannot see images]",
): FlattenableMessage[] {
  return messages.map((m) => {
    const { content, ...rest } = m;

    if (typeof content === "string") return { ...rest, content };
    if (!Array.isArray(content)) return { ...rest, content: String(content ?? "") };

    const parts: string[] = [];
    for (const p of content as { type?: string; text?: string }[]) {
      if (p?.type === "text" && p.text) parts.push(p.text);
      else if (p?.type === "image_url") parts.push(imageNote);
    }
    return { ...rest, content: parts.join("\n") };
  });
}
