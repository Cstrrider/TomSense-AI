/**
 * The utility tier: small, high-frequency calls that are not the conversation.
 *
 * Titles, follow-up suggestions, summaries and the auto-route classifier all
 * run here. Stable's reasoning for a separate tier is economic — these fire on
 * nearly every turn, and running them on the chat model means paying frontier
 * prices to generate a six-word title.
 *
 * Two details carried over deliberately:
 *
 * **Cache affinity is keyed by (user, purpose), not by conversation.** Every
 * `title` call shares one fixed prompt prefix and every `route` call another,
 * so pinning per purpose is what actually lands on a warm prefix cache.
 * Keying by chat would scatter them and defeat the discount entirely — stable
 * notes these were previously sent with no affinity header at all.
 *
 * **Failure is never fatal.** A utility call is an enhancement; if it fails,
 * the turn should proceed without a title rather than not at all. Everything
 * here returns null instead of throwing.
 */

import type { Env, Principal } from "./types";
import type { ToolModels } from "./prefs";
import { resolveProvider } from "./providers";
import { parseModelStr } from "./providers";
import { completeOnce } from "./stream";

export type TaskPurpose = "title" | "route" | "followups" | "summary";

/** Affinity key: one warm prefix per purpose, per user. */
export function taskSession(userId: string, purpose: TaskPurpose): string {
  return `task:${purpose}:${userId}`;
}

export async function runTaskModel(
  env: Env,
  who: Principal,
  opts: {
    purpose: TaskPurpose;
    prompt: string;
    slots: ToolModels;
    maxTokens?: number;
  },
): Promise<string | null> {
  // The user's title slot, else the configured utility model.
  //
  // NOT TIER1_MODEL: that is the stall fallback and is currently a reasoning
  // model. Giving a reasoning model a 4-token budget means it reasons, emits
  // no content, and every caller silently gets nothing — which is precisely
  // how the auto-route classifier failed on first run.
  const spec = opts.slots.title?.trim() || env.TASK_MODEL || env.TIER1_MODEL;
  const attempts = [spec, opts.slots.title_fallback?.trim()].filter(
    (s): s is string => Boolean(s),
  );

  for (const attempt of attempts) {
    const { providerId, modelId } = parseModelStr(attempt, env.TIER2_MODEL);
    const provider = await resolveProvider(env, who.userId, providerId);
    if (!provider) continue;

    try {
      const text = await completeOnce(env, provider, modelId, {
        messages: [{ role: "user", content: opts.prompt }],
        maxTokens: opts.maxTokens ?? 64,
        session: taskSession(who.userId, opts.purpose),
      });
      if (text && text.trim()) return text.trim();
    } catch {
      // Try the fallback, then give up quietly.
    }
  }
  return null;
}
