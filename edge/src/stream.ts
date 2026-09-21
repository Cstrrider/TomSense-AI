/**
 * Streaming chat round — port of `providers.stream_round` / `dispatch_stream_round`.
 *
 * The event contract is preserved deliberately (spec §4): text / reasoning /
 * heartbeat / done, with `done` emitted exactly once and carrying the terminal
 * content, tool calls, and usage. Clients written against the current backend
 * stay close to working.
 *
 * Two behaviours from main that are easy to drop and shouldn't be:
 *
 *   STALL DETECTION — some models (notably the larger CF ones under load) open
 *   the stream, emit nothing, and hold the connection until it times out. A
 *   watchdog turns that into a visible message plus a fallback attempt rather
 *   than a spinner that never resolves.
 *
 *   HEARTBEATS — long reasoning stretches emit no text for tens of seconds.
 *   Without a heartbeat the client cannot distinguish "thinking" from "dead",
 *   and intermediaries may close an idle connection.
 */

import type { StreamEvent, ToolCall, Usage, Provider, ChatMessage, Env } from "./types";
import { modelCapabilities } from "./capabilities";
import { flattenForTextModel, chatCompletionsUrl } from "./providers";

/** No token at all for this long → treat the stream as stalled. */
const STALL_MS = 25_000;
/** Emit a heartbeat if nothing has been sent to the client for this long. */
const HEARTBEAT_MS = 5_000;

export interface RoundOptions {
  provider: Provider;
  modelId: string;
  messages: ChatMessage[];
  tools?: unknown[];
  temperature?: number;
  maxTokens?: number;
  /** Model string to retry with if the primary stalls. */
  fallback?: { provider: Provider; modelId: string };
  signal?: AbortSignal;
  /**
   * Workers AI binding, required for `cf`-kind providers.
   *
   * CF models go through the binding rather than the REST endpoint on
   * purpose. The REST path would need an account id AND an API token stored
   * as a Worker secret, and the only token available here also carries
   * Workers/D1/R2 edit rights — concentrating that inside an
   * internet-facing Worker to call a model is a bad trade. The binding needs
   * no credential at all.
   */
  ai?: Ai;
  /**
   * Cache-affinity key — pins this call to the instance holding its prefix
   * cache. See [sessionHeaders]. Omit and the discount simply does not apply.
   */
  session?: string;
  /**
   * Raises the reasoning budget for think mode.
   *
   * Sent only to models that actually understand it. Stable notes the inverse
   * hazard too: gpt-oss models need an explicit LOW effort or they burn the
   * token budget on invisible reasoning, so the default is set rather than
   * omitted.
   */
  reasoningEffort?: "low" | "high";
}

/**
 * "Pin this to the cache-holding instance" header, per provider.
 *
 *   Cloudflare Workers AI  `x-session-affinity`  (stable measured the hit
 *                                                 ratio going ~60% → 80%)
 *   OpenRouter             `x-session-id`        (sticky routing, ≤256 chars)
 *
 * Everything else gets nothing: those providers either auto-pin after the
 * first hit or do not cache, and an unrecognised header can upset a strict
 * OpenAI-compatible server. No session, no header.
 */
function sessionHeaders(
  provider: Provider,
  session?: string,
): Record<string, string> {
  if (!session) return {};
  const base = provider.baseUrl || "";
  if (base.includes("openrouter.ai")) return { "x-session-id": session.slice(0, 256) };
  if (provider.kind === "cf" || base.includes("cloudflare.com")) {
    return { "x-session-affinity": session };
  }
  return {};
}

function shortName(modelId: string): string {
  const tail = modelId.split("/").pop() ?? modelId;
  return tail.length > 28 ? `${tail.slice(0, 27)}…` : tail;
}

function emptyUsage(): Usage {
  return { in: 0, out: 0, cache_read: 0, cache_write: 0 };
}

/**
 * Normalize usage across providers. Each one reports prompt-cache differently
 * and the field names are NOT stable; getting this wrong silently reports a 0%
 * cache hit rate, which is indistinguishable from caching being broken.
 */
function readUsage(raw: Record<string, unknown> | undefined): Usage {
  if (!raw) return emptyUsage();
  const details = (raw["prompt_tokens_details"] ?? {}) as Record<string, unknown>;
  const num = (v: unknown): number => (typeof v === "number" ? v : 0);
  return {
    in: num(raw["prompt_tokens"]) || num(raw["input_tokens"]),
    out: num(raw["completion_tokens"]) || num(raw["output_tokens"]),
    cache_read:
      num(details["cached_tokens"]) ||
      num(raw["cache_read_input_tokens"]) ||
      num(raw["cached_tokens"]),
    cache_write: num(raw["cache_creation_input_tokens"]),
  };
}

/** Accumulates OpenAI-style streamed tool_call deltas, which arrive fragmented. */
class ToolCallAccumulator {
  private byIndex = new Map<number, { id: string; name: string; args: string }>();

  push(deltas: unknown): void {
    if (!Array.isArray(deltas)) return;
    for (const d of deltas as Record<string, unknown>[]) {
      const idx = typeof d["index"] === "number" ? d["index"] : 0;
      const cur = this.byIndex.get(idx) ?? { id: "", name: "", args: "" };
      if (typeof d["id"] === "string" && d["id"]) cur.id = d["id"];
      const fn = d["function"] as Record<string, unknown> | undefined;
      if (fn) {
        if (typeof fn["name"] === "string" && fn["name"]) cur.name = fn["name"];
        // Arguments stream as a JSON string in fragments — concatenate, then
        // parse once at the end. Parsing per-fragment always fails.
        if (typeof fn["arguments"] === "string") cur.args += fn["arguments"];
      }
      this.byIndex.set(idx, cur);
    }
  }

  finish(): ToolCall[] {
    const out: ToolCall[] = [];
    for (const [, v] of [...this.byIndex.entries()].sort((a, b) => a[0] - b[0])) {
      if (!v.name) continue;
      let args: unknown = {};
      try {
        args = v.args ? JSON.parse(v.args) : {};
      } catch {
        // A truncated stream leaves invalid JSON. Surface the raw string so
        // the caller can decide, rather than silently dropping the call.
        args = { __raw: v.args };
      }
      out.push({ id: v.id || crypto.randomUUID(), name: v.name, arguments: args });
    }
    return out;
  }
}

/** Strip CF template tokens and channel labels that leak into content. */
const TEMPLATE_TOKEN_RE = /<\|[a-z_]+\|>/gi;
const CHANNEL_LABEL_RE = /^(analysis|final|commentary)\s*\n/i;

function stripTemplateTokens(s: string): string {
  return s.replace(TEMPLATE_TOKEN_RE, "").replace(CHANNEL_LABEL_RE, "");
}

async function buildRequest(
  opts: RoundOptions,
  url: string,
): Promise<Request> {
  const { provider, modelId, messages, tools, temperature, maxTokens } = opts;
  const caps = modelCapabilities(provider, modelId);

  // Non-vision models 400 on an array `content`, so flatten before sending.
  const msgs = caps.vision ? messages : flattenForTextModel(messages as never);

  const body: Record<string, unknown> = {
    model: modelId,
    messages: msgs,
    stream: true,
    // Ask for usage on the terminal chunk; without this most OpenAI-compatible
    // endpoints omit usage entirely when streaming, and cache_read is lost.
    stream_options: { include_usage: true },
    ...provider.extraBody, // per-provider passthrough (OpenRouter routing, etc.)
  };
  if (tools?.length) body["tools"] = tools;
  if (modelId.includes("gpt-oss")) {
    body["reasoning_effort"] = opts.reasoningEffort ?? "low";
  } else if (opts.reasoningEffort) {
    body["reasoning_effort"] = opts.reasoningEffort;
  }
  if (temperature !== undefined) body["temperature"] = temperature;
  if (maxTokens !== undefined) body["max_tokens"] = maxTokens;

  const headers: Record<string, string> = {
    "content-type": "application/json",
    ...sessionHeaders(provider, opts.session),
  };
  if (provider.apiKey) {
    if (provider.kind === "anthropic") {
      headers["x-api-key"] = provider.apiKey;
      headers["anthropic-version"] = "2023-06-01";
    } else {
      headers["authorization"] = `Bearer ${provider.apiKey}`;
    }
  }

  return new Request(url, { method: "POST", headers, body: JSON.stringify(body) });
}

/**
 * Run one streaming round against one model. Yields the event contract.
 * Does NOT handle fallback — see streamWithFallback.
 */
export async function* streamRound(
  opts: RoundOptions,
  url: string,
): AsyncGenerator<StreamEvent> {
  if (opts.provider.kind === "cf") {
    yield* streamWorkersAi(opts);
    return;
  }
  const toolCalls = new ToolCallAccumulator();
  let content = "";
  let usage = emptyUsage();
  let sawAnything = false;

  let res: Response;
  try {
    res = await fetch(await buildRequest(opts, url), { signal: opts.signal });
  } catch (e) {
    yield { type: "text", text: `\n\n[stream error: ${(e as Error).name}: ${(e as Error).message}]` };
    yield { type: "done", content, toolCalls: [], usage };
    return;
  }

  if (!res.ok || !res.body) {
    const detail = await res.text().catch(() => "");
    yield { type: "text", text: `\n\n[${res.status} from ${opts.provider.name}: ${detail.slice(0, 300)}]` };
    yield { type: "done", content, toolCalls: [], usage };
    return;
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = "";
  let lastToken = Date.now();
  let lastEmit = Date.now();

  try {
    for (;;) {
      // Race the read against the stall watchdog so a silent model cannot
      // hold the connection open indefinitely.
      const timeout = new Promise<"timeout">((r) => setTimeout(() => r("timeout"), HEARTBEAT_MS));
      const result = await Promise.race([reader.read(), timeout]);

      if (result === "timeout") {
        const now = Date.now();
        if (now - lastToken > STALL_MS) {
          await reader.cancel().catch(() => {});
          yield { type: "done", content, toolCalls: toolCalls.finish(), usage, stalled: true };
          return;
        }
        if (now - lastEmit >= HEARTBEAT_MS) {
          lastEmit = now;
          yield { type: "heartbeat" };
        }
        continue;
      }

      const { done, value } = result;
      if (done) break;
      lastToken = Date.now();
      buf += value;

      // SSE frames are separated by a blank line.
      let nl: number;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith("data:")) continue;

        const payload = line.slice(5).trim();
        if (payload === "[DONE]") continue;

        let chunk: Record<string, unknown>;
        try {
          chunk = JSON.parse(payload);
        } catch {
          continue; // partial frame; the next read completes it
        }

        // A usage-only terminal chunk carries no choices.
        const u = chunk["usage"] as Record<string, unknown> | undefined;
        if (u) usage = readUsage(u);

        const choices = chunk["choices"] as Record<string, unknown>[] | undefined;
        const delta = choices?.[0]?.["delta"] as Record<string, unknown> | undefined;
        if (!delta) continue;

        if (delta["tool_calls"]) toolCalls.push(delta["tool_calls"]);

        // Thinking models expose reasoning on a separate key; providers
        // disagree on which one, so accept both spellings.
        const reasoning = delta["reasoning_content"] ?? delta["reasoning"];
        if (typeof reasoning === "string" && reasoning) {
          sawAnything = true;
          lastEmit = Date.now();
          yield { type: "reasoning", text: reasoning };
        }

        const text = delta["content"];
        if (typeof text === "string" && text) {
          const cleaned = stripTemplateTokens(text);
          if (cleaned) {
            sawAnything = true;
            content += cleaned;
            lastEmit = Date.now();
            yield { type: "text", text: cleaned };
          }
        }
      }
    }
  } catch (e) {
    yield { type: "text", text: `\n\n[stream error: ${(e as Error).name}: ${(e as Error).message}]` };
  }

  // A clean close with zero output is the other shape of "went silent" — but
  // a round that produced only tool calls emitted no text by design and must
  // not be mistaken for one.
  const calls = toolCalls.finish();
  yield {
    type: "done",
    content,
    toolCalls: calls,
    usage,
    stalled: !sawAnything && calls.length === 0,
  };
}

/**
 * Cloudflare Workers AI via the `AI` binding.
 *
 * Workers AI emits a hybrid chunk: it carries BOTH a bare `response` string and
 * an OpenAI-shaped `choices[0].delta`. The delta is the richer of the two — it
 * is the only one that carries tool calls — so it wins where present, with
 * `response` as the fallback for models that omit `choices`. Reading both and
 * appending both would duplicate every token.
 *
 * Tool definitions are passed straight through. Without that, the default
 * Cloudflare models silently cannot call tools at all, which would make every
 * device tool a no-op on the out-of-the-box configuration.
 */
async function* streamWorkersAi(opts: RoundOptions): AsyncGenerator<StreamEvent> {
  const { ai, modelId, messages, tools, temperature, maxTokens } = opts;
  if (!ai) {
    yield { type: "text", text: "\n\n[cf provider selected but no AI binding available]" };
    yield { type: "done", content: "", toolCalls: [], usage: emptyUsage() };
    return;
  }

  const caps = modelCapabilities(opts.provider, modelId);
  const msgs = caps.vision ? messages : flattenForTextModel(messages as never);

  const toolCalls = new ToolCallAccumulator();
  let content = "";
  let usage = emptyUsage();
  let sawAnything = false;

  try {
    const input: Record<string, unknown> = { messages: msgs, stream: true };
    if (tools?.length) input["tools"] = tools;
    if (temperature !== undefined) input["temperature"] = temperature;
    if (maxTokens !== undefined) input["max_tokens"] = maxTokens;
    // Same rule as the fetch path: gpt-oss needs an explicit effort or it
    // spends the whole budget reasoning invisibly.
    if (modelId.includes("gpt-oss")) {
      input["reasoning_effort"] = opts.reasoningEffort ?? "low";
    } else if (opts.reasoningEffort) {
      input["reasoning_effort"] = opts.reasoningEffort;
    }

    // Cache affinity travels in the binding options, not a header.
    const runOpts = opts.session ? { sessionId: opts.session } : undefined;
    const result = (await ai.run(modelId as never, input as never, runOpts as never)) as unknown;
    const body = result as ReadableStream<Uint8Array>;
    const reader = body.pipeThrough(new TextDecoderStream()).getReader();

    let buf = "";
    let lastToken = Date.now();
    let lastEmit = Date.now();

    for (;;) {
      const timeout = new Promise<"timeout">((r) => setTimeout(() => r("timeout"), HEARTBEAT_MS));
      const step = await Promise.race([reader.read(), timeout]);

      if (step === "timeout") {
        const now = Date.now();
        if (now - lastToken > STALL_MS) {
          await reader.cancel().catch(() => {});
          yield { type: "done", content, toolCalls: [], usage, stalled: true };
          return;
        }
        if (now - lastEmit >= HEARTBEAT_MS) {
          lastEmit = now;
          yield { type: "heartbeat" };
        }
        continue;
      }

      const { done, value } = step;
      if (done) break;
      lastToken = Date.now();
      buf += value;

      let nl: number;
      while ((nl = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (payload === "[DONE]") continue;

        let chunk: Record<string, unknown>;
        try {
          chunk = JSON.parse(payload);
        } catch {
          continue;
        }

        // Intermediate frames carry per-chunk counts and the terminal frame
        // carries the cumulative total, so last-write-wins is correct here.
        const u = chunk["usage"] as Record<string, unknown> | undefined;
        if (u) usage = readUsage(u);

        const choices = chunk["choices"] as Record<string, unknown>[] | undefined;
        const delta = choices?.[0]?.["delta"] as Record<string, unknown> | undefined;

        if (delta?.["tool_calls"]) toolCalls.push(delta["tool_calls"]);

        const reasoning = delta?.["reasoning_content"] ?? delta?.["reasoning"];
        if (typeof reasoning === "string" && reasoning) {
          sawAnything = true;
          lastEmit = Date.now();
          yield { type: "reasoning", text: reasoning };
        }

        // Prefer the delta; fall back to `response` only when there is no
        // `choices` at all, or the same token is emitted twice.
        const piece = delta ? delta["content"] : chunk["response"];
        if (typeof piece === "string" && piece) {
          const cleaned = stripTemplateTokens(piece);
          if (cleaned) {
            sawAnything = true;
            content += cleaned;
            lastEmit = Date.now();
            yield { type: "text", text: cleaned };
          }
        }
      }
    }
  } catch (e) {
    yield { type: "text", text: `\n\n[workers-ai error: ${(e as Error).message}]` };
  }

  const calls = toolCalls.finish();
  yield {
    type: "done",
    content,
    toolCalls: calls,
    usage,
    // A round that produced only tool calls is a working round, not a stall.
    stalled: !sawAnything && calls.length === 0,
  };
}

/**
 * streamRound plus the silent-model fallback from `dispatch_stream_round`.
 * If the primary produces nothing, say so in-band and retry once on the
 * fallback model — the user gets an answer plus an explanation, rather than
 * either a dead spinner or a silent model swap they can't account for.
 */
export async function* streamWithFallback(
  opts: RoundOptions,
  urlFor: (p: Provider) => string,
): AsyncGenerator<StreamEvent> {
  let terminal: Extract<StreamEvent, { type: "done" }> | null = null;

  for await (const ev of streamRound(opts, urlFor(opts.provider))) {
    if (ev.type === "done") {
      terminal = ev;
      break;
    }
    yield ev;
  }

  const stalled = terminal?.stalled && !terminal.content && terminal.toolCalls.length === 0;
  if (!stalled || !opts.fallback) {
    yield terminal ?? { type: "done", content: "", toolCalls: [], usage: emptyUsage() };
    return;
  }

  const fb = opts.fallback;
  yield {
    type: "text",
    text: `*[${shortName(opts.modelId)} stalled — answering with ${shortName(fb.modelId)}]*\n\n`,
  };

  let fbTerminal: Extract<StreamEvent, { type: "done" }> | null = null;
  for await (const ev of streamRound(
    { ...opts, provider: fb.provider, modelId: fb.modelId, fallback: undefined },
    urlFor(fb.provider),
  )) {
    if (ev.type === "done") {
      fbTerminal = ev;
      break;
    }
    yield ev;
  }

  if (fbTerminal && (fbTerminal.content || fbTerminal.toolCalls.length)) {
    yield fbTerminal;
    return;
  }

  yield { type: "text", text: `\n\n*[${shortName(fb.modelId)} also went silent.]*` };
  yield {
    type: "done",
    content: fbTerminal?.content ?? "",
    toolCalls: [],
    usage: fbTerminal?.usage ?? emptyUsage(),
    stalled: true,
  };
}

/**
 * Run one short completion and return its text.
 *
 * For the utility tier — titles, follow-ups, the auto-route classifier — where
 * the caller wants an answer, not a stream. Implemented by draining
 * [streamRound] rather than duplicating request construction, so utility calls
 * inherit provider quirks, vision flattening and session affinity for free.
 *
 * Returns "" rather than throwing on a stalled or empty round: a utility call
 * is an enhancement, and its failure must never take the turn down with it.
 */
export async function completeOnce(
  env: Env,
  provider: Provider,
  modelId: string,
  opts: {
    messages: ChatMessage[];
    maxTokens?: number;
    session?: string;
    signal?: AbortSignal;
  },
): Promise<string> {
  const round: RoundOptions = {
    provider,
    modelId,
    messages: opts.messages,
    maxTokens: opts.maxTokens ?? 64,
    session: opts.session,
    signal: opts.signal,
    ai: env.AI,
  };

  let text = "";
  for await (const ev of streamRound(round, chatCompletionsUrl(provider))) {
    if (ev.type === "text") text += ev.text;
    else if (ev.type === "done") return (ev.content || text).trim();
  }
  return text.trim();
}
