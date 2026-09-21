/**
 * Running Cloudflare image models.
 *
 * Exists because Workers AI image models do not share one request shape, and
 * the difference is invisible until call time:
 *
 *   flux-1-schnell, SDXL, dreamshaper …  JSON      { prompt }
 *   flux-2-klein, flux-2-dev …           MULTIPART form-data
 *
 * A flux-2 model given JSON returns `required properties at '/' are
 * 'multipart'`, which surfaces to the user as the assistant apologising that
 * it cannot generate images. Picking flux-2 in settings did exactly that.
 *
 * Multipart is also the only way to EDIT: the source goes in `input_image_0`
 * (through `input_image_3`; up to four), and — the part that wastes an
 * afternoon if you miss it — **every input image must be under 512×512**. An
 * oversized one is silently ignored, so the model cheerfully generates a fresh
 * picture from the prompt and nothing indicates the source was dropped.
 */

import type { Env } from "./types";

/** Cloudflare's documented cap for flux-2 input images. */
const MAX_INPUT_EDGE = 512;
/** Comfortably under it, since the cap is exclusive in practice. */
const EDIT_EDGE = 480;

/** Models that take multipart rather than JSON. */
export function needsMultipart(modelId: string): boolean {
  return /flux-2/i.test(modelId);
}

export interface ImageRunResult {
  bytes: Uint8Array | null;
  error?: string;
}

/**
 * Generate or edit, picking the request shape from the model.
 *
 * `sources` are raw image bytes already sized for input; pass none to
 * generate. Returns bytes rather than a Response so the caller decides where
 * they are stored.
 */
export async function runImageModel(
  env: Env,
  modelId: string,
  prompt: string,
  sources: Uint8Array[] = [],
): Promise<ImageRunResult> {
  // Editing is only expressible in multipart, whatever the model normally
  // takes — so a source forces the shape.
  const multipart = sources.length > 0 || needsMultipart(modelId);

  try {
    const out = multipart
      ? await runMultipart(env, modelId, prompt, sources)
      : await env.AI.run(modelId as never, { prompt } as never);

    const bytes = await imageBytes(out);
    if (bytes) return { bytes };

    // A JSON model that turned out to need multipart says so in its error.
    // Retrying rather than failing means a model Cloudflare adds to the
    // multipart family later keeps working without a code change.
    if (!multipart) {
      const retry = await runMultipart(env, modelId, prompt, sources);
      return { bytes: await imageBytes(retry) };
    }
    return { bytes: null, error: "the model returned no image" };
  } catch (e) {
    const msg = (e as Error).message ?? String(e);
    if (!multipart && /multipart/i.test(msg)) {
      try {
        const retry = await runMultipart(env, modelId, prompt, sources);
        return { bytes: await imageBytes(retry) };
      } catch (e2) {
        return { bytes: null, error: (e2 as Error).message };
      }
    }
    return { bytes: null, error: msg };
  }
}

/**
 * Build a multipart body and hand it to the AI binding.
 *
 * The binding takes `{ multipart: { body, contentType } }` — it does not build
 * the form itself. `Request` is used purely as a multipart encoder: it
 * produces both the serialised body and the matching boundary header, which is
 * fiddly and easy to get subtly wrong by hand.
 */
async function runMultipart(
  env: Env,
  modelId: string,
  prompt: string,
  sources: Uint8Array[],
): Promise<unknown> {
  const form = new FormData();
  form.append("prompt", prompt);

  // Up to four, named input_image_0..3 exactly as Cloudflare documents.
  sources.slice(0, 4).forEach((src, i) => {
    form.append(
      `input_image_${i}`,
      new Blob([src as unknown as ArrayBuffer], { type: "image/jpeg" }),
      `input_${i}.jpg`,
    );
  });

  const encoder = new Request("https://encode.invalid", { method: "POST", body: form });
  const contentType = encoder.headers.get("content-type") ?? "multipart/form-data";

  // `body` MUST be a ReadableStream. An ArrayBuffer, a Uint8Array and a raw
  // FormData are each rejected with "8001: Invalid input" — verified against
  // the live binding, which matters because the declared type (`body?: object`)
  // admits all of them and the failure says nothing about which is wanted.
  return env.AI.run(
    modelId as never,
    { multipart: { body: encoder.body, contentType } } as never,
  );
}

/**
 * Shrink an image to something flux-2 will actually look at.
 *
 * Returns null when it cannot, and the caller must treat that as a hard
 * failure rather than sending the original: an oversized input is DROPPED
 * silently, which turns "edit this" into "generate something else" with no
 * error anywhere.
 */
export async function resizeForEdit(
  env: Env,
  bytes: Uint8Array,
): Promise<Uint8Array | null> {
  if (!env.IMAGES) return null;
  try {
    const source = new Response(bytes as BodyInit).body;
    if (!source) return null;

    const result = await env.IMAGES.input(source)
      .transform({ width: EDIT_EDGE, height: EDIT_EDGE, fit: "scale-down" })
      .output({ format: "image/jpeg", quality: 90 });

    const out = new Uint8Array(await new Response(result.image()).arrayBuffer());
    return out.byteLength ? out : null;
  } catch {
    return null;
  }
}

export { MAX_INPUT_EDGE };

/**
 * Workers AI image models return EITHER a stream of bytes or JSON carrying
 * base64. Handling one shape silently breaks when the model changes.
 */
export async function imageBytes(out: unknown): Promise<Uint8Array | null> {
  if (out instanceof ReadableStream) {
    const buf = new Uint8Array(await new Response(out).arrayBuffer());
    return buf.byteLength ? buf : null;
  }

  const b64 = (out as { image?: unknown })?.image;
  if (typeof b64 === "string" && b64) {
    const bin = atob(b64);
    const buf = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
    return buf;
  }
  return null;
}
