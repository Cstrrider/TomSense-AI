/**
 * Scheduled prompts: "every weekday at 7:30, summarise my calendar and the
 * weather", run by the edge whether or not the phone is awake.
 *
 * A due schedule goes through the SAME /chat pipeline as a typed message —
 * routing, persona, memories, server + home + MCP tools — by calling the chat
 * handler headlessly and reading its stream to the end. Only device tools are
 * missing, because there is no device in the loop; the model is told so.
 *
 * The result is written into the schedule's own conversation through the
 * sync writer (so it appears on every device) and a notification row is left
 * for the app's poller.
 *
 * Times are wall-clock in the user's IANA zone, so "8:00" stays 8:00 across a
 * DST change instead of drifting an hour.
 */

import type { ChatMessage, Env, Principal } from "./types";
import { push } from "./sync";

export interface ScheduleRow {
  id: string;
  user_id: string;
  title: string;
  prompt: string;
  kind: "daily" | "weekly" | "once";
  time_local: string;
  days: string;
  run_at: number | null;
  tz: string;
  conv_id: string | null;
  enabled: number;
  next_run_at: number | null;
  last_run_at: number | null;
  last_error: string | null;
  created_at: number;
}

/** Offset of `tz` from UTC at instant `ms`, in ms. */
function tzOffset(ms: number, tz: string): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).formatToParts(new Date(ms));
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second")) - ms;
}

/** The UTC instant of a wall-clock time in `tz`, DST-correct. */
function zoned(y: number, mo: number, d: number, h: number, mi: number, tz: string): number {
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  const first = guess - tzOffset(guess, tz);
  return guess - tzOffset(first, tz);
}

function localDate(ms: number, tz: string): { y: number; m: number; d: number; dow: number } {
  const p = new Intl.DateTimeFormat("en-US", {
    timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit", weekday: "short",
  }).formatToParts(new Date(ms));
  const get = (t: string) => p.find((x) => x.type === t)?.value ?? "";
  const dow = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(get("weekday")) + 1;
  return { y: Number(get("year")), m: Number(get("month")), d: Number(get("day")), dow };
}

export function computeNextRun(s: Pick<ScheduleRow, "kind" | "time_local" | "days" | "run_at" | "tz">, from: number): number | null {
  if (s.kind === "once") return s.run_at && s.run_at > from ? s.run_at : null;
  const tz = validTz(s.tz);
  const [hh, mm] = s.time_local.split(":").map(Number);
  const days = new Set(s.days.split(",").map((x) => Number(x.trim())).filter((n) => n >= 1 && n <= 7));
  for (let i = 0; i < 9; i++) {
    const { y, m, d, dow } = localDate(from + i * 86_400_000, tz);
    if (s.kind === "weekly" && days.size && !days.has(dow)) continue;
    const at = zoned(y, m, d, hh ?? 8, mm ?? 0, tz);
    if (at > from) return at;
  }
  return null;
}

function validTz(tz: string): string {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return tz;
  } catch {
    return "UTC";
  }
}

type ChatHandler = (req: Request, env: Env, who: Principal) => Promise<Response>;

/** Read a /chat SSE stream to its end and return the reply text. */
async function drain(res: Response): Promise<{ text: string; error?: string }> {
  if (!res.ok || !res.body) {
    const b = (await res.json().catch(() => ({}))) as { error?: string };
    return { text: "", error: b.error ?? `HTTP ${res.status}` };
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  let text = "";
  let error: string | undefined;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += value;
    let nl: number;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line.startsWith("data:")) continue;
      try {
        const ev = JSON.parse(line.slice(5)) as { type?: string; text?: string; status?: string; error?: string };
        if (ev.type === "text" && ev.text) text += ev.text;
        if (ev.type === "end" && ev.status === "error") error = ev.error ?? "run failed";
      } catch {
        // partial frame
      }
    }
  }
  return { text: text.trim(), error };
}

export async function runDueSchedules(env: Env, chat: ChatHandler): Promise<void> {
  const now = Date.now();
  const { results } = await env.DB.prepare(
    `SELECT * FROM schedules WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ? LIMIT 10`,
  ).bind(now).all<ScheduleRow>();

  for (const s of results ?? []) {
    const who: Principal = { userId: s.user_id, email: "", deviceId: "edge" };
    // Claim it first, so an overrun into the next tick cannot run it twice.
    const next = s.kind === "once" ? null : computeNextRun(s, now + 60_000);
    await env.DB.prepare(`UPDATE schedules SET next_run_at = ?, enabled = ?, last_run_at = ? WHERE id = ?`)
      .bind(next, s.kind === "once" ? 0 : 1, now, s.id).run();

    try {
      let convId = s.conv_id;
      const at = Date.now();
      if (!convId) {
        convId = crypto.randomUUID();
        await push(env, who, {
          deviceId: "edge",
          rows: [{ table: "conversations", row: { id: convId, title: `⏰ ${s.title}`, model: "", pinned: 0, created_at: at, updated_at: at, deleted: 0, lamport: at } }],
        });
        await env.DB.prepare(`UPDATE schedules SET conv_id = ? WHERE id = ?`).bind(convId, s.id).run();
      }

      const local = new Intl.DateTimeFormat("en-US", {
        timeZone: validTz(s.tz), dateStyle: "full", timeStyle: "short",
      }).format(new Date(at));
      const messages: ChatMessage[] = [
        {
          role: "system",
          content:
            `You are TomSense, the user's assistant, running a scheduled task named "${s.title}". ` +
            `Current local time: ${local} (${s.tz}). No device is connected, so phone tools (calendar, ` +
            `location, alarms) are unavailable — use web search, weather and the other available tools. ` +
            `Write the result to be read later as a notification and a chat message: lead with what matters.`,
        } as ChatMessage,
        { role: "user", content: s.prompt } as ChatMessage,
      ];
      const res = await chat(
        new Request("https://edge/chat", {
          method: "POST",
          body: JSON.stringify({ conversationId: convId, messages, tools: [] }),
        }),
        env,
        who,
      );
      const { text, error } = await drain(res);
      if (!text) throw new Error(error ?? "empty reply");

      const t = Date.now();
      await push(env, who, {
        deviceId: "edge",
        rows: [
          { table: "messages", row: { id: crypto.randomUUID(), conv_id: convId, role: "user", content: s.prompt, encrypted: 0, created_at: t - 1, deleted: 0, lamport: t - 1 } },
          { table: "messages", row: { id: crypto.randomUUID(), conv_id: convId, role: "assistant", content: text, encrypted: 0, created_at: t, deleted: 0, lamport: t } },
        ],
      });
      // Bump the conversation so it sorts to the top of the chat list.
      const conv = await env.DB.prepare(`SELECT * FROM conversations WHERE id = ?`).bind(convId).first<Record<string, unknown>>();
      if (conv) {
        delete conv["seq"];
        await push(env, who, { deviceId: "edge", rows: [{ table: "conversations", row: { ...conv, updated_at: t, lamport: Math.max(t, Number(conv["lamport"]) + 1) } }] });
      }
      await env.DB.prepare(
        `INSERT INTO notifications (id, user_id, title, body, conv_id, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
      ).bind(crypto.randomUUID(), s.user_id, s.title, text.replace(/[#*_`>]/g, "").slice(0, 300), convId, t).run();
      await env.DB.prepare(`UPDATE schedules SET last_error = NULL WHERE id = ?`).bind(s.id).run();
    } catch (e) {
      await env.DB.prepare(`UPDATE schedules SET last_error = ? WHERE id = ?`).bind((e as Error).message.slice(0, 300), s.id).run();
    }
  }
}
