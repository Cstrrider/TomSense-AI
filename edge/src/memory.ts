/**
 * Long-term memory (spec §9), three layers:
 *
 *   1. PROFILE — a short free-text note the user writes ("I'm vegetarian,
 *      I work nights"). Always in context.
 *   2. MEMORIES — individual facts, written by the user, by the model through
 *      the `remember` tool, or extracted automatically after a turn. Pinned
 *      ones are always in context; the rest are chosen per turn.
 *   3. RETRIEVAL — which unpinned memories a turn sees is decided by vector
 *      similarity to the user's message, plus the most recent few.
 *
 * Rows go through the sync writer rather than raw INSERTs: `memories` is a
 * sync table, and a row without a seq would never reach another device.
 * Decay is real: last_used_at / use_count are bumped whenever a memory is put
 * in front of the model, so facts that stop mattering stop being chosen.
 */

import type { Env, Principal } from "./types";
import { push } from "./sync";
import { deleteMemoryVector, relatedMemoryIds, upsertMemoryVector } from "./rag";
import { getPrefs } from "./prefs";
import { runTaskModel } from "./task_model";

export interface Memory {
  id: string;
  text: string;
  pinned: number;
  created_at: number;
  last_used_at: number | null;
  use_count: number;
  source_msg_id: string | null;
}

/** Server-authored sync rows need a principal; "edge" marks who wrote them. */
function edgeWriter(userId: string): Principal {
  return { userId, email: "", deviceId: "edge" };
}

export async function listMemories(env: Env, userId: string): Promise<Memory[]> {
  const { results } = await env.DB.prepare(
    `SELECT id, text, pinned, created_at, last_used_at, use_count, source_msg_id
       FROM memories WHERE user_id = ? AND deleted = 0
      ORDER BY pinned DESC, created_at DESC`,
  )
    .bind(userId)
    .all<Memory>();
  return results ?? [];
}

export async function addMemory(
  env: Env,
  userId: string,
  text: string,
  opts: { pinned?: boolean; sourceMsgId?: string; confidence?: number } = {},
): Promise<Memory | null> {
  const clean = text.trim().replace(/\s+/g, " ").slice(0, 500);
  if (!clean) return null;

  // Exact duplicates are the common case (the model re-remembering a fact it
  // was already shown) and must not pile up.
  const dup = await env.DB.prepare(
    `SELECT id FROM memories WHERE user_id = ? AND deleted = 0 AND lower(text) = lower(?)`,
  )
    .bind(userId, clean)
    .first<{ id: string }>();
  if (dup) return null;

  const now = Date.now();
  const row: Memory = {
    id: crypto.randomUUID(),
    text: clean,
    pinned: opts.pinned ? 1 : 0,
    created_at: now,
    last_used_at: null,
    use_count: 0,
    source_msg_id: opts.sourceMsgId ?? null,
  };
  await push(env, edgeWriter(userId), {
    deviceId: "edge",
    rows: [{
      table: "memories",
      row: { ...row, confidence: opts.confidence ?? 1, deleted: 0, lamport: now },
    }],
  });
  await upsertMemoryVector(env, userId, row.id, clean);
  return row;
}

export async function updateMemory(
  env: Env,
  userId: string,
  id: string,
  patch: { text?: string; pinned?: boolean; deleted?: boolean },
): Promise<boolean> {
  const cur = await env.DB.prepare(`SELECT * FROM memories WHERE id = ? AND user_id = ?`)
    .bind(id, userId)
    .first<Record<string, unknown>>();
  if (!cur) return false;
  const next = {
    ...cur,
    text: patch.text?.trim() || cur["text"],
    pinned: patch.pinned === undefined ? cur["pinned"] : patch.pinned ? 1 : 0,
    deleted: patch.deleted ? 1 : cur["deleted"],
    // Server-authored lamports are wall-clock ms, far above any device's
    // counter, so an edit made here always wins over a stale device copy.
    lamport: Math.max(Date.now(), Number(cur["lamport"] ?? 0) + 1),
  };
  delete (next as Record<string, unknown>)["seq"];
  await push(env, edgeWriter(userId), { deviceId: "edge", rows: [{ table: "memories", row: next }] });
  if (patch.deleted) await deleteMemoryVector(env, id);
  else if (patch.text) await upsertMemoryVector(env, userId, id, String(next.text));
  return true;
}

/** Delete by id, or by fuzzy text match — for `forget` "that I live in Denver". */
export async function forgetMemory(env: Env, userId: string, idOrText: string): Promise<string[]> {
  const all = await listMemories(env, userId);
  const q = idOrText.trim().toLowerCase();
  let targets = all.filter((m) => m.id === idOrText);
  if (!targets.length) targets = all.filter((m) => m.text.toLowerCase().includes(q));
  if (!targets.length) {
    const related = await relatedMemoryIds(env, userId, idOrText, 1);
    targets = all.filter((m) => related.includes(m.id));
  }
  for (const m of targets) await updateMemory(env, userId, m.id, { deleted: true });
  return targets.map((m) => m.text);
}

export async function getProfile(env: Env, userId: string): Promise<string> {
  const row = await env.DB.prepare(`SELECT profile FROM users WHERE id = ?`).bind(userId).first<{ profile: string }>();
  return row?.profile ?? "";
}

export async function setProfile(env: Env, userId: string, text: string): Promise<void> {
  await env.DB.prepare(`UPDATE users SET profile = ? WHERE id = ?`).bind(text.slice(0, 4_000), userId).run();
}

/**
 * The memories to put in front of the model for this turn: every pinned one,
 * the ones most related to what was just asked, and the newest few (a fact
 * learned five minutes ago is usually still the subject).
 */
export async function memoriesForTurn(env: Env, userId: string, query: string): Promise<Memory[]> {
  const all = await listMemories(env, userId);
  if (!all.length) return [];
  const related = new Set(await relatedMemoryIds(env, userId, query, 8));
  const pinned = all.filter((m) => m.pinned);
  const relevant = all.filter((m) => related.has(m.id));
  const recent = [...all].sort((a, b) => b.created_at - a.created_at).slice(0, 4);
  const chosen = [...new Map([...pinned, ...relevant, ...recent].map((m) => [m.id, m])).values()].slice(0, 16);

  if (chosen.length) {
    const now = Date.now();
    const stmts = chosen.map((m) =>
      env.DB.prepare(`UPDATE memories SET last_used_at = ?, use_count = use_count + 1 WHERE id = ?`).bind(now, m.id),
    );
    await env.DB.batch(stmts).catch(() => {});
  }
  return chosen;
}

/**
 * After a turn: ask the task model whether the USER said anything worth
 * keeping. Conservative on purpose — a memory store full of trivia is worse
 * than an empty one, because every junk fact costs context on every turn.
 */
export async function extractMemories(
  env: Env,
  userId: string,
  userText: string,
  sourceMsgId?: string,
): Promise<void> {
  if (userText.trim().length < 25) return;
  const prefs = await getPrefs(env, userId);
  if (!prefs.auto_memory) return;

  const existing = (await listMemories(env, userId)).slice(0, 40).map((m) => `- ${m.text}`).join("\n");
  const prompt =
    "Extract durable personal facts about the USER from their message below — preferences, " +
    "circumstances, relationships, ongoing projects, things they asked to be remembered. " +
    "Ignore anything temporary, hypothetical, about other people in general, or already known.\n" +
    // No concrete example fact here: a small model copies it back verbatim
    // (that is how "Prefers metric units." appeared from a weather question).
    "Reply with one fact per line, each a short third-person sentence starting with a verb, " +
    "or exactly NONE.\n\nAlready known:\n" + (existing || "(nothing)") + "\n\nUser message:\n" +
    userText.slice(0, 2_000);

  const out = await runTaskModel(env, { userId, email: "", deviceId: "edge" }, {
    purpose: "summary",
    prompt,
    slots: prefs.tool_models,
    maxTokens: 160,
  });
  if (!out || /^\s*none\b/i.test(out)) return;

  const facts = out
    .split("\n")
    .map((l) => l.replace(/^[-*•\d.)\s]+/, "").trim())
    .filter((l) => l.length >= 8 && l.length <= 200 && !/^none$/i.test(l))
    .slice(0, 3);

  // Two guards against a model that "extracts" what it was merely shown:
  // a fact must share a real word with what the user actually said (seen in
  // testing: "Prefers metric units." pulled from a message about Paris
  // weather, because it was in the known list), and it must not be a
  // near-duplicate of a memory that already exists.
  const said = new Set(words(userText));
  for (const f of facts) {
    if (!words(f).some((w) => said.has(w))) continue;
    const near = await relatedMemoryIds(env, userId, f, 1, 0.9);
    if (near.length) continue;
    await addMemory(env, userId, f, { sourceMsgId, confidence: 0.7 });
  }
}

const STOP = new Set(["user", "users", "their", "they", "them", "this", "that", "with", "have", "from", "about", "prefers", "likes", "wants", "would"]);
function words(t: string): string[] {
  return (t.toLowerCase().match(/[\p{L}\p{N}]{4,}/gu) ?? []).filter((w) => !STOP.has(w));
}
