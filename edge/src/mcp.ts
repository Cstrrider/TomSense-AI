/**
 * MCP client: tools from remote MCP servers the user has connected.
 *
 * Streamable HTTP transport only — the one a Worker can speak (no stdio, no
 * long-lived SSE GET). Each server's tools are listed once per turn, merged
 * into the model's tool list under a namespaced name, and executed from the
 * run DO when called. Listing per turn rather than caching matches how home
 * tools work: a server can add, drop or rename tools at any time.
 *
 * Failures are contained per server: one unreachable server loses its own
 * tools for that turn and nothing else.
 */

import type { Env } from "./types";
import { decryptKey } from "./crypto";

export interface McpServerRow {
  id: string;
  name: string;
  url: string;
  auth_enc: string;
}

/** A remote tool as the model sees it, plus how to route a call back. */
export interface McpToolRef {
  name: string; // namespaced, what the model calls
  serverId: string;
  tool: string; // the server's own name
}

interface RpcSession {
  url: string;
  headers: Record<string, string>;
  sessionId?: string;
  nextId: number;
}

async function rpc(s: RpcSession, method: string, params?: unknown, notify = false): Promise<unknown> {
  const body: Record<string, unknown> = { jsonrpc: "2.0", method };
  if (params !== undefined) body["params"] = params;
  if (!notify) body["id"] = s.nextId++;

  const res = await fetch(s.url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
      ...(s.sessionId ? { "mcp-session-id": s.sessionId } : {}),
      ...s.headers,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(20_000),
  });
  const sid = res.headers.get("mcp-session-id");
  if (sid) s.sessionId = sid;
  if (notify) return null;
  if (!res.ok) throw new Error(`HTTP ${res.status}`);

  const type = res.headers.get("content-type") ?? "";
  let msg: { result?: unknown; error?: { message?: string } } | undefined;
  if (type.includes("text/event-stream")) {
    // The response to OUR request is one of the data: frames; others can be
    // server notifications, so match on having a result or an error.
    const text = await res.text();
    for (const line of text.split("\n")) {
      if (!line.startsWith("data:")) continue;
      try {
        const m = JSON.parse(line.slice(5).trim());
        if (m && (m.result !== undefined || m.error)) msg = m;
      } catch {
        // keep scanning
      }
    }
  } else {
    msg = (await res.json()) as typeof msg;
  }
  if (!msg) throw new Error("empty response");
  if (msg.error) throw new Error(msg.error.message ?? "MCP error");
  return msg.result;
}

async function open(env: Env, server: McpServerRow): Promise<RpcSession> {
  const token = server.auth_enc ? await decryptKey(env, server.auth_enc) : "";
  const s: RpcSession = {
    url: server.url,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    nextId: 1,
  };
  await rpc(s, "initialize", {
    protocolVersion: "2025-03-26",
    capabilities: {},
    clientInfo: { name: "tomsense", version: "1.0" },
  });
  await rpc(s, "notifications/initialized", undefined, true).catch(() => null);
  return s;
}

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 20) || "server";
}

export async function listServerTools(
  env: Env,
  server: McpServerRow,
): Promise<{ name: string; description?: string; inputSchema?: unknown }[]> {
  const s = await open(env, server);
  const r = (await rpc(s, "tools/list", {})) as { tools?: { name: string; description?: string; inputSchema?: unknown }[] };
  return r.tools ?? [];
}

/** Every enabled server's tools, as OpenAI-style schemas plus routing refs. */
export async function mcpToolSurface(
  env: Env,
  userId: string,
): Promise<{ schemas: Record<string, unknown>[]; refs: McpToolRef[] }> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, url, auth_enc FROM mcp_servers WHERE user_id = ? AND enabled = 1`,
  )
    .bind(userId)
    .all<McpServerRow>();
  const schemas: Record<string, unknown>[] = [];
  const refs: McpToolRef[] = [];

  await Promise.all(
    (results ?? []).map(async (server) => {
      try {
        const tools = await listServerTools(env, server);
        for (const t of tools.slice(0, 40)) {
          const name = `mcp_${slug(server.name)}__${t.name}`.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 64);
          refs.push({ name, serverId: server.id, tool: t.name });
          schemas.push({
            type: "function",
            function: {
              name,
              description: `[${server.name}] ${t.description ?? t.name}`.slice(0, 1_000),
              parameters: (t.inputSchema as Record<string, unknown>) ?? { type: "object", properties: {} },
            },
          });
        }
      } catch {
        // Unreachable this turn: its tools are simply not offered.
      }
    }),
  );
  return { schemas, refs };
}

export async function callMcpTool(
  env: Env,
  userId: string,
  ref: McpToolRef,
  args: Record<string, unknown>,
): Promise<{ content: string; error?: string }> {
  const server = await env.DB.prepare(
    `SELECT id, name, url, auth_enc FROM mcp_servers WHERE id = ? AND user_id = ?`,
  )
    .bind(ref.serverId, userId)
    .first<McpServerRow>();
  if (!server) return { content: "That MCP server is no longer connected.", error: `${ref.name}: server removed` };
  try {
    const s = await open(env, server);
    const r = (await rpc(s, "tools/call", { name: ref.tool, arguments: args })) as {
      content?: { type: string; text?: string }[];
      isError?: boolean;
    };
    const text = (r.content ?? [])
      .map((c) => (c.type === "text" ? c.text ?? "" : `[${c.type} content]`))
      .join("\n")
      .slice(0, 30_000);
    return r.isError ? { content: text || "tool error", error: `${server.name}: ${text.slice(0, 120)}` } : { content: text };
  } catch (e) {
    const why = (e as Error).message;
    return { content: JSON.stringify({ error: why }), error: `${server.name}: ${why}` };
  }
}
