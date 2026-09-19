/**
 * DetachedRun — a chat generation that outlives the client connection.
 *
 * This already exists on main (`liverun.py`, reconnect by `run_id`), but there
 * it lives in the FastAPI process: a backend restart loses every in-flight
 * run, and the phone must stay connected for the reply to be produced.
 *
 * As a Durable Object it gets strictly better. The run is anchored to durable
 * storage, survives eviction, and the phone can close the socket, sleep, or
 * change networks and still reattach by run_id — which is the behaviour you
 * actually want when a frontier model takes 90 seconds.
 */

import type { Env, StreamEvent } from "./../types";

interface RunRecord {
  id: string;
  userId: string;
  convId: string;
  status: "running" | "done" | "error" | "cancelled";
  /** Everything emitted so far, so a late subscriber can be caught up. */
  events: StreamEvent[];
  error?: string;
}

export class DetachedRun implements DurableObject {
  private subscribers = new Set<WebSocket>();
  private record: RunRecord | null = null;

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    if (url.pathname.endsWith("/attach")) return this.attach(req);
    if (url.pathname.endsWith("/cancel")) return this.cancel();
    if (url.pathname.endsWith("/start")) return this.start(req);

    return new Response("not found", { status: 404 });
  }

  private async load(): Promise<RunRecord | null> {
    if (this.record) return this.record;
    this.record = (await this.ctx.storage.get<RunRecord>("record")) ?? null;
    return this.record;
  }

  private async persist(): Promise<void> {
    if (this.record) await this.ctx.storage.put("record", this.record);
  }

  private async start(req: Request): Promise<Response> {
    const body = (await req.json()) as { id: string; userId: string; convId: string };
    this.record = {
      id: body.id,
      userId: body.userId,
      convId: body.convId,
      status: "running",
      events: [],
    };
    await this.persist();
    return Response.json({ ok: true, runId: body.id });
  }

  /**
   * Attach a subscriber. Replays everything already emitted BEFORE streaming
   * live events — without the replay, a client that reconnects mid-run sees
   * the tail of a sentence and no way to recover the beginning.
   */
  private async attach(req: Request): Promise<Response> {
    const rec = await this.load();
    if (!rec) return new Response("no such run", { status: 404 });

    if (req.headers.get("upgrade") !== "websocket") {
      return Response.json({ status: rec.status, events: rec.events });
    }

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    server.accept();

    for (const ev of rec.events) server.send(JSON.stringify(ev));

    if (rec.status === "running") {
      this.subscribers.add(server);
      server.addEventListener("close", () => this.subscribers.delete(server));
    } else {
      server.close();
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  /** Called by the producer as the model streams. */
  async emit(ev: StreamEvent): Promise<void> {
    const rec = await this.load();
    if (!rec || rec.status !== "running") return;

    rec.events.push(ev);
    if (ev.type === "done") rec.status = "done";
    await this.persist();

    for (const ws of this.subscribers) {
      try {
        ws.send(JSON.stringify(ev));
      } catch {
        this.subscribers.delete(ws);
      }
    }
  }

  private async cancel(): Promise<Response> {
    const rec = await this.load();
    if (rec && rec.status === "running") {
      rec.status = "cancelled";
      await this.persist();
    }
    for (const ws of this.subscribers) {
      try {
        ws.close();
      } catch {
        /* already gone */
      }
    }
    this.subscribers.clear();
    return Response.json({ ok: true });
  }
}
