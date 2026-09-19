/**
 * HomeLink — the edge side of the residual 5% (spec §7).
 *
 * An edge data plane cannot see 192.168.x. So one small container at home
 * dials OUTBOUND to this Durable Object and holds the socket open; the edge
 * then addresses LAN-only tools by writing down that pipe.
 *
 * Why outbound-only matters: no inbound ports, no port-forward, no tunnel for
 * the app itself, and nothing at home is reachable from the internet. The home
 * side initiates, and the blast radius of the whole mechanism is exactly the
 * allow-listed tool surface the agent chooses to expose.
 *
 * Degradation is deliberately narrow: if the agent is offline, LAN tools
 * return "home agent offline" and everything else — chat, voice, history,
 * memory — keeps working. That is the entire point of splitting the planes.
 */

import type { Env } from "./../types";
import { timingSafeEqual } from "./../auth";

interface PendingCall {
  resolve: (v: unknown) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

/** A LAN tool call should be fast; anything slower is a hung agent. */
const CALL_TIMEOUT_MS = 30_000;

export class HomeLink implements DurableObject {
  private agent: WebSocket | null = null;
  private tools: unknown[] = [];
  private pending = new Map<string, PendingCall>();
  private connectedAt = 0;

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    // The home agent connecting inbound-to-us.
    if (url.pathname.endsWith("/agent")) return this.acceptAgent(req);
    // The Worker asking us to invoke a tool.
    if (url.pathname.endsWith("/call")) return this.callTool(req);
    if (url.pathname.endsWith("/tools")) {
      return Response.json({ online: this.agent !== null, tools: this.tools });
    }
    return new Response("not found", { status: 404 });
  }

  private acceptAgent(req: Request): Response {
    const token = req.headers.get("x-home-agent-token") ?? "";
    if (!this.env.HOME_AGENT_TOKEN || !timingSafeEqual(token, this.env.HOME_AGENT_TOKEN)) {
      return new Response("unauthorized", { status: 401 });
    }
    if (req.headers.get("upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    server.accept();

    // Replace any previous connection. A reconnecting agent (container
    // restart, network blip) must not leave a dead socket installed, or every
    // subsequent call times out against a peer that will never answer.
    if (this.agent) {
      try {
        this.agent.close();
      } catch {
        /* already gone */
      }
    }
    this.agent = server;
    this.connectedAt = Date.now();

    server.addEventListener("message", (ev) => this.onAgentMessage(ev.data as string));
    server.addEventListener("close", () => {
      if (this.agent === server) this.agent = null;
      this.failAllPending("home agent disconnected");
    });

    return new Response(null, { status: 101, webSocket: client });
  }

  private onAgentMessage(raw: string): void {
    let msg: { t: string; id?: string; result?: unknown; error?: string; tools?: unknown[] };
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    // The agent advertises its allow-listed tool surface on connect.
    if (msg.t === "hello" && Array.isArray(msg.tools)) {
      this.tools = msg.tools;
      return;
    }

    if (msg.t === "result" && msg.id) {
      const p = this.pending.get(msg.id);
      if (!p) return; // already timed out
      this.pending.delete(msg.id);
      clearTimeout(p.timer);
      if (msg.error) p.reject(new Error(msg.error));
      else p.resolve(msg.result);
    }
  }

  private async callTool(req: Request): Promise<Response> {
    if (!this.agent) {
      return Response.json(
        { error: "home agent offline", online: false },
        { status: 503 },
      );
    }

    const body = (await req.json()) as { name: string; arguments: unknown };
    const id = crypto.randomUUID();

    const result = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`home tool ${body.name} timed out after ${CALL_TIMEOUT_MS}ms`));
      }, CALL_TIMEOUT_MS);
      this.pending.set(id, { resolve, reject, timer });
    });

    try {
      this.agent.send(JSON.stringify({ t: "call", id, name: body.name, arguments: body.arguments }));
    } catch (e) {
      this.pending.delete(id);
      return Response.json({ error: `send failed: ${(e as Error).message}` }, { status: 503 });
    }

    try {
      return Response.json({ result: await result });
    } catch (e) {
      return Response.json({ error: (e as Error).message }, { status: 504 });
    }
  }

  private failAllPending(reason: string): void {
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new Error(reason));
    }
    this.pending.clear();
  }
}
