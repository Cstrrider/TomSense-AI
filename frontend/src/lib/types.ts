export type Role = 'system' | 'user' | 'assistant' | 'tool';
export type UploadKind = 'image' | 'text' | 'pdf';

export interface Message {
  role: Role;
  content: string;
  tool_calls?: unknown[] | null;
  tool_call_id?: string | null;
  uploads?: UploadRef[];
  /** db id; absent on client-side optimistic-stream placeholders */
  dbId?: number;
}

export interface UploadRef {
  id: string;
  kind: UploadKind;
  filename: string;
  mime: string;
  size_bytes: number;
}

export interface UploadResponse extends UploadRef {
  text_preview: string | null;
}

export interface Artifact {
  id: number;
  message_id: number | null;
  kind: 'image' | 'code' | 'report';
  title: string | null;
  url: string | null;
  content: string | null;
  language: string | null;
  created_at: string;
}

/** Cross-chat artifact (from `GET /me/artifacts`) — adds chat provenance
 *  and ships only a truncated preview of the body; full content via
 *  `getArtifact(id)`. */
export interface UserArtifact {
  id: number;
  chat_id: string;
  chat_title: string | null;
  message_id: number | null;
  kind: 'image' | 'code' | 'report';
  title: string | null;
  url: string | null;
  content_preview: string | null;
  content_len: number;
  language: string | null;
  created_at: string | null;
}

/** Project mount registered via /me/mounts — a host directory bound into
 *  the code-mode sandbox at /workspace/projects/<name>/. */
export interface ProjectMount {
  name: string;
  host_path: string;
}

export interface MountsResponse {
  mounts: ProjectMount[];
  /** True when the backend has docker proxy access and can recreate the
   *  sandbox itself. False = UI shows the manual shell command instead. */
  apply_supported: boolean;
}

export interface MountsApplyResult {
  ok: boolean;
  log: string;
}

/** One entry from `GET /sandbox/fs/list`. */
export interface SandboxEntry {
  name: string;
  type: 'file' | 'dir';
  size: number;
}

export interface SandboxListing {
  path: string;
  entries: SandboxEntry[];
}

export interface SandboxFileContent {
  path: string;
  content: string;
  total_lines: number;
  offset: number;
  returned_lines: number;
  truncated: boolean;
}

export interface Chat {
  id: string;
  title: string | null;
  model: string;
  system_prompt?: string | null;
  share_token?: string | null;
  is_pinned?: boolean;
  is_code?: boolean;
  folder?: string | null;
  project_id?: string | null;
  created_at: string;
  updated_at: string;
}

export interface SearchHit {
  message_id: number;
  role: Role;
  snippet: string;
}

export interface ChatSearchResult extends Chat {
  title_match: boolean;
  hits: SearchHit[];
}

export interface Project {
  id: string;
  name: string;
  system_prompt?: string | null;
  created_at: string;
  updated_at: string;
}

export interface Persona {
  id: string;
  name: string;
  system_prompt: string | null;
  model: string | null;
  created_at: string;
  updated_at: string;
}

export interface ChatWithMessages extends Chat {
  messages: PersistedMessage[];
}

export interface PersistedMessage {
  id: number;
  role: Role;
  content: string;
  tool_calls: unknown[] | null;
  uploads?: UploadRef[];
  created_at: string;
}

export interface ProviderOption {
  id: string;
  label: string;
  voices?: string[];
}

/** One slot in the tool-models registry — sourced from /info. The
 *  `ToolKey` literal type below should stay in sync with the backend's
 *  TOOL_MODELS_CATALOG; mismatch is logged in the UI but not enforced. */
export interface ToolModelCatalogEntry {
  key: string;
  label: string;
  chip: string;
  hint: string;
}

export interface InfoResponse {
  name: string;
  version: string;
  chat_model: string;
  chat_model_short?: string;
  tools: string[];
  auth_required: boolean;
  tts_providers?: ProviderOption[];
  stt_providers?: ProviderOption[];
  tool_models_catalog?: ToolModelCatalogEntry[];
  /** Default code-mode picker entries — sourced by codeModels.ts. */
  code_models_catalog?: Array<{ id: string; label: string; hint: string }>;
}

export type ToolKey =
  | 'chat'
  | 'vision'
  | 'code'
  | 'code_mode'
  | 'research'
  | 'image'
  | 'image_hd'
  | 'image_edit'
  | 'image_edit_hd';

export interface ToolModels {
  chat?: string | null;
  vision?: string | null;
  code?: string | null;
  code_mode?: string | null;
  research?: string | null;
  image?: string | null;
  image_hd?: string | null;
  image_edit?: string | null;
  image_edit_hd?: string | null;
}

export interface ProviderModel {
  id: string;
  label: string;
  note?: string;
  tools: ToolKey[];
  /** Declared capabilities — the backend registry trusts these over any
   *  name-based guess, so vision/reasoning models are handled correctly on
   *  any provider (fixes CF-name-substring misclassification). */
  vision?: boolean;
  reasoning?: boolean;
  context?: number;
  /** Optional step-count override for Flux family models. Klein bills per
   *  megapixel so this only affects latency/quality there; Dev bills per
   *  step so this directly drives cost. Ignored for non-Flux models. */
  steps?: number | null;
}

/** A single masked credential entry from `GET /me/credentials`. */
export interface CredentialInfo {
  set: boolean;
  preview: string;
}

export interface Credentials {
  cf_api_token: CredentialInfo;
  anthropic_api_key: CredentialInfo;
}

export interface Provider {
  id: string;
  name: string;
  base_url: string;
  api_key: string;            // masked on the wire (e.g. "sk-a…b3c2")
  kind: string;               // "openai-compat"
  models: ProviderModel[];
  builtin?: boolean;          // true for the synthetic Cloudflare provider
  created_at?: string;
  updated_at?: string;
}

export interface ProviderTestResult {
  ok: boolean;
  status?: number;
  error?: string;
  model_count?: number | null;
}

export interface UserPrefs {
  tts_provider?: string;
  tts_voice?: string;
  stt_provider?: string;
  tool_models?: ToolModels;
  /** When non-empty, replaces the frontend's hardcoded Cloudflare model list
   *  in the dropdowns. Lets the user add models CF launches after this
   *  build, or remove ones that get deprecated. */
  cf_models?: ProviderModel[];
  export_format?: 'md' | 'json';
  /** First-run wizard "I've seen this" flag. Auto-redirect to /setup runs
   *  once per user; setting this to true makes the redirect stop. */
  setup_dismissed?: boolean;
  /** Code mode: when true, every file edit pauses for Apply/Reject before it
   *  lands on disk (the diff-approval gate). Default off. */
  review_edits?: boolean;
  /** Chat: difficulty-route default turns to the heavy model. Default on. */
  auto_route?: boolean;
  /** Chat: auto-extract durable facts into memory. Default on. */
  auto_memory?: boolean;
  /** Code mode: run the post-edit build/type-check. Default on (undefined=on). */
  verify_edits?: boolean;
  /** Code mode: per-user agentic round cap override (null/unset = env default). */
  max_rounds_code?: number | null;
  /** Code mode: per-user response-length cap override (null/unset = default). */
  max_tokens_coder?: number | null;
  /** Sidebar usage counter: 'auto' follows the active provider (CF→neurons,
   *  others→tokens/cost), 'neurons' always CF, 'tokens' always tokens+cost.
   *  Unset = auto. */
  usage_display?: 'auto' | 'neurons' | 'tokens';
}

export interface MeResponse {
  email: string;
  name: string | null;
  tokens_used: number;
  monthly_token_limit: number;
  usage_period_start: string;
  chat_count: number;
}

export interface NeuronUsage {
  used: number;
  limit: number;
  period_start: string;
  period_end: string;
  error?: string;
  /** Estimated $ spend today: neurons = Workers AI overage (0 until past the
   *  free daily allocation), gateway = unified-billing external providers. */
  dollars?: { neurons: number; gateway: number; total: number };
}

export interface UsageProvider {
  provider_id: string;
  provider_name: string | null;
  tokens_in: number;
  tokens_out: number;
  cost: number;
  requests: number;
}

/** Today's per-provider token/cost usage (sidebar counter, tokens mode). */
export interface UsageToday {
  providers: UsageProvider[];
  active_provider: string;
  totals: { tokens_in: number; tokens_out: number; tokens: number; cost: number };
}

export interface Memory {
  id: number;
  content: string;
  created_at: string;
}

export interface UserUpload {
  id: string;
  kind: UploadKind;
  filename: string;
  mime: string;
  size_bytes: number;
  created_at: string;
  indexed: boolean;
  /** Project this upload is attached to as a knowledge file (or null). */
  project_id?: string | null;
}
