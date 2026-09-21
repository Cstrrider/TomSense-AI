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
  /** Small non-reasoning model for the utility tier; see task_model.ts. */
  TASK_MODEL: string;
  TIER2_MODEL: string;
  /** Budget mode: free daily neuron allowance, and the % at which to downshift. */
  NEURON_DAILY_LIMIT: string;
  NEURON_SOFT_CAP_PCT: string;
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
 *   done       end of one model ROUND, carrying tool_calls/content/usage
 *
 * Two events are additions, both consequences of the generation living in a
 * Durable Object rather than inside the request that started it:
 *
 *   run        first frame; carries the run id, so the client can reconnect,
 *              stop, or return tool results for this generation
 *   end        the run as a whole is finished — distinct from `done`, which is
 *              now a round boundary. A generation that uses tools emits
 *              several `done`s and exactly one `end`.
 *
 * A generation that calls no tools emits one `done` then `end`, so what a
 * simple client sees is unchanged from main.
 */
export type StreamEvent =
  | { type: "text"; text: string }
  | { type: "reasoning"; text: string }
  | { type: "heartbeat" }
  | { type: "run"; runId: string; status: string }
  /**
   * A routing override worth telling the user about — "answering with your
   * Vision model", "budget mode". Persisted with the run, so it replays on
   * reconnect: a model swap the user never sees is a model swap they will
   * eventually be confused by.
   */
  | { type: "notice"; text: string }
  /**
   * A file the run produced — a generated image, for instance. Carries the R2
   * key rather than bytes: the client fetches it through the authenticated
   * /files route, so nothing large ever travels down the SSE stream.
   */
  | { type: "attachment"; key: string; mime: string }
  | { type: "done"; content: string; toolCalls: ToolCall[]; usage: Usage; stalled?: boolean }
  | { type: "end"; status: string; error?: string };

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
  /**
   * Present on an assistant turn that asked for tools. Kept in provider wire
   * format rather than our `ToolCall` shape because it is replayed verbatim
   * into the next request — providers reject tool results whose matching call
   * is missing or reshaped.
   */
  tool_calls?: unknown[];
  tool_call_id?: string;
  name?: string;
  /**
   * R2 keys the client attached to this turn.
   *
   * Kept as KEYS on the wire and in storage, and expanded into image_url
   * parts only at request time (see expandAttachments). Inlining base64 into
   * the stored message would put megabytes into every sync payload and into
   * every replay of the conversation.
   */
  attachments?: string[];
}
