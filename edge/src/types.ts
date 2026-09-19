export interface Env {
  DB: D1Database;
  /** Memory layer 3 (§9). Optional: the binding is not attached until M7, and
   *  the deploy token currently lacks Vectorize permission. */
  VECTORS?: VectorizeIndex;
  FILES: R2Bucket;
  AI: Ai;

  VOICE: DurableObjectNamespace;
  RUN: DurableObjectNamespace;
  HOMELINK: DurableObjectNamespace;

  TIER1_MODEL: string;
  TIER2_MODEL: string;
  STT_MODEL: string;
  TTS_MODEL: string;

  /** CF Access team domain, e.g. "example.cloudflareaccess.com". */
  ACCESS_TEAM_DOMAIN: string;
  /** Access application AUD tag. Unset → all JWT auth is rejected. */
  ACCESS_AUD: string;

  KEY_ENC_SECRET: string;
  HOME_AGENT_TOKEN: string;
}

/** Authenticated caller, resolved by auth.ts from CF Access or a device token. */
export interface Principal {
  userId: string;
  email: string;
  deviceId: string;
}

/**
 * Stream event contract, preserved verbatim from `providers.stream_round` on
 * main so existing clients stay close to working.
 *
 *   text       incremental assistant content
 *   reasoning  incremental reasoning trace (thinking models)
 *   heartbeat  liveness during a long silent stretch; carries no payload
 *   done       terminal, exactly once, carries tool_calls/content/usage
 */
export type StreamEvent =
  | { type: "text"; text: string }
  | { type: "reasoning"; text: string }
  | { type: "heartbeat" }
  | { type: "done"; content: string; toolCalls: ToolCall[]; usage: Usage; stalled?: boolean };

export interface ToolCall {
  id: string;
  name: string;
  arguments: unknown;
}

export interface Usage {
  in: number;
  out: number;
  /** Prompt-cache instrumentation carried over from main — first-class, not
   *  buried in a blob, because it is how you tell if caching actually works. */
  cache_read: number;
  cache_write: number;
}

/** A model's declared capabilities — see capabilities.ts for resolution order. */
export interface Capabilities {
  vision: boolean;
  reasoning: boolean;
  context: number | null;
}

export interface ModelEntry {
  id: string;
  vision?: boolean;
  reasoning?: boolean;
  context?: number;
  /** Tool roles this model is assigned to, e.g. ["vision", "title"]. */
  tools?: string[];
}

export interface Provider {
  id: string;
  name: string;
  kind: "openai-compat" | "anthropic" | "cf";
  baseUrl: string;
  apiKey: string;
  models: ModelEntry[];
  extraBody: Record<string, unknown>;
}

export interface ChatMessage {
  role: "user" | "assistant" | "system" | "tool";
  content: unknown;
  tool_call_id?: string;
  name?: string;
}
