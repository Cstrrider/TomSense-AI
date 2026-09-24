/**
 * The per-turn context the EDGE adds in front of a conversation.
 *
 * The device already sends its own system message (the clock, device-tool
 * guidance, the per-chat instructions). What only the edge knows is added
 * here: the active persona, the conversation's project instructions, the
 * user's profile and relevant memories, which documents are searchable, and
 * the artifacts this chat has made (so "change the title" can update one
 * rather than rewrite it).
 */

import type { Env } from "./types";
import { getPrefs } from "./prefs";
import { getProfile, memoriesForTurn } from "./memory";

export async function buildContext(
  env: Env,
  userId: string,
  convId: string,
  lastUserText: string,
): Promise<string> {
  const parts: string[] = [];
  const prefs = await getPrefs(env, userId);

  if (prefs.persona_id) {
    const p = await env.DB.prepare(`SELECT name, prompt FROM personas WHERE id = ? AND user_id = ?`)
      .bind(prefs.persona_id, userId)
      .first<{ name: string; prompt: string }>();
    if (p?.prompt.trim()) parts.push(`Persona — ${p.name}:\n${p.prompt.trim()}`);
  }

  const project = await env.DB.prepare(
    `SELECT p.name, p.instructions FROM conversations c JOIN projects p ON p.id = c.project_id
      WHERE c.id = ? AND c.user_id = ? AND p.deleted = 0`,
  )
    .bind(convId, userId)
    .first<{ name: string; instructions: string }>()
    .catch(() => null);
  if (project?.instructions?.trim()) {
    parts.push(`This conversation belongs to the project "${project.name}". Project instructions:\n${project.instructions.trim()}`);
  }

  const profile = (await getProfile(env, userId)).trim();
  if (profile) parts.push(`About the user (written by them):\n${profile}`);

  const memories = await memoriesForTurn(env, userId, lastUserText);
  if (memories.length) {
    parts.push(
      "Things you remember about the user (use when relevant; never recite the list):\n" +
        memories.map((m) => `- ${m.text}`).join("\n") +
        "\nUse the remember tool for new lasting facts the user shares, and forget when they ask you to.",
    );
  } else {
    parts.push("Use the remember tool when the user shares a lasting fact about themselves or asks you to remember something.");
  }

  const docs = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM documents WHERE user_id = ? AND status = 'ready'`,
  )
    .bind(userId)
    .first<{ n: number }>();
  if (docs?.n) {
    parts.push(`The user has ${docs.n} uploaded document(s). Use search_docs to look things up in them before saying you don't know.`);
  }

  const { results: arts } = await env.DB.prepare(
    `SELECT id, title, version FROM artifacts WHERE user_id = ? AND conv_id = ? ORDER BY updated_at DESC LIMIT 8`,
  )
    .bind(userId, convId)
    .all<{ id: string; title: string; version: number }>();
  if (arts?.length) {
    parts.push(
      "Artifacts in this conversation (revise with update_artifact instead of re-printing them):\n" +
        arts.map((a) => `- ${a.id}: "${a.title}" (v${a.version})`).join("\n"),
    );
  }

  return parts.join("\n\n");
}
