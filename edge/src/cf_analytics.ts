/**
 * Neuron usage for today, for budget mode.
 *
 * Port of `backend/app/cf.py:fetch_neurons_today`. The distinction that shapes
 * this file: **running** inference needs no credential — the AI binding bills
 * the account directly — but **reading** how much has been used is the GraphQL
 * analytics API, which does.
 *
 * So the token is a per-user setting rather than a Worker secret. Budget mode
 * is dormant until someone provides one, the Worker holds nothing but its
 * bindings by default, and a Worker compromise does not hand over an account
 * credential. The cost is that the token is only as scoped as the one the user
 * pastes in — `Account Analytics: Read` is all this needs.
 */

import type { Env } from "./types";
import { decryptKey } from "./crypto";

const GRAPHQL = "https://api.cloudflare.com/client/v4/graphql";

/** Cached per isolate: the number moves slowly and the query is not free. */
const CACHE = new Map<string, { at: number; used: number }>();
const CACHE_MS = 120_000;

const QUERY = `
query getNeurons($accountId: string!, $start: string!, $end: string!) {
  viewer {
    accounts(filter: { accountTag: $accountId }) {
      aiInferenceAdaptiveGroups(
        limit: 10000,
        filter: { datetime_geq: $start, datetime_leq: $end }
      ) {
        sum { totalNeurons }
      }
    }
  }
}`;

/**
 * Neurons used today (UTC), or null when unavailable.
 *
 * Null covers "no token configured", "token rejected" and "query failed"
 * alike, and every one of them means the same thing to the caller: do not
 * downshift. A budget guard that fails CLOSED would silently degrade every
 * model choice the moment analytics had a bad day, which is far worse than
 * not capping.
 */
export async function neuronsToday(
  env: Env,
  userId: string,
): Promise<number | null> {
  const hit = CACHE.get(userId);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.used;

  const row = await env.DB.prepare(
    `SELECT cf_analytics_key_enc, cf_account_id FROM users WHERE id = ?`,
  )
    .bind(userId)
    .first<{ cf_analytics_key_enc: string; cf_account_id: string }>();

  if (!row?.cf_analytics_key_enc || !row.cf_account_id) return null;

  let token: string;
  try {
    token = await decryptKey(env, row.cf_analytics_key_enc);
  } catch {
    return null;
  }
  if (!token) return null;

  const now = new Date();
  const start = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 0, 0, 0),
  ).toISOString();
  const end = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 23, 59, 59),
  ).toISOString();

  try {
    const res = await fetch(GRAPHQL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        query: QUERY,
        variables: { accountId: row.cf_account_id, start, end },
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) return null;

    const data = (await res.json()) as {
      data?: {
        viewer?: {
          accounts?: { aiInferenceAdaptiveGroups?: { sum?: { totalNeurons?: number } }[] }[];
        };
      };
    };

    const groups = data.data?.viewer?.accounts?.[0]?.aiInferenceAdaptiveGroups ?? [];
    const used = groups.reduce((acc, g) => acc + (g.sum?.totalNeurons ?? 0), 0);

    CACHE.set(userId, { at: Date.now(), used: Math.round(used) });
    return Math.round(used);
  } catch {
    return null;
  }
}
