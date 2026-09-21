/**
 * Public share links (migration doc §3).
 *
 * A shared conversation is readable by anyone holding the link and nobody
 * else. That works naturally on this architecture because Cloudflare Access
 * only guards `/auth/mobile` — the Worker authenticates per route, so a public
 * route is genuinely public rather than something that has to be carved out of
 * a blanket rule.
 *
 * The token is the entire credential, so it is 256 bits of CSPRNG output. It
 * is also the only thing standing in front of a whole conversation, which is
 * why minting one is an explicit action and revoking it is a single UPDATE.
 */

import type { Env, Principal } from "./types";

/** URL-safe base64 of 32 random bytes — no padding, no characters to escape. */
function mintToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/**
 * POST /chats/{id}/share — mint a link, or revoke the existing one.
 *
 * Returns the token so the client can mirror it into its local row and show a
 * "shared" marker offline.
 */
export async function setShare(
  env: Env,
  who: Principal,
  convId: string,
  shared: boolean,
): Promise<{ shareToken: string | null } | { error: string }> {
  // Ownership is checked by the WHERE clause rather than a prior SELECT, so
  // there is no window between the check and the write, and a conversation
  // belonging to someone else is indistinguishable from one that is missing.
  const conv = await env.DB.prepare(
    `SELECT c.id, c.project_id, p.e2ee AS e2ee
       FROM conversations c
       LEFT JOIN projects p ON p.id = c.project_id
      WHERE c.id = ? AND c.user_id = ? AND c.deleted = 0`,
  )
    .bind(convId, who.userId)
    .first<{ id: string; project_id: string | null; e2ee: number | null }>();

  if (!conv) return { error: "conversation not found" };

  // An E2EE conversation is ciphertext at rest here (spec §10). A share link
  // would serve unreadable base64 to the reader while implying to the AUTHOR
  // that the conversation was published — the dangerous half of that pair is
  // the author's belief, so this refuses rather than sharing something
  // useless.
  if (shared && conv.e2ee) {
    return { error: "cannot share an end-to-end encrypted conversation" };
  }

  const token = shared ? mintToken() : null;
  await env.DB.prepare(
    `UPDATE conversations SET share_token = ? WHERE id = ? AND user_id = ?`,
  )
    .bind(token, convId, who.userId)
    .run();

  return { shareToken: token };
}

/**
 * GET /share/{token} — the public read.
 *
 * Deliberately NOT authenticated, and deliberately narrow: it resolves a token
 * to exactly one conversation and returns only what is needed to read it. No
 * ids, no user, no model keys, no neighbouring conversations.
 */
export async function readShared(
  env: Env,
  token: string,
): Promise<Response> {
  if (!token) return notFound();

  const conv = await env.DB.prepare(
    `SELECT id, title FROM conversations
      WHERE share_token = ? AND deleted = 0`,
  )
    .bind(token)
    .first<{ id: string; title: string }>();

  if (!conv) return notFound();

  const res = await env.DB.prepare(
    `SELECT role, content FROM messages
      WHERE conv_id = ? AND deleted = 0 AND encrypted = 0
      ORDER BY created_at
      LIMIT 2000`,
  )
    .bind(conv.id)
    .all<{ role: string; content: string }>();

  const messages = (res.results ?? []).filter(
    // Tool turns are protocol, not conversation — raw JSON payloads that mean
    // nothing to a reader who was handed a link.
    (m) => m.role !== "tool" && m.content.trim() !== "",
  );

  return new Response(renderPage(conv.title, messages), {
    headers: {
      "content-type": "text/html; charset=utf-8",
      // A share link is a capability. Keeping it out of shared caches and
      // search indexes is the difference between "anyone with the link" and
      // "anyone at all".
      "cache-control": "private, no-store",
      "x-robots-tag": "noindex, nofollow",
      "referrer-policy": "no-referrer",
    },
  });
}

/**
 * A missing token and a revoked one return the SAME response.
 *
 * Distinguishing them would turn this endpoint into an oracle for probing
 * which tokens once existed.
 */
function notFound(): Response {
  return new Response(renderPage("Not found", []), {
    status: 404,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}

/**
 * Escape text for HTML.
 *
 * Every string below comes from a conversation, which means it is arbitrary
 * user text that may contain markup — and on a shared domain, interpolating it
 * raw is stored XSS. This runs on the title and on every message body without
 * exception.
 */
function esc(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderPage(
  title: string,
  messages: { role: string; content: string }[],
): string {
  const body = messages.length
    ? messages
        .map(
          (m) =>
            `<div class="m ${m.role === "user" ? "u" : "a"}">` +
            `<div class="r">${esc(m.role === "user" ? "You" : "TomSense")}</div>` +
            `<div class="c">${esc(m.content)}</div>` +
            `</div>`,
        )
        .join("")
    : `<p class="empty">This link is no longer available.</p>`;

  // Content-Security-Policy via meta as well as the escaping above: defence in
  // depth, so a future change that forgets esc() still cannot run a script.
  return `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<meta name="robots" content="noindex, nofollow">
<title>${esc(title || "Shared conversation")}</title>
<style>
  body { font: 16px/1.6 system-ui, sans-serif; max-width: 46rem; margin: 0 auto; padding: 2rem 1rem; color: #16181d; background: #fff; }
  h1 { font-size: 1.4rem; margin-bottom: 1.5rem; }
  .m { margin: 0 0 1.25rem; }
  .r { font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; color: #6b7280; margin-bottom: .25rem; }
  .c { white-space: pre-wrap; overflow-wrap: anywhere; }
  .u .c { background: #f3f4f6; padding: .75rem; border-radius: .5rem; }
  .empty { color: #6b7280; }
  footer { margin-top: 3rem; font-size: .8rem; color: #9ca3af; }
  @media (prefers-color-scheme: dark) {
    body { background: #0f1115; color: #e6e8eb; }
    .u .c { background: #1b1f27; }
  }
</style>
</head><body>
<h1>${esc(title || "Shared conversation")}</h1>
${body}
<footer>Shared from TomSense</footer>
</body></html>`;
}
