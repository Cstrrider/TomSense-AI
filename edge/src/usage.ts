/**
 * What a turn cost, and what today has cost.
 *
 * Cloudflare does not report neurons per request — neurons are an
 * account-level analytics figure. Tokens and price ARE known per request, so
 * cost is computed exactly and neurons are derived from it at Cloudflare's
 * published rate. Anything labelled a neuron figure here is therefore an
 * ESTIMATE unless it came from the analytics API, and the API says which.
 */

import type { Env, Usage } from "./types";
import { cfModelPrice, warmCfCapabilities } from "./capabilities";
import { neuronsToday } from "./cf_analytics";

/** Cloudflare's published conversion: $0.011 per 1,000 neurons. */
const USD_PER_1K_NEURONS = 0.011;

export function neuronsFromUsd(usd: number): number {
  return Math.round((usd / USD_PER_1K_NEURONS) * 1000);
}

/**
 * Cost of one turn, in USD.
 *
 * Cached input is billed at its own lower rate, so it is subtracted from the
 * input count rather than double-charged — glm-5.2 bills cached input at
 * $0.26/M against $1.40/M fresh, which is a large enough gap to matter on any
 * long conversation.
 *
 * Returns null for a model with no published price, i.e. every BYO provider.
 * Inventing a number there would be worse than admitting we cannot know.
 */
export function costUsd(modelId: string, usage: Usage): number | null {
  const price = cfModelPrice(modelId);
  if (!price) return null;

  const cached = Math.min(usage.cache_read ?? 0, usage.in ?? 0);
  const fresh = Math.max((usage.in ?? 0) - cached, 0);

  return (
    (fresh / 1_000_000) * price.inPerM +
    (cached / 1_000_000) * price.cachedPerM +
    ((usage.out ?? 0) / 1_000_000) * price.outPerM
  );
}

export interface ModelUsageRow {
  providerId: string;
  modelId: string;
  tokensIn: number;
  tokensOut: number;
  cacheRead: number;
  requests: number;
  costUsd: number | null;
}

export interface UsageToday {
  day: string;
  tokensIn: number;
  tokensOut: number;
  cacheRead: number;
  requests: number;
  costUsd: number;
  /** Estimated from cost unless `neuronsMeasured` is true. */
  neurons: number;
  neuronsMeasured: boolean;
  neuronLimit: number;
  byModel: ModelUsageRow[];
}

export async function usageToday(env: Env, userId: string): Promise<UsageToday> {
  // Prices live in the same live-metadata map as capabilities, and reading it
  // cold yields null for every model — which silently reports a days work as
  // costing nothing.
  await warmCfCapabilities(env);

  const day = new Date().toISOString().slice(0, 10);

  const res = await env.DB.prepare(
    `SELECT provider_id, model_id, tokens_in, tokens_out, cache_read, requests
       FROM usage_daily WHERE user_id = ? AND day = ?
      ORDER BY tokens_in + tokens_out DESC`,
  )
    .bind(userId, day)
    .all<{
      provider_id: string;
      model_id: string;
      tokens_in: number;
      tokens_out: number;
      cache_read: number;
      requests: number;
    }>();

  const byModel: ModelUsageRow[] = [];
  let tokensIn = 0, tokensOut = 0, cacheRead = 0, requests = 0, cost = 0;

  for (const r of res.results ?? []) {
    const rowCost = costUsd(r.model_id, {
      in: r.tokens_in,
      out: r.tokens_out,
      cache_read: r.cache_read,
      cache_write: 0,
    });
    tokensIn += r.tokens_in;
    tokensOut += r.tokens_out;
    cacheRead += r.cache_read;
    requests += r.requests;
    cost += rowCost ?? 0;
    byModel.push({
      providerId: r.provider_id,
      modelId: r.model_id,
      tokensIn: r.tokens_in,
      tokensOut: r.tokens_out,
      cacheRead: r.cache_read,
      requests: r.requests,
      costUsd: rowCost,
    });
  }

  // Real neurons when the user has supplied an analytics token; otherwise the
  // estimate. Reported separately so the UI never presents one as the other.
  const measured = await neuronsToday(env, userId);

  return {
    day,
    tokensIn,
    tokensOut,
    cacheRead,
    requests,
    costUsd: cost,
    neurons: measured ?? neuronsFromUsd(cost),
    neuronsMeasured: measured !== null,
    neuronLimit: Number(env.NEURON_DAILY_LIMIT) || 10_000,
    byModel,
  };
}
