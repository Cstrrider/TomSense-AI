<script lang="ts">
  // First-run wizard. Auto-triggered from +layout.svelte when the user
  // hasn't set `setup_dismissed=true` in their prefs. Three short steps:
  // welcome → Cloudflare key → Anthropic key → done. Every step has a
  // Skip so users without that provider can move forward, and the final
  // step marks the wizard dismissed regardless of completion state.
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { app } from '$lib/stores.svelte';
  import { getCredentials, setCredentials } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import { IconCheck, IconSparkles, IconGitBranch, IconX } from '$lib/icons';
  import type { Credentials } from '$lib/types';

  type Step = 1 | 2 | 3 | 4;
  let step = $state<Step>(1);

  let creds = $state<Credentials | null>(null);
  let cfDraft = $state('');
  let anthDraft = $state('');
  let savingCf = $state(false);
  let savingAnth = $state(false);
  let dismissing = $state(false);

  onMount(async () => {
    try {
      creds = await getCredentials();
      // Existing-user fast-forward: if either credential is already set
      // (e.g. the user upgraded into this build), skip the paste screens
      // and let them one-click out via the summary. Same code path catches
      // users who landed here intentionally to revisit their setup.
      if (creds.cf_api_token.set || creds.anthropic_api_key.set) {
        step = 4;
      }
    } catch {
      // No-op — wizard still works, user can paste keys; we just don't
      // know what's already set.
    }
  });

  async function saveCf(skip = false) {
    if (!skip && cfDraft.trim()) {
      savingCf = true;
      try {
        creds = await setCredentials({ cf_api_token: cfDraft.trim() });
        cfDraft = '';
        toast.success('Cloudflare key saved');
      } catch (e) {
        toast.error(`Could not save: ${(e as Error).message}`);
        savingCf = false;
        return;
      } finally {
        savingCf = false;
      }
    }
    step = 3;
  }

  async function saveAnth(skip = false) {
    if (!skip && anthDraft.trim()) {
      savingAnth = true;
      try {
        creds = await setCredentials({ anthropic_api_key: anthDraft.trim() });
        anthDraft = '';
        toast.success('Anthropic key saved');
      } catch (e) {
        toast.error(`Could not save: ${(e as Error).message}`);
        savingAnth = false;
        return;
      } finally {
        savingAnth = false;
      }
    }
    step = 4;
  }

  async function finish() {
    dismissing = true;
    try {
      // Mark the wizard dismissed so the layout's redirect stops firing on
      // future visits. Failures here are non-fatal — the user still proceeds
      // to the app; the redirect just retriggers next session.
      await app.savePrefs({ setup_dismissed: true });
    } catch {
      // ignore
    }
    await goto('/');
  }

  function exitSkip() {
    // "Skip for now" — same as finish but doesn't pretend to celebrate.
    finish();
  }
</script>

<div class="wizard">
  <div class="card">
    <header>
      <div class="brand">
        <IconSparkles size={22} />
        <span>TomSense</span>
      </div>
      <button class="skip-link" onclick={exitSkip} disabled={dismissing} aria-label="Skip setup">
        Skip for now <IconX size={12} />
      </button>
    </header>

    <div class="progress" aria-hidden="true">
      <span class:on={step >= 1}></span>
      <span class:on={step >= 2}></span>
      <span class:on={step >= 3}></span>
      <span class:on={step >= 4}></span>
    </div>

    {#if step === 1}
      <h1>Welcome.</h1>
      <p class="lede">
        TomSense is your private chat / coding / research assistant. To get
        started we'll set up the API keys it needs — just two paste-and-go
        screens. You can skip either and configure later in Settings.
      </p>
      <p class="muted small">
        Your keys are encrypted at rest (Fernet) and never leave the server
        after entry. You only ever see masked previews.
      </p>
      <div class="actions">
        <button class="primary" onclick={() => (step = 2)}>
          Let's go <IconCheck size={14} />
        </button>
      </div>
    {/if}

    {#if step === 2}
      <h1>Cloudflare Workers AI</h1>
      <p class="lede">
        Powers the default model lineup: <strong>Qwen3</strong>,
        <strong>GLM-4.7</strong>, <strong>Kimi K2.6</strong>,
        <strong>Llama</strong>, image gen, embeddings. Without this,
        regular chats won't run.
      </p>
      {#if creds?.cf_api_token.set}
        <div class="already-set">
          <IconCheck size={14} />
          <span>Already set as <code class="mono">{creds.cf_api_token.preview}</code></span>
        </div>
      {/if}
      <p class="muted small">
        Get a token at
        <a href="https://dash.cloudflare.com/profile/api-tokens" target="_blank" rel="noopener">
          Cloudflare → API Tokens
        </a>. Use the <em>Workers AI</em> template.
      </p>
      <input
        type="password"
        placeholder={creds?.cf_api_token.set ? 'paste new key to rotate…' : 'paste your CF API token here'}
        bind:value={cfDraft}
        autocomplete="off"
        spellcheck="false"
        onkeydown={(e) => { if (e.key === 'Enter' && cfDraft.trim()) saveCf(); }}
      />
      <div class="actions">
        <button class="ghost" onclick={() => saveCf(true)} disabled={savingCf}>
          Skip
        </button>
        <button class="primary" disabled={!cfDraft.trim() || savingCf} onclick={() => saveCf()}>
          {savingCf ? 'Saving…' : 'Save & continue'} <IconCheck size={14} />
        </button>
      </div>
    {/if}

    {#if step === 3}
      <h1>Anthropic Claude <span class="muted">(optional)</span></h1>
      <p class="lede">
        Required only for the <strong>Claude</strong> entries in Code Mode
        (Haiku, Sonnet, Opus). Reach for these on niche-language tasks
        where the cheaper open-weight models hallucinate.
      </p>
      {#if creds?.anthropic_api_key.set}
        <div class="already-set">
          <IconCheck size={14} />
          <span>Already set as <code class="mono">{creds.anthropic_api_key.preview}</code></span>
        </div>
      {/if}
      <p class="muted small">
        Get a key at
        <a href="https://console.anthropic.com/settings/keys" target="_blank" rel="noopener">
          Anthropic Console → API Keys
        </a>.
      </p>
      <input
        type="password"
        placeholder={creds?.anthropic_api_key.set ? 'paste new key to rotate…' : 'sk-ant-…'}
        bind:value={anthDraft}
        autocomplete="off"
        spellcheck="false"
        onkeydown={(e) => { if (e.key === 'Enter' && anthDraft.trim()) saveAnth(); }}
      />
      <div class="actions">
        <button class="ghost" onclick={() => saveAnth(true)} disabled={savingAnth}>
          Skip
        </button>
        <button class="primary" disabled={!anthDraft.trim() || savingAnth} onclick={() => saveAnth()}>
          {savingAnth ? 'Saving…' : 'Save & continue'} <IconCheck size={14} />
        </button>
      </div>
    {/if}

    {#if step === 4}
      <h1>You're all set.</h1>
      <ul class="summary">
        <li class:on={creds?.cf_api_token.set}>
          {#if creds?.cf_api_token.set}<IconCheck size={14} />{:else}<IconX size={14} />{/if}
          Cloudflare Workers AI {creds?.cf_api_token.set ? '' : '— skipped'}
        </li>
        <li class:on={creds?.anthropic_api_key.set}>
          {#if creds?.anthropic_api_key.set}<IconCheck size={14} />{:else}<IconX size={14} />{/if}
          Anthropic Claude {creds?.anthropic_api_key.set ? '' : '— skipped'}
        </li>
      </ul>
      <p class="muted small">
        Change any of this later via Settings → Providers → Builtin
        credentials, or add custom providers (OpenRouter, Groq, local
        Ollama, etc.) below that. For code-mode work,
        <IconGitBranch size={11} /> Code chat in the sidebar opens an
        agentic loop with sandboxed file/shell tools.
      </p>
      <div class="actions">
        <button class="primary big" onclick={finish} disabled={dismissing}>
          {dismissing ? '…' : 'Open TomSense'} <IconCheck size={14} />
        </button>
      </div>
    {/if}
  </div>
</div>

<style>
  .wizard {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--sp-4);
    background: radial-gradient(ellipse at top, var(--panel) 0%, var(--bg) 60%);
  }
  .card {
    width: 100%;
    max-width: 520px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--r-4);
    padding: var(--sp-5) var(--sp-5) var(--sp-4);
    box-shadow: var(--shadow-md);
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: var(--accent);
    font-weight: 700;
  }
  .brand {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: var(--fs-lg);
  }
  .skip-link {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: var(--fs-xs);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    border-radius: var(--r-pill);
  }
  .skip-link:hover:not(:disabled) {
    color: var(--text);
    background: var(--row-hover);
  }
  .progress {
    display: flex;
    gap: 4px;
  }
  .progress span {
    flex: 1;
    height: 3px;
    border-radius: 2px;
    background: var(--border);
    transition: background var(--t-fast);
  }
  .progress span.on {
    background: var(--accent);
  }
  h1 {
    font-size: var(--fs-2xl);
    margin: var(--sp-1) 0 0;
    color: var(--text-strong);
    letter-spacing: -0.02em;
  }
  h1 .muted {
    color: var(--muted);
    font-weight: 500;
  }
  .lede {
    color: var(--text);
    line-height: 1.5;
    margin: 0;
  }
  .muted {
    color: var(--muted);
  }
  .small {
    font-size: var(--fs-sm);
  }
  .small a {
    color: var(--accent);
    text-decoration: none;
  }
  .small a:hover {
    text-decoration: underline;
  }
  input[type='password'] {
    width: 100%;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 10px 12px;
    color: var(--text);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
    margin-top: var(--sp-1);
  }
  input[type='password']:focus {
    outline: none;
    border-color: var(--accent);
  }
  .already-set {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--panel);
    border: 1px solid var(--accent);
    border-radius: var(--r-pill);
    padding: 4px 10px;
    font-size: var(--fs-xs);
    color: var(--accent);
    align-self: flex-start;
  }
  .mono {
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
  }
  .summary {
    list-style: none;
    margin: var(--sp-1) 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .summary li {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
  }
  .summary li.on {
    color: var(--accent);
  }
  .actions {
    display: flex;
    gap: var(--sp-2);
    justify-content: flex-end;
    margin-top: var(--sp-2);
    flex-wrap: wrap;
  }
  .primary {
    background: var(--accent);
    border: 1px solid var(--accent);
    color: var(--accent-fg);
    padding: 8px 16px;
    border-radius: var(--r-pill);
    cursor: pointer;
    font-size: var(--fs-md);
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: opacity var(--t-fast);
  }
  .primary:hover:not(:disabled) {
    opacity: 0.9;
  }
  .primary:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .primary.big {
    padding: 10px 22px;
    font-size: var(--fs-lg);
  }
  .ghost {
    background: transparent;
    border: 1px solid var(--border);
    color: var(--muted);
    padding: 8px 16px;
    border-radius: var(--r-pill);
    cursor: pointer;
    font-size: var(--fs-md);
  }
  .ghost:hover:not(:disabled) {
    color: var(--text);
    border-color: var(--border-strong);
  }
  .ghost:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>
