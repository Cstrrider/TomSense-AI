/**
 * TomSense as an MCP SERVER (Streamable HTTP, stateless JSON responses).
 *
 * Lets other assistants — Claude, an IDE agent — use what TomSense knows:
 * search the user's documents and chats, and read or write memories. Same
 * surface as stable's mcp_server.py.
 *
 * Authentication is the ordinary device-token path (Settings → Connected
 * tools → "Create MCP token" mints a revocable device). Every tool is scoped
 * to the authenticated user; there is no cross-user operation here.
 */

import type { Env, Principal } from "./types";
import { addMemory, forgetMemory, listMemories } from "./memory";
import { searchDocs } from "./rag";

const TOOLS = [
  {
    name: "search_docs",
    description: "Search the user's uploaded TomSense documents. Returns relevant passages.",
    inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"] },
  },
  {
    name: "list_memories",
    description: "List the facts TomSense remembers about the user.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "remember",
    description: "Save a lasting fact about the user to TomSense memory.",
    inputSchema: { type: "object", properties: { fact: { type: "string" } }, required: ["fact"] },
  },
  {
    name: "forget",
    description: "Delete a remembered fact (by id or words from it).",
    inputSchema: { type: "object", properties: { what: { type: "string" } }, required: ["what"] },
  },
  {
    name: "search_chats",
    description: "Search the user's TomSense chat history for messages containing the query.",
    inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"] },
  },
  {
    name: "get_chat",
    description: "Read a TomSense conversation by id (as returned by search_chats).",
    inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"] },
  },
];

type Args = Record<string, unknown>;

async function callTool(env: Env, uid: string, name: string, a: Args): Promise<string> {
  const s = (k: string) => String(a[k] ?? "").trim();
  switch (name) {
    case "search_docs": {
      const hits = await searchDocs(env, uid, s("query"), 6);
      return hits.length ? hits.map((h, i) => `[${i + 1}] ${h.doc}\n${h.text}`).join("\n\n---\n\n") : "No matches.";
    }
    case "list_memories": {
      const ms = await listMemories(env, uid);
      return ms.length ? ms.map((m) => `${m.id}: ${m.text}${m.pinned ? " (pinned)" : ""}`).join("\n") : "No memories.";
    }
    case "remember":
      return (await addMemory(env, uid, s("fact"))) ? "Remembered." : "Already known.";
    case "forget": {
      const gone = await forgetMemory(env, uid, s("what"));
      return gone.length ? `Forgot: ${gone.join("; ")}` : "No match.";
    }
    case "search_chats": {
      const { results } = await env.DB.prepare(
        `SELECT m.conv_id, c.title, substr(m.content, 1, 300) AS snippet, m.created_at
           FROM messages m JOIN conversations c ON c.id = m.conv_id
          WHERE m.user_id = ? AND m.deleted = 0 AND c.deleted = 0 AND m.encrypted = 0 AND m.content LIKE ?
          ORDER BY m.created_at DESC LIMIT 15`,
      ).bind(uid, `%${s("query")}%`).all<{ conv_id: string; title: string; snippet: string; created_at: number }>();
      return (results ?? []).length
        ? (results ?? []).map((r) => `${r.conv_id} — ${r.title || "Untitled"} (${new Date(r.created_at).toISOString().slice(0, 10)})\n  ${r.snippet}`).join("\n")
        : "No matching messages.";
    }
    case "get_chat": {
      const { results } = await env.DB.prepare(
        `SELECT role, content FROM messages WHERE conv_id = ? AND user_id = ? AND deleted = 0 AND encrypted = 0 ORDER BY created_at LIMIT 200`,
      ).bind(s("id"), uid).all<{ role: string; content: string }>();
      return (results ?? []).map((r) => `${r.role.toUpperCase()}: ${r.content}`).join("\n\n").slice(0, 60_000) || "Not found.";
    }
  }
  throw new Error(`unknown tool ${name}`);
}

export async function handleMcpServer(req: Request, env: Env, who: Principal): Promise<Response> {
  if (req.method === "GET") return new Response("Method Not Allowed", { status: 405 });
  if (req.method === "DELETE") return new Response(null, { status: 204 });

  const msg = (await req.json().catch(() => null)) as { id?: unknown; method?: string; params?: Record<string, unknown> } | null;
  if (!msg?.method) return Response.json({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "invalid request" } }, { status: 400 });

  // Notifications get no response body.
  if (msg.id === undefined) return new Response(null, { status: 202 });

  const reply = (result: unknown) => Response.json({ jsonrpc: "2.0", id: msg.id, result });
  try {
    switch (msg.method) {
      case "initialize":
        return reply({
          protocolVersion: String(msg.params?.["protocolVersion"] ?? "2025-03-26"),
          capabilities: { tools: {} },
          serverInfo: { name: "tomsense", version: "1.0" },
        });
      case "ping":
        return reply({});
      case "tools/list":
        return reply({ tools: TOOLS });
      case "tools/call": {
        const name = String(msg.params?.["name"] ?? "");
        const text = await callTool(env, who.userId, name, (msg.params?.["arguments"] as Args) ?? {});
        return reply({ content: [{ type: "text", text }] });
      }
      default:
        return Response.json({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: `unknown method ${msg.method}` } });
    }
  } catch (e) {
    return reply({ content: [{ type: "text", text: (e as Error).message }], isError: true });
  }
}
