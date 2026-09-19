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

import type { StreamEvent, ToolCall, Usage, Provider, ChatMessage } from "./types";
import { modelCapabilities } from "./capabilities";
import { flattenForTextModel } from "./providers";

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
  if (temperature !== undefined) body["temperature"] = temperature;
  if (maxTokens !== undefined) body["max_tokens"] = maxTokens;

  const headers: Record<string, string> = { "content-type": "application/json" };
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

  // A clean close with zero output is the other shape of "went silent".
  yield {
    type: "done",
    content,
    toolCalls: toolCalls.finish(),
    usage,
    stalled: !sawAnything,
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
