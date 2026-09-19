/**
 * Sync — local SQLite is the source of truth, D1 is a convergence target.
 *
 * This is the piece that makes local-first real (spec §5). The phone never
 * blocks a render on the network; it writes locally, then pushes batches here
 * and pulls whatever other devices wrote.
 *
 * Conflict resolution: last-writer-wins on `lamport`, ties broken by
 * `device_id`. That is weak in general, but adequate HERE because message
 * bodies are append-only in practice — real conflicts are confined to edits,
 * titles, and settings, where LWW is what a user expects anyway.
 *
 * The pull cursor is a server-assigned `seq`, never a wall clock. Device
 * clocks are untrusted and skew freely; a cursor that can move backwards
 * silently drops rows, and the failure is invisible until someone notices
 * missing history.
 */

import type { Env, Principal } from "./types";

/** Tables that participate in sync, and their syncable columns. */
const SYNCABLE = {
  conversations: [
    "id", "user_id", "project_id", "title", "model",
    "created_at", "updated_at", "deleted", "lamport", "device_id",
  ],
  messages: [
    "id", "conv_id", "user_id", "role", "content", "encrypted", "reasoning",
    "tool_calls", "attachments", "usage", "created_at", "deleted",
    "lamport", "device_id",
  ],
  projects: [
    "id", "user_id", "name", "instructions", "e2ee",
    "created_at", "updated_at", "deleted", "lamport", "device_id",
  ],
  memories: [
    "id", "user_id", "text", "source_msg_id", "confidence", "last_used_at",
    "use_count", "pinned", "created_at", "deleted", "lamport", "device_id",
  ],
} as const;

export type SyncTable = keyof typeof SYNCABLE;

export interface PushRow {
  table: SyncTable;
  row: Record<string, unknown>;
}

export interface PushRequest {
  deviceId: string;
  rows: PushRow[];
}

export interface PullResponse {
  cursor: number;
  rows: { table: SyncTable; row: Record<string, unknown> }[];
  /** True when the page was truncated — the client should pull again. */
  more: boolean;
}

const PULL_PAGE = 500;

/** Reserve `n` sequence numbers. D1 is single-threaded per db, so no locking. */
async function reserveSeq(env: Env, n: number): Promise<number> {
  const row = await env.DB.prepare(
    `UPDATE seq_counter SET next = next + ? WHERE id = 0 RETURNING next`,
  )
    .bind(n)
    .first<{ next: number }>();
  if (!row) throw new Error("seq_counter row missing — migration not applied?");
  return row.next - n; // first reserved value
}

/**
 * Apply a batch of client writes.
 *
 * Every statement is an upsert guarded by the LWW predicate in its WHERE
 * clause, so an out-of-order or replayed push is a no-op rather than a
 * regression. This is why the whole batch can go through `D1.batch()` without
 * read-modify-write round trips: the database decides the winner, not us.
 */
export async function push(
  env: Env,
  who: Principal,
  req: PushRequest,
): Promise<{ applied: number; cursor: number }> {
  if (!req.rows.length) {
    const cur = await currentSeq(env);
    return { applied: 0, cursor: cur };
  }

  const base = await reserveSeq(env, req.rows.length);
  const stmts: D1PreparedStatement[] = [];

  req.rows.forEach((entry, i) => {
    const cols = SYNCABLE[entry.table] as readonly string[] | undefined;
    if (!cols) throw new Error(`unknown sync table: ${entry.table}`);

    // Force ownership from the authenticated principal. Trusting a client-
    // supplied user_id here would let any device write into another account.
    const row: Record<string, unknown> = { ...entry.row, user_id: who.userId };
    row["device_id"] = req.deviceId;
    row["seq"] = base + i;

    const names = [...cols, "seq"];
    const values = names.map((c) => row[c] ?? null);
    const placeholders = names.map(() => "?").join(", ");
    const updates = names
      .filter((c) => c !== "id" && c !== "user_id")
      .map((c) => `${c} = excluded.${c}`)
      .join(", ");

    stmts.push(
      env.DB.prepare(
        `INSERT INTO ${entry.table} (${names.join(", ")})
         VALUES (${placeholders})
         ON CONFLICT(id) DO UPDATE SET ${updates}
         WHERE excluded.lamport > ${entry.table}.lamport
            OR (excluded.lamport = ${entry.table}.lamport
                AND excluded.device_id > ${entry.table}.device_id)`,
      ).bind(...values),
    );
  });

  // Record the device's high-water mark so replays are cheap to detect.
  const maxLamport = Math.max(
    ...req.rows.map((r) => Number(r.row["lamport"] ?? 0)),
    0,
  );
  stmts.push(
    env.DB.prepare(
      `UPDATE devices SET last_lamport = MAX(last_lamport, ?), last_seen_at = ?
        WHERE id = ? AND user_id = ?`,
    ).bind(maxLamport, Date.now(), req.deviceId, who.userId),
  );

  await env.DB.batch(stmts);
  return { applied: req.rows.length, cursor: base + req.rows.length - 1 };
}

async function currentSeq(env: Env): Promise<number> {
  const row = await env.DB.prepare(`SELECT next FROM seq_counter WHERE id = 0`).first<{
    next: number;
  }>();
  return (row?.next ?? 1) - 1;
}

/**
 * Pull everything this user has with `seq > cursor`.
 *
 * Rows authored by the requesting device are INCLUDED rather than filtered
 * out. It costs a little bandwidth and removes a whole class of bug: after a
 * reinstall or a restore from backup, a device needs its own history back,
 * and "exclude my own writes" makes that unrecoverable.
 */
export async function pull(
  env: Env,
  who: Principal,
  cursor: number,
): Promise<PullResponse> {
  const collected: { table: SyncTable; row: Record<string, unknown>; seq: number }[] = [];
  let maxSeq = cursor;
  let more = false;
  // Lowest seq at which some table was cut off. Rows above this are dropped
  // from the page — see the truncation note below.
  let truncatedAt = Number.POSITIVE_INFINITY;

  for (const table of Object.keys(SYNCABLE) as SyncTable[]) {
    const res = await env.DB.prepare(
      `SELECT * FROM ${table} WHERE user_id = ? AND seq > ?
        ORDER BY seq LIMIT ?`,
    )
      .bind(who.userId, cursor, PULL_PAGE + 1)
      .all<Record<string, unknown>>();

    const rows = res.results ?? [];
    if (rows.length > PULL_PAGE) {
      more = true;
      rows.length = PULL_PAGE;
      const last = rows[PULL_PAGE - 1];
      const lastSeq = Number(last?.["seq"] ?? 0);
      if (lastSeq < truncatedAt) truncatedAt = lastSeq;
    }
    for (const row of rows) {
      const s = Number(row["seq"] ?? 0);
      collected.push({ table, row, seq: s });
      if (s > maxSeq) maxSeq = s;
    }
  }

  // Truncation is per-table, but the cursor is global. If `messages` was cut
  // off at seq 900 while `memories` returned rows up to 1200, advancing the
  // cursor to 1200 would permanently skip messages 901–1200: the next pull
  // asks for seq > 1200 and those rows are never sent again.
  //
  // So the page only advances as far as the LOWEST truncation point, and any
  // row above it is dropped from this response and re-sent next round.
  if (more) {
    const safe = truncatedAt;
    return {
      cursor: safe,
      rows: collected.filter((c) => c.seq <= safe).map(({ table, row }) => ({ table, row })),
      more: true,
    };
  }

  return {
    cursor: maxSeq,
    rows: collected.map(({ table, row }) => ({ table, row })),
    more: false,
  };
}
