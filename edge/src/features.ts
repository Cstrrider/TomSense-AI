/**
 * REST surface for the features Settings manages: memory, personas, projects,
 * starters, follow-ups, secrets, schedules, notifications, MCP servers,
 * artifacts and documents.
 *
 * One router rather than a branch per path in index.ts: these are ordinary
 * CRUD over user-owned rows, and every query here is scoped by user_id. That
 * scoping is the security property of the whole file — none of these tables
 * is ever read without it.
 */

import type { Env, Principal } from "./types";
import { addMemory, getProfile, listMemories, setProfile, updateMemory } from "./memory";
import { DEFAULT_STARTERS, getPrefs, setPrefs } from "./prefs";
import { runTaskModel } from "./task_model";
import { listSecrets, setSecret } from "./secrets";
import { deleteDocument, indexDocument } from "./rag";
import { encryptKey } from "./crypto";
import { listServerTools, type McpServerRow } from "./mcp";
import { push } from "./sync";
import { computeNextRun, type ScheduleRow } from "./schedules";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });
}

async function body<T>(req: Request): Promise<T> {
  return (await req.json().catch(() => ({}))) as T;
}

const id8 = () => crypto.randomUUID();

export async function handleFeatures(
  req: Request,
  env: Env,
  who: Principal,
  ctx: ExecutionContext,
): Promise<Response | null> {
  const url = new URL(req.url);
  const path = url.pathname;
  const m = req.method;
  const uid = who.userId;
  const seg = path.split("/").filter(Boolean);

  // ─── memory ───────────────────────────────────────────────────────────
  if (path === "/me/profile") {
    if (m === "GET") return json({ text: await getProfile(env, uid) });
    if (m === "PUT") {
      await setProfile(env, uid, (await body<{ text?: string }>(req)).text ?? "");
      return json({ ok: true });
    }
  }
  if (path === "/memories") {
    if (m === "GET") return json({ memories: await listMemories(env, uid), auto: (await getPrefs(env, uid)).auto_memory });
    if (m === "POST") {
      const b = await body<{ text?: string; pinned?: boolean }>(req);
      const row = await addMemory(env, uid, b.text ?? "", { pinned: b.pinned });
      return json(row ?? { error: "empty or duplicate" }, row ? 200 : 400);
    }
  }
  if (seg[0] === "memories" && seg[1]) {
    if (m === "DELETE") return json({ ok: await updateMemory(env, uid, seg[1], { deleted: true }) });
    if (m === "PATCH" || m === "PUT") {
      const b = await body<{ text?: string; pinned?: boolean }>(req);
      return json({ ok: await updateMemory(env, uid, seg[1], b) });
    }
  }

  // ─── personas ─────────────────────────────────────────────────────────
  if (path === "/personas") {
    if (m === "GET") {
      const { results } = await env.DB.prepare(
        `SELECT id, name, prompt, updated_at FROM personas WHERE user_id = ? ORDER BY name`,
      ).bind(uid).all();
      return json({ personas: results ?? [], active: (await getPrefs(env, uid)).persona_id });
    }
    if (m === "POST") {
      const b = await body<{ name?: string; prompt?: string }>(req);
      if (!b.name?.trim() || !b.prompt?.trim()) return json({ error: "name and prompt are required" }, 400);
      const id = id8();
      const now = Date.now();
      await env.DB.prepare(`INSERT INTO personas (id, user_id, name, prompt, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)`)
        .bind(id, uid, b.name.trim(), b.prompt.trim(), now, now).run();
      return json({ id });
    }
  }
  if (path === "/personas/active" && m === "PUT") {
    const b = await body<{ id?: string }>(req);
    await setPrefs(env, who, { persona_id: b.id ?? "" });
    return json({ ok: true });
  }
  if (seg[0] === "personas" && seg[1] && seg[1] !== "active") {
    if (m === "PUT") {
      const b = await body<{ name?: string; prompt?: string }>(req);
      await env.DB.prepare(
        `UPDATE personas SET name = COALESCE(?, name), prompt = COALESCE(?, prompt), updated_at = ? WHERE id = ? AND user_id = ?`,
      ).bind(b.name?.trim() || null, b.prompt?.trim() || null, Date.now(), seg[1], uid).run();
      return json({ ok: true });
    }
    if (m === "DELETE") {
      await env.DB.prepare(`DELETE FROM personas WHERE id = ? AND user_id = ?`).bind(seg[1], uid).run();
      const prefs = await getPrefs(env, uid);
      if (prefs.persona_id === seg[1]) await setPrefs(env, who, { persona_id: "" });
      return json({ ok: true });
    }
  }

  // ─── projects (a sync table, so writes go through the sync writer) ──────
  if (path === "/projects") {
    if (m === "GET") {
      const { results } = await env.DB.prepare(
        `SELECT id, name, instructions, updated_at FROM projects WHERE user_id = ? AND deleted = 0 ORDER BY name`,
      ).bind(uid).all();
      return json({ projects: results ?? [] });
    }
    if (m === "POST") {
      const b = await body<{ name?: string; instructions?: string }>(req);
      if (!b.name?.trim()) return json({ error: "name is required" }, 400);
      const now = Date.now();
      const id = id8();
      await push(env, { ...who, deviceId: "edge" }, {
        deviceId: "edge",
        rows: [{ table: "projects", row: { id, name: b.name.trim(), instructions: b.instructions ?? "", e2ee: 0, created_at: now, updated_at: now, deleted: 0, lamport: now } }],
      });
      return json({ id });
    }
  }
  if (seg[0] === "projects" && seg[1] && (m === "PUT" || m === "DELETE")) {
    const cur = await env.DB.prepare(`SELECT * FROM projects WHERE id = ? AND user_id = ?`).bind(seg[1], uid).first<Record<string, unknown>>();
    if (!cur) return json({ error: "not found" }, 404);
    const b = m === "PUT" ? await body<{ name?: string; instructions?: string }>(req) : {};
    const now = Date.now();
    const row = {
      ...cur,
      name: b.name?.trim() || cur["name"],
      instructions: b.instructions ?? cur["instructions"],
      deleted: m === "DELETE" ? 1 : 0,
      updated_at: now,
      lamport: Math.max(now, Number(cur["lamport"] ?? 0) + 1),
    };
    delete (row as Record<string, unknown>)["seq"];
    await push(env, { ...who, deviceId: "edge" }, { deviceId: "edge", rows: [{ table: "projects", row }] });
    return json({ ok: true });
  }

  // ─── starters & follow-ups ────────────────────────────────────────────
  if (path === "/starters") {
    if (m === "GET") {
      const p = await getPrefs(env, uid);
      return json({ starters: p.starters.length ? p.starters : DEFAULT_STARTERS, custom: p.starters.length > 0 });
    }
    if (m === "PUT") {
      const b = await body<{ starters?: string[] }>(req);
      const p = await setPrefs(env, who, { starters: b.starters ?? [] });
      return json({ starters: p.starters.length ? p.starters : DEFAULT_STARTERS, custom: p.starters.length > 0 });
    }
  }
  if (path === "/followups" && m === "POST") {
    const b = await body<{ question?: string; answer?: string }>(req);
    const prefs = await getPrefs(env, uid);
    const out = await runTaskModel(env, who, {
      purpose: "followups",
      prompt:
        "Suggest 3 short follow-up messages the USER might send next, in their voice (under 8 words each). " +
        "One per line, no numbering, no quotes.\n\nUser asked:\n" + (b.question ?? "").slice(0, 1_500) +
        "\n\nAssistant answered:\n" + (b.answer ?? "").slice(0, 3_000),
      slots: prefs.tool_models,
      maxTokens: 70,
    });
    const suggestions = (out ?? "")
      .split("\n")
      .map((l) => l.replace(/^[-*•\d.)\s"]+|"$/g, "").trim())
      .filter((l) => l.length >= 3 && l.length <= 80)
      .slice(0, 3);
    return json({ suggestions });
  }

  // ─── secrets ──────────────────────────────────────────────────────────
  if (path === "/secrets" && m === "GET") return json({ secrets: await listSecrets(env, uid) });
  if (seg[0] === "secrets" && seg[1] && (m === "PUT" || m === "DELETE")) {
    const value = m === "PUT" ? (await body<{ value?: string }>(req)).value ?? "" : "";
    await setSecret(env, uid, decodeURIComponent(seg[1]), value);
    return json({ ok: true });
  }

  // ─── schedules ────────────────────────────────────────────────────────
  if (path === "/schedules") {
    if (m === "GET") {
      const { results } = await env.DB.prepare(`SELECT * FROM schedules WHERE user_id = ? ORDER BY created_at`).bind(uid).all();
      return json({ schedules: results ?? [] });
    }
    if (m === "POST") {
      const b = await body<Partial<ScheduleRow>>(req);
      if (!b.prompt?.trim()) return json({ error: "prompt is required" }, 400);
      const row: ScheduleRow = {
        id: id8(),
        user_id: uid,
        title: b.title?.trim() || b.prompt.trim().slice(0, 40),
        prompt: b.prompt.trim(),
        kind: b.kind === "weekly" || b.kind === "once" ? b.kind : "daily",
        time_local: /^\d{2}:\d{2}$/.test(b.time_local ?? "") ? b.time_local! : "08:00",
        days: b.days ?? "",
        run_at: b.run_at ?? null,
        tz: b.tz || "UTC",
        conv_id: null,
        enabled: 1,
        next_run_at: null,
        last_run_at: null,
        last_error: null,
        created_at: Date.now(),
      };
      row.next_run_at = computeNextRun(row, Date.now());
      await env.DB.prepare(
        `INSERT INTO schedules (id, user_id, title, prompt, kind, time_local, days, run_at, tz, enabled, next_run_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      ).bind(row.id, uid, row.title, row.prompt, row.kind, row.time_local, row.days, row.run_at, row.tz, row.next_run_at, row.created_at).run();
      return json(row);
    }
  }
  if (seg[0] === "schedules" && seg[1]) {
    const cur = await env.DB.prepare(`SELECT * FROM schedules WHERE id = ? AND user_id = ?`).bind(seg[1], uid).first<ScheduleRow>();
    if (!cur) return json({ error: "not found" }, 404);
    if (m === "DELETE") {
      await env.DB.prepare(`DELETE FROM schedules WHERE id = ? AND user_id = ?`).bind(seg[1], uid).run();
      return json({ ok: true });
    }
    if (seg[2] === "run" && m === "POST") {
      // "Run now": due immediately; the next cron tick picks it up.
      await env.DB.prepare(`UPDATE schedules SET next_run_at = ?, enabled = 1 WHERE id = ?`).bind(Date.now() - 1, seg[1]).run();
      return json({ ok: true });
    }
    if (m === "PUT") {
      const b = await body<Partial<ScheduleRow>>(req);
      const next: ScheduleRow = {
        ...cur,
        title: b.title?.trim() || cur.title,
        prompt: b.prompt?.trim() || cur.prompt,
        kind: b.kind ?? cur.kind,
        time_local: b.time_local ?? cur.time_local,
        days: b.days ?? cur.days,
        run_at: b.run_at ?? cur.run_at,
        tz: b.tz ?? cur.tz,
        enabled: b.enabled === undefined ? cur.enabled : b.enabled ? 1 : 0,
      };
      next.next_run_at = next.enabled ? computeNextRun(next, Date.now()) : null;
      await env.DB.prepare(
        `UPDATE schedules SET title=?, prompt=?, kind=?, time_local=?, days=?, run_at=?, tz=?, enabled=?, next_run_at=? WHERE id=? AND user_id=?`,
      ).bind(next.title, next.prompt, next.kind, next.time_local, next.days, next.run_at, next.tz, next.enabled, next.next_run_at, cur.id, uid).run();
      return json(next);
    }
  }

  // ─── notifications (polled by the app) ────────────────────────────────
  if (path === "/notifications" && m === "GET") {
    const { results } = await env.DB.prepare(
      `SELECT id, title, body, conv_id, created_at FROM notifications
        WHERE user_id = ? AND delivered = 0 ORDER BY created_at LIMIT 20`,
    ).bind(uid).all<{ id: string }>();
    const rows = results ?? [];
    if (rows.length) {
      await env.DB.prepare(
        `UPDATE notifications SET delivered = 1 WHERE user_id = ? AND id IN (${rows.map(() => "?").join(",")})`,
      ).bind(uid, ...rows.map((r) => r.id)).run();
    }
    return json({ notifications: rows });
  }

  // ─── MCP servers ──────────────────────────────────────────────────────
  if (path === "/mcp/servers") {
    if (m === "GET") {
      const { results } = await env.DB.prepare(
        `SELECT id, name, url, enabled, auth_enc != '' AS has_auth, created_at FROM mcp_servers WHERE user_id = ? ORDER BY name`,
      ).bind(uid).all();
      return json({ servers: results ?? [] });
    }
    if (m === "POST") {
      const b = await body<{ name?: string; url?: string; token?: string }>(req);
      let u: URL;
      try {
        u = new URL(b.url ?? "");
      } catch {
        return json({ error: "a valid https URL is required" }, 400);
      }
      if (u.protocol !== "https:") return json({ error: "MCP servers must use https" }, 400);
      const row: McpServerRow = { id: id8(), name: b.name?.trim() || u.hostname, url: u.toString(), auth_enc: await encryptKey(env, b.token?.trim() ?? "") };
      // Proved reachable BEFORE saving: a server that cannot list its tools
      // would otherwise sit in Settings looking connected and do nothing.
      let tools: { name: string }[];
      try {
        tools = await listServerTools(env, row);
      } catch (e) {
        return json({ error: `Couldn't connect: ${(e as Error).message}` }, 400);
      }
      await env.DB.prepare(`INSERT INTO mcp_servers (id, user_id, name, url, auth_enc, enabled, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)`)
        .bind(row.id, uid, row.name, row.url, row.auth_enc, Date.now()).run();
      return json({ id: row.id, tools: tools.map((t) => t.name) });
    }
  }
  if (seg[0] === "mcp" && seg[1] === "servers" && seg[2]) {
    const sid = seg[2];
    if (m === "DELETE") {
      await env.DB.prepare(`DELETE FROM mcp_servers WHERE id = ? AND user_id = ?`).bind(sid, uid).run();
      return json({ ok: true });
    }
    if (m === "PUT") {
      const b = await body<{ enabled?: boolean }>(req);
      await env.DB.prepare(`UPDATE mcp_servers SET enabled = ? WHERE id = ? AND user_id = ?`).bind(b.enabled ? 1 : 0, sid, uid).run();
      return json({ ok: true });
    }
    if (seg[3] === "tools" && m === "GET") {
      const row = await env.DB.prepare(`SELECT id, name, url, auth_enc FROM mcp_servers WHERE id = ? AND user_id = ?`).bind(sid, uid).first<McpServerRow>();
      if (!row) return json({ error: "not found" }, 404);
      try {
        return json({ tools: (await listServerTools(env, row)).map((t) => ({ name: t.name, description: t.description ?? "" })) });
      } catch (e) {
        return json({ error: (e as Error).message }, 502);
      }
    }
  }
  // A token for OUR MCP server (/mcp), for connecting TomSense to Claude and
  // other MCP clients. It is a device token — revocable like any device.
  if (path === "/mcp/token" && m === "POST") {
    const { issueDeviceToken } = await import("./auth");
    const deviceId = id8();
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO devices (id, user_id, name, platform, created_at, last_seen_at) VALUES (?, ?, 'MCP client', 'mcp', ?, ?)`,
    ).bind(deviceId, uid, now, now).run();
    const token = await issueDeviceToken(env, uid, deviceId);
    return json({ url: `${url.origin}/mcp`, token });
  }

  // ─── artifacts ────────────────────────────────────────────────────────
  if (path === "/artifacts" && m === "GET") {
    const conv = url.searchParams.get("conv");
    const q = conv
      ? env.DB.prepare(`SELECT id, conv_id, title, kind, language, version, updated_at FROM artifacts WHERE user_id = ? AND conv_id = ? ORDER BY updated_at DESC`).bind(uid, conv)
      : env.DB.prepare(`SELECT id, conv_id, title, kind, language, version, updated_at FROM artifacts WHERE user_id = ? ORDER BY updated_at DESC LIMIT 100`).bind(uid);
    return json({ artifacts: (await q.all()).results ?? [] });
  }
  if (seg[0] === "artifacts" && seg[1]) {
    if (m === "GET") {
      const a = await env.DB.prepare(`SELECT * FROM artifacts WHERE id = ? AND user_id = ?`).bind(seg[1], uid).first();
      return a ? json(a) : json({ error: "not found" }, 404);
    }
    if (m === "DELETE") {
      await env.DB.prepare(`DELETE FROM artifacts WHERE id = ? AND user_id = ?`).bind(seg[1], uid).run();
      return json({ ok: true });
    }
  }

  // ─── documents ────────────────────────────────────────────────────────
  if (path === "/documents") {
    if (m === "GET") {
      const { results } = await env.DB.prepare(
        `SELECT id, name, mime, size, status, chunks, error, created_at FROM documents WHERE user_id = ? ORDER BY created_at DESC`,
      ).bind(uid).all();
      return json({ documents: results ?? [], vector: Boolean(env.VECTORS) });
    }
    if (m === "POST") {
      const name = (url.searchParams.get("name") || req.headers.get("x-file-name") || "document").replace(/[/\\]/g, "_").slice(0, 150);
      const mime = req.headers.get("content-type") || "application/octet-stream";
      const bytes = await req.arrayBuffer();
      if (bytes.byteLength > 25 * 1024 * 1024) return json({ error: "documents are limited to 25 MB" }, 413);
      const id = id8();
      const key = `u/${uid}/docs/${id}/${name}`;
      await env.FILES.put(key, bytes, { httpMetadata: { contentType: mime } });
      await env.DB.prepare(
        `INSERT INTO documents (id, user_id, name, mime, size, r2_key, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'indexing', ?)`,
      ).bind(id, uid, name, mime, bytes.byteLength, key, Date.now()).run();
      // Indexing (text extraction + embedding) can take a while for a long
      // PDF; the upload returns now and the list shows its status.
      ctx.waitUntil(indexDocument(env, uid, id));
      return json({ id, status: "indexing" });
    }
  }
  if (seg[0] === "documents" && seg[1] && m === "DELETE") {
    await deleteDocument(env, uid, seg[1]);
    return json({ ok: true });
  }

  return null;
}
