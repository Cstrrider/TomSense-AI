/**
 * Single source of truth for "what can this model do?" — port of
 * backend/app/capabilities.py, which was the root-cause fix for the vision
 * bug (scattered name-substring guessing in three places).
 *
 * Resolution order, unchanged:
 *
 *   1. Capabilities DECLARED on the provider's models[] entry. This is what
 *      lets the same model id differ per provider — gemma-4 on Cloudflare and
 *      gemma-4 on OpenRouter genuinely do not behave the same.
 *   2. LIVE Cloudflare metadata. Workers AI reports `vision`, `reasoning` and
 *      `context_window` per model; asking it beats guessing from the name.
 *   3. The bundled Cloudflare catalogue, for `@cf/...` ids it still covers.
 *   4. Demoted name-substring heuristics, last resort only.
 *
 * Step 2 was added after glm-5.3-flash — which Cloudflare reports as
 * `vision = true` — had its images stripped by flattenForTextModel, because
 * no substring in the hint list matched it. The bundled catalogue knows 10 of
 * the 31 text models Workers AI now serves, so the heuristics were answering
 * for the other 21, and answering wrongly. This is the same root cause stable
 * fixed once already by replacing scattered name-substring guessing.
 *
 * Step 4 exists solely so an un-annotated NON-Cloudflare model degrades rather
 * than breaks. Do not promote it.
 */

import { CF_MODELS_BY_ID } from "./cf_catalog";
import type { Capabilities, Provider, Env } from "./types";

/** CF model id substrings for models that emit a hidden reasoning channel. */
const REASONING_HINTS = [
  "gpt-oss",
  "gemma-4",
  "nemotron-3",
  "qwen3",
  "kimi",
  "deepseek-r1",
  "glm-4.7",
  // Added once Cloudflare discovery started returning the live catalogue
  // instead of the bundled one: these became selectable and were reported as
  // non-reasoning. glm-5.3 was observed streaming a reasoning channel
  // directly, and QwQ is a reasoning model by definition.
  "glm-5",
  "qwq",
] as const;

/**
 * CF model id substrings for models accepting OpenAI-shaped multimodal
 * content (a `content` ARRAY with image_url parts). Everything else only
 * accepts a string `content` and 400s on an array — hence flattenForTextModel.
 */
const VISION_HINTS = [
  "gemma-4",
  "llama-4",
  "kimi-k2.6",
  "kimi-k2.7",
  "vision", // llama-3.2-*-vision
  "pixtral",
  "llava",
  "qwen2-vl",
  "qwen2.5-vl",
] as const;

export function isReasoningModel(modelId: string): boolean {
  const m = (modelId || "").toLowerCase();
  return REASONING_HINTS.some((h) => m.includes(h));
}

export function isVisionModel(modelId: string): boolean {
  const m = (modelId || "").toLowerCase();
  return VISION_HINTS.some((h) => m.includes(h));
}

/**
 * Live Cloudflare capabilities, by model id.
 *
 * Module-level and synchronous to READ, because `modelCapabilities` is called
 * from request construction where there is nothing to await. [warmCfCapabilities]
 * fills it; until it does, resolution simply falls through to the catalogue and
 * heuristics, so a cold isolate degrades rather than breaking.
 */
let cfLive: Map<string, Capabilities> | null = null;
let cfLiveAt = 0;

/**
 * Per-model pricing, from the same metadata call.
 *
 * Cloudflare quotes USD per million tokens, and separately for CACHED input —
 * glm-5.2 is $1.40/M in, $0.26/M cached. That gap is why `cache_read` was worth
 * instrumenting, and why a cost figure that ignores it overstates spend on any
 * long conversation.
 *
 * There is no per-request neuron figure anywhere in the API; neurons are an
 * account-level analytics number. Cost is the thing that can be known exactly
 * per message, so it is what gets computed, with neurons derived from it.
 */
export interface ModelPrice {
  inPerM: number;
  outPerM: number;
  /** Falls back to inPerM when a model does not price cached input separately. */
  cachedPerM: number;
}
let cfPrice: Map<string, ModelPrice> | null = null;

export function cfModelPrice(modelId: string): ModelPrice | null {
  return cfPrice?.get(modelId) ?? null;
}
const CF_LIVE_TTL_MS = 60 * 60 * 1000;

/** Property values arrive as booleans or as the strings "true"/"false". */
function truthy(v: unknown): boolean {
  return v === true || v === "true" || v === 1 || v === "1";
}

/**
 * Populate the live capability map. Cheap after the first call, and safe to
 * call on every run.
 *
 * MUST be awaited before a Workers AI request is built, or vision models whose
 * names the heuristics do not recognise will have their image parts flattened
 * away — which looks exactly like the model ignoring the picture.
 */
export async function warmCfCapabilities(env: Env): Promise<void> {
  if (cfLive && Date.now() - cfLiveAt < CF_LIVE_TTL_MS) return;
  try {
    const listed = await env.AI.models({ task: "Text Generation", per_page: 200 });
    const next = new Map<string, Capabilities>();
    const prices = new Map<string, ModelPrice>();
    for (const model of listed) {
      const props: Record<string, unknown> = {};
      for (const prop of model.properties ?? []) {
        props[prop.property_id] = prop.value;
      }
      const ctx = Number(props["context_window"]);
      next.set(model.name, {
        vision: truthy(props["vision"]),
        reasoning: truthy(props["reasoning"]),
        context: Number.isFinite(ctx) && ctx > 0 ? ctx : null,
      });

      const price = readPrice(props["price"]);
      if (price) prices.set(model.name, price);
    }
    // Only adopt a non-empty result: a transient empty response must not
    // replace good data with a map that says nothing can see.
    if (next.size) {
      cfLive = next;
      cfPrice = prices;
      cfLiveAt = Date.now();
    }
  } catch {
    // Keep whatever is cached. Losing the live list is a degradation, not a
    // failure worth taking a generation down for.
  }
}

/**
 * Parse Cloudflare's price array.
 *
 * Shape: [{ unit: "per M input tokens", price: 0.1, currency: "USD" }, …].
 * Matched on the unit text because the array order is not guaranteed and not
 * every model prices cached input at all.
 */
function readPrice(raw: unknown): ModelPrice | null {
  const rows = Array.isArray(raw) ? raw : null;
  if (!rows) return null;

  let inPerM = 0;
  let outPerM = 0;
  let cachedPerM = -1;

  for (const row of rows as { unit?: string; price?: number }[]) {
    const unit = (row?.unit ?? "").toLowerCase();
    const price = Number(row?.price);
    if (!Number.isFinite(price)) continue;
    if (unit.includes("cached")) cachedPerM = price;
    else if (unit.includes("input")) inPerM = price;
    else if (unit.includes("output")) outPerM = price;
  }

  if (!inPerM && !outPerM) return null;
  return { inPerM, outPerM, cachedPerM: cachedPerM >= 0 ? cachedPerM : inPerM };
}

/** Step 1: capabilities explicitly declared on the provider's model entry. */
function declared(provider: Provider | null, modelId: string): Capabilities | null {
  for (const m of provider?.models ?? []) {
    if (m.id !== modelId) continue;

    // Assignment to the `vision` tool role IMPLIES vision capability — a model
    // only lands in the Vision slot if it can see images, so one control
    // covers both and the UI needs no separate capability toggle.
    const visionRole = (m.tools ?? []).includes("vision");
    const hasCapField =
      "vision" in m || "reasoning" in m || "context" in m;

    if (visionRole || hasCapField) {
      return {
        vision: Boolean(m.vision) || visionRole,
        reasoning: Boolean(m.reasoning),
        context: m.context ?? null,
      };
    }
    // Listed, but carries no capability data — fall through to the catalogue.
    return null;
  }
  return null;
}

/**
 * Which thinking controls a model accepts: an effort level and/or an off
 * switch. Swept from the Workers AI input schemas on 2026-09-26 — they differ
 * per family: Nemotron takes only the on/off toggle (reasoning_effort is
 * silently ignored), gpt-oss only effort, older reasoning models neither.
 * Other providers get the OpenAI-standard reasoning_effort and no off switch.
 */
export function thinkingControls(
  provider: Provider | null,
  modelId: string,
): { effort: boolean; off: boolean } {
  if (!modelCapabilities(provider, modelId).reasoning) return { effort: false, off: false };
  const id = modelId.toLowerCase();
  if (provider?.kind === "cf" || id.startsWith("@cf/")) {
    if (id.includes("gpt-oss")) return { effort: true, off: false };
    if (id.includes("nemotron-3")) return { effort: false, off: true };
    if (["gemma-4", "glm-", "kimi", "deepseek-v4", "qwen3.8"].some((h) => id.includes(h))) {
      return { effort: true, off: true };
    }
    return { effort: false, off: false };
  }
  return { effort: true, off: false };
}

export function modelCapabilities(
  provider: Provider | null,
  modelId: string,
): Capabilities {
  const fromProvider = declared(provider, modelId);
  if (fromProvider !== null) return fromProvider;

  const live = cfLive?.get(modelId);
  if (live !== undefined) return live;

  const entry = CF_MODELS_BY_ID[modelId];
  if (entry !== undefined) {
    return {
      vision: Boolean(entry.vision),
      reasoning: Boolean(entry.reasoning),
      context: entry.context ?? null,
    };
  }

  return {
    vision: isVisionModel(modelId),
    reasoning: isReasoningModel(modelId),
    context: null,
  };
}
