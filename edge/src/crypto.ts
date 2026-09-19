/**
 * Provider API keys at rest in D1.
 *
 * D1 is a managed database whose backups and exports you do not fully control,
 * so BYO keys are wrapped with a Worker secret before they are stored. This is
 * envelope-lite: it protects against a database dump, NOT against a compromised
 * Worker — anything with KEY_ENC_SECRET can unwrap. That is the honest scope.
 *
 * Note this is a DIFFERENT mechanism from the per-project E2EE in spec §10.
 * That one uses a device-held key the Worker never sees; this one the Worker
 * must be able to unwrap, because it has to call the provider on your behalf.
 */

import type { Env } from "./types";

const enc = new TextEncoder();
const dec = new TextDecoder();

let cachedKey: CryptoKey | null = null;

async function aesKey(env: Env): Promise<CryptoKey> {
  if (cachedKey) return cachedKey;
  // The secret is arbitrary-length text; SHA-256 it into exactly 256 bits.
  const digest = await crypto.subtle.digest("SHA-256", enc.encode(env.KEY_ENC_SECRET));
  cachedKey = await crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, [
    "encrypt",
    "decrypt",
  ]);
  return cachedKey;
}

function b64encode(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function b64decode(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Returns `v1.<iv>.<ciphertext>`, both base64. */
export async function encryptKey(env: Env, plaintext: string): Promise<string> {
  if (!plaintext) return "";
  const key = await aesKey(env);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, enc.encode(plaintext));
  return `v1.${b64encode(iv.buffer)}.${b64encode(ct)}`;
}

export async function decryptKey(env: Env, stored: string): Promise<string> {
  if (!stored) return "";
  // Tolerate pre-encryption rows so a partial migration doesn't hard-fail:
  // anything without the version prefix is assumed to be plaintext.
  if (!stored.startsWith("v1.")) return stored;

  const parts = stored.split(".");
  const ivB64 = parts[1];
  const ctB64 = parts[2];
  if (parts.length !== 3 || !ivB64 || !ctB64) return "";

  try {
    const key = await aesKey(env);
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: b64decode(ivB64) },
      key,
      b64decode(ctB64),
    );
    return dec.decode(pt);
  } catch {
    // Wrong secret or corrupted row. Return empty rather than throwing, so one
    // bad provider row degrades to "no key" instead of breaking every request.
    return "";
  }
}
