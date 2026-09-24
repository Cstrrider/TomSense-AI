/**
 * Retrieval: uploaded documents and long-term memories.
 *
 * Two indexes over the same content, merged at query time:
 *
 *  - Vectorize (bge-base, 768d) for meaning — "what did the lease say about
 *    pets" finds the clause that never uses the word "pets".
 *  - D1 FTS5 for exact tokens — invoice numbers, names, error codes are what
 *    embeddings blur, and BM25 nails them.
 *
 * Vectorize is optional at runtime: with no binding, search degrades to
 * keyword-only instead of failing, so documents still work on a deployment
 * whose token lacks the permission.
 *
 * Vector ids are namespaced by kind ("d:<chunk>" / "m:<memory>") and carry
 * userId metadata; every query FILTERS on userId. A missing filter here would
 * be a cross-user data leak, so it is not optional anywhere in this file.
 */

import type { Env } from "./types";

const EMBED_MODEL = "@cf/baai/bge-base-en-v1.5";
const CHUNK_CHARS = 1_600;
const CHUNK_OVERLAP = 200;

export async function embed(env: Env, texts: string[]): Promise<number[][]> {
  const out: number[][] = [];
  // The model takes batches; 50 keeps each call well under its input limit.
  for (let i = 0; i < texts.length; i += 50) {
    const res = (await env.AI.run(EMBED_MODEL as keyof AiModels, {
      text: texts.slice(i, i + 50),
    } as never)) as unknown as { data: number[][] };
    out.push(...res.data);
  }
  return out;
}

/**
 * Split on paragraph boundaries into ~1600-char chunks with overlap, so a
 * fact that straddles a boundary is still whole in at least one chunk.
 */
export function chunkText(text: string): string[] {
  const paras = text.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);
  const chunks: string[] = [];
  let cur = "";
  for (const p of paras) {
    if ((cur + "\n\n" + p).length > CHUNK_CHARS && cur) {
      chunks.push(cur);
      cur = cur.slice(-CHUNK_OVERLAP) + "\n\n" + p;
    } else {
      cur = cur ? cur + "\n\n" + p : p;
    }
    // A single enormous paragraph (a PDF with no blank lines) is cut hard.
    while (cur.length > CHUNK_CHARS * 1.5) {
      chunks.push(cur.slice(0, CHUNK_CHARS));
      cur = cur.slice(CHUNK_CHARS - CHUNK_OVERLAP);
    }
  }
  if (cur.trim()) chunks.push(cur);
  return chunks;
}

/**
 * Turn an uploaded file into text.
 *
 * Workers AI toMarkdown handles PDF, Office documents, HTML, CSV and images
 * (described by a vision model) — far more than hand-rolled parsers could in
 * a Worker. Plain text formats skip it.
 */
export async function extractText(env: Env, name: string, mime: string, bytes: ArrayBuffer): Promise<string> {
  if (mime.startsWith("text/") || /\.(txt|md|csv|json|log|ya?ml|xml|kt|ts|js|py|java|go|rs|c|cpp|h|sh)$/i.test(name)) {
    return new TextDecoder().decode(bytes);
  }
  const ai = env.AI as unknown as {
    toMarkdown(files: { name: string; blob: Blob }[]): Promise<{ data?: string; error?: string }[]>;
  };
  const [res] = await ai.toMarkdown([{ name, blob: new Blob([bytes], { type: mime }) }]);
  if (!res || res.error || !res.data) throw new Error(res?.error ?? "could not extract text");
  return res.data;
}

/** Index a stored document: extract, chunk, write text + FTS + vectors. */
export async function indexDocument(env: Env, userId: string, docId: string): Promise<void> {
  const doc = await env.DB.prepare(`SELECT * FROM documents WHERE id = ? AND user_id = ?`)
    .bind(docId, userId)
    .first<{ name: string; mime: string; r2_key: string }>();
  if (!doc) return;
  try {
    const obj = await env.FILES.get(doc.r2_key);
    if (!obj) throw new Error("file missing from storage");
    const text = await extractText(env, doc.name, doc.mime, await obj.arrayBuffer());
    const chunks = chunkText(text);
    if (!chunks.length) throw new Error("no text found in the document");

    const ids = chunks.map((_, i) => `${docId}:${i}`);
    const stmts: D1PreparedStatement[] = [];
    chunks.forEach((c, i) => {
      stmts.push(
        env.DB.prepare(`INSERT OR REPLACE INTO doc_chunks (id, doc_id, user_id, ord, text) VALUES (?, ?, ?, ?, ?)`)
          .bind(ids[i], docId, userId, i, c),
        env.DB.prepare(`INSERT INTO doc_chunks_fts (text, chunk_id, user_id) VALUES (?, ?, ?)`)
          .bind(`${doc.name}\n${c}`, ids[i], userId),
      );
    });
    for (let i = 0; i < stmts.length; i += 90) await env.DB.batch(stmts.slice(i, i + 90));

    if (env.VECTORS) {
      // The file name goes into every chunk's embedding: "the lease" should
      // find lease.pdf even when a chunk never mentions what it is part of.
      const vecs = await embed(env, chunks.map((c) => `${doc.name}\n${c}`));
      const records = vecs.map((values, i) => ({
        id: `d:${ids[i]}`,
        values,
        metadata: { userId, kind: "doc", docId },
      }));
      for (let i = 0; i < records.length; i += 500) await env.VECTORS.upsert(records.slice(i, i + 500));
    }

    await env.DB.prepare(`UPDATE documents SET status = 'ready', chunks = ?, error = NULL WHERE id = ?`)
      .bind(chunks.length, docId)
      .run();
  } catch (e) {
    await env.DB.prepare(`UPDATE documents SET status = 'error', error = ? WHERE id = ?`)
      .bind((e as Error).message.slice(0, 300), docId)
      .run();
  }
}

export async function deleteDocument(env: Env, userId: string, docId: string): Promise<void> {
  const { results } = await env.DB.prepare(`SELECT id FROM doc_chunks WHERE doc_id = ? AND user_id = ?`)
    .bind(docId, userId)
    .all<{ id: string }>();
  const ids = (results ?? []).map((r) => r.id);
  if (env.VECTORS && ids.length) {
    for (let i = 0; i < ids.length; i += 500) {
      await env.VECTORS.deleteByIds(ids.slice(i, i + 500).map((id) => `d:${id}`));
    }
  }
  const doc = await env.DB.prepare(`SELECT r2_key FROM documents WHERE id = ? AND user_id = ?`)
    .bind(docId, userId)
    .first<{ r2_key: string }>();
  await env.DB.batch([
    env.DB.prepare(`DELETE FROM doc_chunks_fts WHERE user_id = ? AND chunk_id LIKE ?`).bind(userId, `${docId}:%`),
    env.DB.prepare(`DELETE FROM doc_chunks WHERE doc_id = ? AND user_id = ?`).bind(docId, userId),
    env.DB.prepare(`DELETE FROM documents WHERE id = ? AND user_id = ?`).bind(docId, userId),
  ]);
  if (doc?.r2_key) await env.FILES.delete(doc.r2_key);
}

export interface DocHit {
  doc: string;
  text: string;
  score: number;
}

/** FTS5 wants bare terms; strip operators so user text cannot break the query. */
function ftsQuery(q: string): string {
  const terms = q.toLowerCase().match(/[\p{L}\p{N}]{2,}/gu) ?? [];
  return [...new Set(terms)].slice(0, 12).map((t) => `"${t}"`).join(" OR ");
}

export async function searchDocs(env: Env, userId: string, query: string, k = 6): Promise<DocHit[]> {
  const scores = new Map<string, number>();

  if (env.VECTORS) {
    try {
      const [v] = await embed(env, [query]);
      const res = await env.VECTORS.query(v!, { topK: k * 2, filter: { userId, kind: "doc" } });
      for (const m of res.matches) scores.set(m.id.slice(2), m.score);
    } catch {
      // Keyword search below still answers.
    }
  }

  const fq = ftsQuery(query);
  if (fq) {
    const { results } = await env.DB.prepare(
      `SELECT chunk_id, bm25(doc_chunks_fts) AS r FROM doc_chunks_fts
        WHERE doc_chunks_fts MATCH ? AND user_id = ? ORDER BY r LIMIT ?`,
    )
      .bind(fq, userId, k * 2)
      .all<{ chunk_id: string; r: number }>();
    // bm25 is negative-is-better; fold it into the same 0..1-ish range and
    // let an exact keyword hit lift a chunk the embedding ranked lower.
    (results ?? []).forEach((row, i) => {
      const kw = 0.55 - i * 0.03;
      scores.set(row.chunk_id, Math.max(scores.get(row.chunk_id) ?? 0, kw) + 0.05);
    });
  }

  const top = [...scores.entries()].sort((a, b) => b[1] - a[1]).slice(0, k);
  if (!top.length) return [];
  const placeholders = top.map(() => "?").join(",");
  const { results } = await env.DB.prepare(
    `SELECT c.id, c.text, d.name FROM doc_chunks c JOIN documents d ON d.id = c.doc_id
      WHERE c.user_id = ? AND c.id IN (${placeholders})`,
  )
    .bind(userId, ...top.map(([id]) => id))
    .all<{ id: string; text: string; name: string }>();
  const byId = new Map((results ?? []).map((r) => [r.id, r]));
  return top
    .map(([id, score]) => {
      const r = byId.get(id);
      return r ? { doc: r.name, text: r.text, score } : null;
    })
    .filter((h): h is DocHit => h !== null);
}

// ─── memory vectors ─────────────────────────────────────────────────────────

export async function upsertMemoryVector(env: Env, userId: string, id: string, text: string): Promise<void> {
  if (!env.VECTORS) return;
  try {
    const [values] = await embed(env, [text]);
    await env.VECTORS.upsert([{ id: `m:${id}`, values: values!, metadata: { userId, kind: "memory" } }]);
  } catch {
    // Recency and pinning still surface it; a missing vector only costs recall.
  }
}

export async function deleteMemoryVector(env: Env, id: string): Promise<void> {
  if (!env.VECTORS) return;
  await env.VECTORS.deleteByIds([`m:${id}`]).catch(() => {});
}

/** Memory ids most related to `query`, best first. */
export async function relatedMemoryIds(
  env: Env,
  userId: string,
  query: string,
  k = 8,
  minScore = 0.55,
): Promise<string[]> {
  if (!env.VECTORS || !query.trim()) return [];
  try {
    const [v] = await embed(env, [query]);
    const res = await env.VECTORS.query(v!, { topK: k, filter: { userId, kind: "memory" } });
    // Below ~0.55 bge similarities are topical noise, not relevance.
    return res.matches.filter((m) => m.score >= minScore).map((m) => m.id.slice(2));
  } catch {
    return [];
  }
}
