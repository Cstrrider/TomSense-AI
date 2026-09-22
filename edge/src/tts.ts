/**
 * Speech out.
 *
 * Deliberately a plain request/response returning audio bytes, not a stream.
 * The latency that matters is time-to-FIRST-SOUND, and that is won on the
 * client by speaking sentence one while sentence two is still arriving — see
 * SentenceChunker. Streaming the audio of a single sentence would add
 * complexity to shave milliseconds off something already short.
 *
 * The model id matters more than it looks. `TTS_MODEL` was configured as
 * `@cf/deepgram/aura-2`, which does not exist: Workers AI serves `aura-2-en`
 * and `aura-2-es`, and the bare name returns a 502 "could not route request to
 * AI model". Nothing had ever called it, so the misconfiguration sat there
 * silently — the same shape of bug as flux-2-klein, found the same way.
 */

import type { Env } from "./types";

const DEFAULT_TTS_MODEL = "@cf/deepgram/aura-2-en";
const DEFAULT_VOICE = "asteria";

/** Long inputs are a mistake, not a request — a model reciting an essay. */
const MAX_CHARS = 1200;

export interface TtsResult {
  audio: Uint8Array;
  mime: string;
}

export async function synthesize(
  env: Env,
  text: string,
  voice?: string,
): Promise<TtsResult | { error: string }> {
  const say = text.trim().slice(0, MAX_CHARS);
  if (!say) return { error: "nothing to say" };

  // An explicitly configured model wins, but a stale or wrong value must not
  // take speech down — hence the fallback rather than trusting the env var.
  const model = env.TTS_MODEL?.includes("aura") ? env.TTS_MODEL : DEFAULT_TTS_MODEL;

  try {
    const out = (await env.AI.run(model as never, {
      text: say,
      speaker: voice || DEFAULT_VOICE,
    } as never)) as unknown;

    const audio = await audioBytes(out);
    if (!audio) return { error: "the model returned no audio" };

    return { audio, mime: "audio/mpeg" };
  } catch (e) {
    return { error: (e as Error).message ?? String(e) };
  }
}

/**
 * aura returns a stream of MP3 frames; some Workers AI audio models return
 * base64 JSON instead. Handling one shape breaks silently on the other.
 */
async function audioBytes(out: unknown): Promise<Uint8Array | null> {
  if (out instanceof ReadableStream) {
    const buf = new Uint8Array(await new Response(out).arrayBuffer());
    return buf.byteLength ? buf : null;
  }
  if (out instanceof ArrayBuffer) {
    const buf = new Uint8Array(out);
    return buf.byteLength ? buf : null;
  }
  const b64 = (out as { audio?: unknown })?.audio;
  if (typeof b64 === "string" && b64) {
    const bin = atob(b64);
    const buf = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
    return buf;
  }
  return null;
}

/**
 * Voices aura-2 offers, for the settings picker.
 *
 * Hardcoded because Workers AI does not expose them through `models()` — the
 * speaker list lives in the binding's TYPE, which is not readable at runtime.
 * A stale entry here degrades to the default rather than failing.
 */
export const TTS_VOICES = [
  "asteria", "luna", "stella", "athena", "hera",
  "orion", "arcas", "perseus", "angus", "orpheus",
  "helios", "zeus", "apollo", "aurora", "iris",
] as const;
