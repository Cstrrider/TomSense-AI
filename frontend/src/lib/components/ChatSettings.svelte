<script lang="ts">
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
  import { getThemeChoice, setThemeChoice, type ThemeChoice } from '$lib/theme';
  import { toast } from '$lib/toast.svelte';
  import {
    IconBrain,
    IconCheck,
    IconCopy,
    IconEdit,
    IconFile,
    IconFileText,
    IconImage,
    IconKey,
    IconLink,
    IconMonitor,
    IconRefresh,
    IconShare,
    IconTrash,
    IconVolume,
    IconX
  } from '$lib/icons';
  import type {
    Memory,
    Persona,
    Provider,
    ProviderModel,
    ToolKey,
    ToolModels,
    UserPrefs,
    UserUpload
  } from '$lib/types';

  interface Props {
    chatId: string | null;
    open: boolean;
    onclose: () => void;
  }

  let { chatId, open = $bindable(), onclose }: Props = $props();
  const PENDING_KEY = 'pending-persona';

  type Tab = 'persona' | 'memory' | 'providers' | 'voice' | 'share' | 'coder' | 'secrets' | 'app';
  let tab = $state<Tab>('providers');

  // Theme choice (System / Dark / Light) — device-local via lib/theme.ts.
  let themeChoice = $state<ThemeChoice>(getThemeChoice());

  // Instance URL (Android APK only) — null means "not running in the app",
  // which hides the App tab entirely. See ServerConfig.java / ActionsPlugin.
  let instanceUrl = $state<{ url: string; isSet: boolean } | null>(null);
  let instanceUrlDraft = $state('');
  let instanceUrlSaving = $state(false);

  async function loadInstanceUrl() {
    instanceUrl = await getInstanceUrl();
    if (instanceUrl) instanceUrlDraft = instanceUrl.url;
  }

  async function saveInstanceUrl() {
    const url = instanceUrlDraft.trim();
    if (!url) return;
    instanceUrlSaving = true;
    try {
      // Resolves just before the native side relaunches the WebView against
      // the new URL — anything after this line may not get to run.
      await setInstanceUrl(url);
      toast.success('Reconnecting…');
    } catch {
      toast.error('That does not look like a valid URL');
      instanceUrlSaving = false;
    }
  }

  const CF_BUILTIN_ID = 'cf';
  // Tool-model slots — sourced from /info's tool_models_catalog (the
  // backend's single source of truth, defined in tool_registry.py). The
  // fallback values below are used only if /info hasn't loaded yet.
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
    { key: 'image_edit_hd', label: 'Image edit (HD)', chip: 'edit HD', hint: 'Image-to-image, picked when you type /HD.' },
  ];
  let toolCatalog = $derived(app.info?.tool_models_catalog ?? FALLBACK_CATALOG);
  let TOOL_KEYS = $derived(toolCatalog.map((t) => t.key as ToolKey));
  let TOOL_CHIP_LABEL = $derived(
    Object.fromEntries(toolCatalog.map((t) => [t.key, t.chip])) as Record<ToolKey, string>
  );

  /** Backwards-compat: stored model strings from before Round A don't carry
   *  a provider prefix. Treat them as Cloudflare so they still resolve. */
  function normalizeModelId(s: string | null | undefined): string {
    if (!s) return '';
    return s.includes('::') ? s : `${CF_BUILTIN_ID}::${s}`;
  }

  let loading = $state(false);
  let saving = $state(false);

  // Persona
  let prompt = $state('');
  let originalPrompt = $state('');

  // Model — catalog grouped by which tool each entry is valid for. Backend
  // defaults live in config.py; these mirror them so the UI can show the
  // active selection even when the user hasn't picked one yet.
  type ModelOption = { id: string; label: string; note?: string };
  const CHAT_MODELS: ModelOption[] = [
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
  // Vision-capable models only — this row answers turns with image input.
  // Unset = the Chat model handles them (fine while it's vision-capable).
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
  // Tiny background "utility" model — titles, follow-ups, auto-memory, routing.
  const TITLE_MODELS: ModelOption[] = [
    { id: '@cf/meta/llama-3.2-3b-instruct', label: 'Llama 3.2 3B', note: 'default · tiny & cheap' },
    { id: '@cf/meta/llama-3.1-8b-instruct', label: 'Llama 3.1 8B' },
    { id: '@cf/google/gemma-4-26b-a4b-it', label: 'Gemma 4 (26B)' }
  ];
  // Image generation models (txt2img). All CF-routed — either native Workers
  // AI (Flux/SD) or Unified Billing passthrough (Imagen, Nano Banana 2,
  // gpt-image-2). Sorted cheapest → priciest.
  const IMAGE_GEN_MODELS: ModelOption[] = [
    { id: '@cf/black-forest-labs/flux-2-klein-4b',   label: 'Flux 2 Klein 4B',  note: 'default · ~$0.001/img' },
    { id: '@cf/runwayml/stable-diffusion-v1-5-img2img', label: 'SD v1.5',       note: 'beta · FREE · CF' },
    { id: '@cf/black-forest-labs/flux-2-klein-9b',   label: 'Flux 2 Klein 9B',  note: '~$0.015/img · better quality' },
    { id: 'google/imagen-4',                         label: 'Imagen 4',         note: '~$0.04/img · photorealistic' },
    { id: '@cf/black-forest-labs/flux-2-dev',        label: 'Flux 2 Dev',       note: 'premium · ~$0.04/img · multi-ref' },
    { id: 'openai/gpt-image-2',                      label: 'gpt-image-2',      note: '$0.055/img medium · OpenAI' },
    { id: 'google/nano-banana-2',                    label: 'Nano Banana 2',    note: '~$0.08/img · best all-rounder' }
  ];
  // Image editing models. Imagen omitted — no img2img support.
  const IMAGE_EDIT_MODELS: ModelOption[] = [
    { id: '@cf/runwayml/stable-diffusion-v1-5-img2img', label: 'SD v1.5 img2img', note: 'beta · FREE · single ref' },
    { id: '@cf/black-forest-labs/flux-2-klein-4b',   label: 'Flux 2 Klein 4B',   note: 'default · ~$0.001/edit · multi-ref' },
    { id: '@cf/black-forest-labs/flux-2-klein-9b',   label: 'Flux 2 Klein 9B',   note: '~$0.017/edit · multi-ref' },
    { id: '@cf/black-forest-labs/flux-2-dev',        label: 'Flux 2 Dev',        note: 'premium · ~$0.06/edit · multi-ref' },
    { id: 'openai/gpt-image-2',                      label: 'gpt-image-2',       note: '~$0.10/edit · best edits' },
    { id: 'google/nano-banana-2',                    label: 'Nano Banana 2',     note: '~$0.08/edit · great identity preservation' }
  ];

  // Code Mode chats use the agentic coder loop (write/edit/run files), and
  // need a model that emits structured tool calls reliably. Sourced from
  // /info (backend's tool_registry.py); the same list drives the per-new-chat
  // picker on `/?code=1`, so the two UIs agree.
  let CODE_MODE_MODEL_OPTIONS = $derived(
    getCodeModeModels(app.info).map((m) => ({
      id: m.id,
      label: m.label,
      note: m.hint,
    }))
  );

  // Derived so the code_mode list stays in sync with the /info-sourced
  // catalog. The other rows are static frontend constants.
  let CF_DEFAULTS = $derived<Record<ToolKey, ModelOption[]>>({
    chat: CHAT_MODELS,
    vision: VISION_MODELS,
    code: CODE_MODELS,
    code_mode: CODE_MODE_MODEL_OPTIONS,
    research: RESEARCH_MODELS,
    title: TITLE_MODELS,
    image: IMAGE_GEN_MODELS,
    image_hd: IMAGE_GEN_MODELS,
    image_edit: IMAGE_EDIT_MODELS,
    image_edit_hd: IMAGE_EDIT_MODELS
  });

  // Derived from the catalog, in catalog order, with key narrowed to ToolKey.
  let TOOL_LABELS = $derived(
    toolCatalog.map((t) => ({ key: t.key as ToolKey, label: t.label, hint: t.hint }))
  );

  // Picks per tool. Initialized from chat (for "chat") and user prefs (for the rest).
  let chatModel = $state('');           // reflects this chat's model when chatId is set, normalized to provider::model
  let originalChatModel = $state('');
  let savingChatModel = $state(false);
  let toolModels = $state<ToolModels>({});
  let savingToolKey = $state<ToolKey | null>(null);

  // Providers
  let providersList = $state<Provider[]>([]);

  // Builtin-provider credentials (Cloudflare + Anthropic). Edited in-place
  // via a small per-row form; the raw key is never read back, only set/cleared.
  let credentials = $state<import('$lib/types').Credentials | null>(null);
  let credentialsLoading = $state(false);
  let credEditing = $state<string | null>(null);
  let credDraft = $state('');
  let credSaving = $state<string | null>(null);

  async function loadCredentials() {
    credentialsLoading = true;
    try {
      credentials = await getCredentials();
    } catch (e) {
      console.warn('getCredentials failed', e);
    } finally {
      credentialsLoading = false;
    }
  }

  function startCredEdit(key: string) {
    credEditing = key;
    credDraft = '';
  }
  function cancelCredEdit() {
    credEditing = null;
    credDraft = '';
  }
  async function saveCred(key: string, value: string | null) {
    credSaving = key;
    try {
      credentials = await setCredentials({ [key]: value });
      credEditing = null;
      credDraft = '';
      toast.success(value ? 'Credential saved' : 'Credential cleared');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      credSaving = null;
    }
  }
  let providersLoading = $state(false);

  // Providers tab: collapsible section state. Models/Providers are the
  // day-to-day sections and start open; MCP and the HD model variants are
  // set-and-forget, so they start collapsed to cut clutter. Builtin
  // credentials live on their provider cards inside the Providers section.
  let secModels = $state(true);
  let secProviders = $state(true);
  let secMcp = $state(false);
  let secHd = $state(false);
  let secEmbed = $state(false);

  // Custom CF model list — when non-empty, replaces the hardcoded defaults.
  let cfModels = $state<ProviderModel[]>([]);
  let cfFormOpen = $state(false);
  let cfFormDraft = $state<ProviderModel[]>([]);
  let cfFormSaving = $state(false);

  // Add/edit-provider form state. editingProviderId === null means we're
  // creating a brand-new one; otherwise we're editing an existing one.
  let providerFormOpen = $state(false);
  let editingProviderId = $state<string | null>(null);
  let providerForm = $state<{
    name: string;
    base_url: string;
    api_key: string;
    models: ProviderModel[];
  }>({ name: '', base_url: '', api_key: '', models: [] });
  let providerSaving = $state(false);
  let providerTesting = $state<string | null>(null);
  let providerTestResult = $state<Record<string, string>>({});

  function startCreateProvider() {
    editingProviderId = null;
    providerForm = { name: '', base_url: '', api_key: '', models: [] };
    providerFormOpen = true;
  }

  function startEditProvider(p: Provider) {
    editingProviderId = p.id;
    providerForm = {
      name: p.name,
      base_url: p.base_url,
      // Leave api_key blank — the user only types it when rotating. Empty
      // values are skipped server-side, preserving the existing key.
      api_key: '',
      models: (p.models || []).map((m) => ({ ...m, tools: [...(m.tools || [])] }))
    };
    providerFormOpen = true;
  }

  function cancelProviderForm() {
    providerFormOpen = false;
    editingProviderId = null;
  }

  function addModelRow() {
    providerForm.models = [
      ...providerForm.models,
      { id: '', label: '', note: '', tools: ['chat'] as ToolKey[] }
    ];
  }

  function removeModelRow(idx: number) {
    providerForm.models = providerForm.models.filter((_, i) => i !== idx);
  }

  function toggleModelTool(idx: number, t: ToolKey) {
    const m = providerForm.models[idx];
    const has = (m.tools || []).includes(t);
    const next: ToolKey[] = has ? m.tools.filter((x) => x !== t) : [...(m.tools || []), t];
    providerForm.models = providerForm.models.map((row, i) =>
      i === idx ? { ...row, tools: next } : row
    );
  }

  // Declared model capabilities (vision / reasoning) — the backend registry
  // trusts these over any name-based guess, so a vision or reasoning model on
  // this provider is handled correctly regardless of its id.
  function toggleModelCap(idx: number, cap: 'vision' | 'reasoning') {
    providerForm.models = providerForm.models.map((row, i) =>
      i === idx ? { ...row, [cap]: !row[cap] } : row
    );
  }

  async function saveProviderForm() {
    const name = providerForm.name.trim();
    const base_url = providerForm.base_url.trim();
    const api_key = providerForm.api_key.trim();
    if (!name || !base_url) {
      toast.error('Name and base URL required');
      return;
    }
    if (!editingProviderId && !api_key) {
      toast.error('API key required to create a provider');
      return;
    }
    // Filter out blank model rows so half-finished entries don't get saved.
    const models = providerForm.models
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
    providerSaving = true;
    try {
      if (editingProviderId) {
        const patch: any = { name, base_url, models };
        if (api_key) patch.api_key = api_key;
        await updateProvider(editingProviderId, patch);
        toast.success('Provider updated');
      } else {
        await createProvider({ name, base_url, api_key, models });
        toast.success('Provider added');
      }
      providerFormOpen = false;
      editingProviderId = null;
      await loadProviders();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      providerSaving = false;
    }
  }

  async function onDeleteProvider(p: Provider) {
    if (!confirm(`Delete provider "${p.name}"?\nAny chats currently using its models will fall back to defaults.`)) return;
    try {
      await deleteProvider(p.id);
      await loadProviders();
      toast.success(`Deleted ${p.name}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function onTestProvider(p: Provider) {
    providerTesting = p.id;
    providerTestResult = { ...providerTestResult, [p.id]: '' };
    try {
      const r = await testProvider(p.id);
      if (r.ok) {
        const n = r.model_count;
        providerTestResult = {
          ...providerTestResult,
          [p.id]: n === null || n === undefined
            ? `OK (HTTP ${r.status})`
            : `OK · ${n} models available`
        };
      } else {
        providerTestResult = {
          ...providerTestResult,
          [p.id]: `Failed${r.status ? ` (${r.status})` : ''}: ${r.error || ''}`.trim()
        };
      }
    } catch (e) {
      providerTestResult = {
        ...providerTestResult,
        [p.id]: `Failed: ${(e as Error).message}`
      };
    } finally {
      providerTesting = null;
    }
  }

  /** Build the model-options list for a given tool. Source order:
   *  1. User's cf_models pref, if it has any model tagged for this tool —
   *     those REPLACE the hardcoded CF defaults for this tool, so the user
   *     can drop deprecated models.
   *  2. Hardcoded CF defaults for this tool — used when there are no
   *     cf_models at all, OR there are some but none cover this tool. The
   *     fallback is PER-TOOL: without it, one stray custom model (tagged for
   *     only, say, chat) blanks out every other dropdown — and a zero-option
   *     <select> wedges the Android WebView's native picker.
   *  3. Models registered on each non-builtin user provider for this tool. */
  function optionsForTool(toolKey: ToolKey): ModelOption[] {
    // Shared with the new-code-chat picker (+page.svelte) so custom CF
    // models / provider models are added & removed identically everywhere.
    return buildToolOptions(toolKey, CF_DEFAULTS[toolKey] ?? [], cfModels, providersList);
  }

  /** Combined list of every CF model the dropdowns currently surface — used
   *  to seed the "Edit CF models" form so the user starts from the active
   *  set, not an empty page. */
  function allCfModelsSeed(): ProviderModel[] {
    if (cfModels.length > 0) {
      return cfModels.map((m) => ({ ...m, tools: [...(m.tools || [])], steps: m.steps ?? null }));
    }
    // Stitch the hardcoded defaults together by id (some models appear in
    // multiple tool lists — collapse to one row with merged `tools`).
    // `provider::`-qualified defaults (e.g. anthropic::claude-*) are NOT CF
    // models — seeding them here would corrupt the CF list.
    const map = new Map<string, ProviderModel>();
    for (const t of TOOL_KEYS) {
      for (const m of CF_DEFAULTS[t]) {
        if (typeof m.id === 'string' && m.id.includes('::')) continue;
        const existing = map.get(m.id);
        if (existing) {
          if (!existing.tools.includes(t)) existing.tools.push(t);
        } else {
          map.set(m.id, {
            id: m.id,
            label: m.label,
            note: m.note,
            tools: [t],
            steps: null
          });
        }
      }
    }
    return Array.from(map.values());
  }

  function startEditCfModels() {
    cfFormDraft = allCfModelsSeed();
    cfFormOpen = true;
  }
  function cancelEditCfModels() {
    cfFormOpen = false;
  }
  function addCfModelRow() {
    cfFormDraft = [...cfFormDraft, { id: '', label: '', note: '', tools: ['image'] as ToolKey[], steps: null }];
  }
  function removeCfModelRow(idx: number) {
    cfFormDraft = cfFormDraft.filter((_, i) => i !== idx);
  }
  function toggleCfModelTool(idx: number, t: ToolKey) {
    const row = cfFormDraft[idx];
    const has = (row.tools || []).includes(t);
    const next = has ? row.tools.filter((x) => x !== t) : [...(row.tools || []), t];
    cfFormDraft = cfFormDraft.map((r, i) => (i === idx ? { ...r, tools: next as ToolKey[] } : r));
  }
  async function saveCfModels() {
    const cleaned = cfFormDraft
      .map((m) => {
        const stepsNum = typeof m.steps === 'number'
          ? m.steps
          : (m.steps != null && (m.steps as any) !== '' ? Number(m.steps) : null);
        const stepsClamped =
          stepsNum != null && Number.isFinite(stepsNum) && stepsNum >= 1 && stepsNum <= 50
            ? Math.round(stepsNum)
            : null;
        return {
          id: m.id.trim(),
          label: (m.label || '').trim() || m.id.trim(),
          note: (m.note || '').trim() || undefined,
          tools: (m.tools || []).filter(Boolean) as ToolKey[],
          steps: stepsClamped
        };
      })
      .filter((m) => m.id && m.tools.length > 0);
    cfFormSaving = true;
    try {
      const merged = await updatePrefs({ cf_models: cleaned });
      cfModels = [...((merged.cf_models as ProviderModel[] | undefined) ?? [])];
      app.prefs = merged;
      cfFormOpen = false;
      toast.success('Cloudflare model list saved');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      cfFormSaving = false;
    }
  }
  async function resetCfModelsToDefaults() {
    if (!confirm('Reset Cloudflare model list to the built-in defaults?')) return;
    cfFormSaving = true;
    try {
      const merged = await updatePrefs({ cf_models: [] });
      cfModels = [];
      app.prefs = merged;
      cfFormOpen = false;
      toast.success('Reset to built-in CF models');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      cfFormSaving = false;
    }
  }

  // Voice prefs
  let prefs = $state<UserPrefs>({});
  let prefsLoading = $state(false);
  let prefsSaving = $state(false);

  // Personas library
  let personas = $state<Persona[]>([]);
  let personasLoading = $state(false);
  let editingPersona = $state<Persona | null>(null);
  let newPersonaOpen = $state(false);
  let personaForm = $state({ name: '', system_prompt: '', model: '' });

  // Share
  let shareToken = $state<string | null>(null);

  // Memories
  let memories = $state<Memory[]>([]);
  let memoriesLoading = $state(false);
  let newMemory = $state('');
  let addingMemory = $state(false);

  // Documents
  let uploads = $state<UserUpload[]>([]);
  let uploadsLoading = $state(false);
  let reindexing = $state<Set<string>>(new Set());

  $effect(() => {
    if (open) {
      void load();
      void loadInstanceUrl(); // no-op outside the Android app
    }
  });

  async function load() {
    loading = true;
    try {
      // Use untrack() when reading toolModels here. Without it, the $effect
      // that calls load() picks up toolModels.chat as a dependency; loadPrefs()
      // (called below) then writes toolModels, re-firing the effect → infinite
      // loop. Manifested as the Persona tab flickering between Loading and
      // empty states when settings opened on a new chat.
      if (chatId) {
        const chat = await getChat(chatId);
        prompt = chat.system_prompt ?? '';
        shareToken = chat.share_token ?? null;
        chatModel = normalizeModelId(
          chat.model
            ?? untrack(() => toolModels.chat)
            ?? `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`
        );
      } else {
        prompt = sessionStorage.getItem(PENDING_KEY) ?? '';
        shareToken = null;
        chatModel = normalizeModelId(
          untrack(() => toolModels.chat) ?? `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`
        );
      }
      originalPrompt = prompt;
      originalChatModel = chatModel;
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      loading = false;
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

  // ── MCP servers (Providers tab) ─────────────────────────────────────────
  let mcpServers = $state<McpServer[]>([]);
  let mcpForm = $state({ name: '', url: '', auth_header: '' });
  let mcpSaving = $state(false);
  let mcpTesting = $state<string | null>(null);

  async function loadMcp() {
    try {
      mcpServers = await listMcpServers();
    } catch (e) {
      console.warn('listMcpServers failed', e);
    }
  }

  async function addMcp() {
    if (!mcpForm.name.trim() || !mcpForm.url.trim()) {
      toast.error('Name and URL are required');
      return;
    }
    mcpSaving = true;
    try {
      const s = await createMcpServer({
        name: mcpForm.name.trim(),
        url: mcpForm.url.trim(),
        auth_header: mcpForm.auth_header.trim() || undefined
      });
      mcpServers = [...mcpServers, s];
      mcpForm = { name: '', url: '', auth_header: '' };
      toast.success('MCP server added');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      mcpSaving = false;
    }
  }

  async function toggleMcp(s: McpServer) {
    try {
      const updated = await updateMcpServer(s.id, { enabled: !s.enabled });
      mcpServers = mcpServers.map((x) => (x.id === s.id ? updated : x));
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function removeMcp(s: McpServer) {
    if (!confirm(`Remove MCP server "${s.name}"?`)) return;
    try {
      await deleteMcpServer(s.id);
      mcpServers = mcpServers.filter((x) => x.id !== s.id);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function testMcp(s: McpServer) {
    mcpTesting = s.id;
    try {
      const r = await testMcpServer(s.id);
      toast.success(`${r.tools.length} tool(s): ${r.tools.slice(0, 4).join(', ')}${r.tools.length > 4 ? '…' : ''}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      mcpTesting = null;
    }
  }

  async function loadProviders() {
    providersLoading = true;
    try {
      providersList = await listProviders();
    } catch (e) {
      console.warn('listProviders failed', e);
      providersList = [];
    } finally {
      providersLoading = false;
    }
  }

  async function loadPersonas() {
    personasLoading = true;
    try {
      personas = await listPersonas();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      personasLoading = false;
    }
  }

  function startCreatePersona() {
    editingPersona = null;
    personaForm = { name: '', system_prompt: '', model: '' };
    newPersonaOpen = true;
  }
  function startEditPersona(p: Persona) {
    editingPersona = p;
    personaForm = {
      name: p.name,
      system_prompt: p.system_prompt ?? '',
      model: p.model ?? ''
    };
    newPersonaOpen = true;
  }
  function cancelPersonaEdit() {
    newPersonaOpen = false;
    editingPersona = null;
  }
  async function savePersonaForm() {
    const name = personaForm.name.trim();
    if (!name) return;
    const sp = personaForm.system_prompt.trim() || null;
    const md = personaForm.model.trim() || null;
    try {
      if (editingPersona) {
        await updatePersona(editingPersona.id, { name, system_prompt: sp, model: md });
        toast.success('Persona updated');
      } else {
        await createPersona(name, sp, md);
        toast.success('Persona created');
      }
      newPersonaOpen = false;
      editingPersona = null;
      await loadPersonas();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }
  async function deletePersonaConfirm(p: Persona) {
    if (!confirm(`Delete persona "${p.name}"?`)) return;
    try {
      await deletePersona(p.id);
      await loadPersonas();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }
  /** Apply persona to the current chat — copies system_prompt + model. */
  async function applyPersonaToCurrentChat(p: Persona) {
    if (!chatId) return;
    try {
      if (p.system_prompt !== null) {
        await setSystemPrompt(chatId, p.system_prompt);
      }
      if (p.model) {
        await setChatModel(chatId, p.model);
      }
      toast.success(`Applied "${p.name}"`);
      // Refresh persona/system prompt tab state
      const chat = await getChat(chatId);
      prompt = chat.system_prompt ?? '';
      originalPrompt = prompt;
      chatModel = normalizeModelId(chat.model ?? chatModel);
      originalChatModel = chatModel;
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function loadPrefs() {
    prefsLoading = true;
    try {
      prefs = await getPrefs();
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
      toolModels = tm as ToolModels;
      cfModels = Array.isArray(prefs.cf_models) ? [...prefs.cf_models] : [];
      const defaultChat = `${CF_BUILTIN_ID}::${CHAT_MODELS[0].id}`;
      if (!chatId && (!chatModel || chatModel === defaultChat)) {
        chatModel = toolModels.chat || defaultChat;
        originalChatModel = chatModel;
      }
      app.prefs = prefs;
    } catch (e) {
      // non-fatal — falls back to env defaults
    } finally {
      prefsLoading = false;
    }
  }

  async function savePersistentTool(key: ToolKey, value: string) {
    const next: ToolModels = { ...toolModels, [key]: value || null };
    savingToolKey = key;
    try {
      // Server JSONB merge replaces nested objects wholesale — send the full
      // tool_models dict so we don't wipe sibling keys.
      const merged = await updatePrefs({ tool_models: next });
      toolModels = { ...(merged.tool_models ?? {}) };
      app.prefs = merged;
      toast.success(`${key.charAt(0).toUpperCase() + key.slice(1)} model saved`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      savingToolKey = null;
    }
  }


  // ─── Secret vault ─────────────────────────────────────────────────────
  let secrets = $state<SecretMeta[]>([]);
  let secretsLoading = $state(false);
  let newSecretName = $state('');
  let newSecretValue = $state('');
  let savingSecret = $state(false);

  async function loadSecrets() {
    secretsLoading = true;
    try {
      secrets = await listSecrets();
    } catch (e) {
      console.warn('listSecrets failed', e);
    } finally {
      secretsLoading = false;
    }
  }

  async function onAddSecret() {
    const name = newSecretName.trim().toUpperCase();
    const value = newSecretValue;
    if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(name)) {
      toast.error('Name must be UPPER_SNAKE_CASE (start with a letter)');
      return;
    }
    if (!value) {
      toast.error('Value is required');
      return;
    }
    savingSecret = true;
    try {
      await setSecret(name, value);
      newSecretName = '';
      newSecretValue = '';
      await loadSecrets();
      toast.success(`Saved $${name}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      savingSecret = false;
    }
  }

  async function onDeleteSecret(name: string) {
    try {
      await deleteSecret(name);
      await loadSecrets();
      toast.success(`Deleted $${name}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  // ─── Coder tab settings ───────────────────────────────────────────────
  let reviewEdits = $derived(!!(prefs.review_edits ?? app.prefs?.review_edits));
  // Auto-verify defaults ON: only false when explicitly turned off.
  let verifyEdits = $derived(
    (prefs.verify_edits ?? app.prefs?.verify_edits ?? true) !== false
  );
  let maxRoundsCode = $state<string>('');
  let maxTokensCoder = $state<string>('');
  // Seed the number inputs from prefs once loaded (empty = use default).
  $effect(() => {
    const mr = prefs.max_rounds_code ?? app.prefs?.max_rounds_code;
    maxRoundsCode = mr != null ? String(mr) : '';
    const mt = prefs.max_tokens_coder ?? app.prefs?.max_tokens_coder;
    maxTokensCoder = mt != null ? String(mt) : '';
  });

  async function saveCoderPref(patch: UserPrefs, okMsg: string) {
    prefsSaving = true;
    try {
      const merged = await updatePrefs(patch);
      prefs = merged;
      app.prefs = merged;
      toast.success(okMsg);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      prefsSaving = false;
    }
  }

  function toggleReviewEdits() {
    const next = !reviewEdits;
    saveCoderPref({ review_edits: next },
      next ? 'Edit review on — edits will pause for approval' : 'Edit review off');
  }
  function toggleVerifyEdits() {
    const next = !verifyEdits;
    saveCoderPref({ verify_edits: next },
      next ? 'Auto-verify on' : 'Auto-verify off');
  }
  function saveMaxRounds() {
    const raw = maxRoundsCode.trim();
    const val = raw === '' ? null : Math.max(1, Math.min(parseInt(raw, 10) || 0, 100));
    maxRoundsCode = val == null ? '' : String(val);
    saveCoderPref({ max_rounds_code: val }, 'Max steps saved');
  }
  function saveMaxTokens() {
    const raw = maxTokensCoder.trim();
    const val = raw === '' ? null : Math.max(512, Math.min(parseInt(raw, 10) || 0, 32768));
    maxTokensCoder = val == null ? '' : String(val);
    saveCoderPref({ max_tokens_coder: val }, 'Response cap saved');
  }

  async function savePrefsPatch(patch: UserPrefs) {
    try {
      prefs = await updatePrefs(patch);
      app.prefs = prefs;  // keep the store in sync so the sidebar reacts live
      toast.success('Saved');
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function savePrefs(patch: UserPrefs) {
    prefsSaving = true;
    try {
      prefs = await updatePrefs(patch);
      toast.success('Voice settings saved');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      prefsSaving = false;
    }
  }

  let currentTtsProvider = $derived(
    prefs.tts_provider ?? app.info?.tts_providers?.[0]?.id ?? 'piper'
  );
  let currentTtsVoices = $derived(
    (app.info?.tts_providers ?? []).find((p) => p.id === currentTtsProvider)?.voices ?? []
  );
  let currentTtsVoice = $derived(
    prefs.tts_voice ?? currentTtsVoices[0] ?? 'alloy'
  );
  let currentSttProvider = $derived(
    prefs.stt_provider ?? app.info?.stt_providers?.[0]?.id ?? 'whisper'
  );

  // ── Custom audio / embedding backends (reuse configured OpenAI-compatible
  // providers). A `provider::model` value selects one of these. ──
  let customProviders = $derived(providersList.filter((p) => !p.builtin));
  const CUSTOM_PREFIX = 'custom:';
  function splitRef(v: string | null | undefined): { pid: string; model: string } {
    const s = v ?? '';
    const i = s.indexOf('::');
    return i < 0 ? { pid: '', model: '' } : { pid: s.slice(0, i), model: s.slice(i + 2) };
  }
  let ttsCustom = $derived(splitRef(prefs.tts_provider));
  let ttsIsCustom = $derived((prefs.tts_provider ?? '').includes('::'));
  let ttsSelectValue = $derived(ttsIsCustom ? `${CUSTOM_PREFIX}${ttsCustom.pid}` : currentTtsProvider);
  let sttCustom = $derived(splitRef(prefs.stt_provider));
  let sttIsCustom = $derived((prefs.stt_provider ?? '').includes('::'));
  let sttSelectValue = $derived(sttIsCustom ? `${CUSTOM_PREFIX}${sttCustom.pid}` : currentSttProvider);
  function onAudioProviderChange(kind: 'tts' | 'stt', value: string, prevModel: string) {
    if (value.startsWith(CUSTOM_PREFIX)) {
      savePrefs({ [`${kind}_provider`]: `${value.slice(CUSTOM_PREFIX.length)}::${prevModel || ''}` });
    } else {
      savePrefs({ [`${kind}_provider`]: value });
    }
  }
  function onAudioModelChange(kind: 'tts' | 'stt', pid: string, model: string) {
    savePrefs({ [`${kind}_provider`]: `${pid}::${model.trim()}` });
  }

  // ── Embedding backend (RAG) ──
  let embedCustom = $derived(splitRef(prefs.embed_model));
  let embedIsCustom = $derived(!!(prefs.embed_model ?? '').includes('::'));
  let embedSelectValue = $derived(embedIsCustom ? embedCustom.pid : '');
  let ragStatus = $state<RagStatus | null>(null);
  let ragBusy = $state(false);
  async function loadRagStatus() {
    try { ragStatus = await getRagStatus(); } catch { ragStatus = null; }
  }
  async function saveEmbed(patch: UserPrefs) {
    ragBusy = true;
    try {
      prefs = await updatePrefs(patch);
      app.prefs = prefs;
      await loadRagStatus();
      toast.success('Embedding settings saved');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      ragBusy = false;
    }
  }
  function onEmbedProviderChange(value: string) {
    if (!value) void saveEmbed({ embed_model: null, embed_dim: null });
    else void saveEmbed({ embed_model: `${value}::${embedCustom.model || ''}` });
  }
  async function runReindexAll() {
    ragBusy = true;
    try {
      const r = await reindexAll();
      await loadRagStatus();
      toast.success(`Re-embedded ${r.reindexed} chunk${r.reindexed === 1 ? '' : 's'}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      ragBusy = false;
    }
  }

  async function applyChatModel(value: string) {
    // 1. Update the user-level default so future new chats pick it up.
    void savePersistentTool('chat', value);
    // 2. If we're inside an existing chat, pin it to that chat too — without
    //    this the backend's chat_model_override would keep using the model
    //    the chat was created with and the dropdown would appear to reset on
    //    the next drawer open.
    if (chatId && value !== originalChatModel) {
      savingChatModel = true;
      try {
        await setChatModel(chatId, value);
        originalChatModel = value;
        await app.refreshChats();
      } catch (e) {
        toast.error((e as Error).message);
      } finally {
        savingChatModel = false;
      }
    } else {
      originalChatModel = value;
    }
  }

  async function savePersona() {
    saving = true;
    try {
      const next = prompt.trim();
      if (chatId) {
        await setSystemPrompt(chatId, next || null);
        await app.refreshChats();
        toast.success('Custom instructions saved');
      } else {
        if (next) sessionStorage.setItem(PENDING_KEY, next);
        else sessionStorage.removeItem(PENDING_KEY);
        toast.success('Will apply when you send the first message');
      }
      originalPrompt = prompt;
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      saving = false;
    }
  }

  // ─── Memories ─────────────────────────────────────────────────────────
  async function loadMemories() {
    memoriesLoading = true;
    try {
      memories = await listMemories();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      memoriesLoading = false;
    }
  }

  async function onAddMemory() {
    const content = newMemory.trim();
    if (!content) return;
    addingMemory = true;
    try {
      const m = await addMemory(content);
      memories = [...memories, m];
      newMemory = '';
      toast.success('Memory saved');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      addingMemory = false;
    }
  }

  async function onDeleteMemory(id: number) {
    try {
      await deleteMemory(id);
      memories = memories.filter((m) => m.id !== id);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  // ─── Documents ────────────────────────────────────────────────────────
  async function loadUploads() {
    uploadsLoading = true;
    try {
      uploads = await listUserUploads();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      uploadsLoading = false;
    }
  }

  async function onDeleteUpload(u: UserUpload) {
    if (!confirm(`Delete ${u.filename}?\nThis removes the ${u.indexed ? 'document AND its RAG index' : 'file'}.`)) return;
    try {
      await deleteUserUpload(u.id);
      uploads = uploads.filter((x) => x.id !== u.id);
      toast.success(`Deleted ${u.filename}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function onReindex(u: UserUpload) {
    reindexing = new Set([...reindexing, u.id]);
    try {
      const res = await reindexUpload(u.id);
      toast.success(`Re-indexed ${u.filename} (${res.chunks} chunks)`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      const next = new Set(reindexing);
      next.delete(u.id);
      reindexing = next;
    }
  }

  function formatSize(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  }

  // ─── Share ────────────────────────────────────────────────────────────
  async function generateShare() {
    saving = true;
    try {
      const { share_token } = await createShare(chatId!);
      shareToken = share_token;
      await copyShareUrl();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      saving = false;
    }
  }

  async function copyShareUrl() {
    if (!shareToken) return;
    const url = `${location.origin}/s/${shareToken}`;
    try {
      await navigator.clipboard.writeText(url);
      toast.success('Share link copied');
    } catch {
      window.prompt('Copy this share URL:', url);
    }
  }

  async function revoke() {
    if (!confirm('Revoke this share link? Anyone with the URL will get a 404.')) return;
    saving = true;
    try {
      await revokeShare(chatId!);
      shareToken = null;
      toast.success('Share link revoked');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      saving = false;
    }
  }

  let dirty = $derived(prompt !== originalPrompt);
  let shareUrl = $derived(shareToken ? `${location.origin}/s/${shareToken}` : '');
</script>

{#if open}
  <button class="scrim" aria-label="Close settings" onclick={onclose}></button>
  <div class="drawer" role="dialog" aria-label="Chat settings">
    <header>
      <h2>Settings</h2>
      <button class="icon-btn close" onclick={onclose} aria-label="Close">
        <IconX size={18} />
      </button>
    </header>

    <nav class="tabs">
      <button class:active={tab === 'providers'} onclick={() => (tab = 'providers')}>
        <IconLink size={14} /> Providers
        {#if providersList.filter((p) => !p.builtin).length}
          <span class="badge">{providersList.filter((p) => !p.builtin).length}</span>
        {/if}
      </button>
      <button class:active={tab === 'persona'} onclick={() => (tab = 'persona')}>
        <IconEdit size={14} /> Persona
      </button>
      <button class:active={tab === 'memory'} onclick={() => (tab = 'memory')}>
        <IconBrain size={14} /> Memory
        {#if memories.length}<span class="badge">{memories.length}</span>{/if}
      </button>
      <button class:active={tab === 'coder'} onclick={() => (tab = 'coder')}>
        <IconFileText size={14} /> Coder
      </button>
      <button class:active={tab === 'secrets'} onclick={() => (tab = 'secrets')}>
        <IconKey size={14} /> Secrets
        {#if secrets.length}<span class="badge">{secrets.length}</span>{/if}
      </button>
      <button class:active={tab === 'voice'} onclick={() => (tab = 'voice')}>
        <IconVolume size={14} /> Voice
      </button>
      {#if instanceUrl}
        <button class:active={tab === 'app'} onclick={() => (tab = 'app')}>
          <IconMonitor size={14} /> App
        </button>
      {/if}
      {#if chatId}
        <button class:active={tab === 'share'} onclick={() => (tab = 'share')}>
          <IconShare size={14} /> Share
        </button>
      {/if}
    </nav>

    <div class="body">
      {#snippet credRow(key: string, label: string, hint: string)}
        {@const info = credentials?.[key as 'cf_api_token' | 'anthropic_api_key']}
        <div class="cred-row">
          <div class="cred-meta">
            <div class="cred-label">{label}</div>
            <div class="cred-hint">{hint}</div>
            <div class="cred-state">
              {#if info?.set}
                <span class="cred-set"><IconCheck size={11} /> {info.preview}</span>
              {:else}
                <span class="cred-unset">(not set — using env fallback if available)</span>
              {/if}
            </div>
          </div>
          {#if credEditing === key}
            <div class="cred-edit">
              <input
                type="password"
                placeholder="paste new key…"
                bind:value={credDraft}
                onkeydown={(e) => {
                  if (e.key === 'Enter' && credDraft.trim()) saveCred(key, credDraft.trim());
                  else if (e.key === 'Escape') cancelCredEdit();
                }}
                autocomplete="off"
              />
              <button
                class="primary small"
                disabled={!credDraft.trim() || credSaving === key}
                onclick={() => saveCred(key, credDraft.trim())}
              >
                {credSaving === key ? 'Saving…' : 'Save'}
              </button>
              <button class="icon-btn" onclick={cancelCredEdit} aria-label="Cancel">
                <IconX size={14} />
              </button>
            </div>
          {:else}
            <div class="cred-actions">
              <button class="cred-btn" onclick={() => startCredEdit(key)}>
                <IconEdit size={12} /> {info?.set ? 'Rotate' : 'Set'}
              </button>
              {#if info?.set}
                <button
                  class="cred-btn danger"
                  disabled={credSaving === key}
                  onclick={() => {
                    if (confirm(`Clear ${label} key? Will fall back to env default.`)) {
                      void saveCred(key, null);
                    }
                  }}
                >
                  <IconTrash size={12} /> Clear
                </button>
              {/if}
            </div>
          {/if}
        </div>
      {/snippet}

      <!-- Collapsible section header for the providers tab. `hint` renders
           right-aligned and muted (counts, status summaries). -->
      {#snippet secHead(title: string, open: boolean, toggle: () => void, hint?: string)}
        <button type="button" class="sec-head" class:open aria-expanded={open} onclick={toggle}>
          <span class="sec-chev" aria-hidden="true">›</span>
          <span class="sec-title">{title}</span>
          {#if hint}<span class="sec-hint">{hint}</span>{/if}
        </button>
      {/snippet}

      {#if loading}
        <p class="muted">Loading…</p>
      {:else}
        {#if tab === 'persona'}
          <p class="muted">
            Added to this chat's system prompt. Use it to set tone, role, or
            domain. The default tool-routing rules stay intact.
          </p>
          <textarea
            bind:value={prompt}
            rows="8"
            placeholder="e.g. You are a no-nonsense Go reviewer. Be terse, point out idiomatic alternatives, and never apologize."
          ></textarea>
          <div class="actions">
            <button class="primary" disabled={saving || !dirty} onclick={savePersona}>
              {saving ? 'Saving…' : dirty ? 'Save' : 'Saved'}
            </button>
          </div>

          <h3 style="margin-top: var(--sp-3);">
            Saved personas
            {#if personas.length}<span class="badge">{personas.length}</span>{/if}
          </h3>
          <p class="muted">
            Reusable custom-instructions + model presets. Create one, then tap
            Apply to set it on this chat (or pick one before sending your first
            message on a new chat).
          </p>
          {#if newPersonaOpen}
            <div class="persona-form">
              <input
                placeholder="Persona name (e.g. Go reviewer)"
                bind:value={personaForm.name}
                class="model-select"
              />
              <textarea
                rows="5"
                placeholder="System prompt — describe how the model should behave."
                bind:value={personaForm.system_prompt}
              ></textarea>
              <input
                placeholder="Model (optional, e.g. cf::@cf/google/gemma-4-26b-a4b-it)"
                bind:value={personaForm.model}
                class="model-select"
              />
              <div class="actions">
                <button class="btn-text" onclick={cancelPersonaEdit}>Cancel</button>
                <button class="primary" onclick={savePersonaForm} disabled={!personaForm.name.trim()}>
                  {editingPersona ? 'Save' : 'Create'}
                </button>
              </div>
            </div>
          {:else}
            <button class="btn-tonal" onclick={startCreatePersona}>+ New persona</button>
            {#if personasLoading && personas.length === 0}
              <p class="muted">Loading…</p>
            {:else if personas.length === 0}
              <div class="empty">
                <IconEdit size={36} />
                <p>No personas yet. Save your favorite custom-instructions presets here.</p>
              </div>
            {:else}
              <ul class="rows">
                {#each personas as p (p.id)}
                  <li>
                    <div class="row-meta">
                      <div class="row-title">{p.name}</div>
                      <div class="row-sub">
                        {(p.system_prompt ?? '').slice(0, 60)}{(p.system_prompt ?? '').length > 60 ? '…' : ''}
                      </div>
                    </div>
                    <div class="row-actions">
                      {#if chatId}
                        <button
                          class="icon-btn"
                          title="Apply to this chat"
                          aria-label="Apply"
                          onclick={() => applyPersonaToCurrentChat(p)}
                        ><IconCheck size={14} /></button>
                      {/if}
                      <button class="icon-btn" title="Edit" aria-label="Edit" onclick={() => startEditPersona(p)}>
                        <IconEdit size={14} />
                      </button>
                      <button
                        class="icon-btn danger"
                        title="Delete"
                        aria-label="Delete"
                        onclick={() => deletePersonaConfirm(p)}
                      ><IconTrash size={14} /></button>
                    </div>
                  </li>
                {/each}
              </ul>
            {/if}
          {/if}
        {/if}

        {#if tab === 'memory'}
          <p class="muted">
            Facts the model has saved about you (across all chats). Add or
            remove them by hand here, or say "remember that I…" in any chat.
          </p>
          <label class="mcp-row auto-mem">
            <span>
              <strong>Automatic memory</strong>
              <span class="muted"> — quietly save durable facts from your messages</span>
            </span>
            <input
              type="checkbox"
              checked={prefs?.auto_memory !== false}
              onchange={(e) =>
                savePrefsPatch({ auto_memory: (e.currentTarget as HTMLInputElement).checked })}
            />
          </label>
          {#if memoriesLoading && memories.length === 0}
            <p class="muted">Loading…</p>
          {:else if memories.length === 0}
            <div class="empty">
              <IconBrain size={36} />
              <p>No memories yet.</p>
            </div>
          {:else}
            <ul class="rows">
              {#each memories as m (m.id)}
                <li>
                  <span class="row-content">{m.content}</span>
                  <button
                    class="icon-btn danger"
                    aria-label="Forget"
                    title="Forget"
                    onclick={() => onDeleteMemory(m.id)}
                  >
                    <IconTrash size={14} />
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
          <div class="add-row">
            <input
              placeholder="Add a fact about yourself…"
              bind:value={newMemory}
              onkeydown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  onAddMemory();
                }
              }}
            />
            <button class="primary" onclick={onAddMemory} disabled={addingMemory || !newMemory.trim()}>
              Add
            </button>
          </div>
        {/if}


        {#if tab === 'secrets'}
          <p class="muted">
            Encrypted secrets the code-mode agent can <strong>use</strong> (API
            tokens, keys) without ever seeing or printing the value. They're
            injected into its sandbox shell as <code>$NAME</code> at runtime and
            redacted from any output. Stored encrypted; values are write-only —
            you can replace one but never read it back.
          </p>
          {#if secretsLoading && secrets.length === 0}
            <p class="muted">Loading…</p>
          {:else if secrets.length === 0}
            <div class="empty">
              <IconKey size={36} />
              <p>No secrets yet.</p>
            </div>
          {:else}
            <ul class="rows">
              {#each secrets as s (s.name)}
                <li>
                  <span class="row-content"><code>${s.name}</code></span>
                  <button
                    class="icon-btn danger"
                    aria-label="Delete secret"
                    title="Delete"
                    onclick={() => onDeleteSecret(s.name)}
                  >
                    <IconTrash size={14} />
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
          <div class="add-row" style="flex-wrap: wrap;">
            <input
              style="flex: 1 1 130px; text-transform: uppercase;"
              placeholder="NAME (e.g. CLOUDFLARE_API_TOKEN)"
              bind:value={newSecretName}
            />
            <input
              style="flex: 2 1 180px;"
              type="password"
              autocomplete="off"
              placeholder="value (hidden, write-only)"
              bind:value={newSecretValue}
              onkeydown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  onAddSecret();
                }
              }}
            />
            <button class="primary" onclick={onAddSecret} disabled={savingSecret || !newSecretName.trim() || !newSecretValue}>
              Save
            </button>
          </div>
        {/if}


        {#if tab === 'providers'}
          {#snippet toolRow(t: { key: ToolKey; label: string; hint: string })}
            {@const isChat = t.key === 'chat'}
            {@const options = optionsForTool(t.key)}
            {@const defaultId = options[0]?.id ?? ''}
            {@const current = isChat
              ? (chatModel || defaultId)
              : (toolModels[t.key] ?? defaultId)}
            <div class="tool-row">
              <div class="tool-meta">
                <div class="tool-label">{t.label}</div>
                <div class="tool-hint">{t.hint}</div>
              </div>
              <select
                class="model-select compact"
                value={current}
                onchange={(e) => {
                  const v = (e.currentTarget as HTMLSelectElement).value;
                  if (isChat) {
                    chatModel = v;
                    void applyChatModel(v);
                  } else {
                    toolModels = { ...toolModels, [t.key]: v };
                    void savePersistentTool(t.key, v);
                  }
                }}
                disabled={savingToolKey === t.key || (isChat && savingChatModel)}
              >
                {#each options as m}
                  <option value={m.id}>{m.label}{m.note ? ` — ${m.note}` : ''}</option>
                {/each}
              </select>
            </div>
          {/snippet}

          <div class="tool-row" style="margin-bottom: var(--sp-2);">
            <div class="tool-meta">
              <div class="tool-label">Theme</div>
              <div class="tool-hint">
                System follows your device's light/dark setting.
              </div>
            </div>
            <select
              class="model-select compact"
              value={themeChoice}
              onchange={(e) => {
                themeChoice = (e.currentTarget as HTMLSelectElement).value as ThemeChoice;
                setThemeChoice(themeChoice);
              }}
            >
              <option value="system">System</option>
              <option value="dark">Dark</option>
              <option value="light">Light</option>
            </select>
          </div>

          <div class="tool-row" style="margin-bottom: var(--sp-2);">
            <div class="tool-meta">
              <div class="tool-label">Usage counter</div>
              <div class="tool-hint">
                What the sidebar shows. Auto follows the active provider —
                Cloudflare neurons, or tokens + $ cost for OpenRouter etc.
              </div>
            </div>
            <select
              class="model-select compact"
              value={prefs?.usage_display ?? 'auto'}
              onchange={(e) =>
                savePrefsPatch({ usage_display: (e.currentTarget as HTMLSelectElement).value as any })}
            >
              <option value="auto">Auto (per provider)</option>
              <option value="neurons">Neurons (Cloudflare)</option>
              <option value="tokens">Tokens &amp; cost</option>
            </select>
          </div>

          {@render secHead('Models', secModels, () => (secModels = !secModels))}
          {#if secModels}
            <p class="muted">
              Pick a model for each tool. Saved to your profile and used on every
              chat. The Chat row also overrides the model for this conversation.
            </p>
            <div class="tool-models">
              {#each TOOL_LABELS.filter((t) => !t.key.endsWith('_hd')) as t}
                {@render toolRow(t)}
              {/each}
            </div>
            <button
              type="button"
              class="sec-head sub"
              class:open={secHd}
              aria-expanded={secHd}
              onclick={() => (secHd = !secHd)}
            >
              <span class="sec-chev" aria-hidden="true">›</span>
              <span class="sec-title">HD variants</span>
              <span class="sec-hint">/HD · "best quality" auto-routes here</span>
            </button>
            {#if secHd}
              <p class="muted small">
                Image / image edit each get an <strong>HD</strong> row. Type
                <code>/HD</code> (or ask for HD / 4K / "best quality") and the
                model auto-routes to it.
              </p>
              <div class="tool-models">
                {#each TOOL_LABELS.filter((t) => t.key.endsWith('_hd')) as t}
                  {@render toolRow(t)}
                {/each}
              </div>
            {/if}

            <button type="button" class="sec-head sub" class:open={secEmbed}
              aria-expanded={secEmbed} onclick={() => (secEmbed = !secEmbed)}>
              <span class="sec-chev" aria-hidden="true">›</span>
              <span class="sec-title">Embedding (document search)</span>
              <span class="sec-hint">
                {embedIsCustom ? `${embedCustom.pid} · dim ${prefs.embed_dim ?? '?'}` : 'Cloudflare bge'}
              </span>
            </button>
            {#if secEmbed}
              <p class="muted small">
                The model that turns uploaded documents into vectors. Defaults to
                Cloudflare's bge; point it at any OpenAI-compatible
                <code>/embeddings</code> provider.
              </p>
              <label class="field">
                <span class="field-label">Provider</span>
                <select class="model-select" value={embedSelectValue}
                  onchange={(e) => onEmbedProviderChange((e.currentTarget as HTMLSelectElement).value)}
                  disabled={ragBusy}>
                  <option value="">Cloudflare — {app.info?.embed_default?.model ?? 'bge'} (default)</option>
                  {#each customProviders as p (p.id)}
                    <option value={p.id}>{p.name} — custom</option>
                  {/each}
                </select>
              </label>
              {#if embedIsCustom}
                <label class="field">
                  <span class="field-label">Model</span>
                  <input type="text" placeholder="e.g. nomic-embed-text / text-embedding-3-small"
                    value={embedCustom.model}
                    onchange={(e) => saveEmbed({ embed_model: `${embedCustom.pid}::${(e.currentTarget as HTMLInputElement).value.trim()}` })}
                    disabled={ragBusy} />
                </label>
                <label class="field">
                  <span class="field-label">Dimensions</span>
                  <input type="number" min="8" max="8192" placeholder="e.g. 768 / 1024 / 1536"
                    value={prefs.embed_dim ?? ''}
                    onchange={(e) => {
                      const n = parseInt((e.currentTarget as HTMLInputElement).value, 10);
                      void saveEmbed({ embed_dim: Number.isFinite(n) ? n : null });
                    }}
                    disabled={ragBusy} />
                </label>
                <p class="muted small">Dimensions must match the model's output width exactly.</p>
              {/if}
              {#if ragStatus}
                <div class="embed-status" class:warn={ragStatus.stale}>
                  {#if ragStatus.indexed_chunks === 0}
                    No documents indexed yet.
                  {:else if ragStatus.stale}
                    ⚠ {ragStatus.indexed_chunks} chunk{ragStatus.indexed_chunks === 1 ? '' : 's'}
                    embedded at dim {ragStatus.collection_dim}, but the model now
                    outputs {ragStatus.configured_dim}. Search is degraded until re-embed.
                    <button class="primary" onclick={runReindexAll} disabled={ragBusy}>
                      {ragBusy ? 'Re-embedding…' : 'Re-embed all'}
                    </button>
                  {:else}
                    {ragStatus.indexed_chunks} chunk{ragStatus.indexed_chunks === 1 ? '' : 's'} indexed
                    at dim {ragStatus.collection_dim ?? ragStatus.configured_dim}.
                    <button class="btn-text" onclick={runReindexAll} disabled={ragBusy}>
                      {ragBusy ? 'Re-embedding…' : 'Re-embed all'}
                    </button>
                  {/if}
                </div>
              {/if}
            {/if}
          {/if}

          {@render secHead(
            'Providers',
            secProviders,
            () => (secProviders = !secProviders),
            credentials
              ? [credentials.cf_api_token?.set ? 'CF ✓' : 'CF —',
                 credentials.anthropic_api_key?.set ? 'Claude ✓' : 'Claude —'].join(' · ')
              : undefined
          )}
          {#if secProviders}
          <p class="muted">
            Builtin provider keys can be set / rotated / cleared here without
            touching the server's <code>.env</code> (a cleared key falls back to
            the env default). Or connect any OpenAI-compatible API (OpenAI,
            OpenRouter, Groq, Ollama) — models you register appear in the
            per-tool pickers above. Image generation still routes through
            Cloudflare for now.
          </p>

          {#each providersList.filter((p) => p.builtin) as p (p.id)}
            <div class="provider-card builtin">
              <div class="provider-head">
                <div class="provider-name">{p.name} <span class="tag">builtin</span></div>
                <div class="provider-sub">{p.base_url}</div>
                <div class="provider-sub">
                  {cfModels.length > 0
                    ? `${cfModels.length} custom model(s) — overrides defaults`
                    : 'Using built-in default model list'}
                </div>
                {@render credRow(
                  'cf_api_token',
                  'API key',
                  'Used for Gemma / Qwen / Llama / Kimi / GLM / gpt-oss + image gen.'
                )}
              </div>
              <div class="provider-actions">
                <button
                  class="icon-btn"
                  title="Edit Cloudflare model list"
                  aria-label="Edit CF models"
                  onclick={startEditCfModels}
                >
                  <IconEdit size={14} />
                </button>
              </div>
            </div>
          {/each}

          {#if cfFormOpen}
            <div class="provider-form">
              <div class="field-label">Cloudflare model list</div>
              <p class="muted small">
                One row per model. Tick which tools each model should appear
                in. Removing a row hides that model from all dropdowns. Reset
                to defaults to throw away your customizations.
              </p>
              <div class="model-rows">
                {#each cfFormDraft as m, idx (idx)}
                  <div class="model-row">
                    <input
                      class="model-id"
                      placeholder="@cf/black-forest-labs/flux-2-klein-4b"
                      bind:value={cfFormDraft[idx].id}
                    />
                    <input
                      class="model-label"
                      placeholder="Label"
                      bind:value={cfFormDraft[idx].label}
                    />
                    <input
                      class="model-note"
                      placeholder="note (optional)"
                      bind:value={cfFormDraft[idx].note}
                    />
                    <input
                      class="model-steps"
                      type="number"
                      min="1"
                      max="50"
                      placeholder="steps"
                      title="Optional step count (1-50). Blank or 0 = use the model default. Currently applied by Flux models; other model types ignore it unless their endpoint supports a steps parameter."
                      bind:value={cfFormDraft[idx].steps}
                    />
                    <div class="model-tools-pick">
                      {#each TOOL_KEYS as t}
                        <button
                          type="button"
                          class="tool-chip"
                          class:on={(cfFormDraft[idx].tools || []).includes(t)}
                          onclick={() => toggleCfModelTool(idx, t)}
                          title={`Use this model for ${t}`}
                        >{TOOL_CHIP_LABEL[t]}</button>
                      {/each}
                    </div>
                    <button
                      type="button"
                      class="icon-btn danger"
                      aria-label="Remove model"
                      onclick={() => removeCfModelRow(idx)}
                    >
                      <IconX size={14} />
                    </button>
                  </div>
                {/each}
              </div>
              <button class="btn-text" onclick={addCfModelRow}>+ Add model</button>
              <div class="actions" style="justify-content: space-between;">
                <button class="btn-text" onclick={resetCfModelsToDefaults} disabled={cfFormSaving}>
                  Reset to defaults
                </button>
                <div style="display: flex; gap: var(--sp-2);">
                  <button class="btn-text" onclick={cancelEditCfModels} disabled={cfFormSaving}>Cancel</button>
                  <button class="primary" onclick={saveCfModels} disabled={cfFormSaving}>
                    {cfFormSaving ? 'Saving…' : 'Save'}
                  </button>
                </div>
              </div>
            </div>
          {/if}

          <!-- Anthropic isn't a row in providersList (it's not OpenAI-compatible;
               the backend talks native Messages API) — render its builtin card
               statically so its key lives with the other providers. -->
          <div class="provider-card builtin">
            <div class="provider-head">
              <div class="provider-name">Anthropic Claude <span class="tag">builtin</span></div>
              <div class="provider-sub">Native Messages API — the Claude entries in Code Mode.</div>
              {@render credRow('anthropic_api_key', 'API key', '')}
            </div>
          </div>

          {#each providersList.filter((p) => !p.builtin) as p (p.id)}
            <div class="provider-card">
              <div class="provider-head">
                <div class="provider-name">{p.name}</div>
                <div class="provider-sub">{p.base_url}</div>
                <div class="provider-sub">Key: <code>{p.api_key}</code></div>
                {#if (p.models?.length ?? 0) > 0}
                  <div class="provider-models">
                    {#each p.models as pm}
                      <span class="model-chip" title={pm.id}>
                        {pm.label}
                        <span class="model-tools">{(pm.tools || []).join(' · ')}</span>
                      </span>
                    {/each}
                  </div>
                {:else}
                  <div class="provider-sub italic">No models registered — add some so they appear in pickers.</div>
                {/if}
                {#if providerTestResult[p.id]}
                  <div class="provider-test-result">{providerTestResult[p.id]}</div>
                {/if}
              </div>
              <div class="provider-actions">
                <button
                  class="icon-btn"
                  title="Test connection"
                  aria-label="Test"
                  disabled={providerTesting === p.id}
                  onclick={() => onTestProvider(p)}
                >
                  {#if providerTesting === p.id}<span class="spinner" aria-hidden="true"></span>
                  {:else}<IconCheck size={14} />{/if}
                </button>
                <button
                  class="icon-btn"
                  title="Edit"
                  aria-label="Edit"
                  onclick={() => startEditProvider(p)}
                >
                  <IconEdit size={14} />
                </button>
                <button
                  class="icon-btn danger"
                  title="Delete"
                  aria-label="Delete"
                  onclick={() => onDeleteProvider(p)}
                >
                  <IconTrash size={14} />
                </button>
              </div>
            </div>
          {/each}

          {#if !providerFormOpen}
            <button class="btn-tonal" onclick={startCreateProvider}>+ Add provider</button>
          {:else}
            <div class="provider-form">
              <label class="field">
                <span class="field-label">Display name</span>
                <input bind:value={providerForm.name} placeholder="e.g. OpenAI" />
              </label>
              <label class="field">
                <span class="field-label">Base URL</span>
                <input bind:value={providerForm.base_url} placeholder="https://api.openai.com/v1" />
              </label>
              <label class="field">
                <span class="field-label">
                  API key{#if editingProviderId} <span class="muted-strong">(leave blank to keep existing)</span>{/if}
                </span>
                <input type="password" bind:value={providerForm.api_key} placeholder="sk-…" autocomplete="off" />
              </label>

              <div class="field">
                <span class="field-label">Models</span>
                <p class="muted small">
                  Add the specific model IDs you want surfaced in the per-tool pickers.
                  Tick the tools each model is appropriate for.
                </p>
                <div class="model-rows">
                  {#each providerForm.models as m, idx (idx)}
                    <div class="model-row">
                      <input
                        class="model-id"
                        placeholder="gpt-4o"
                        bind:value={providerForm.models[idx].id}
                      />
                      <input
                        class="model-label"
                        placeholder="Label (e.g. GPT-4o)"
                        bind:value={providerForm.models[idx].label}
                      />
                      <input
                        class="model-note"
                        placeholder="note (optional)"
                        bind:value={providerForm.models[idx].note}
                      />
                      <div class="model-tools-pick">
                        {#each TOOL_KEYS as t}
                          <button
                            type="button"
                            class="tool-chip"
                            class:on={(providerForm.models[idx].tools || []).includes(t)}
                            onclick={() => toggleModelTool(idx, t)}
                            title={`Use this model for ${t}`}
                          >{TOOL_CHIP_LABEL[t]}</button>
                        {/each}
                        <span class="cap-sep" aria-hidden="true">|</span>
                        <button
                          type="button"
                          class="tool-chip cap"
                          class:on={!!providerForm.models[idx].vision}
                          onclick={() => toggleModelCap(idx, 'vision')}
                          title="This model can see images (accepts image input)"
                        >👁 vision</button>
                        <button
                          type="button"
                          class="tool-chip cap"
                          class:on={!!providerForm.models[idx].reasoning}
                          onclick={() => toggleModelCap(idx, 'reasoning')}
                          title="This model emits a reasoning channel (cap its effort)"
                        >💭 reasoning</button>
                      </div>
                      <button
                        type="button"
                        class="icon-btn danger"
                        aria-label="Remove"
                        onclick={() => removeModelRow(idx)}
                      >
                        <IconX size={14} />
                      </button>
                    </div>
                  {/each}
                </div>
                <button class="btn-text" onclick={addModelRow}>+ Add model</button>
              </div>

              <div class="actions">
                <button class="btn-text" onclick={cancelProviderForm} disabled={providerSaving}>Cancel</button>
                <button class="primary" onclick={saveProviderForm} disabled={providerSaving}>
                  {providerSaving ? 'Saving…' : editingProviderId ? 'Save' : 'Add provider'}
                </button>
              </div>
            </div>
          {/if}
          {/if}

          <!-- ── MCP servers ─────────────────────────────────────────── -->
          {@render secHead(
            'MCP servers',
            secMcp,
            () => (secMcp = !secMcp),
            `${mcpServers.length || 'none'}`
          )}
          {#if secMcp}
          <p class="muted">
            Remote Model Context Protocol servers — their tools are offered to
            the chat model automatically (as <code>mcp__…</code> tools).
          </p>
          {#each mcpServers as s (s.id)}
            <div class="mcp-row" class:disabled={!s.enabled}>
              <div class="mcp-meta">
                <strong>{s.name}</strong>
                <span class="muted mcp-url">{s.url}</span>
              </div>
              <div class="mcp-actions">
                <button class="btn-text" disabled={mcpTesting === s.id} onclick={() => testMcp(s)}>
                  {mcpTesting === s.id ? 'Testing…' : 'Test'}
                </button>
                <button class="btn-text" onclick={() => toggleMcp(s)}>
                  {s.enabled ? 'Disable' : 'Enable'}
                </button>
                <button class="btn-text danger" onclick={() => removeMcp(s)}>Remove</button>
              </div>
            </div>
          {/each}
          <div class="mcp-form">
            <input placeholder="Name (e.g. workers-ai)" bind:value={mcpForm.name} maxlength="60" />
            <input placeholder="URL (https://…/mcp)" bind:value={mcpForm.url} />
            <input placeholder="Authorization header (optional, e.g. Bearer …)" bind:value={mcpForm.auth_header} />
            <button class="primary" onclick={addMcp} disabled={mcpSaving}>
              {mcpSaving ? 'Adding…' : 'Add MCP server'}
            </button>
          </div>
          {/if}
        {/if}

        {#if tab === 'coder'}
          <p class="muted">
            Settings for agentic code chats. Pick the code-mode model under the
            <strong>Providers</strong> tab (Code Mode slot).
          </p>
          <div class="switch-row">
            <span class="switch-text">
              <span class="switch-label">Review edits before applying</span>
              <span class="switch-hint">
                When on, every file edit in a code chat pauses and shows you the
                diff — nothing is written until you tap Apply. Reject sends the
                agent back to revise. Off (default) applies edits immediately.
              </span>
            </span>
            <button
              class="switch"
              class:on={reviewEdits}
              role="switch"
              aria-checked={reviewEdits}
              aria-label="Review edits before applying"
              disabled={prefsSaving}
              onclick={toggleReviewEdits}
            >
              <span class="knob"></span>
            </button>
          </div>

          <div class="switch-row">
            <span class="switch-text">
              <span class="switch-label">Auto-verify edits</span>
              <span class="switch-hint">
                After an edit, run the project's build / type-check (tsc, py_compile,
                go build) and push the agent to fix any errors before it finishes.
                On by default — turn off for speed, or if a pre-existing repo error
                causes false failures.
              </span>
            </span>
            <button
              class="switch"
              class:on={verifyEdits}
              role="switch"
              aria-checked={verifyEdits}
              aria-label="Auto-verify edits"
              disabled={prefsSaving}
              onclick={toggleVerifyEdits}
            >
              <span class="knob"></span>
            </button>
          </div>

          <div class="switch-row">
            <span class="switch-text">
              <span class="switch-label">Max steps per turn</span>
              <span class="switch-hint">
                How many tool rounds the agent may take before it must stop (1–100).
                Higher lets it grind through bigger tasks; lower reins it in. Blank
                uses the default (40).
              </span>
            </span>
            <input
              class="num-input"
              type="number"
              min="1"
              max="100"
              placeholder="40"
              bind:value={maxRoundsCode}
              disabled={prefsSaving}
              onchange={saveMaxRounds}
              onblur={saveMaxRounds}
            />
          </div>

          <div class="switch-row">
            <span class="switch-text">
              <span class="switch-label">Response length cap</span>
              <span class="switch-hint">
                Max tokens the coder model may emit per round (512–32768). Lower it
                if the model rambles; raise it for long files. Blank uses the
                default.
              </span>
            </span>
            <input
              class="num-input"
              type="number"
              min="512"
              max="32768"
              step="512"
              placeholder="default"
              bind:value={maxTokensCoder}
              disabled={prefsSaving}
              onchange={saveMaxTokens}
              onblur={saveMaxTokens}
            />
          </div>
        {/if}

        {#if tab === 'voice'}
          <p class="muted">
            Choose the TTS engine for assistant speech and the STT engine
            for the mic. Cloudflare options run on CF's GPUs and are much
            faster but consume CF neurons.
          </p>

          <h3 style="margin-top: var(--sp-2);">Text-to-speech</h3>
          <label class="field">
            <span class="field-label">Provider</span>
            <select
              class="model-select"
              value={ttsSelectValue}
              onchange={(e) => onAudioProviderChange('tts', (e.currentTarget as HTMLSelectElement).value, ttsCustom.model)}
              disabled={prefsSaving}
            >
              {#each app.info?.tts_providers ?? [] as p}
                <option value={p.id}>{p.label}</option>
              {/each}
              {#if customProviders.length}
                <optgroup label="Custom (OpenAI-compatible)">
                  {#each customProviders as p (p.id)}
                    <option value={`${CUSTOM_PREFIX}${p.id}`}>{p.name} — custom</option>
                  {/each}
                </optgroup>
              {/if}
            </select>
          </label>
          {#if ttsIsCustom}
            <label class="field">
              <span class="field-label">Model</span>
              <input type="text" placeholder="e.g. tts-1 / gpt-4o-mini-tts"
                value={ttsCustom.model}
                onchange={(e) => onAudioModelChange('tts', ttsCustom.pid, (e.currentTarget as HTMLInputElement).value)}
                disabled={prefsSaving} />
            </label>
            <label class="field">
              <span class="field-label">Voice</span>
              <input type="text" placeholder="e.g. alloy" value={prefs.tts_voice ?? ''}
                onchange={(e) => savePrefs({ tts_voice: (e.currentTarget as HTMLInputElement).value })}
                disabled={prefsSaving} />
            </label>
            <p class="muted small">Calls <code>POST {'{base_url}'}/audio/speech</code> — set the API key on its Providers card.</p>
          {:else if currentTtsVoices.length > 0}
            <label class="field">
              <span class="field-label">Voice</span>
              <select
                class="model-select"
                value={currentTtsVoice}
                onchange={(e) => savePrefs({ tts_voice: (e.currentTarget as HTMLSelectElement).value })}
                disabled={prefsSaving}
              >
                {#each currentTtsVoices as v}
                  <option value={v}>{v}</option>
                {/each}
              </select>
            </label>
          {/if}

          <h3 style="margin-top: var(--sp-3);">Speech-to-text</h3>
          <label class="field">
            <span class="field-label">Provider</span>
            <select
              class="model-select"
              value={sttSelectValue}
              onchange={(e) => onAudioProviderChange('stt', (e.currentTarget as HTMLSelectElement).value, sttCustom.model)}
              disabled={prefsSaving}
            >
              {#each app.info?.stt_providers ?? [] as p}
                <option value={p.id}>{p.label}</option>
              {/each}
              {#if customProviders.length}
                <optgroup label="Custom (OpenAI-compatible)">
                  {#each customProviders as p (p.id)}
                    <option value={`${CUSTOM_PREFIX}${p.id}`}>{p.name} — custom</option>
                  {/each}
                </optgroup>
              {/if}
            </select>
          </label>
          {#if sttIsCustom}
            <label class="field">
              <span class="field-label">Model</span>
              <input type="text" placeholder="e.g. whisper-1"
                value={sttCustom.model}
                onchange={(e) => onAudioModelChange('stt', sttCustom.pid, (e.currentTarget as HTMLInputElement).value)}
                disabled={prefsSaving} />
            </label>
            <p class="muted small">Calls <code>POST {'{base_url}'}/audio/transcriptions</code> — set the API key on its Providers card.</p>
          {/if}
        {/if}

        {#if tab === 'app' && instanceUrl}
          <p class="muted">
            This Android app is a shell around a TomSense server. Point it at
            a different instance here — the app reconnects immediately.
            {#if !instanceUrl.isSet}
              Currently using the URL baked into the APK.
            {/if}
          </p>
          <label class="field">
            <span class="field-label">Server URL</span>
            <input
              type="url"
              placeholder="https://tomsense.your-domain.com"
              bind:value={instanceUrlDraft}
              disabled={instanceUrlSaving}
            />
          </label>
          <button
            class="primary"
            onclick={saveInstanceUrl}
            disabled={instanceUrlSaving || instanceUrlDraft.trim() === instanceUrl.url}
          >
            <IconRefresh size={14} /> {instanceUrlSaving ? 'Reconnecting…' : 'Save & reconnect'}
          </button>
        {/if}

        {#if tab === 'share' && chatId}
          <p class="muted">
            Generate a public read-only URL. Anyone with the link can read
            the chat (no composer). Revoke to invalidate.
          </p>
          {#if shareToken}
            <div class="share-row">
              <input readonly value={shareUrl} onclick={(e) => (e.currentTarget as HTMLInputElement).select()} />
              <button class="primary inline" onclick={copyShareUrl}>
                <IconCopy size={14} /> Copy
              </button>
            </div>
            <button class="danger-link" onclick={revoke} disabled={saving}>
              Revoke share link
            </button>
          {:else}
            <button class="primary" onclick={generateShare} disabled={saving}>
              <IconLink size={14} /> {saving ? 'Creating…' : 'Create share link'}
            </button>
          {/if}
        {/if}
      {/if}
    </div>
  </div>
{/if}

<style>
  /* Builtin-provider credentials */
  .cred-row {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-3);
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .cred-meta {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .cred-label {
    color: var(--text-strong);
    font-weight: 600;
  }
  .cred-hint {
    color: var(--muted);
    font-size: var(--fs-xs);
  }
  .cred-state {
    font-size: var(--fs-sm);
    margin-top: 4px;
  }
  .cred-set {
    color: var(--accent);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  }
  .cred-unset {
    color: var(--muted);
    font-style: italic;
  }
  .cred-actions {
    display: flex;
    gap: var(--sp-1);
    flex-wrap: wrap;
  }
  .cred-edit {
    display: flex;
    gap: var(--sp-1);
    align-items: center;
    flex-wrap: wrap;
  }
  .cred-edit input {
    flex: 1;
    min-width: 200px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    padding: 6px 10px;
    color: var(--text);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
  }
  .cred-edit input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .cred-btn {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--r-pill);
    color: var(--text);
    cursor: pointer;
    padding: 4px 10px;
    font-size: var(--fs-xs);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    transition: border-color var(--t-fast), color var(--t-fast);
  }
  .cred-btn:hover:not(:disabled) {
    border-color: var(--border-strong);
    color: var(--text-strong);
  }
  .cred-btn.danger:hover:not(:disabled) {
    color: #d63d3d;
    border-color: rgba(214, 61, 61, 0.4);
  }
  .cred-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    z-index: 100;
    border: 0;
    padding: 0;
    animation: fade-in 0.15s var(--ease);
  }
  .drawer {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: min(440px, 100%);
    background: var(--bg-elevated);
    border-left: 1px solid var(--border);
    z-index: 101;
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-drawer);
    animation: slide-in 0.2s var(--ease);
  }
  @keyframes slide-in {
    from { transform: translateX(20px); opacity: 0.5; }
    to   { transform: translateX(0); opacity: 1; }
  }
  @keyframes fade-in {
    from { opacity: 0; }
    to   { opacity: 1; }
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: var(--sp-3) var(--sp-4);
    /* edge-to-edge (targetSdk 35): clear the status bar. */
    padding-top: calc(var(--sp-3) + env(safe-area-inset-top));
    border-bottom: 1px solid var(--border);
  }
  h2 {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: 600;
  }
  .tabs {
    display: flex;
    gap: 0;
    border-bottom: 1px solid var(--border);
    padding: 0 var(--sp-3);
    overflow-x: auto;
  }
  .tabs button {
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: var(--sp-3) var(--sp-2);
    font-size: var(--fs-sm);
    font-weight: 500;
    cursor: pointer;
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
    transition: color var(--t-fast);
  }
  .tabs button:hover {
    color: var(--text);
  }
  .tabs button.active {
    color: var(--accent);
  }
  .tabs button.active::after {
    content: '';
    position: absolute;
    bottom: -1px;
    left: var(--sp-2);
    right: var(--sp-2);
    height: 2px;
    background: var(--accent);
    border-radius: 2px;
  }
  .badge {
    background: var(--panel-2);
    color: var(--muted-strong);
    border-radius: var(--r-pill);
    padding: 1px 6px;
    font-size: 10px;
    font-weight: 600;
  }
  .tabs button.active .badge {
    background: rgba(255, 138, 76, 0.2);
    color: var(--accent);
  }
  .body {
    flex: 1;
    overflow-y: auto;
    padding: var(--sp-4);
    /* edge-to-edge (targetSdk 35): clear the gesture-nav bar. */
    padding-bottom: calc(var(--sp-4) + env(safe-area-inset-bottom));
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .muted {
    color: var(--muted);
    font-size: var(--fs-sm);
    margin: 0;
    line-height: var(--lh-base);
  }
  /* Settings toggle row (Coder tab). */
  .switch-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-top: 16px;
    padding: 12px 0;
  }
  .switch-text {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .switch-label {
    font-weight: 600;
    font-size: var(--fs-sm);
  }
  .switch-hint {
    color: var(--muted);
    font-size: var(--fs-xs);
    line-height: var(--lh-base);
    max-width: 46ch;
  }
  .switch {
    flex: none;
    width: 44px;
    height: 26px;
    border-radius: 999px;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    position: relative;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s;
  }
  .switch .knob {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: var(--muted-strong);
    transition: transform 0.15s, background 0.15s;
  }
  .switch.on {
    background: var(--accent);
    border-color: var(--accent);
  }
  .switch.on .knob {
    transform: translateX(18px);
    background: var(--accent-fg);
  }
  .switch:disabled {
    opacity: 0.6;
    cursor: default;
  }
  .num-input {
    flex: none;
    width: 90px;
    padding: 7px 10px;
    border: 1px solid var(--border-strong);
    border-radius: var(--r-3, 8px);
    background: var(--bg);
    color: var(--text);
    font-size: var(--fs-sm);
    text-align: right;
  }
  .num-input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .num-input:disabled {
    opacity: 0.6;
  }
  textarea {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 10px 12px;
    font: inherit;
    font-size: var(--fs-md);
    line-height: var(--lh-base);
    min-height: 140px;
    resize: vertical;
    transition: border-color var(--t-fast);
  }
  textarea:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
  }
  .primary {
    background: var(--accent);
    color: var(--accent-fg);
    border: 0;
    border-radius: var(--r-3);
    padding: 8px 16px;
    font-weight: 600;
    font-size: var(--fs-md);
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: background var(--t-fast);
  }
  .primary:hover:not(:disabled) {
    background: var(--accent-hover);
  }
  .primary:disabled {
    background: var(--panel-2);
    color: var(--muted);
    cursor: not-allowed;
  }
  .primary.inline {
    padding: 6px 12px;
    font-size: var(--fs-sm);
  }
  .danger-link {
    background: transparent;
    color: var(--danger);
    border: 0;
    padding: 4px 0;
    font-size: var(--fs-sm);
    align-self: flex-start;
  }
  .danger-link:hover:not(:disabled) {
    text-decoration: underline;
  }
  .icon-btn.danger:hover:not(:disabled) {
    color: var(--danger);
  }

  /* Rows used in Docs + Memory tabs */
  .rows {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: var(--sp-1);
  }
  .rows li {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 8px 8px 8px 12px;
    transition: border-color var(--t-fast);
  }
  .rows li:hover {
    border-color: var(--border-strong);
  }
  .row-icon {
    color: var(--muted-strong);
    flex-shrink: 0;
  }
  .row-meta {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
  }
  .row-title {
    color: var(--text);
    font-size: var(--fs-md);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-decoration: none;
  }
  .row-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }
  .row-sub {
    font-size: var(--fs-xs);
    color: var(--muted);
    display: flex;
    gap: 6px;
    align-items: center;
  }
  .row-content {
    flex: 1;
    font-size: var(--fs-sm);
    line-height: var(--lh-base);
    word-break: break-word;
  }
  .row-actions {
    display: flex;
    gap: 2px;
    flex-shrink: 0;
  }
  .tag {
    border-radius: 3px;
    padding: 1px 5px;
    font-weight: 600;
    font-size: 9px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .tag.indexed {
    background: rgba(74, 222, 128, 0.18);
    color: var(--success);
  }

  .add-row {
    display: flex;
    gap: var(--sp-2);
  }
  .add-row input {
    flex: 1;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 8px 12px;
    font: inherit;
    font-size: var(--fs-md);
  }
  .add-row input:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }

  .share-row {
    display: flex;
    gap: var(--sp-2);
  }
  .share-row input {
    flex: 1;
    background: var(--panel);
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: var(--r-3);
    padding: 8px 10px;
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: var(--sp-6);
    color: var(--muted);
    gap: var(--sp-2);
    text-align: center;
  }
  .empty p {
    margin: 0;
    font-size: var(--fs-sm);
  }

  .model-select {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    padding: 10px 12px;
    font: inherit;
    font-size: var(--fs-md);
  }
  .model-select:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }
  .tool-models {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .tool-row {
    display: grid;
    grid-template-columns: 1fr minmax(180px, 240px);
    gap: var(--sp-3);
    align-items: center;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 10px 12px;
  }
  .tool-meta {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .tool-label {
    font-size: var(--fs-md);
    font-weight: 600;
    color: var(--text);
  }
  .tool-hint {
    color: var(--muted);
    font-size: var(--fs-xs);
    line-height: var(--lh-tight);
  }
  .model-select.compact {
    padding: 8px 10px;
    font-size: var(--fs-sm);
  }
  .small {
    font-size: var(--fs-xs);
  }
  .embed-status {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--sp-2);
    margin-top: var(--sp-2);
    padding: var(--sp-2);
    border-radius: var(--r-2, 6px);
    border: 1px solid var(--border);
    font-size: var(--fs-xs);
    color: var(--muted);
  }
  .embed-status.warn {
    border-color: var(--warning, #d19a00);
    color: var(--text);
  }
  .embed-status button {
    margin-left: auto;
  }
  .link-btn {
    background: transparent;
    border: 0;
    padding: 0;
    color: var(--accent);
    cursor: pointer;
    font: inherit;
    text-decoration: underline;
  }

  /* Provider cards */
  .provider-card {
    display: flex;
    gap: var(--sp-2);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 10px 12px;
  }
  .provider-card.builtin {
    border-style: dashed;
    opacity: 0.9;
  }
  /* Cred rows embedded in provider cards blend into the card instead of
     rendering their own box-in-box panel. */
  .provider-card .cred-row {
    background: transparent;
    border: 0;
    padding: 0;
    margin-top: var(--sp-1);
  }
  .provider-card .cred-label {
    font-size: var(--fs-sm);
  }
  .cred-hint:empty {
    display: none;
  }
  .provider-head {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .provider-name {
    font-weight: 600;
    color: var(--text);
    font-size: var(--fs-md);
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .provider-sub {
    font-size: var(--fs-xs);
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .provider-sub code {
    font-family: var(--font-mono);
    color: var(--muted-strong);
  }
  .provider-sub.italic {
    font-style: italic;
  }
  .provider-models {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 4px;
  }
  .model-chip {
    background: var(--panel-2);
    border-radius: var(--r-pill);
    padding: 2px 8px;
    font-size: var(--fs-xs);
    color: var(--text);
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .model-tools {
    color: var(--muted);
    font-size: 10px;
  }
  .provider-test-result {
    margin-top: 4px;
    font-size: var(--fs-xs);
    color: var(--muted-strong);
  }
  .provider-actions {
    display: flex;
    gap: 2px;
    flex-shrink: 0;
    align-items: flex-start;
  }

  .provider-form {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-3);
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .provider-form input {
    width: 100%;
    background: var(--panel-2);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 8px 10px;
    font: inherit;
    font-size: var(--fs-sm);
  }
  .provider-form input:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 2px rgba(255, 138, 76, 0.15);
  }
  .model-rows {
    display: flex;
    flex-direction: column;
    gap: var(--sp-1);
    margin-top: 4px;
  }
  /* Always-stacked since the drawer is capped at 440px wide — a 6-col grid
   * here never actually fits even on desktop. Each row is a small card with
   * the remove button pinned top-right. */
  .model-row {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px 40px 10px 10px;
    background: var(--panel-2);
    border-radius: var(--r-3);
    position: relative;
  }
  .model-row > input {
    width: 100%;
  }
  .model-steps {
    text-align: center;
    width: 100%;
  }
  .model-row > .icon-btn.danger {
    position: absolute;
    top: 8px;
    right: 8px;
  }
  .model-row input {
    font-size: var(--fs-xs);
    padding: 6px 8px;
  }
  .model-tools-pick {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
  .tool-chip {
    background: var(--panel-2);
    border: 1px solid var(--border);
    border-radius: var(--r-pill);
    padding: 2px 8px;
    font-size: 10px;
    color: var(--muted);
    cursor: pointer;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    font-weight: 600;
  }
  .tool-chip.on {
    background: rgba(255, 138, 76, 0.2);
    color: var(--accent);
    border-color: var(--accent);
  }
  .tool-chip.cap {
    text-transform: none;
    letter-spacing: 0;
  }
  .tool-chip.cap.on {
    background: rgba(96, 165, 250, 0.2);
    color: #60a5fa;
    border-color: #60a5fa;
  }
  .cap-sep {
    align-self: center;
    color: var(--border);
    font-size: 11px;
  }
  @media (max-width: 480px) {
    .tool-row {
      grid-template-columns: 1fr;
      gap: var(--sp-2);
    }
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .field-label {
    font-size: var(--fs-xs);
    color: var(--muted-strong);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .persona-form {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .persona-form textarea {
    min-height: 100px;
  }

  .spinner {
    width: 12px;
    height: 12px;
    border: 1.5px solid var(--muted);
    border-top-color: transparent;
    border-radius: 50%;
    display: inline-block;
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* ── Collapsible section headers (providers tab) ── */
  .sec-head {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    width: 100%;
    background: transparent;
    border: 0;
    border-top: 1px solid var(--border);
    color: var(--text-strong);
    font: inherit;
    font-size: var(--fs-md);
    font-weight: 600;
    text-align: left;
    padding: var(--sp-3) 0;
    margin-top: var(--sp-3);
    cursor: pointer;
  }
  .sec-head:first-of-type {
    border-top: 0;
    margin-top: 0;
  }
  .sec-head.sub {
    font-size: var(--fs-sm);
    font-weight: 500;
    color: var(--muted-strong);
    border-top: 0;
    padding: var(--sp-2) 0;
    margin-top: var(--sp-2);
  }
  .sec-chev {
    display: inline-block;
    color: var(--muted);
    transition: transform var(--t-fast);
    flex-shrink: 0;
  }
  .sec-head.open .sec-chev {
    transform: rotate(90deg);
  }
  .sec-title {
    flex: 1;
    min-width: 0;
  }
  .sec-hint {
    color: var(--muted);
    font-size: var(--fs-xs);
    font-weight: 400;
    flex-shrink: 0;
    white-space: pre;
  }
  .mcp-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--sp-3);
    padding: var(--sp-2) var(--sp-3);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    margin-bottom: var(--sp-2);
  }
  .mcp-row.disabled { opacity: 0.55; }
  .mcp-meta { min-width: 0; display: flex; flex-direction: column; }
  .mcp-url {
    font-size: var(--fs-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mcp-actions { display: flex; gap: var(--sp-2); flex-shrink: 0; }
  .mcp-actions .danger { color: var(--danger, #e05555); }
  .mcp-form { display: flex; flex-direction: column; gap: var(--sp-2); margin-top: var(--sp-2); }
  .mcp-form input {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    color: var(--text);
    padding: 8px 10px;
    font: inherit;
  }
  .auto-mem input[type='checkbox'] { width: 18px; height: 18px; }
</style>
