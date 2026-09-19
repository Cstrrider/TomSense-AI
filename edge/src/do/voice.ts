/**
 * VoiceSession — duplex voice as its own path, not "chat with an audio
 * attachment" (spec §6).
 *
 * This is the highest-risk assumption in the whole design. If it does not feel
 * instant on real hardware, the premise ("replaces Gemini on my phone") is
 * wrong and should be revisited before the client cutover.
 *
 *   mic ──► flux (streaming STT, does its own endpointing/turn-taking)
 *             │  partial transcript
 *             ├──► speculative generation starts   ◄── discarded if final differs
 *             │
 *           final ──► router ──► sentence chunks ──► aura-2 ──► speaker
 *                                      ▲
 *           VAD fires ──── barge-in ───┘  (kill TTS + generation NOW)
 *
 * Why a Durable Object: the session is stateful and long-lived (a WebSocket,
 * an in-flight generation, a TTS queue), and it must survive the client
 * briefly dropping. A stateless Worker cannot hold any of that.
 *
 * Latency budget this is built against — the thing to measure on device:
 *
 *   endpoint detection (flux)      ~100ms   after speech actually stops
 *   first token from tier-2 model  ~250ms   warm, prompt-cached
 *   first TTS audio (aura-2)       ~150ms   after first sentence boundary
 *   ───────────────────────────────────
 *   perceived                      ~500ms   target; >1s reads as "demo"
 *
 * Speculative generation is what buys the difference: generation starts on the
 * PARTIAL transcript while the user is still finishing, so by the time flux
 * confirms the final we are already streaming tokens. When the final differs
 * from the partial we threw compute away — that is the intended trade.
 */

import type { Env } from "./../types";
import { parseModelStr, resolveProvider, chatCompletionsUrl } from "./../providers";
import { streamRound } from "./../stream";

type ClientMsg =
  | { t: "start"; conversationId: string; model?: string }
  | { t: "audio"; pcm: string } // base64 16-bit PCM @ 16kHz
  | { t: "barge" } // client-side VAD detected the user speaking over TTS
  | { t: "stop" };

type ServerMsg =
  | { t: "partial"; text: string }
  | { t: "final"; text: string }
  | { t: "token"; text: string }
  | { t: "audio"; mp3: string }
  | { t: "state"; state: SessionState }
  | { t: "error"; message: string };

type SessionState = "idle" | "listening" | "thinking" | "speaking";

/** A partial must be at least this stable before we gamble compute on it. */
const SPECULATE_MIN_CHARS = 12;
/** Don't speculate more than once per this interval — partials fire fast. */
const SPECULATE_COOLDOWN_MS = 400;

export class VoiceSession implements DurableObject {
  private ws: WebSocket | null = null;
  private state: SessionState = "idle";
  private conversationId = "";
  private model = "";
  /** Set from a trusted header by the Worker — never from the client payload,
   *  which would let a caller resolve another account's provider keys. */
  private userId = "";

  /** Aborts the in-flight generation — used by barge-in and by re-speculation. */
  private genAbort: AbortController | null = null;
  /** Transcript the in-flight speculative generation was started from. */
  private speculatedOn = "";
  private lastSpeculateAt = 0;

  /** Text buffered since the last sentence boundary we sent to TTS. */
  private ttsBuffer = "";
  /** Incremented on every barge-in; stale TTS results check it and bail. */
  private turnEpoch = 0;

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(req: Request): Promise<Response> {
    if (req.headers.get("upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    // The Worker has already authenticated the caller; it forwards the
    // resolved id here. The DO is not publicly addressable, so this header
    // cannot be spoofed by a client.
    this.userId = req.headers.get("x-tomsense-user") ?? "";
    if (!this.userId) return new Response("missing principal", { status: 400 });

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    server.accept();
    this.ws = server;

    server.addEventListener("message", (ev) => {
      void this.onMessage(ev.data as string).catch((e) =>
        this.send({ t: "error", message: String(e) }),
      );
    });
    server.addEventListener("close", () => this.teardown());

    return new Response(null, { status: 101, webSocket: client });
  }

  private send(msg: ServerMsg): void {
    try {
      this.ws?.send(JSON.stringify(msg));
    } catch {
      // Client vanished mid-turn. The DO stays alive briefly so a reconnect
      // can resume rather than restarting the whole turn.
    }
  }

  private setState(s: SessionState): void {
    if (this.state === s) return;
    this.state = s;
    this.send({ t: "state", state: s });
  }

  private async onMessage(raw: string): Promise<void> {
    let msg: ClientMsg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    switch (msg.t) {
      case "start":
        this.conversationId = msg.conversationId;
        this.model = msg.model ?? this.env.TIER2_MODEL;
        this.setState("listening");
        return;

      case "audio":
        await this.onAudio(msg.pcm);
        return;

      case "barge":
        // The single most important interaction in the whole feature. If
        // interrupting the assistant is not instant, the conversation feels
        // like a phone tree.
        this.bargeIn();
        return;

      case "stop":
        this.teardown();
        return;
    }
  }

  /**
   * Cancel everything belonging to the current turn, immediately.
   *
   * Bumping the epoch is what makes this safe: TTS and generation callbacks
   * already in flight will observe the change and drop their results instead
   * of racing new audio onto the wire after the user has started talking.
   */
  private bargeIn(): void {
    this.turnEpoch++;
    this.genAbort?.abort();
    this.genAbort = null;
    this.ttsBuffer = "";
    this.speculatedOn = "";
    this.setState("listening");
  }

  private async onAudio(pcmB64: string): Promise<void> {
    // flux handles endpointing and turn-taking itself — that is the reason to
    // use it over nova-3 here. We forward audio and react to what it says
    // rather than running our own VAD state machine on the server.
    const audio = Uint8Array.from(atob(pcmB64), (c) => c.charCodeAt(0));

    const result = (await this.env.AI.run(this.env.STT_MODEL as never, {
      audio: [...audio],
    } as never)) as { text?: string; is_final?: boolean; speech_final?: boolean };

    const text = (result.text ?? "").trim();
    if (!text) return;

    const isFinal = Boolean(result.is_final ?? result.speech_final);

    if (!isFinal) {
      this.send({ t: "partial", text });
      this.maybeSpeculate(text);
      return;
    }

    this.send({ t: "final", text });
    await this.respond(text);
  }

  /**
   * Start generating from a partial transcript, betting it won't change much.
   *
   * Guarded three ways, because the naive version re-fires on every partial
   * and burns tokens continuously for the whole utterance:
   *   - a minimum length, so we don't speculate on "uh"
   *   - a cooldown, since partials arrive every few hundred ms
   *   - a prefix check, so we only restart when the meaning actually changed
   */
  private maybeSpeculate(partial: string): void {
    if (partial.length < SPECULATE_MIN_CHARS) return;

    const now = Date.now();
    if (now - this.lastSpeculateAt < SPECULATE_COOLDOWN_MS) return;

    // Still the same utterance growing at the tail → the running generation is
    // probably still valid; let it continue rather than restarting.
    if (this.speculatedOn && partial.startsWith(this.speculatedOn)) return;

    this.lastSpeculateAt = now;
    this.genAbort?.abort();
    this.speculatedOn = partial;
    void this.generate(partial, { speculative: true });
  }

  private async respond(final: string): Promise<void> {
    // If the speculative run was started from exactly this text, it is already
    // streaming and correct — let it finish rather than starting over.
    if (this.speculatedOn === final && this.genAbort) return;

    this.genAbort?.abort();
    this.speculatedOn = "";
    await this.generate(final, { speculative: false });
  }

  private async generate(
    prompt: string,
    opts: { speculative: boolean },
  ): Promise<void> {
    const epoch = this.turnEpoch;
    const abort = new AbortController();
    this.genAbort = abort;
    this.setState("thinking");
    this.ttsBuffer = "";

    try {
      // Routed through the SAME provider path as text chat, deliberately.
      // Calling env.AI.run directly here would work only for Cloudflare-hosted
      // models and would silently break every BYO-key provider — and it would
      // be a second, divergent implementation of stall handling and usage
      // accounting that then drifts from the text path.
      const { providerId, modelId } = parseModelStr(this.model, this.env.TIER2_MODEL);
      const provider = await resolveProvider(this.env, this.userId, providerId);
      if (!provider) throw new Error(`unknown provider ${providerId}`);

      for await (const ev of streamRound(
        {
          provider,
          modelId,
          messages: [{ role: "user", content: prompt }],
          signal: abort.signal,
        },
        chatCompletionsUrl(provider),
      )) {
        if (epoch !== this.turnEpoch) return; // barged in — drop everything
        if (ev.type === "done") break;
        // Reasoning traces are not spoken; they'd be bizarre read aloud.
        if (ev.type !== "text") continue;

        this.send({ t: "token", text: ev.text });
        this.ttsBuffer += ev.text;
        await this.flushSentences(epoch, opts.speculative);
      }

      // Speak whatever is left that never hit a sentence boundary.
      if (epoch === this.turnEpoch && this.ttsBuffer.trim()) {
        await this.speak(this.ttsBuffer.trim(), epoch);
        this.ttsBuffer = "";
      }
      if (epoch === this.turnEpoch) this.setState("listening");
    } catch (e) {
      if ((e as Error).name === "AbortError") return; // expected on barge-in
      this.send({ t: "error", message: (e as Error).message });
      this.setState("listening");
    } finally {
      if (this.genAbort === abort) this.genAbort = null;
    }
  }

  /**
   * Emit complete sentences to TTS as they form.
   *
   * Chunking on sentence boundaries rather than waiting for the full response
   * is most of the perceived-latency win: audio starts after the FIRST
   * sentence, not after the model finishes. The cost is slightly less natural
   * prosody across the boundary, which is a good trade at this budget.
   */
  private async flushSentences(epoch: number, speculative: boolean): Promise<void> {
    // Don't emit audio for a speculative run — we might be wrong, and speaking
    // a wrong answer then cutting it off is far worse than waiting.
    if (speculative) return;

    for (;;) {
      const m = /[.!?]["')\]]?\s/.exec(this.ttsBuffer);
      if (!m) return;
      const cut = m.index + m[0].length;
      const sentence = this.ttsBuffer.slice(0, cut).trim();
      this.ttsBuffer = this.ttsBuffer.slice(cut);
      if (sentence) await this.speak(sentence, epoch);
    }
  }

  private async speak(text: string, epoch: number): Promise<void> {
    if (epoch !== this.turnEpoch) return;
    this.setState("speaking");
    try {
      const out = (await this.env.AI.run(this.env.TTS_MODEL as never, {
        text,
      } as never)) as { audio?: string };

      // Re-check AFTER the await: the user may have barged in while aura-2
      // was synthesising, and playing this now would talk over them.
      if (epoch !== this.turnEpoch) return;
      if (out.audio) this.send({ t: "audio", mp3: out.audio });
    } catch (e) {
      this.send({ t: "error", message: `tts: ${(e as Error).message}` });
    }
  }

  private teardown(): void {
    this.turnEpoch++;
    this.genAbort?.abort();
    this.genAbort = null;
    this.state = "idle";
    try {
      this.ws?.close();
    } catch {
      /* already closed */
    }
    this.ws = null;
  }
}
