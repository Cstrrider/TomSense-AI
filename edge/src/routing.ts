/**
 * Which model actually answers this turn.
 *
 * Ported from stable, where the decision is six layers deep and spread across
 * `main.py` (~3130–3235), `_route_model`, `_budget_downshift` and `_vision_model`.
 * Gathering them here is the point of the port: on stable the order is implicit
 * in the order the statements happen to appear, which is why it was hard to
 * answer "why did THAT model reply?".
 *
 * Precedence, highest first:
 *
 *   1. Explicit per-request pick      — always wins
 *   2. Think mode                     — research slot, or the reasoning default
 *   3. Vision override                — image attached
 *   4. Auto-route                     — EASY/HARD difficulty escalation
 *   5. Saved default / first usable   — the pre-existing beta behaviour
 *   6. Budget downshift               — applied LAST, over whatever won above
 *
 * Budget mode is last deliberately: it is a cost ceiling, so it has to be able
 * to override the router's own escalation. Stable makes the same choice, with
 * the comment "this also reins the router back in when the neuron cap is near".
 */

import type { Env, Principal, ChatMessage } from "./types";
import { CF_BUILTIN_ID } from "./providers";
import { listModels, getDefaultModel, resolveChatModel } from "./providers_api";
import { getPrefs, type ToolModels } from "./prefs";
import { neuronsToday } from "./cf_analytics";
import { runTaskModel } from "./task_model";

/** Heavy CF models worth downshifting. Substring match, as on stable. */
const NEURON_HEAVY = ["kimi", "glm-5", "nemotron", "120b", "70b", "deepseek-v4"];

/**
 * Fraction of the daily allowance at which heavy models downshift, and the
 * allowance itself. Configurable like stable (NEURON_SOFT_CAP_PCT /
 * CF_DAILY_NEURON_LIMIT): the free tier changes, and a hardcoded ceiling
 * becomes wrong silently.
 */
function budgetLimits(env: Env): { limit: number; capPct: number } {
  const limit = Number(env.NEURON_DAILY_LIMIT) || 10_000;
  const capPct = Number(env.NEURON_SOFT_CAP_PCT) || 80;
  return { limit, capPct };
}

export interface RoutingDecision {
  model: string;
  /** Used on stall, and as the budget-downshift target. */
  fallbackModel: string | null;
  /** `high` when think mode is on; the provider layer decides what to do. */
  reasoningEffort: "high" | null;
  /**
   * User-visible explanations of any override, rendered in the transcript.
   * Stable streams these as the first chunk so a surprising model choice is
   * never silent — that transparency IS the feature.
   */
  notices: string[];
}

/** Does this turn carry an image? Mirrors the OpenAI content-parts shape. */
export function hasImage(messages: ChatMessage[]): boolean {
  for (const m of messages) {
    const c = m.content as unknown;
    if (Array.isArray(c)) {
      for (const part of c) {
        const t = (part as { type?: string })?.type;
        if (t === "image_url" || t === "image") return true;
      }
    }
  }
  return false;
}

/** Last user message as plain text, for the difficulty classifier. */
function lastUserText(messages: ChatMessage[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m?.role !== "user") continue;
    const c = m.content as unknown;
    if (typeof c === "string") return c;
    if (Array.isArray(c)) {
      return c
        .map((p) => (p as { text?: string })?.text ?? "")
        .join(" ")
        .trim();
    }
    return "";
  }
  return "";
}

function shortName(spec: string): string {
  const id = spec.includes("::") ? spec.slice(spec.indexOf("::") + 2) : spec;
  return id.split("/").pop() || id;
}

/** Can the model behind this spec accept image parts? */
async function seesImages(env: Env, who: Principal, spec: string): Promise<boolean> {
  const options = await listModels(env, who);
  return options.find((o) => o.value === spec)?.vision ?? false;
}

export async function routeChat(
  env: Env,
  who: Principal,
  opts: {
    messages: ChatMessage[];
    requested?: string | null;
    think?: boolean;
  },
): Promise<RoutingDecision | { error: string }> {
  const prefs = await getPrefs(env, who.userId);
  const slots: ToolModels = prefs.tool_models;
  const notices: string[] = [];

  let model: string | null = opts.requested?.trim() || null;
  const explicit = Boolean(model);
  let reasoningEffort: "high" | null = null;

  // ── 2. Think mode ──────────────────────────────────────────────────────
  // Only when the user did not pick a model for this turn: an explicit pick
  // plus think should raise the effort, not silently change the model.
  if (opts.think) {
    reasoningEffort = "high";
    if (!explicit) {
      const research = slots.research?.trim();
      if (research) model = research;
    }
  }

  // ── 3. Vision override ─────────────────────────────────────────────────
  if (hasImage(opts.messages)) {
    const visionSlot = slots.vision?.trim();
    const currentSees = model ? await seesImages(env, who, model) : false;

    if (visionSlot && !(explicit && currentSees)) {
      // The Vision slot OWNS image turns, even when the chat model can also
      // see. Stable learned this the hard way (comment dated 2026-07-11): a
      // model was chosen specifically for landmark recognition, a photo was
      // attached, and a merely vision-CAPABLE chat model answered instead.
      // "The model I picked for images" must beat "a model that can see".
      // The one exception is an explicit per-request pick that can see.
      if (visionSlot !== model) {
        model = visionSlot;
        reasoningEffort = null; // the vision slot is not the think model
        notices.push(
          `*[image attached — answering with ${shortName(visionSlot)} (your Vision model)]*`,
        );
      }
    } else if (!currentSees) {
      // No slot set: rescue a text-only model rather than letting it 400 on
      // an image part.
      const resolved = model ?? (await getDefaultModel(env, who));
      const stillBlind = resolved ? !(await seesImages(env, who, resolved)) : true;
      if (stillBlind) {
        const candidate = (await listModels(env, who)).find((o) => o.vision);
        if (candidate && candidate.value !== model) {
          model = candidate.value;
          reasoningEffort = null;
          notices.push(
            `*[image attached — answering with ${shortName(candidate.value)} (vision). ` +
              `Set a Vision model in Settings to choose which.]*`,
          );
        }
      }
    }
  }

  // ── 4. Auto-route ──────────────────────────────────────────────────────
  // Only for turns that would otherwise run the plain default: an explicit
  // pick, think mode and a vision override have all already decided.
  if (!model && prefs.auto_route && !opts.think) {
    const escalated = await difficultyRoute(env, who, opts.messages, slots);
    if (escalated) model = escalated;
  }

  // ── 5. Saved default / first usable ────────────────────────────────────
  const picked = await resolveChatModel(env, who, model);
  if ("error" in picked) return picked;
  model = picked.model;

  // Slot fallback: which model catches a stall, and where budget mode lands.
  const slotFallback =
    (notices.length && slots.vision_fallback?.trim()) || slots.chat_fallback?.trim() || null;

  // ── 6. Budget downshift ────────────────────────────────────────────────
  const downshifted = await budgetDownshift(env, who, model, slotFallback);
  if (downshifted) {
    model = downshifted.model;
    notices.push(downshifted.notice);
  }

  return {
    model,
    fallbackModel: slotFallback ?? (await defaultFallback(env, who, model)),
    reasoningEffort,
    notices,
  };
}

/**
 * One tiny classifier call: EASY stays put, HARD escalates.
 *
 * The short-circuit is what makes this affordable. Stable skips anything under
 * 60 characters with no code fence, because that is near-always chitchat — so
 * the common case costs nothing and only substantial turns pay for the call.
 */
async function difficultyRoute(
  env: Env,
  who: Principal,
  messages: ChatMessage[],
  slots: ToolModels,
): Promise<string | null> {
  const text = lastUserText(messages);
  if (!text) return null;
  if (text.length < 60 && !text.includes("```")) return null;

  const verdict = await runTaskModel(env, who, {
    purpose: "route",
    slots,
    // Stable uses 4. A little more headroom here so that a user who points
    // the title slot at a reasoning model still gets a usable verdict rather
    // than silently losing auto-routing altogether.
    maxTokens: 16,
    prompt:
      "Classify the difficulty of answering this message well. Reply with " +
      "exactly one word — EASY (casual chat, simple facts, short rewrites) " +
      "or HARD (multi-step reasoning, math proofs, non-trivial code, nuanced " +
      "analysis, long structured writing).\n\nMessage:\n" + text.slice(0, 2000),
  });

  if (!verdict || !verdict.toUpperCase().includes("HARD")) return null;

  // The Research slot is the user's own answer to "which model for hard
  // questions" — it wins. Without one, fall back to stable's rule (a heavy
  // CF model), but ONLY if the default isn't heavy already: escalating a
  // glm-5.3-flash default to the first "70b" in the list sent a hard question
  // to llama-3.3-70b, a sideways-to-worse move dressed up as an upgrade.
  const research = slots.research?.trim();
  if (research) return research;
  const current = await getDefaultModel(env, who);
  if (current && NEURON_HEAVY.some((h) => current.includes(h))) return null;

  const options = await listModels(env, who);
  const heavy = options.find(
    (o) => o.value.startsWith(`${CF_BUILTIN_ID}::`) && NEURON_HEAVY.some((h) => o.value.includes(h)),
  );
  return heavy?.value ?? null;
}

/**
 * Swap a heavy Cloudflare model when today's neuron use crosses the soft cap.
 *
 * Dormant unless the user has supplied an analytics token — reading usage is
 * the only part of this that needs a credential. Non-Cloudflare providers are
 * never downshifted: their spend is the user's own arrangement with that
 * provider and not something this cap knows anything about.
 */
async function budgetDownshift(
  env: Env,
  who: Principal,
  model: string,
  slotFallback: string | null,
): Promise<{ model: string; notice: string } | null> {
  if (!model.startsWith(`${CF_BUILTIN_ID}::`)) return null;
  if (!NEURON_HEAVY.some((h) => model.includes(h))) return null;

  const used = await neuronsToday(env, who.userId);
  if (used === null) return null; // no token, or the lookup failed

  const { limit, capPct } = budgetLimits(env);
  if (used < (limit * capPct) / 100) return null;

  // Prefer what the user chose to fall back to; otherwise the cheapest
  // configured Cloudflare model, which beats a hardcoded id they may have
  // removed from their list.
  let target = slotFallback;
  if (!target) {
    const options = await listModels(env, who);
    target =
      options.find(
        (o) =>
          o.value.startsWith(`${CF_BUILTIN_ID}::`) &&
          o.value !== model &&
          !NEURON_HEAVY.some((h) => o.value.includes(h)),
      )?.value ?? null;
  }
  if (!target || target === model) return null;

  return {
    model: target,
    notice:
      `> 🪫 **Budget mode** — ${used.toLocaleString()}/${limit.toLocaleString()} ` +
      `neurons used today; running \`${shortName(target)}\` instead of ` +
      `\`${shortName(model)}\`. Resets at midnight UTC.`,
  };
}

/** Last-resort stall target when no slot fallback is configured. */
async function defaultFallback(
  env: Env,
  who: Principal,
  primary: string,
): Promise<string | null> {
  const options = await listModels(env, who);
  return options.find((m) => m.value !== primary)?.value ?? null;
}
