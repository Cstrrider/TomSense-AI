<script lang="ts">
  import { app } from '$lib/stores.svelte';
  import { IconCheck, IconEdit, IconTrash, IconX } from '$lib/icons';
  import type { ToolKey, ToolModels } from '$lib/types';
  import {
    S,
    TOOL_KEYS,
    TOOL_CHIP_LABEL,
    TOOL_LABELS,
    optionsForTool,
    cfCardModels,
    claudeCardModels,
    customProviders,
    embedCustom,
    embedIsCustom,
    embedSelectValue,
    pinChatModel,
    savePersistentTool,
    startCredEdit,
    cancelCredEdit,
    saveCred,
    startEditCfModels,
    cancelEditCfModels,
    addCfModelRow,
    removeCfModelRow,
    toggleCfModelTool,
    saveCfModels,
    resetCfModelsToDefaults,
    startCreateProvider,
    startEditProvider,
    cancelProviderForm,
    saveProviderForm,
    onDeleteProvider,
    onTestProvider,
    addModelRow,
    removeModelRow,
    toggleModelTool,
    toggleModelCap,
    discoverModels,
    modelIdMatches,
    pickModelId,
    addMcp,
    toggleMcp,
    removeMcp,
    testMcp,
    saveEmbed,
    onEmbedProviderChange,
    runReindexAll
  } from './state.svelte';
</script>

{#snippet credRow(key: string, label: string, hint: string)}
  {@const info = S.credentials?.[key as 'cf_api_token' | 'anthropic_api_key']}
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
    {#if S.credEditing === key}
      <div class="cred-edit">
        <input
          type="password"
          placeholder="paste new key…"
          bind:value={S.credDraft}
          onkeydown={(e) => {
            if (e.key === 'Enter' && S.credDraft.trim()) saveCred(key, S.credDraft.trim());
            else if (e.key === 'Escape') cancelCredEdit();
          }}
          autocomplete="off"
        />
        <button
          class="primary small"
          disabled={!S.credDraft.trim() || S.credSaving === key}
          onclick={() => saveCred(key, S.credDraft.trim())}
        >
          {S.credSaving === key ? 'Saving…' : 'Save'}
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
            disabled={S.credSaving === key}
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

<!-- Collapsible section header. `hint` renders right-aligned and muted
     (counts, status summaries). -->
{#snippet secHead(title: string, open: boolean, toggle: () => void, hint?: string)}
  <button type="button" class="sec-head" class:open aria-expanded={open} onclick={toggle}>
    <span class="sec-chev" aria-hidden="true">›</span>
    <span class="sec-title">{title}</span>
    {#if hint}<span class="sec-hint">{hint}</span>{/if}
  </button>
{/snippet}

{#snippet toolRow(t: { key: ToolKey; label: string; hint: string })}
  {@const isChat = t.key === 'chat'}
  {@const options = optionsForTool(t.key)}
  {@const defaultId = options[0]?.id ?? ''}
  {@const fbKey = `${t.key}_fallback`}
  <div class="tool-row">
    <div class="tool-meta">
      <div class="tool-label">{t.label}</div>
      <div class="tool-hint">{t.hint}</div>
    </div>
    <!-- Profile default — one uniform control for every slot. The old Chat
         dropdown silently ALSO pinned the open chat; pinning is now the
         explicit row below. -->
    <select
      class="model-select compact"
      value={S.toolModels[t.key] ?? defaultId}
      onchange={(e) => {
        const v = (e.currentTarget as HTMLSelectElement).value;
        S.toolModels = { ...S.toolModels, [t.key]: v };
        void savePersistentTool(t.key, v);
      }}
      disabled={S.savingToolKey === t.key}
    >
      {#each options as m}
        <option value={m.id}>{m.label}{m.note ? ` — ${m.note}` : ''}</option>
      {/each}
    </select>
    {#if isChat && S.chatId}
      <div class="tool-fallback">
        <span class="tool-fallback-label">📌 this chat:</span>
        <select
          class="model-select compact fallback-select"
          value={S.chatModel || defaultId}
          onchange={(e) => {
            const v = (e.currentTarget as HTMLSelectElement).value;
            S.chatModel = v;
            void pinChatModel(v);
          }}
          disabled={S.savingChatModel}
        >
          {#each options as m}
            <option value={m.id}>{m.label}{m.note ? ` — ${m.note}` : ''}</option>
          {/each}
        </select>
      </div>
    {/if}
    <div class="tool-fallback">
      <span class="tool-fallback-label">⚡ if slow / over limit:</span>
      <select
        class="model-select compact fallback-select"
        value={S.toolModels[fbKey as keyof ToolModels] ?? ''}
        onchange={(e) => {
          const v = (e.currentTarget as HTMLSelectElement).value;
          S.toolModels = { ...S.toolModels, [fbKey]: v || null };
          void savePersistentTool(fbKey, v);
        }}
        disabled={S.savingToolKey === fbKey}
      >
        <option value="">(none)</option>
        {#each options as m}
          <option value={m.id}>{m.label}{m.note ? ` — ${m.note}` : ''}</option>
        {/each}
      </select>
    </div>
  </div>
{/snippet}

{@render secHead('Models', S.secModels, () => (S.secModels = !S.secModels))}
{#if S.secModels}
  <p class="muted">
    Pick a default model for each tool — saved to your profile and used on
    every chat. Inside a chat, the Chat row gains a 📌 row that pins a model
    to that conversation only.
  </p>
  <div class="tool-models">
    {#each TOOL_LABELS().filter((t) => !t.key.endsWith('_hd')) as t}
      {@render toolRow(t)}
    {/each}
  </div>
  <button
    type="button"
    class="sec-head sub"
    class:open={S.secHd}
    aria-expanded={S.secHd}
    onclick={() => (S.secHd = !S.secHd)}
  >
    <span class="sec-chev" aria-hidden="true">›</span>
    <span class="sec-title">HD variants</span>
    <span class="sec-hint">/HD · "best quality" auto-routes here</span>
  </button>
  {#if S.secHd}
    <p class="muted small">
      Image / image edit each get an <strong>HD</strong> row. Type
      <code>/HD</code> (or ask for HD / 4K / "best quality") and the
      model auto-routes to it.
    </p>
    <div class="tool-models">
      {#each TOOL_LABELS().filter((t) => t.key.endsWith('_hd')) as t}
        {@render toolRow(t)}
      {/each}
    </div>
  {/if}

  <button type="button" class="sec-head sub" class:open={S.secEmbed}
    aria-expanded={S.secEmbed} onclick={() => (S.secEmbed = !S.secEmbed)}>
    <span class="sec-chev" aria-hidden="true">›</span>
    <span class="sec-title">Embedding (document search)</span>
    <span class="sec-hint">
      {embedIsCustom() ? `${embedCustom().pid} · dim ${S.prefs.embed_dim ?? '?'}` : 'Cloudflare bge'}
    </span>
  </button>
  {#if S.secEmbed}
    <p class="muted small">
      The model that turns uploaded documents into vectors. Defaults to
      Cloudflare's bge; point it at any OpenAI-compatible
      <code>/embeddings</code> provider.
    </p>
    <label class="field">
      <span class="field-label">Provider</span>
      <select class="model-select" value={embedSelectValue()}
        onchange={(e) => onEmbedProviderChange((e.currentTarget as HTMLSelectElement).value)}
        disabled={S.ragBusy}>
        <option value="">Cloudflare — {app.info?.embed_default?.model ?? 'bge'} (default)</option>
        {#each customProviders() as p (p.id)}
          <option value={p.id}>{p.name} — custom</option>
        {/each}
      </select>
    </label>
    {#if embedIsCustom()}
      <label class="field">
        <span class="field-label">Model</span>
        <input type="text" placeholder="e.g. nomic-embed-text / text-embedding-3-small"
          value={embedCustom().model}
          onchange={(e) => saveEmbed({ embed_model: `${embedCustom().pid}::${(e.currentTarget as HTMLInputElement).value.trim()}` })}
          disabled={S.ragBusy} />
      </label>
      <label class="field">
        <span class="field-label">Dimensions</span>
        <input type="number" min="8" max="8192" placeholder="e.g. 768 / 1024 / 1536"
          value={S.prefs.embed_dim ?? ''}
          onchange={(e) => {
            const n = parseInt((e.currentTarget as HTMLInputElement).value, 10);
            void saveEmbed({ embed_dim: Number.isFinite(n) ? n : null });
          }}
          disabled={S.ragBusy} />
      </label>
      <p class="muted small">Dimensions must match the model's output width exactly.</p>
    {/if}
    {#if S.ragStatus}
      <div class="embed-status" class:warn={S.ragStatus.stale}>
        {#if S.ragStatus.indexed_chunks === 0}
          No documents indexed yet.
        {:else if S.ragStatus.stale}
          ⚠ {S.ragStatus.indexed_chunks} chunk{S.ragStatus.indexed_chunks === 1 ? '' : 's'}
          embedded at dim {S.ragStatus.collection_dim}, but the model now
          outputs {S.ragStatus.configured_dim}. Search is degraded until re-embed.
          <button class="primary" onclick={runReindexAll} disabled={S.ragBusy}>
            {S.ragBusy ? 'Re-embedding…' : 'Re-embed all'}
          </button>
        {:else}
          {S.ragStatus.indexed_chunks} chunk{S.ragStatus.indexed_chunks === 1 ? '' : 's'} indexed
          at dim {S.ragStatus.collection_dim ?? S.ragStatus.configured_dim}.
          <button class="btn-text" onclick={runReindexAll} disabled={S.ragBusy}>
            {S.ragBusy ? 'Re-embedding…' : 'Re-embed all'}
          </button>
        {/if}
      </div>
    {/if}
  {/if}
{/if}

{@render secHead(
  'Providers',
  S.secProviders,
  () => (S.secProviders = !S.secProviders),
  S.credentials
    ? [S.credentials.cf_api_token?.set ? 'CF ✓' : 'CF —',
       S.credentials.anthropic_api_key?.set ? 'Claude ✓' : 'Claude —'].join(' · ')
    : undefined
)}
{#if S.secProviders}
<p class="muted">
  Builtin provider keys can be set / rotated / cleared here without
  touching the server's <code>.env</code> (a cleared key falls back to
  the env default). Or connect any OpenAI-compatible API (OpenAI,
  OpenRouter, Groq, Ollama) — models you register appear in the
  per-tool pickers above. Image generation still routes through
  Cloudflare for now.
</p>

{#each S.providersList.filter((p) => p.builtin && p.id === 'cf') as p (p.id)}
  <div class="provider-card builtin">
    <div class="provider-head">
      <div class="provider-name">{p.name} <span class="tag">builtin</span></div>
      <div class="provider-sub">{p.base_url}</div>
      <div class="provider-sub">
        {S.cfModels.length > 0
          ? `${S.cfModels.length} custom model(s) — overrides defaults`
          : `${cfCardModels().length} bundled models`}
      </div>
      {#if cfCardModels().length}
        <div class="provider-models">
          {#each cfCardModels() as m (m.id)}
            <span class="model-chip" title={m.id}>{m.label}
              <span class="model-tools">{m.tags.join(' · ')}</span>
            </span>
          {/each}
        </div>
      {/if}
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

{#if S.cfFormOpen}
  <div class="provider-form">
    <div class="field-label">Cloudflare model list</div>
    <p class="muted small">
      One row per model. Tick which tools each model should appear
      in. Removing a row hides that model from all dropdowns. Reset
      to defaults to throw away your customizations.
    </p>
    <div class="model-rows">
      {#each S.cfFormDraft as m, idx (idx)}
        <div class="model-row">
          <input
            class="model-id"
            placeholder="@cf/black-forest-labs/flux-2-klein-4b"
            bind:value={S.cfFormDraft[idx].id}
          />
          <input
            class="model-label"
            placeholder="Label"
            bind:value={S.cfFormDraft[idx].label}
          />
          <input
            class="model-note"
            placeholder="note (optional)"
            bind:value={S.cfFormDraft[idx].note}
          />
          <input
            class="model-steps"
            type="number"
            min="1"
            max="50"
            placeholder="steps"
            title="Optional step count (1-50). Blank or 0 = use the model default. Currently applied by Flux models; other model types ignore it unless their endpoint supports a steps parameter."
            bind:value={S.cfFormDraft[idx].steps}
          />
          <div class="model-tools-pick">
            {#each TOOL_KEYS() as t}
              <button
                type="button"
                class="tool-chip"
                class:on={(S.cfFormDraft[idx].tools || []).includes(t)}
                onclick={() => toggleCfModelTool(idx, t)}
                title={`Use this model for ${t}`}
              >{TOOL_CHIP_LABEL()[t]}</button>
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
      <button class="btn-text" onclick={resetCfModelsToDefaults} disabled={S.cfFormSaving}>
        Reset to defaults
      </button>
      <div style="display: flex; gap: var(--sp-2);">
        <button class="btn-text" onclick={cancelEditCfModels} disabled={S.cfFormSaving}>Cancel</button>
        <button class="primary" onclick={saveCfModels} disabled={S.cfFormSaving}>
          {S.cfFormSaving ? 'Saving…' : 'Save'}
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
    <div class="provider-sub">Native Messages API — prompt-cached.</div>
    {#if claudeCardModels().length}
      <div class="provider-models">
        {#each claudeCardModels() as m (m.id)}
          <span class="model-chip" title={`anthropic::${m.id}`}>{m.label}
            <span class="model-tools">{m.tags.join(' · ')}</span>
          </span>
        {/each}
      </div>
    {/if}
    {@render credRow('anthropic_api_key', 'API key', 'Used for the Claude models above (chat, code, vision, research).')}
  </div>
</div>

{#each S.providersList.filter((p) => !p.builtin) as p (p.id)}
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
      {#if S.providerTestResult[p.id]}
        <div class="provider-test-result">{S.providerTestResult[p.id]}</div>
      {/if}
    </div>
    <div class="provider-actions">
      <button
        class="icon-btn"
        title="Test connection"
        aria-label="Test"
        disabled={S.providerTesting === p.id}
        onclick={() => onTestProvider(p)}
      >
        {#if S.providerTesting === p.id}<span class="spinner" aria-hidden="true"></span>
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

{#if !S.providerFormOpen}
  <button class="btn-tonal" onclick={startCreateProvider}>+ Add provider</button>
{:else}
  <div class="provider-form">
    <label class="field">
      <span class="field-label">Display name</span>
      <input bind:value={S.providerForm.name} placeholder="e.g. OpenAI" />
    </label>
    <label class="field">
      <span class="field-label">Base URL</span>
      <input bind:value={S.providerForm.base_url} placeholder="https://api.openai.com/v1" />
    </label>
    <label class="field">
      <span class="field-label">
        API key{#if S.editingProviderId} <span class="muted-strong">(leave blank to keep existing)</span>{/if}
      </span>
      <input type="password" bind:value={S.providerForm.api_key} placeholder="sk-…" autocomplete="off" />
    </label>

    <div class="field">
      <span class="field-label">Models</span>
      <p class="muted small">
        Add the specific model IDs you want surfaced in the per-tool pickers.
        Tick the tools each model is appropriate for.
      </p>
      <div class="model-rows">
        {#each S.providerForm.models as m, idx (idx)}
          <div class="model-row">
            <div class="model-id-wrap">
              <input
                class="model-id"
                placeholder="gpt-4o"
                autocomplete="off"
                bind:value={S.providerForm.models[idx].id}
                onfocus={() => (S.modelIdOpen = S.discoveredModels.length ? idx : null)}
                oninput={() => (S.modelIdOpen = S.discoveredModels.length ? idx : null)}
                onblur={() => setTimeout(() => { if (S.modelIdOpen === idx) S.modelIdOpen = null; }, 150)}
              />
              {#if S.modelIdOpen === idx && modelIdMatches(idx).length}
                <div class="model-id-menu">
                  {#each modelIdMatches(idx) as dm}
                    <button type="button" class="model-id-opt"
                      onmousedown={(e) => { e.preventDefault(); pickModelId(idx, dm); }}
                    >{dm}</button>
                  {/each}
                </div>
              {/if}
            </div>
            <input
              class="model-label"
              placeholder="Label (e.g. GPT-4o)"
              bind:value={S.providerForm.models[idx].label}
            />
            <input
              class="model-note"
              placeholder="note (optional)"
              bind:value={S.providerForm.models[idx].note}
            />
            <div class="model-tools-pick">
              {#each TOOL_KEYS() as t}
                <button
                  type="button"
                  class="tool-chip"
                  class:cap={t === 'vision'}
                  class:on={(S.providerForm.models[idx].tools || []).includes(t)}
                  onclick={() => toggleModelTool(idx, t)}
                  title={t === 'vision'
                    ? 'Vision — the model can see images; also lists it in the Vision slot'
                    : `Use this model for ${t}`}
                >{t === 'vision' ? '👁 vision' : TOOL_CHIP_LABEL()[t]}</button>
              {/each}
              <span class="cap-sep">can:</span>
              <button
                type="button"
                class="tool-chip cap"
                class:on={!!S.providerForm.models[idx].reasoning}
                onclick={() => toggleModelCap(idx, 'reasoning')}
                title="This model emits a hidden reasoning channel (its effort gets capped)"
              >💭 reasons</button>
            </div>
            <div class="model-tools-legend">
              <span>Chips = which pickers list the model.</span>
              <span>👁 vision also flags image capability; 💭 reasons a hidden reasoning channel.</span>
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
      <div class="model-add-row">
        <button class="btn-text" onclick={addModelRow}>+ Add model</button>
        <button class="btn-text" onclick={discoverModels}
          disabled={S.discovering || !S.providerForm.base_url.trim()}
          title="Fetch the model list from this provider's /models endpoint">
          {S.discovering ? 'Discovering…' : '⟳ Discover models'}
        </button>
        {#if S.discoveredModels.length}
          <span class="muted small">{S.discoveredModels.length} available — pick from the ID field</span>
        {/if}
      </div>
    </div>

    <div class="actions">
      <button class="btn-text" onclick={cancelProviderForm} disabled={S.providerSaving}>Cancel</button>
      <button class="primary" onclick={saveProviderForm} disabled={S.providerSaving}>
        {S.providerSaving ? 'Saving…' : S.editingProviderId ? 'Save' : 'Add provider'}
      </button>
    </div>
  </div>
{/if}
{/if}

<!-- ── MCP servers ─────────────────────────────────────────── -->
{@render secHead(
  'MCP servers',
  S.secMcp,
  () => (S.secMcp = !S.secMcp),
  `${S.mcpServers.length || 'none'}`
)}
{#if S.secMcp}
<p class="muted">
  Remote Model Context Protocol servers — their tools are offered to
  the chat model automatically (as <code>mcp__…</code> tools).
</p>
{#each S.mcpServers as s (s.id)}
  <div class="mcp-row" class:disabled={!s.enabled}>
    <div class="mcp-meta">
      <strong>{s.name}</strong>
      <span class="muted mcp-url">{s.url}</span>
    </div>
    <div class="mcp-actions">
      <button class="btn-text" disabled={S.mcpTesting === s.id} onclick={() => testMcp(s)}>
        {S.mcpTesting === s.id ? 'Testing…' : 'Test'}
      </button>
      <button class="btn-text" onclick={() => toggleMcp(s)}>
        {s.enabled ? 'Disable' : 'Enable'}
      </button>
      <button class="btn-text danger" onclick={() => removeMcp(s)}>Remove</button>
    </div>
  </div>
{/each}
<div class="mcp-form">
  <input placeholder="Name (e.g. workers-ai)" bind:value={S.mcpForm.name} maxlength="60" />
  <input placeholder="URL (https://…/mcp)" bind:value={S.mcpForm.url} />
  <input placeholder="Authorization header (optional, e.g. Bearer …)" bind:value={S.mcpForm.auth_header} />
  <button class="primary" onclick={addMcp} disabled={S.mcpSaving}>
    {S.mcpSaving ? 'Adding…' : 'Add MCP server'}
  </button>
</div>
{/if}
