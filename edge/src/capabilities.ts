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
 *   2. The bundled Cloudflare catalogue, for `@cf/...` ids.
 *   3. Demoted name-substring heuristics, last resort only.
 *
 * Step 3 exists solely so an un-annotated custom model degrades rather than
 * breaks. Do not promote it.
 */

import { CF_MODELS_BY_ID } from "./cf_catalog";
import type { Capabilities, Provider } from "./types";

/** CF model id substrings for models that emit a hidden reasoning channel. */
const REASONING_HINTS = [
  "gpt-oss",
  "gemma-4",
  "nemotron-3",
  "qwen3",
  "kimi",
  "deepseek-r1",
  "glm-4.7",
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

export function modelCapabilities(
  provider: Provider | null,
  modelId: string,
): Capabilities {
  const fromProvider = declared(provider, modelId);
  if (fromProvider !== null) return fromProvider;

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
