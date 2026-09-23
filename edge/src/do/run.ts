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
 *
 * The DO owns the *whole* generation, not one model call, because a generation
 * with tools is inherently multi-round: model → tool calls → results → model
 * again. Putting that loop in the Worker would mean the round trip dies with
 * the request that started it, which is precisely the failure this object
 * exists to prevent. One consequence worth stating plainly: the producer is
 * here, so usage accounting is here too — a client that walks away mid-reply
 * must still be billed for the tokens it caused.
 */

import type { Env, StreamEvent, ToolCall, ChatMessage, Usage } from "./../types";
import { parseModelStr, resolveProvider, chatCompletionsUrl } from "./../providers";
import { streamWithFallback } from "./../stream";
import { isServerTool, runServerTool } from "./../server_tools";
import { getPrefs } from "./../prefs";
import { costUsd } from "./../usage";
import { warmCfCapabilities } from "./../capabilities";

/**
 * Ceiling on model→tool→model cycles in a single run.
 *
 * A model that calls a tool, dislikes the result, and calls it again will do
 * that forever given the chance, and every cycle is a paid request. Twelve is
 * well past what a legitimate task needs while still bounding the bill.
 */
const MAX_ROUNDS = 12;

/**
 * Stop persisting replay text past this size.
 *
 * A DO storage value is capped at 128 KiB. Streaming is unaffected — this only
 * bounds what a *reconnecting* client can be caught up on, and 96 KiB is far
 * longer than any real reply. Silently exceeding the cap would make `put`
 * throw mid-run and lose the run entirely, which is much worse than a
 * truncated replay.
 */
const MAX_REPLAY_CHARS = 96_000;

/** Persist no more often than this while tokens stream. */
const PERSIST_INTERVAL_MS = 1_000;

type RunStatus = "running" | "awaiting_tools" | "done" | "error" | "cancelled";

interface CompletedRound {
  /** Which model served it — the fallback, if the primary stalled. */
  model?: string;
  content: string;
  toolCalls: ToolCall[];
  usage: Usage;
  stalled?: boolean;
}

interface RunRecord {
  id: string;
  userId: string;
  convId: string;
  /** Resolved at /chat time so model-selection errors surface synchronously. */
  model: string;
  fallbackModel: string | null;
  status: RunStatus;
  /** The running conversation — grows with each assistant turn and tool result. */
  messages: ChatMessage[];
  tools: unknown[];
  /**
   * Which of those tools live at home.
   *
   * The model is shown one flat list; only the round splitter needs to know
   * that these go down the HomeLink socket instead of being parked for the
   * phone. Names rather than schemas, because that is all the split needs and
   * the schemas are already in `tools`.
   */
  homeTools: string[];
  /**
   * Replay state, deliberately compacted rather than an event log. Storing
   * every delta would blow the value limit on a long answer and replay
   * hundreds of one-token frames to a reconnecting client for no benefit.
   */
  content: string;
  reasoning: string;
  rounds: CompletedRound[];
  pendingToolCalls: ToolCall[];
  /** Routing overrides to show the user; replayed on reconnect. */
  notices: string[];
  /** R2 keys produced during this run — generated images and the like. */
  attachments: string[];
  /**
   * Image keys the CLIENT attached, in order.
   *
   * Carried separately because the messages reaching this DO have already had
   * their attachments expanded into data URLs, so the keys are no longer
   * recoverable from them — and edit_image needs a key, not a data URL.
   */
  sourceImageKeys: string[];
  /** "high" when think mode routed this turn. */
  reasoningEffort: "high" | null;
  error?: string;
}

export class DetachedRun implements DurableObject {
  /** SSE subscribers. A dead writer is dropped, never awaited. */
  private subscribers = new Set<WritableStreamDefaultWriter<Uint8Array>>();
  private sockets = new Set<WebSocket>();
  private record: RunRecord | null = null;
  private abort: AbortController | null = null;
  private lastPersist = 0;
  private encoder = new TextEncoder();

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const tail = url.pathname.split("/").pop() ?? "";

    if (tail === "start") return this.start(req);
    if (tail === "attach") return this.attach(req);
    if (tail === "cancel") return this.cancel();
    if (tail === "tool_result") return this.toolResult(req);
    if (tail === "state") return this.state();

    return new Response("not found", { status: 404 });
  }

  // ─── lifecycle ────────────────────────────────────────────────────────────

  private async load(): Promise<RunRecord | null> {
    if (this.record) return this.record;
    this.record = (await this.ctx.storage.get<RunRecord>("record")) ?? null;
    return this.record;
  }

  private async persist(force = false): Promise<void> {
    if (!this.record) return;
    const now = Date.now();
    if (!force && now - this.lastPersist < PERSIST_INTERVAL_MS) return;
    this.lastPersist = now;

    // Truncate the replay copy only; the live stream already went out intact.
    const rec = this.record;
    const safe: RunRecord =
      rec.content.length > MAX_REPLAY_CHARS
        ? { ...rec, content: rec.content.slice(0, MAX_REPLAY_CHARS) }
        : rec;
    await this.ctx.storage.put("record", safe);
  }

  private async start(req: Request): Promise<Response> {
    const body = (await req.json()) as {
      id: string;
      userId: string;
      convId: string;
      model: string;
      fallbackModel: string | null;
      messages: ChatMessage[];
      tools?: unknown[];
      homeTools?: string[];
      sourceImageKeys?: string[];
      notices?: string[];
      reasoningEffort?: "high" | null;
    };

    this.record = {
      id: body.id,
      userId: body.userId,
      convId: body.convId,
      model: body.model,
      fallbackModel: body.fallbackModel,
      status: "running",
      messages: body.messages,
      tools: body.tools ?? [],
      homeTools: body.homeTools ?? [],
      content: "",
      reasoning: "",
      rounds: [],
      pendingToolCalls: [],
      notices: body.notices ?? [],
      attachments: [],
      sourceImageKeys: body.sourceImageKeys ?? [],
      reasoningEffort: body.reasoningEffort ?? null,
    };
    await this.persist(true);

    // Not awaited: /start returns immediately so the caller can attach. The
    // DO stays alive for the duration because of waitUntil.
    this.ctx.waitUntil(this.runRounds());
    return Response.json({ ok: true, runId: body.id });
  }

  private async state(): Promise<Response> {
    const rec = await this.load();
    if (!rec) return new Response("no such run", { status: 404 });
    return Response.json({
      id: rec.id,
      status: rec.status,
      model: rec.model,
      content: rec.content,
      pendingToolCalls: rec.pendingToolCalls,
      error: rec.error,
    });
  }

  // ─── subscribers ──────────────────────────────────────────────────────────

  /**
   * Attach a subscriber. Replays what has already been produced BEFORE
   * streaming live events — without the replay, a client that reconnects
   * mid-run sees the tail of a sentence and no way to recover the beginning.
   */
  private async attach(req: Request): Promise<Response> {
    const rec = await this.load();
    if (!rec) return new Response("no such run", { status: 404 });

    if (req.headers.get("upgrade") === "websocket") return this.attachSocket(rec);

    const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
    const writer = writable.getWriter();

    // Queued, never awaited. Nothing is reading this stream yet — the Response
    // carrying it has not been returned — and a default TransformStream has a
    // readable highWaterMark of 0, so awaiting the first write here deadlocks
    // the attach. Enqueue order is preserved without the await.
    for (const ev of this.replayEvents(rec)) this.send(writer, ev);

    if (rec.status === "running" || rec.status === "awaiting_tools") {
      this.subscribers.add(writer);
    } else {
      this.finish(writer);
    }

    return new Response(readable, {
      headers: {
        "content-type": "text/event-stream",
        "cache-control": "no-cache",
        connection: "keep-alive",
      },
    });
  }

  private attachSocket(rec: RunRecord): Response {
    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    server.accept();

    for (const ev of this.replayEvents(rec)) server.send(JSON.stringify(ev));

    if (rec.status === "running" || rec.status === "awaiting_tools") {
      this.sockets.add(server);
      server.addEventListener("close", () => this.sockets.delete(server));
    } else {
      server.close();
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * Reconstruct the stream a late subscriber missed.
   *
   * Reasoning comes first because that is the order it was produced in, and a
   * client that renders reasoning in a separate pane relies on it.
   */
  private replayEvents(rec: RunRecord): StreamEvent[] {
    const out: StreamEvent[] = [{ type: "run", runId: rec.id, status: rec.status }];
    // Before any content: the notice explains the model that produced it.
    for (const n of rec.notices ?? []) out.push({ type: "notice", text: n });
    for (const k of rec.attachments ?? []) {
      out.push({ type: "attachment", key: k, mime: "application/octet-stream" });
    }
    if (rec.reasoning) out.push({ type: "reasoning", text: rec.reasoning });
    if (rec.content) out.push({ type: "text", text: rec.content });
    for (const r of rec.rounds) {
      out.push({
        type: "done",
        content: r.content,
        toolCalls: r.toolCalls,
        usage: r.usage,
        stalled: r.stalled,
      });
    }
    if (rec.status !== "running" && rec.status !== "awaiting_tools") {
      out.push({ type: "end", status: rec.status, error: rec.error });
    }
    return out;
  }

  /**
   * Enqueue one SSE frame.
   *
   * The write is deliberately NOT awaited. Awaiting it means the generation
   * proceeds at the speed of the slowest subscriber, so a phone that sleeps
   * with the socket still open would stall the run — reintroducing the exact
   * client dependency this object exists to remove. A failed write means that
   * subscriber is gone, and only that subscriber is dropped.
   */
  private send(w: WritableStreamDefaultWriter<Uint8Array>, ev: StreamEvent): void {
    w.write(this.encoder.encode(`data: ${JSON.stringify(ev)}\n\n`)).catch(() => {
      this.subscribers.delete(w);
    });
  }

  /** Terminate one subscriber's stream: end-of-stream marker, then close. */
  private finish(w: WritableStreamDefaultWriter<Uint8Array>): void {
    w.write(this.encoder.encode("data: [DONE]\n\n")).catch(() => {});
    w.close().catch(() => {});
  }

  /** Fan out to every subscriber. */
  private emit(ev: StreamEvent): void {
    for (const w of [...this.subscribers]) this.send(w, ev);
    for (const ws of [...this.sockets]) {
      try {
        ws.send(JSON.stringify(ev));
      } catch {
        this.sockets.delete(ws);
      }
    }
  }

  /** Terminal: tell every subscriber the run is over and close them out. */
  private closeAll(status: RunStatus, error?: string): void {
    this.emit({ type: "end", status, error });
    for (const w of [...this.subscribers]) this.finish(w);
    this.subscribers.clear();
    for (const ws of [...this.sockets]) {
      try {
        ws.close();
      } catch {
        /* already gone */
      }
    }
    this.sockets.clear();
  }

  // ─── the generation loop ──────────────────────────────────────────────────

  /**
   * Drive model rounds until the model stops asking for tools.
   *
   * Returns (rather than finishing the run) when tool calls are outstanding:
   * the run parks in `awaiting_tools` and resumes from /tool_result. That park
   * is the whole point — it is what lets the phone execute a device tool, take
   * ten seconds over a permission prompt, and hand the result back.
   */
  private async runRounds(): Promise<void> {
    const rec = await this.load();
    if (!rec) return;

    try {
      while (rec.status === "running") {
        if (rec.rounds.length >= MAX_ROUNDS) {
          this.emit({
            type: "text",
            text: `\n\n*[stopped after ${MAX_ROUNDS} tool rounds]*`,
          });
          rec.status = "done";
          break;
        }

        const round = await this.oneRound(rec);
        if (!round) return; // cancelled mid-flight; cancel() owns the terminal

        rec.rounds.push(round);

        // The assistant turn must go into the transcript verbatim, tool calls
        // included — a provider that receives tool results for calls it has no
        // record of making will reject the request.
        rec.messages.push({
          role: "assistant",
          content: round.content,
          ...(round.toolCalls.length
            ? {
                tool_calls: round.toolCalls.map((t) => ({
                  id: t.id,
                  type: "function",
                  function: { name: t.name, arguments: JSON.stringify(t.arguments ?? {}) },
                })),
              }
            : {}),
        } as ChatMessage);

        this.emit({
          type: "done",
          content: round.content,
          toolCalls: round.toolCalls,
          usage: round.usage,
          stalled: round.stalled,
          model: round.model ?? rec.model,
          costUsd: costUsd(
            parseModelStr(round.model ?? rec.model, this.env.TIER2_MODEL).modelId,
            round.usage,
          ),
        });

        if (round.toolCalls.length) {
          // Split the round: the edge answers its own tools immediately, the
          // phone answers the rest. Parking for a tool the device cannot run
          // would hang the run until it was swept away.
          // Three lanes, not two. The edge answers its own tools, the HOME
          // AGENT answers LAN ones over its socket, and only what is left
          // belongs to the phone. Home tools must not be parked as device
          // calls: the phone has no idea what lan_disk_free is and the run
          // would hang until it was swept away.
          const home = new Set(rec.homeTools);
          const serverCalls = round.toolCalls.filter((t) => isServerTool(t.name));
          const homeCalls = round.toolCalls.filter(
            (t) => !isServerTool(t.name) && home.has(t.name),
          );
          const deviceCalls = round.toolCalls.filter(
            (t) => !isServerTool(t.name) && !home.has(t.name),
          );

          for (const call of serverCalls) {
            const prefs = await getPrefs(this.env, rec.userId);
            const result = await runServerTool(this.env, rec.userId, call, {
              imageModel: prefs.tool_models.image,
              // Oldest first: what the user attached on this turn, then
              // anything generated since. edit_image takes the last, which is
              // what "make it red" refers to.
              recentImageKeys: [...rec.sourceImageKeys, ...rec.attachments],
            });

            // Surfaced to the user, not just to the model: a tool that failed
            // for a concrete reason should say so rather than being relayed as
            // a vague apology.
            if (result.error) {
              rec.notices.push(result.error);
              this.emit({ type: "notice", text: result.error });
            }

            if (result.attachmentKey) {
              rec.attachments.push(result.attachmentKey);
              // Emitted live AND persisted, so an image survives a reconnect
              // the same way text does.
              this.emit({
                type: "attachment",
                key: result.attachmentKey,
                mime: result.attachmentMime ?? "application/octet-stream",
              });
            }

            rec.messages.push({
              role: "tool",
              tool_call_id: call.id,
              name: call.name,
              content: result.content,
            } as ChatMessage);
          }

          for (const call of homeCalls) {
            const result = await this.callHome(call);
            if (result.error) {
              rec.notices.push(result.error);
              this.emit({ type: "notice", text: result.error });
            }
            rec.messages.push({
              role: "tool",
              tool_call_id: call.id,
              name: call.name,
              content: result.content,
            } as ChatMessage);
          }

          if (deviceCalls.length) {
            rec.pendingToolCalls = deviceCalls;
            rec.status = "awaiting_tools";
            await this.persist(true);
            await this.syncStatus(rec);
            return; // parked — /tool_result resumes us
          }

          // Only edge-side tools ran — the edge's own or the home agent's —
          // so nothing is waiting on the phone: keep going and let the model
          // use what it just got back.
          await this.persist(true);
          continue;
        }

        rec.status = "done";
      }
    } catch (e) {
      rec.status = "error";
      rec.error = (e as Error).message;
    }

    await this.persist(true);
    await this.syncStatus(rec);
    this.closeAll(rec.status, rec.error);
  }

  /** One model call. Returns null if the run was cancelled while streaming. */
  private async oneRound(rec: RunRecord): Promise<CompletedRound | null> {
    // Before the request is built: modelCapabilities decides whether image
    // parts survive, and it reads this synchronously.
    await warmCfCapabilities(this.env);

    const { providerId, modelId } = parseModelStr(rec.model, this.env.TIER2_MODEL);
    const provider = await resolveProvider(this.env, rec.userId, providerId);
    if (!provider) throw new Error(`unknown provider ${providerId}`);

    let fallback: { provider: NonNullable<typeof provider>; modelId: string } | undefined;
    if (rec.fallbackModel) {
      const fb = parseModelStr(rec.fallbackModel, rec.fallbackModel);
      const fbProvider = await resolveProvider(this.env, rec.userId, fb.providerId);
      if (fbProvider) fallback = { provider: fbProvider, modelId: fb.modelId };
    }

    this.abort = new AbortController();
    const events = streamWithFallback(
      {
        provider,
        modelId,
        messages: rec.messages,
        tools: rec.tools.length ? rec.tools : undefined,
        fallback,
        signal: this.abort.signal,
        ai: this.env.AI,
        reasoningEffort: rec.reasoningEffort ?? undefined,
        // Pin the whole conversation to one cache-holding instance. Keyed by
        // conversation, unlike the utility tier which keys by purpose.
        session: rec.convId,
      },
      (p) => chatCompletionsUrl(p),
    );

    for await (const ev of events) {
      if (rec.status === "cancelled") return null;

      if (ev.type === "done") {
        this.abort = null;
        await this.recordUsage(rec, providerId, modelId, ev.usage);
        return {
          content: ev.content,
          toolCalls: ev.toolCalls,
          usage: ev.usage,
          stalled: ev.stalled,
          // The model that served THIS round. A stalled round falls back, so
          // crediting rec.model would name the one that went silent.
          model: ev.stalled && rec.fallbackModel ? rec.fallbackModel : rec.model,
        };
      }

      if (ev.type === "text") rec.content += ev.text;
      if (ev.type === "reasoning") rec.reasoning += ev.text;
      this.emit(ev);
      if (ev.type !== "heartbeat") await this.persist();
    }

    this.abort = null;
    return null;
  }

  // ─── client callbacks ─────────────────────────────────────────────────────

  /**
   * Run one tool on the home agent.
   *
   * Never throws. The agent being offline is a NORMAL condition — that is the
   * whole premise of splitting the planes — so it comes back as a tool result
   * the model can read and work around, not as a failed run. The user sees it
   * too, via a notice, because "I couldn't check that" is a better answer than
   * a vague apology with no reason attached.
   */
  private async callHome(call: ToolCall): Promise<{ content: string; error?: string }> {
    try {
      const id = this.env.HOMELINK.idFromName("default");
      const res = await this.env.HOMELINK.get(id).fetch(
        new Request("https://do/call", {
          method: "POST",
          body: JSON.stringify({ name: call.name, arguments: call.arguments }),
        }),
      );
      const body = (await res.json()) as { result?: unknown; error?: string };

      if (!res.ok || body.error) {
        const why = body.error ?? `home agent returned ${res.status}`;
        return { content: JSON.stringify({ error: why }), error: `${call.name}: ${why}` };
      }
      return { content: JSON.stringify(body.result ?? null) };
    } catch (e) {
      const why = (e as Error).message;
      return { content: JSON.stringify({ error: why }), error: `${call.name}: ${why}` };
    }
  }

  /**
   * POST /tool_result — hand back what the client's tools produced and resume.
   *
   * Results are matched to outstanding calls by id and anything unrecognised is
   * ignored: a stale retry from a client that reconnected twice must not be
   * able to inject a tool message for a call the model never made.
   */
  private async toolResult(req: Request): Promise<Response> {
    const rec = await this.load();
    if (!rec) return new Response("no such run", { status: 404 });
    if (rec.status !== "awaiting_tools") {
      return Response.json({ error: `run is ${rec.status}, not awaiting tools` }, { status: 409 });
    }

    const body = (await req.json()) as {
      results: { id: string; name?: string; content: string }[];
    };
    const outstanding = new Map(rec.pendingToolCalls.map((t) => [t.id, t]));

    for (const r of body.results ?? []) {
      const call = outstanding.get(r.id);
      if (!call) continue;
      rec.messages.push({
        role: "tool",
        content: r.content,
        tool_call_id: r.id,
        name: call.name,
      });
      outstanding.delete(r.id);
    }

    // A tool the client could not run still needs an answer, or the provider
    // rejects the next request for an unanswered call and the run wedges.
    for (const [, call] of outstanding) {
      rec.messages.push({
        role: "tool",
        content: JSON.stringify({ error: "no result returned by client" }),
        tool_call_id: call.id,
        name: call.name,
      });
    }

    rec.pendingToolCalls = [];
    rec.status = "running";
    await this.persist(true);
    await this.syncStatus(rec);

    this.ctx.waitUntil(this.runRounds());
    return Response.json({ ok: true, round: rec.rounds.length });
  }

  private async cancel(): Promise<Response> {
    const rec = await this.load();
    if (!rec) return new Response("no such run", { status: 404 });

    if (rec.status === "running" || rec.status === "awaiting_tools") {
      rec.status = "cancelled";
      // Abort first: the in-flight provider fetch keeps charging tokens until
      // the socket actually closes, so "stop" has to reach the network.
      this.abort?.abort();
      this.abort = null;
      await this.persist(true);
      await this.syncStatus(rec);
      this.closeAll("cancelled");
    }
    return Response.json({ ok: true, status: rec.status });
  }

  // ─── durable index ────────────────────────────────────────────────────────

  /**
   * Mirror status into D1 so a run remains discoverable after this object is
   * evicted — the DO is the run, but D1 is how a cold client finds it.
   */
  private async syncStatus(rec: RunRecord): Promise<void> {
    const ended = rec.status === "running" || rec.status === "awaiting_tools" ? null : Date.now();
    await this.env.DB.prepare(
      `UPDATE runs SET status = ?, ended_at = ?, error = ? WHERE id = ?`,
    )
      .bind(rec.status, ended, rec.error ?? null, rec.id)
      .run()
      .catch(() => {});
  }

  private async recordUsage(
    rec: RunRecord,
    providerId: string,
    modelId: string,
    usage: Usage,
  ): Promise<void> {
    const day = new Date().toISOString().slice(0, 10);
    await this.env.DB.prepare(
      `INSERT INTO usage_daily
         (user_id, day, provider_id, model_id, tokens_in, tokens_out, cache_read, cache_write, requests)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
       ON CONFLICT(user_id, day, provider_id, model_id) DO UPDATE SET
         tokens_in   = tokens_in   + excluded.tokens_in,
         tokens_out  = tokens_out  + excluded.tokens_out,
         cache_read  = cache_read  + excluded.cache_read,
         cache_write = cache_write + excluded.cache_write,
         requests    = requests    + 1`,
    )
      .bind(rec.userId, day, providerId, modelId, usage.in, usage.out, usage.cache_read, usage.cache_write)
      .run()
      .catch(() => {});
  }
}
