/**
 * Shared state for the settings drawer (Phase 1 split of ChatSettings.svelte).
 *
 * One reactive `S` object ($state — deep) + exported action functions, imported
 * by the drawer shell and every tab component. Derived values are exported as
 * functions (they read $state during render, so callers stay reactive).
 *
 * The drawer is a singleton UI, so module-level singleton state is appropriate
 * — no prop-drilling through eight tabs.
 */
import { untrack } from 'svelte';
import {
  addMemory,
  createPersona,
  createProvider,
  createShare,
  deleteMemory,
  deletePersona,
  deleteProvider,
  deleteUserUpload,
  getChat,
  getCredentials,
  getPrefs,
  listMemories,
  listPersonas,
  listProviders,
  listSecrets,
  setSecret,
  deleteSecret,
  type SecretMeta,
  listUserUploads,
  reindexUpload,
  getRagStatus,
  reindexAll,
  discoverProviderModels,
  type RagStatus,
  revokeShare,
  setChatModel,
  setCredentials,
  setSystemPrompt,
  testProvider,
  updatePersona,
  updatePrefs,
  updateProvider,
  listMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  testMcpServer,
  type McpServer
} from '$lib/api';
import { app } from '$lib/stores.svelte';
import { getCodeModeModels } from '$lib/codeModels';
import { buildToolOptions } from '$lib/modelOptions';
import { getInstanceUrl, setInstanceUrl } from '$lib/clienttools';
import { setThemeChoice, type ThemeChoice } from '$lib/theme';
import { toast } from '$lib/toast.svelte';
import type {
  Credentials,
  Memory,
  Persona,
  Provider,
  ProviderModel,
  ToolKey,
  ToolModels,
  UserPrefs,
  UserUpload
} from '$lib/types';

export type Tab = 'persona' | 'memory' | 'providers' | 'voice' | 'share' | 'coder' | 'secrets' | 'app';
export type ModelOption = { id: string; label: string; note?: string };

export const PENDING_KEY = 'pending-persona';
export const CF_BUILTIN_ID = 'cf';
export const CUSTOM_PREFIX = 'custom:';

// ─── Catalog constants (fallbacks until /info loads) ─────────────────────────

// Tool-model slots — sourced from /info's tool_models_catalog (the backend's
// single source of truth, defined in tool_registry.py). The fallback values
// below are used only if /info hasn't loaded yet.
const FALLBACK_CATALOG = [
  { key: 'chat', label: 'Chat', chip: 'chat', hint: 'Main model for conversation and tool dispatch.' },
  { key: 'vision', label: 'Vision', chip: 'vision', hint: 'Handles turns with image attachments. Unset = the Chat model.' },
  { key: 'code', label: 'Code Writer', chip: 'writer', hint: '🪄 Single-shot — used by /code → consult_coder.' },
  { key: 'code_mode', label: 'Code Mode', chip: 'agent', hint: '🤖 Agentic — used by code chats.' },
  { key: 'research', label: 'Research', chip: 'research', hint: 'Used by /research → deep_research synthesis.' },
  { key: 'title', label: 'Utility / Title', chip: 'utility', hint: 'Tiny model for titles, follow-ups, auto-memory, routing.' },
  { key: 'image', label: 'Image', chip: 'img', hint: 'Text-to-image, default quality.' },
  { key: 'image_hd', label: 'Image (HD)', chip: 'img HD', hint: 'Text-to-image, picked when you type /HD.' },
  { key: 'image_edit', label: 'Image edit', chip: 'edit', hint: 'Image-to-image, default quality.' },
  { key: 'image_edit_hd', label: 'Image edit (HD)', chip: 'edit HD', hint: 'Image-to-image, picked when you type /HD.' }
];

// Model catalogs — hardcoded fallbacks used only until /info's
// cf_models_catalog loads (or if it's empty). Backend defaults live in
// config.py; these mirror them so the UI can show the active selection even
// when the user hasn't picked one yet.
export const CHAT_MODELS: ModelOption[] = [
  { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 (26B)', note: 'default · vision + tools' },
  { id: '@cf/moonshotai/kimi-k2.7-code', label: 'Kimi K2.7 (1T)', note: 'frontier · 262k ctx · vision + tools · neuron-hungry' },
  { id: '@cf/zai-org/glm-5.2', label: 'GLM-5.2', note: 'flagship agentic · 262k ctx · neuron-hungry' },
  { id: '@cf/meta/llama-3.3-70b-instruct-fp8-fast', label: 'Llama 3.3 70B', note: 'fastest · text-only' },
  { id: '@cf/openai/gpt-oss-120b', label: 'GPT-OSS 120B', note: 'reasoning · expensive' },
  { id: '@cf/openai/gpt-oss-20b', label: 'GPT-OSS 20B', note: 'reasoning · cheap' },
  { id: '@cf/meta/llama-4-scout-17b-16e-instruct', label: 'Llama 4 Scout 17B' },
  { id: '@cf/meta/llama-3.1-8b-instruct', label: 'Llama 3.1 8B' },
  { id: '@cf/meta/llama-3.2-3b-instruct', label: 'Llama 3.2 3B', note: 'tiny' }
];
const CODE_MODELS: ModelOption[] = [
  { id: '@cf/openai/gpt-oss-120b', label: 'GPT-OSS 120B', note: 'default · best' },
  { id: '@cf/moonshotai/kimi-k2.7-code', label: 'Kimi K2.7 (1T)', note: 'frontier coder · 262k ctx' },
  { id: '@cf/zai-org/glm-5.2', label: 'GLM-5.2', note: 'flagship agentic coder' },
  { id: '@cf/qwen/qwen3-30b-a3b-fp8', label: 'Qwen3 30B', note: 'fast & cheap MoE · 3B active FP8' },
  { id: '@cf/openai/gpt-oss-20b', label: 'GPT-OSS 20B', note: 'cheaper' },
  { id: '@cf/meta/llama-3.3-70b-instruct-fp8-fast', label: 'Llama 3.3 70B' },
  { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 (26B)' }
];
const VISION_MODELS: ModelOption[] = [
  { id: '@cf/meta/llama-4-scout-17b-16e-instruct', label: 'Llama 4 Scout 17B', note: 'fast multimodal MoE' },
  { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 (26B)', note: 'same as chat default' },
  { id: '@cf/moonshotai/kimi-k2.7-code', label: 'Kimi K2.7 (1T)', note: 'frontier · neuron-hungry' }
];
const RESEARCH_MODELS: ModelOption[] = [
  { id: '@cf/openai/gpt-oss-120b', label: 'GPT-OSS 120B', note: 'default' },
  { id: '@cf/openai/gpt-oss-20b', label: 'GPT-OSS 20B' },
  { id: '@cf/meta/llama-3.3-70b-instruct-fp8-fast', label: 'Llama 3.3 70B' }
];
const TITLE_MODELS: ModelOption[] = [
  { id: '@cf/meta/llama-3.2-3b-instruct', label: 'Llama 3.2 3B', note: 'default · tiny & cheap' },
  { id: '@cf/meta/llama-3.1-8b-instruct', label: 'Llama 3.1 8B' },
  { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 (26B)' }
];
const IMAGE_GEN_MODELS: ModelOption[] = [
  { id: '@cf/black-forest-labs/flux-2-klein-4b', label: 'Flux 2 Klein 4B', note: 'default · ~$0.001/img' },
  { id: '@cf/runwayml/stable-diffusion-v1-5-img2img', label: 'SD v1.5', note: 'beta · FREE · CF' },
  { id: '@cf/black-forest-labs/flux-2-klein-9b', label: 'Flux 2 Klein 9B', note: '~$0.015/img · better quality' },
  { id: 'google/imagen-4', label: 'Imagen 4', note: '~$0.04/img · photorealistic' },
  { id: '@cf/black-forest-labs/flux-2-dev', label: 'Flux 2 Dev', note: 'premium · ~$0.04/img · multi-ref' },
  { id: 'openai/gpt-image-2', label: 'gpt-image-2', note: '$0.055/img medium · OpenAI' },
  { id: 'google/nano-banana-2', label: 'Nano Banana 2', note: '~$0.08/img · best all-rounder' }
];
const IMAGE_EDIT_MODELS: ModelOption[] = [
  { id: '@cf/runwayml/stable-diffusion-v1-5-img2img', label: 'SD v1.5 img2img', note: 'beta · FREE · single ref' },
  { id: '@cf/black-forest-labs/flux-2-klein-4b', label: 'Flux 2 Klein 4B', note: 'default · ~$0.001/edit · multi-ref' },
  { id: '@cf/black-forest-labs/flux-2-klein-9b', label: 'Flux 2 Klein 9B', note: '~$0.017/edit · multi-ref' },
  { id: '@cf/black-forest-labs/flux-2-dev', label: 'Flux 2 Dev', note: 'premium · ~$0.06/edit · multi-ref' },
  { id: 'openai/gpt-image-2', label: 'gpt-image-2', note: '~$0.10/edit · best edits' },
  { id: 'google/nano-banana-2', label: 'Nano Banana 2', note: '~$0.08/edit · great identity preservation' }
];
const CF_FALLBACK: Record<ToolKey, ModelOption[]> = {
  chat: CHAT_MODELS,
  vision: VISION_MODELS,
  code: CODE_MODELS,
  code_mode: [],
  research: RESEARCH_MODELS,
  title: TITLE_MODELS,
  image: IMAGE_GEN_MODELS,
  image_hd: IMAGE_GEN_MODELS,
  image_edit: IMAGE_EDIT_MODELS,
  image_edit_hd: IMAGE_EDIT_MODELS
};

// ─── The reactive state object ────────────────────────────────────────────────

export const S = $state({
  chatId: null as string | null,
  tab: 'providers' as Tab,

  // Theme choice (System / Dark / Light) — device-local via lib/theme.ts.
  // Seeded in load() (not at module init) so SSR/prerender never touches
  // localStorage.
  themeChoice: 'system' as ThemeChoice,

  // Instance URL (Android APK only) — null means "not running in the app",
  // which hides the server-URL block. See ServerConfig.java / ActionsPlugin.
  instanceUrl: null as { url: string; isSet: boolean } | null,
  instanceUrlDraft: '',
  instanceUrlSaving: false,

  loading: false,
  saving: false,

  // Persona
  prompt: '',
  originalPrompt: '',

  // Picks per tool. chatModel reflects this chat's model when chatId is set,
  // normalized to provider::model.
  chatModel: '',
  originalChatModel: '',
  savingChatModel: false,
  toolModels: {} as ToolModels,
  savingToolKey: null as string | null,

  // Providers
  providersList: [] as Provider[],
  providersLoading: false,

  // Builtin-provider credentials (Cloudflare + Anthropic). Edited in-place
  // via a small per-row form; the raw key is never read back, only set/cleared.
  credentials: null as Credentials | null,
  credentialsLoading: false,
  credEditing: null as string | null,
  credDraft: '',
  credSaving: null as string | null,

  // Providers tab: collapsible section state. Models/Providers are the
  // day-to-day sections and start open; MCP and the HD model variants are
  // set-and-forget, so they start collapsed to cut clutter.
  secModels: true,
  secProviders: true,
  secMcp: false,
  secHd: false,
  secEmbed: false,

  // Custom CF model list — when non-empty, replaces the hardcoded defaults.
  cfModels: [] as ProviderModel[],
  cfFormOpen: false,
  cfFormDraft: [] as ProviderModel[],
  cfFormSaving: false,

  // Add/edit-provider form state. editingProviderId === null → creating new.
  providerFormOpen: false,
  editingProviderId: null as string | null,
  providerForm: { name: '', base_url: '', api_key: '', models: [] as ProviderModel[] },
  providerSaving: false,
  providerTesting: null as string | null,
  providerTestResult: {} as Record<string, string>,

  // Models discovered at the provider's /models endpoint — feed a compact
  // inline combobox (native <datalist> takes over the whole screen on mobile).
  discoveredModels: [] as string[],
  discovering: false,
  modelIdOpen: null as number | null,

  // Prefs (voice/coder/general)
  prefs: {} as UserPrefs,
  prefsLoading: false,
  prefsSaving: false,

  // Personas library
  personas: [] as Persona[],
  personasLoading: false,
  editingPersona: null as Persona | null,
  newPersonaOpen: false,
  personaForm: { name: '', system_prompt: '', model: '' },

  // Share
  shareToken: null as string | null,

  // Memories
  memories: [] as Memory[],
  memoriesLoading: false,
  newMemory: '',
  addingMemory: false,

  // Documents
  uploads: [] as UserUpload[],
  uploadsLoading: false,
  reindexing: new Set<string>(),

  // MCP servers (Providers tab)
  mcpServers: [] as McpServer[],
  mcpForm: { name: '', url: '', auth_header: '' },
  mcpSaving: false,
  mcpTesting: null as string | null,

  // Secret vault
  secrets: [] as SecretMeta[],
  secretsLoading: false,
  newSecretName: '',
  newSecretValue: '',
  savingSecret: false,

  // Coder tab number inputs (empty = use default). Seeded from prefs by
  // CoderTab's $effect.
  maxRoundsCode: '',
  maxTokensCoder: '',

  // Embedding backend (RAG)
  ragStatus: null as RagStatus | null,
  ragBusy: false
});

// ─── Derived values (functions — they read $state, so callers stay reactive) ──

export function toolCatalog() {
  return app.info?.tool_models_catalog ?? FALLBACK_CATALOG;
}
export function TOOL_KEYS(): ToolKey[] {
  return toolCatalog().map((t) => t.key as ToolKey);
}
export function TOOL_CHIP_LABEL(): Record<ToolKey, string> {
  return Object.fromEntries(toolCatalog().map((t) => [t.key, t.chip])) as Record<ToolKey, string>;
}
export function TOOL_LABELS() {
  return toolCatalog().map((t) => ({ key: t.key as ToolKey, label: t.label, hint: t.hint }));
}

/** Backwards-compat: stored model strings from before Round A don't carry
 *  a provider prefix. Treat them as Cloudflare so they still resolve. */
export function normalizeModelId(s: string | null | undefined): string {
  if (!s) return '';
  return s.includes('::') ? s : `${CF_BUILTIN_ID}::${s}`;
}

// Code Mode chats use the agentic coder loop and need a model that emits
// structured tool calls reliably. Sourced from /info (tool_registry.py); the
// same list drives the per-new-chat picker on `/?code=1`.
function codeModeModelOptions(): ModelOption[] {
  return getCodeModeModels(app.info).map((m) => ({ id: m.id, label: m.label, note: m.hint }));
}

// CF model options per tool, sourced from the backend registry
// (cf_models_catalog) by declared `roles`; HD variants reuse the base role.
function catalogFor(role: string): ModelOption[] {
  const cat = app.info?.cf_models_catalog ?? [];
  return cat
    .filter((m) => (m.roles ?? []).includes(role))
    .map((m) => ({ id: m.id, label: m.label, note: m.note }));
}
// Bundled Claude models by role → provider-qualified `anthropic::` options,
// so they show in the per-tool pickers alongside CF (not just code mode).
function anthropicFor(role: string): ModelOption[] {
  return (app.info?.anthropic_models_catalog ?? [])
    .filter((m) => (m.roles ?? []).includes(role))
    .map((m) => ({ id: `anthropic::${m.id}`, label: m.label, note: m.note }));
}
const cfOr = (role: string, fb: ModelOption[]) =>
  catalogFor(role).length ? catalogFor(role) : fb;

export function CF_DEFAULTS(): Record<ToolKey, ModelOption[]> {
  return {
    chat: [...cfOr('chat', CF_FALLBACK.chat), ...anthropicFor('chat')],
    vision: [...cfOr('vision', CF_FALLBACK.vision), ...anthropicFor('vision')],
    code: [...cfOr('code', CF_FALLBACK.code), ...anthropicFor('code')],
    // code_mode stays sourced from the dedicated code-mode picker catalog.
    code_mode: codeModeModelOptions(),
    research: [...cfOr('research', CF_FALLBACK.research), ...anthropicFor('research')],
    title: cfOr('title', CF_FALLBACK.title),
    image: cfOr('image', CF_FALLBACK.image),
    image_hd: cfOr('image', CF_FALLBACK.image),
    image_edit: cfOr('image_edit', CF_FALLBACK.image_edit),
    image_edit_hd: cfOr('image_edit', CF_FALLBACK.image_edit)
  };
}

/** Build the model-options list for a given tool — shared with the
 *  new-code-chat picker via buildToolOptions so custom CF models / provider
 *  models are added & removed identically everywhere. */
export function optionsForTool(toolKey: ToolKey): ModelOption[] {
  return buildToolOptions(toolKey, CF_DEFAULTS()[toolKey] ?? [], S.cfModels, S.providersList);
}

export function customProviders(): Provider[] {
  return S.providersList.filter((p) => !p.builtin);
}

// Model chips for the builtin provider cards — sourced from the registry so
// Cloudflare and Claude list their models like custom providers do. CF shows
// the user's cf_models override if set, else the catalogue.
export function cfCardModels() {
  return S.cfModels.length > 0
    ? S.cfModels.map((m) => ({ id: m.id, label: m.label, tags: (m.tools || []) as string[] }))
    : (app.info?.cf_models_catalog ?? []).map((m) => ({ id: m.id, label: m.label, tags: (m.roles || []) }));
}
export function claudeCardModels() {
  return (app.info?.anthropic_models_catalog ?? []).map((m) => ({ id: m.id, label: m.label, tags: (m.roles || []) }));
}

export function splitRef(v: string | null | undefined): { pid: string; model: string } {
  const s = v ?? '';
  const i = s.indexOf('::');
  return i < 0 ? { pid: '', model: '' } : { pid: s.slice(0, i), model: s.slice(i + 2) };
}

// Voice derived
export function currentTtsProvider() {
  return S.prefs.tts_provider ?? app.info?.tts_providers?.[0]?.id ?? 'piper';
}
export function currentTtsVoices() {
  return (app.info?.tts_providers ?? []).find((p) => p.id === currentTtsProvider())?.voices ?? [];
}
export function currentTtsVoice() {
  return S.prefs.tts_voice ?? currentTtsVoices()[0] ?? 'alloy';
}
export function currentSttProvider() {
  return S.prefs.stt_provider ?? app.info?.stt_providers?.[0]?.id ?? 'whisper';
}
export function ttsCustom() { return splitRef(S.prefs.tts_provider); }
export function ttsIsCustom() { return (S.prefs.tts_provider ?? '').includes('::'); }
export function ttsSelectValue() {
  return ttsIsCustom() ? `${CUSTOM_PREFIX}${ttsCustom().pid}` : currentTtsProvider();
}
export function sttCustom() { return splitRef(S.prefs.stt_provider); }
export function sttIsCustom() { return (S.prefs.stt_provider ?? '').includes('::'); }
export function sttSelectValue() {
  return sttIsCustom() ? `${CUSTOM_PREFIX}${sttCustom().pid}` : currentSttProvider();
}

// Embedding derived
export function embedCustom() { return splitRef(S.prefs.embed_model); }
export function embedIsCustom() { return !!(S.prefs.embed_model ?? '').includes('::'); }
export function embedSelectValue() { return embedIsCustom() ? embedCustom().pid : ''; }

// Coder derived
export function reviewEdits() { return !!(S.prefs.review_edits ?? app.prefs?.review_edits); }
export function verifyEdits() {
  // Auto-verify defaults ON: only false when explicitly turned off.
  return (S.prefs.verify_edits ?? app.prefs?.verify_edits ?? true) !== false;
}

// Persona / share derived
export function dirty() { return S.prompt !== S.originalPrompt; }
export function shareUrl() { return S.shareToken ? `${location.origin}/s/${S.shareToken}` : ''; }

// ─── Load orchestration ───────────────────────────────────────────────────────

export async function load() {
  S.loading = true;
  try {
    // Use untrack() when reading toolModels here. Without it, the $effect
    // that calls load() picks up toolModels.chat as a dependency; loadPrefs()
    // (called below) then writes toolModels, re-firing the effect → infinite
    // loop. Manifested as the Persona tab flickering between Loading and
    // empty states when settings opened on a new chat.
    if (S.chatId) {
      const chat = await getChat(S.chatId);
      S.prompt = chat.system_prompt ?? '';
      S.shareToken = chat.share_token ?? null;
      S.chatModel = normalizeModelId(
        chat.model
          ?? untrack(() => S.toolModels.chat)
          ?? `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`
      );
    } else {
      S.prompt = sessionStorage.getItem(PENDING_KEY) ?? '';
      S.shareToken = null;
      S.chatModel = normalizeModelId(
        untrack(() => S.toolModels.chat) ?? `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`
      );
    }
    S.originalPrompt = S.prompt;
    S.originalChatModel = S.chatModel;
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.loading = false;
  }
  void loadMemories();
  void loadUploads();
  void loadPrefs();
  void loadPersonas();
  void loadProviders();
  void loadCredentials();
  void loadSecrets();
  void loadMcp();
  void loadRagStatus();
}

// ─── Instance URL (Android) + theme ──────────────────────────────────────────

export async function loadInstanceUrl() {
  S.instanceUrl = await getInstanceUrl();
  if (S.instanceUrl) S.instanceUrlDraft = S.instanceUrl.url;
}

export async function saveInstanceUrl() {
  const url = S.instanceUrlDraft.trim();
  if (!url) return;
  S.instanceUrlSaving = true;
  try {
    // Resolves just before the native side relaunches the WebView against
    // the new URL — anything after this line may not get to run.
    await setInstanceUrl(url);
    toast.success('Reconnecting…');
  } catch {
    toast.error('That does not look like a valid URL');
    S.instanceUrlSaving = false;
  }
}

export function applyTheme(choice: ThemeChoice) {
  S.themeChoice = choice;
  setThemeChoice(choice);
}

// ─── Credentials ─────────────────────────────────────────────────────────────

export async function loadCredentials() {
  S.credentialsLoading = true;
  try {
    S.credentials = await getCredentials();
  } catch (e) {
    console.warn('getCredentials failed', e);
  } finally {
    S.credentialsLoading = false;
  }
}

export function startCredEdit(key: string) {
  S.credEditing = key;
  S.credDraft = '';
}
export function cancelCredEdit() {
  S.credEditing = null;
  S.credDraft = '';
}
export async function saveCred(key: string, value: string | null) {
  S.credSaving = key;
  try {
    S.credentials = await setCredentials({ [key]: value });
    S.credEditing = null;
    S.credDraft = '';
    toast.success(value ? 'Credential saved' : 'Credential cleared');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.credSaving = null;
  }
}

// ─── Provider form + model-id combobox ───────────────────────────────────────

export function modelIdMatches(idx: number): string[] {
  const q = (S.providerForm.models[idx]?.id || '').toLowerCase();
  return S.discoveredModels.filter((m) => m.toLowerCase().includes(q)).slice(0, 40);
}
export function pickModelId(idx: number, val: string) {
  S.providerForm.models = S.providerForm.models.map((row, i) =>
    i === idx ? { ...row, id: val, label: row.label || val } : row
  );
  S.modelIdOpen = null;
}

export async function discoverModels() {
  S.discovering = true;
  try {
    const models = await discoverProviderModels({
      provider_id: S.editingProviderId ?? undefined,
      base_url: S.providerForm.base_url.trim(),
      api_key: S.providerForm.api_key.trim() || undefined
    });
    S.discoveredModels = models;
    toast.success(
      models.length ? `Found ${models.length} models` : 'No models advertised at /models'
    );
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.discovering = false;
  }
}

export function startCreateProvider() {
  S.editingProviderId = null;
  S.providerForm = { name: '', base_url: '', api_key: '', models: [] };
  S.discoveredModels = [];
  S.providerFormOpen = true;
}

export function startEditProvider(p: Provider) {
  S.editingProviderId = p.id;
  S.providerForm = {
    name: p.name,
    base_url: p.base_url,
    // Leave api_key blank — the user only types it when rotating. Empty
    // values are skipped server-side, preserving the existing key.
    api_key: '',
    models: (p.models || []).map((m) => ({ ...m, tools: [...(m.tools || [])] }))
  };
  S.discoveredModels = [];
  S.providerFormOpen = true;
  void discoverModels(); // best-effort — saved provider has a stored key
}

export function cancelProviderForm() {
  S.providerFormOpen = false;
  S.editingProviderId = null;
}

export function addModelRow() {
  S.providerForm.models = [
    ...S.providerForm.models,
    { id: '', label: '', note: '', tools: ['chat'] as ToolKey[] }
  ];
}

export function removeModelRow(idx: number) {
  S.providerForm.models = S.providerForm.models.filter((_, i) => i !== idx);
}

export function toggleModelTool(idx: number, t: ToolKey) {
  const m = S.providerForm.models[idx];
  const has = (m.tools || []).includes(t);
  const next: ToolKey[] = has ? m.tools.filter((x) => x !== t) : [...(m.tools || []), t];
  // The `vision` role IS the vision-capability control — keep the declared
  // capability in sync so one chip covers both.
  const patch: Partial<ProviderModel> = { tools: next };
  if (t === 'vision') patch.vision = !has;
  S.providerForm.models = S.providerForm.models.map((row, i) =>
    i === idx ? { ...row, ...patch } : row
  );
}

// Reasoning is the one capability with no tool role — a standalone toggle.
export function toggleModelCap(idx: number, cap: 'reasoning') {
  S.providerForm.models = S.providerForm.models.map((row, i) =>
    i === idx ? { ...row, [cap]: !row[cap] } : row
  );
}

export async function saveProviderForm() {
  const name = S.providerForm.name.trim();
  const base_url = S.providerForm.base_url.trim();
  const api_key = S.providerForm.api_key.trim();
  if (!name || !base_url) {
    toast.error('Name and base URL required');
    return;
  }
  if (!S.editingProviderId && !api_key) {
    toast.error('API key required to create a provider');
    return;
  }
  // Filter out blank model rows so half-finished entries don't get saved.
  const models = S.providerForm.models
    .map((m) => ({
      id: m.id.trim(),
      label: (m.label || '').trim() || m.id.trim(),
      note: (m.note || '').trim() || undefined,
      tools: (m.tools || []).filter(Boolean) as ToolKey[],
      // Declared capabilities — only sent when true, keeping the stored
      // JSONB tidy; the registry reads these to route vision/reasoning.
      ...(m.vision ? { vision: true } : {}),
      ...(m.reasoning ? { reasoning: true } : {})
    }))
    .filter((m) => m.id && m.tools.length > 0);
  S.providerSaving = true;
  try {
    if (S.editingProviderId) {
      const patch: any = { name, base_url, models };
      if (api_key) patch.api_key = api_key;
      await updateProvider(S.editingProviderId, patch);
      toast.success('Provider updated');
    } else {
      await createProvider({ name, base_url, api_key, models });
      toast.success('Provider added');
    }
    S.providerFormOpen = false;
    S.editingProviderId = null;
    await loadProviders();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.providerSaving = false;
  }
}

export async function onDeleteProvider(p: Provider) {
  if (!confirm(`Delete provider "${p.name}"?\nAny chats currently using its models will fall back to defaults.`)) return;
  try {
    await deleteProvider(p.id);
    await loadProviders();
    toast.success(`Deleted ${p.name}`);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

export async function onTestProvider(p: Provider) {
  S.providerTesting = p.id;
  S.providerTestResult = { ...S.providerTestResult, [p.id]: '' };
  try {
    const r = await testProvider(p.id);
    if (r.ok) {
      const n = r.model_count;
      S.providerTestResult = {
        ...S.providerTestResult,
        [p.id]: n === null || n === undefined
          ? `OK (HTTP ${r.status})`
          : `OK · ${n} models available`
      };
    } else {
      S.providerTestResult = {
        ...S.providerTestResult,
        [p.id]: `Failed${r.status ? ` (${r.status})` : ''}: ${r.error || ''}`.trim()
      };
    }
  } catch (e) {
    S.providerTestResult = {
      ...S.providerTestResult,
      [p.id]: `Failed: ${(e as Error).message}`
    };
  } finally {
    S.providerTesting = null;
  }
}

export async function loadProviders() {
  S.providersLoading = true;
  try {
    S.providersList = await listProviders();
  } catch (e) {
    console.warn('listProviders failed', e);
    S.providersList = [];
  } finally {
    S.providersLoading = false;
  }
}

// ─── Custom CF model list ────────────────────────────────────────────────────

/** Combined list of every CF model the dropdowns currently surface — used
 *  to seed the "Edit CF models" form so the user starts from the active
 *  set, not an empty page. */
function allCfModelsSeed(): ProviderModel[] {
  if (S.cfModels.length > 0) {
    return S.cfModels.map((m) => ({ ...m, tools: [...(m.tools || [])], steps: m.steps ?? null }));
  }
  // Stitch the hardcoded defaults together by id (some models appear in
  // multiple tool lists — collapse to one row with merged `tools`).
  // `provider::`-qualified defaults (e.g. anthropic::claude-*) are NOT CF
  // models — seeding them here would corrupt the CF list.
  const map = new Map<string, ProviderModel>();
  const defaults = CF_DEFAULTS();
  for (const t of TOOL_KEYS()) {
    for (const m of defaults[t]) {
      if (typeof m.id === 'string' && m.id.includes('::')) continue;
      const existing = map.get(m.id);
      if (existing) {
        if (!existing.tools.includes(t)) existing.tools.push(t);
      } else {
        map.set(m.id, { id: m.id, label: m.label, note: m.note, tools: [t], steps: null });
      }
    }
  }
  return Array.from(map.values());
}

export function startEditCfModels() {
  S.cfFormDraft = allCfModelsSeed();
  S.cfFormOpen = true;
}
export function cancelEditCfModels() {
  S.cfFormOpen = false;
}
export function addCfModelRow() {
  S.cfFormDraft = [...S.cfFormDraft, { id: '', label: '', note: '', tools: ['image'] as ToolKey[], steps: null }];
}
export function removeCfModelRow(idx: number) {
  S.cfFormDraft = S.cfFormDraft.filter((_, i) => i !== idx);
}
export function toggleCfModelTool(idx: number, t: ToolKey) {
  const row = S.cfFormDraft[idx];
  const has = (row.tools || []).includes(t);
  const next = has ? row.tools.filter((x) => x !== t) : [...(row.tools || []), t];
  S.cfFormDraft = S.cfFormDraft.map((r, i) => (i === idx ? { ...r, tools: next as ToolKey[] } : r));
}
export async function saveCfModels() {
  const cleaned = S.cfFormDraft
    .map((m) => {
      const stepsRaw = m.steps;
      const stepsNum = typeof stepsRaw === 'string' ? parseInt(stepsRaw, 10) : stepsRaw;
      const stepsClamped =
        stepsNum && Number.isFinite(stepsNum) ? Math.max(1, Math.min(Number(stepsNum), 50)) : null;
      return {
        id: m.id.trim(),
        label: (m.label || '').trim() || m.id.trim(),
        note: (m.note || '').trim() || undefined,
        tools: (m.tools || []).filter(Boolean) as ToolKey[],
        steps: stepsClamped
      };
    })
    .filter((m) => m.id && m.tools.length > 0);
  S.cfFormSaving = true;
  try {
    const merged = await updatePrefs({ cf_models: cleaned });
    S.cfModels = [...((merged.cf_models as ProviderModel[] | undefined) ?? [])];
    app.prefs = merged;
    S.cfFormOpen = false;
    toast.success('Cloudflare model list saved');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.cfFormSaving = false;
  }
}
export async function resetCfModelsToDefaults() {
  if (!confirm('Reset Cloudflare model list to the built-in defaults?')) return;
  S.cfFormSaving = true;
  try {
    const merged = await updatePrefs({ cf_models: [] });
    S.cfModels = [];
    app.prefs = merged;
    S.cfFormOpen = false;
    toast.success('Reset to built-in CF models');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.cfFormSaving = false;
  }
}

// ─── MCP servers ─────────────────────────────────────────────────────────────

export async function loadMcp() {
  try {
    S.mcpServers = await listMcpServers();
  } catch (e) {
    console.warn('listMcpServers failed', e);
  }
}

export async function addMcp() {
  if (!S.mcpForm.name.trim() || !S.mcpForm.url.trim()) {
    toast.error('Name and URL are required');
    return;
  }
  S.mcpSaving = true;
  try {
    const s = await createMcpServer({
      name: S.mcpForm.name.trim(),
      url: S.mcpForm.url.trim(),
      auth_header: S.mcpForm.auth_header.trim() || undefined
    });
    S.mcpServers = [...S.mcpServers, s];
    S.mcpForm = { name: '', url: '', auth_header: '' };
    toast.success('MCP server added');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.mcpSaving = false;
  }
}

export async function toggleMcp(s: McpServer) {
  try {
    const updated = await updateMcpServer(s.id, { enabled: !s.enabled });
    S.mcpServers = S.mcpServers.map((x) => (x.id === s.id ? updated : x));
  } catch (e) {
    toast.error((e as Error).message);
  }
}

export async function removeMcp(s: McpServer) {
  if (!confirm(`Remove MCP server "${s.name}"?`)) return;
  try {
    await deleteMcpServer(s.id);
    S.mcpServers = S.mcpServers.filter((x) => x.id !== s.id);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

export async function testMcp(s: McpServer) {
  S.mcpTesting = s.id;
  try {
    const r = await testMcpServer(s.id);
    toast.success(`${r.tools.length} tool(s): ${r.tools.slice(0, 4).join(', ')}${r.tools.length > 4 ? '…' : ''}`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.mcpTesting = null;
  }
}

// ─── Personas ────────────────────────────────────────────────────────────────

export async function loadPersonas() {
  S.personasLoading = true;
  try {
    S.personas = await listPersonas();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.personasLoading = false;
  }
}

export function startCreatePersona() {
  S.editingPersona = null;
  S.personaForm = { name: '', system_prompt: '', model: '' };
  S.newPersonaOpen = true;
}
export function startEditPersona(p: Persona) {
  S.editingPersona = p;
  S.personaForm = {
    name: p.name,
    system_prompt: p.system_prompt ?? '',
    model: p.model ?? ''
  };
  S.newPersonaOpen = true;
}
export function cancelPersonaEdit() {
  S.newPersonaOpen = false;
  S.editingPersona = null;
}
export async function savePersonaForm() {
  const name = S.personaForm.name.trim();
  if (!name) return;
  const sp = S.personaForm.system_prompt.trim() || null;
  const md = S.personaForm.model.trim() || null;
  try {
    if (S.editingPersona) {
      await updatePersona(S.editingPersona.id, { name, system_prompt: sp, model: md });
      toast.success('Persona updated');
    } else {
      await createPersona(name, sp, md);
      toast.success('Persona created');
    }
    S.newPersonaOpen = false;
    S.editingPersona = null;
    await loadPersonas();
  } catch (e) {
    toast.error((e as Error).message);
  }
}
export async function deletePersonaConfirm(p: Persona) {
  if (!confirm(`Delete persona "${p.name}"?`)) return;
  try {
    await deletePersona(p.id);
    await loadPersonas();
  } catch (e) {
    toast.error((e as Error).message);
  }
}
/** Apply persona to the current chat — copies system_prompt + model. */
export async function applyPersonaToCurrentChat(p: Persona) {
  if (!S.chatId) return;
  try {
    if (p.system_prompt !== null) {
      await setSystemPrompt(S.chatId, p.system_prompt);
    }
    if (p.model) {
      await setChatModel(S.chatId, p.model);
    }
    toast.success(`Applied "${p.name}"`);
    // Refresh persona/system prompt tab state
    const chat = await getChat(S.chatId);
    S.prompt = chat.system_prompt ?? '';
    S.originalPrompt = S.prompt;
    S.chatModel = normalizeModelId(chat.model ?? S.chatModel);
    S.originalChatModel = S.chatModel;
  } catch (e) {
    toast.error((e as Error).message);
  }
}

export async function savePersona() {
  S.saving = true;
  try {
    const next = S.prompt.trim();
    if (S.chatId) {
      await setSystemPrompt(S.chatId, next || null);
      await app.refreshChats();
      toast.success('Custom instructions saved');
    } else {
      if (next) sessionStorage.setItem(PENDING_KEY, next);
      else sessionStorage.removeItem(PENDING_KEY);
      toast.success('Will apply when you send the first message');
    }
    S.originalPrompt = S.prompt;
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.saving = false;
  }
}

// ─── Prefs (tool models, voice, coder, general) ──────────────────────────────

export async function loadPrefs() {
  S.prefsLoading = true;
  try {
    const prefs = await getPrefs();
    S.prefs = prefs;
    // Normalize legacy bare model strings to provider::model so the
    // dropdowns find a matching option.
    // Load EVERY saved tool-model key (not a hardcoded subset) — otherwise a
    // key we forget here (vision, code_mode) is dropped from local state, so
    // its Settings row shows the default AND the next savePersistentTool
    // omits it, wiping it server-side.
    const raw = (prefs.tool_models ?? {}) as Record<string, string | null>;
    const tm: Record<string, string | null> = {};
    for (const k of Object.keys(raw)) {
      tm[k] = raw[k] ? normalizeModelId(raw[k] as string) : null;
    }
    S.toolModels = tm as ToolModels;
    S.cfModels = Array.isArray(prefs.cf_models) ? [...prefs.cf_models] : [];
    const defaultChat = `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`;
    if (!S.chatId && (!S.chatModel || S.chatModel === defaultChat)) {
      S.chatModel = S.toolModels.chat || defaultChat;
      S.originalChatModel = S.chatModel;
    }
    app.prefs = prefs;
  } catch (e) {
    // non-fatal — falls back to env defaults
  } finally {
    S.prefsLoading = false;
  }
}

export async function savePersistentTool(key: string, value: string) {
  const next: ToolModels = { ...S.toolModels, [key]: value || null };
  S.savingToolKey = key;
  try {
    // Server JSONB merge replaces nested objects wholesale — send the full
    // tool_models dict so we don't wipe sibling keys.
    const merged = await updatePrefs({ tool_models: next });
    S.toolModels = { ...(merged.tool_models ?? {}) };
    app.prefs = merged;
    const label = key.replace(/_fallback$/, ' fallback').replace(/_/g, ' ');
    toast.success(`${label.charAt(0).toUpperCase() + label.slice(1)} saved`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.savingToolKey = null;
  }
}

export async function applyChatModel(value: string) {
  // 1. Update the user-level default so future new chats pick it up.
  void savePersistentTool('chat', value);
  // 2. If we're inside an existing chat, pin it to that chat too — without
  //    this the backend's chat_model_override would keep using the model
  //    the chat was created with and the dropdown would appear to reset on
  //    the next drawer open.
  if (S.chatId && value !== S.originalChatModel) {
    S.savingChatModel = true;
    try {
      await setChatModel(S.chatId, value);
      S.originalChatModel = value;
      await app.refreshChats();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      S.savingChatModel = false;
    }
  } else {
    S.originalChatModel = value;
  }
}

/** One save path for every prefs patch: busy flag, server-merge resync into
 *  S.prefs AND app.prefs (so the sidebar reacts live), success toast, error
 *  toast. `busy` picks which spinner flag the control watches; `after` runs
 *  extra refresh work (e.g. RAG status) before the toast. */
export async function savedPref(
  patch: UserPrefs,
  ok: string,
  opts: { busy?: 'prefs' | 'rag' | null; after?: () => Promise<void> } = {}
) {
  const busy = opts.busy === undefined ? 'prefs' : opts.busy;
  if (busy === 'prefs') S.prefsSaving = true;
  else if (busy === 'rag') S.ragBusy = true;
  try {
    const merged = await updatePrefs(patch);
    S.prefs = merged;
    app.prefs = merged;
    if (opts.after) await opts.after();
    toast.success(ok);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    if (busy === 'prefs') S.prefsSaving = false;
    else if (busy === 'rag') S.ragBusy = false;
  }
}

export async function saveCoderPref(patch: UserPrefs, okMsg: string) {
  await savedPref(patch, okMsg);
}

export function toggleReviewEdits() {
  const next = !reviewEdits();
  void saveCoderPref({ review_edits: next },
    next ? 'Edit review on — edits will pause for approval' : 'Edit review off');
}
export function toggleVerifyEdits() {
  const next = !verifyEdits();
  void saveCoderPref({ verify_edits: next }, next ? 'Auto-verify on' : 'Auto-verify off');
}
export function saveMaxRounds() {
  const raw = S.maxRoundsCode.trim();
  const val = raw === '' ? null : Math.max(1, Math.min(parseInt(raw, 10) || 0, 100));
  S.maxRoundsCode = val == null ? '' : String(val);
  void saveCoderPref({ max_rounds_code: val }, 'Max steps saved');
}
export function saveMaxTokens() {
  const raw = S.maxTokensCoder.trim();
  const val = raw === '' ? null : Math.max(512, Math.min(parseInt(raw, 10) || 0, 32768));
  S.maxTokensCoder = val == null ? '' : String(val);
  void saveCoderPref({ max_tokens_coder: val }, 'Response cap saved');
}

export async function savePrefsPatch(patch: UserPrefs) {
  await savedPref(patch, 'Saved', { busy: null });
}

export async function savePrefs(patch: UserPrefs) {
  await savedPref(patch, 'Voice settings saved');
}

export function onAudioProviderChange(kind: 'tts' | 'stt', value: string, prevModel: string) {
  if (value.startsWith(CUSTOM_PREFIX)) {
    void savePrefs({ [`${kind}_provider`]: `${value.slice(CUSTOM_PREFIX.length)}::${prevModel || ''}` });
  } else {
    void savePrefs({ [`${kind}_provider`]: value });
  }
}
export function onAudioModelChange(kind: 'tts' | 'stt', pid: string, model: string) {
  void savePrefs({ [`${kind}_provider`]: `${pid}::${model.trim()}` });
}

// ─── Embedding backend (RAG) ─────────────────────────────────────────────────

export async function loadRagStatus() {
  try { S.ragStatus = await getRagStatus(); } catch { S.ragStatus = null; }
}
export async function saveEmbed(patch: UserPrefs) {
  await savedPref(patch, 'Embedding settings saved', { busy: 'rag', after: loadRagStatus });
}
export function onEmbedProviderChange(value: string) {
  if (!value) void saveEmbed({ embed_model: null, embed_dim: null });
  else void saveEmbed({ embed_model: `${value}::${embedCustom().model || ''}` });
}
export async function runReindexAll() {
  S.ragBusy = true;
  try {
    const r = await reindexAll();
    await loadRagStatus();
    toast.success(`Re-embedded ${r.reindexed} chunk${r.reindexed === 1 ? '' : 's'}`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.ragBusy = false;
  }
}

// ─── Memories ────────────────────────────────────────────────────────────────

export async function loadMemories() {
  S.memoriesLoading = true;
  try {
    S.memories = await listMemories();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.memoriesLoading = false;
  }
}

export async function onAddMemory() {
  const content = S.newMemory.trim();
  if (!content) return;
  S.addingMemory = true;
  try {
    const m = await addMemory(content);
    S.memories = [...S.memories, m];
    S.newMemory = '';
    toast.success('Memory saved');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.addingMemory = false;
  }
}

export async function onDeleteMemory(id: number) {
  try {
    await deleteMemory(id);
    S.memories = S.memories.filter((m) => m.id !== id);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

// ─── Documents ───────────────────────────────────────────────────────────────

export async function loadUploads() {
  S.uploadsLoading = true;
  try {
    S.uploads = await listUserUploads();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.uploadsLoading = false;
  }
}

export async function onDeleteUpload(u: UserUpload) {
  if (!confirm(`Delete ${u.filename}?\nThis removes the ${u.indexed ? 'document AND its RAG index' : 'file'}.`)) return;
  try {
    await deleteUserUpload(u.id);
    S.uploads = S.uploads.filter((x) => x.id !== u.id);
    toast.success(`Deleted ${u.filename}`);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

export async function onReindex(u: UserUpload) {
  S.reindexing = new Set([...S.reindexing, u.id]);
  try {
    const res = await reindexUpload(u.id);
    toast.success(`Re-indexed ${u.filename} (${res.chunks} chunks)`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    const next = new Set(S.reindexing);
    next.delete(u.id);
    S.reindexing = next;
  }
}

export function formatSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

// ─── Secrets ─────────────────────────────────────────────────────────────────

export async function loadSecrets() {
  S.secretsLoading = true;
  try {
    S.secrets = await listSecrets();
  } catch (e) {
    console.warn('listSecrets failed', e);
  } finally {
    S.secretsLoading = false;
  }
}

export async function onAddSecret() {
  const name = S.newSecretName.trim().toUpperCase();
  const value = S.newSecretValue;
  if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(name)) {
    toast.error('Name must be UPPER_SNAKE_CASE (start with a letter)');
    return;
  }
  if (!value) {
    toast.error('Value is required');
    return;
  }
  S.savingSecret = true;
  try {
    await setSecret(name, value);
    S.newSecretName = '';
    S.newSecretValue = '';
    await loadSecrets();
    toast.success(`Saved $${name}`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.savingSecret = false;
  }
}

export async function onDeleteSecret(name: string) {
  if (!confirm(`Delete secret $${name}?`)) return;
  try {
    await deleteSecret(name);
    await loadSecrets();
    toast.success(`Deleted $${name}`);
  } catch (e) {
    toast.error((e as Error).message);
  }
}

// ─── Share ───────────────────────────────────────────────────────────────────

export async function generateShare() {
  S.saving = true;
  try {
    const { share_token } = await createShare(S.chatId!);
    S.shareToken = share_token;
    await copyShareUrl();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.saving = false;
  }
}

export async function copyShareUrl() {
  if (!S.shareToken) return;
  const url = `${location.origin}/s/${S.shareToken}`;
  try {
    await navigator.clipboard.writeText(url);
    toast.success('Share link copied');
  } catch {
    window.prompt('Copy this share URL:', url);
  }
}

export async function revoke() {
  if (!confirm('Revoke this share link? Anyone with the URL will get a 404.')) return;
  S.saving = true;
  try {
    await revokeShare(S.chatId!);
    S.shareToken = null;
    toast.success('Share link revoked');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    S.saving = false;
  }
}
