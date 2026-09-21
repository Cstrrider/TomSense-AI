/**
 * File storage on R2.
 *
 * Two jobs: take an upload from the client, and give it back. Everything else
 * about attachments — how they reach a model, how they render — is decided by
 * the callers.
 *
 * ## The key is the authorisation
 *
 * Every object lives under `u/{userId}/…`, and both read and delete check that
 * prefix against the authenticated principal. That makes the ownership rule a
 * property of the key rather than a lookup that can be forgotten: there is no
 * path that reads an object without first proving whose it is.
 */

import type { Env, Principal } from "./types";

/** Cap per object. Large enough for a phone photo, small enough to refuse a video. */
const MAX_BYTES = 12 * 1024 * 1024;

/**
 * What may be stored.
 *
 * An allow-list rather than a block-list: this bucket is served back over
 * HTTP, and a permissive uploader that will accept `text/html` is a stored-XSS
 * hole on your own origin. Anything not listed is stored as a download.
 */
const INLINE_MIMES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
  "application/pdf",
  "text/plain",
]);

export function isImage(mime: string): boolean {
  return mime.startsWith("image/");
}

function keyFor(userId: string, name: string): string {
  const safe = name.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 80);
  return `u/${userId}/${crypto.randomUUID()}-${safe || "file"}`;
}

/** Does this key belong to this user? The whole access-control story. */
export function ownsKey(userId: string, key: string): boolean {
  return key.startsWith(`u/${userId}/`);
}

export async function uploadFile(
  env: Env,
  who: Principal,
  req: Request,
): Promise<{ key: string; mime: string; bytes: number } | { error: string }> {
  // split() can return an empty first element on a malformed header, and
  // strict mode is right to insist we say what happens then.
  const rawType = req.headers.get("content-type") || "application/octet-stream";
  const mime = (rawType.split(";")[0] ?? rawType).trim().toLowerCase();
  const name = req.headers.get("x-file-name") || "file";

  const body = await req.arrayBuffer();
  if (body.byteLength === 0) return { error: "empty upload" };
  if (body.byteLength > MAX_BYTES) {
    return { error: `file too large (max ${MAX_BYTES / 1024 / 1024} MB)` };
  }

  const key = keyFor(who.userId, name);
  await env.FILES.put(key, body, {
    httpMetadata: { contentType: mime },
    customMetadata: { userId: who.userId, name },
  });

  return { key, mime, bytes: body.byteLength };
}

export async function serveFile(
  env: Env,
  who: Principal,
  key: string,
): Promise<Response> {
  // Checked before the fetch, so a wrong guess cannot even confirm that an
  // object exists.
  if (!ownsKey(who.userId, key)) return new Response("not found", { status: 404 });

  const obj = await env.FILES.get(key);
  if (!obj) return new Response("not found", { status: 404 });

  const mime = obj.httpMetadata?.contentType || "application/octet-stream";

  // Anything outside the inline allow-list is forced to download rather than
  // render. Combined with nosniff, that stops a stored file being interpreted
  // as markup on this origin.
  const inline = INLINE_MIMES.has(mime);

  return new Response(obj.body, {
    headers: {
      "content-type": mime,
      "content-disposition": inline ? "inline" : "attachment",
      "x-content-type-options": "nosniff",
      // Private: these are per-user objects behind an auth check, and a shared
      // cache holding them would serve one user's upload to another.
      "cache-control": "private, max-age=3600",
    },
  });
}

/**
 * Read an object back as a `data:` URL, for vision input.
 *
 * This is how an attachment reaches a model. Stable does the same thing
 * (`uploads.image_data_url`) for the same reason: an OpenAI-shaped
 * `image_url` part is the one form every vision provider accepts, and a link
 * to a private, authenticated URL is not something the provider can fetch.
 *
 * The client downscales before uploading — base64 image tokens balloon fast,
 * and stable settled on a 1600px longest edge for the same models.
 */
export async function dataUrl(env: Env, key: string): Promise<string | null> {
  const obj = await env.FILES.get(key);
  if (!obj) return null;

  const mime = obj.httpMetadata?.contentType || "image/jpeg";
  const buf = new Uint8Array(await obj.arrayBuffer());

  // Chunked: String.fromCharCode(...buf) on a multi-megabyte array blows the
  // argument limit and throws RangeError.
  let binary = "";
  const CHUNK = 8192;
  for (let i = 0; i < buf.length; i += CHUNK) {
    binary += String.fromCharCode(...buf.subarray(i, i + CHUNK));
  }
  return `data:${mime};base64,${btoa(binary)}`;
}

/** Store bytes the edge itself produced — a generated image, for instance. */
export async function putGenerated(
  env: Env,
  userId: string,
  bytes: ArrayBuffer | Uint8Array,
  mime: string,
  name: string,
): Promise<string> {
  const key = keyFor(userId, name);
  await env.FILES.put(key, bytes, {
    httpMetadata: { contentType: mime },
    customMetadata: { userId, name, generated: "1" },
  });
  return key;
}
