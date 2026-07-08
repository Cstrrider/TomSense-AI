<script lang="ts">
  import { afterNavigate, goto } from '$app/navigation';
  import { onMount } from 'svelte';
  import AppHeader from '$lib/components/AppHeader.svelte';
  import ChatSettings from '$lib/components/ChatSettings.svelte';
  import Composer from '$lib/components/Composer.svelte';
  import { createChat, setSystemPrompt } from '$lib/api';
  import { consumeSharedContent } from '$lib/clienttools';
  import { app } from '$lib/stores.svelte';
  import { toast } from '$lib/toast.svelte';
  import { IconSparkles, IconImage, IconFileText, IconBrain, IconGitBranch } from '$lib/icons';
  import { getCodeModeModels } from '$lib/codeModels';
  import { sameModelId } from '$lib/modelOptions';
  import type { UploadResponse } from '$lib/types';

  let busy = $state(false);
  let settingsOpen = $state(false);
  let composer: { prefill: (text: string, files: File[]) => void } | undefined = $state();
  // Code mode: ?code=1 (from the sidebar's "Code chat" button) creates the
  // next chat as a coding-agent chat.
  let codeMode = $state(false);
  // Which model code chats will use — the Settings → Models → Code Mode row
  // (prefs.tool_models.code_mode) is the single source of truth; chats are
  // created UNPINNED so they keep following that setting. Shown here as a
  // hint only (the start-page picker was removed — models are managed in
  // Settings like every other tool model).
  let codeModelLabel = $derived.by(() => {
    const id = app.prefs.tool_models?.code_mode;
    if (!id) return getCodeModeModels(app.info)[0]?.label ?? 'server default';
    const cataloged =
      getCodeModeModels(app.info).find((m) => sameModelId(m.id, id))
      ?? (app.prefs.cf_models ?? []).find((m) => sameModelId(`cf::${m.id}`, id));
    return cataloged?.label ?? id.split('::').pop() ?? id;
  });

  // codeMode tracks ?code=1 on *every* navigation, not just the first mount —
  // so the sidebar's "Code chat" button switches this page into code mode even
  // when the new-chat page is already open. (onMount fires once and missed a
  // same-route /?code=1 navigation, so the button looked dead.)
  afterNavigate(() => {
    codeMode = new URLSearchParams(location.search).get('code') === '1';
  });

  // "Share to TomSense": MainActivity stashes the shared payload and opens
  // /?share=1 — drain it here and pre-fill the composer so the user can add a
  // question and send.
  onMount(async () => {
    // Make sure prefs are loaded so the picker reflects the user's saved
    // code-mode model (instead of always showing the default).
    if (!app.prefs.tool_models) await app.refreshPrefs();
    const params = new URLSearchParams(location.search);
    if (params.get('share') !== '1') return;
    history.replaceState(null, '', location.pathname); // one-shot
    const shared = await consumeSharedContent();
    if (!shared) return;
    if (shared.type === 'text') {
      composer?.prefill(shared.text, []);
    } else if (shared.type === 'image') {
      try {
        const blob = await (
          await fetch(`data:${shared.mimeType};base64,${shared.imageBase64}`)
        ).blob();
        const ext = (shared.mimeType.split('/')[1] || 'jpg').replace('jpeg', 'jpg');
        const file = new File([blob], `shared-${Date.now()}.${ext}`, {
          type: shared.mimeType
        });
        composer?.prefill('', [file]);
      } catch {
        /* ignore a malformed shared image */
      }
    }
  });

  const suggestions = [
    { icon: 'sparkles', text: 'Explain what zero-shot prompting is' },
    { icon: 'image',    text: 'Draw a cyberpunk skyline at golden hour' },
    { icon: 'file',     text: 'Search my docs: what does the manual say about regenerative braking?' },
    { icon: 'brain',    text: 'Remember that I prefer concise answers' },
  ];

  async function send(text: string, uploads: UploadResponse[] = []) {
    busy = true;
    try {
      const chat = await createChat(codeMode);
      await app.refreshChats();
      const pendingPersona = sessionStorage.getItem('pending-persona');
      if (pendingPersona) {
        try {
          await setSystemPrompt(chat.id, pendingPersona);
        } catch (e) {
          toast.error(`Could not apply custom instructions: ${(e as Error).message}`);
        }
        sessionStorage.removeItem('pending-persona');
      }
      sessionStorage.setItem(`pending:${chat.id}`, text);
      if (uploads.length > 0) {
        sessionStorage.setItem(`pending-uploads:${chat.id}`, JSON.stringify(uploads));
      }
      await goto(`/c/${chat.id}`);
    } catch (e) {
      toast.error((e as Error).message);
      busy = false;
    }
  }

  function pickSuggestion(text: string) {
    send(text);
  }

  // Live conversation from the welcome screen: there's no chat yet, so create
  // one, flag it, and let the chat view auto-open voice mode on arrival.
  async function startVoice() {
    if (busy) return;
    busy = true;
    try {
      const chat = await createChat();
      await app.refreshChats();
      sessionStorage.setItem(`pending-voice:${chat.id}`, '1');
      await goto(`/c/${chat.id}`);
    } catch (e) {
      toast.error((e as Error).message);
      busy = false;
    }
  }
</script>

<AppHeader
  title={codeMode ? 'New code chat' : 'TomSense'}
  chatId={null}
  onsettings={() => (settingsOpen = true)}
  onvoice={startVoice}
  ontemp={() => goto('/c/temp')}
/>

<ChatSettings
  chatId={null}
  bind:open={settingsOpen}
  onclose={() => (settingsOpen = false)}
/>

<div class="welcome">
  <div class="hero">
    <div class="mark" class:code={codeMode}>
      {#if codeMode}<IconGitBranch size={26} />{:else}<IconSparkles size={28} />{/if}
    </div>
    {#if codeMode}
      <h2>Code mode</h2>
      <p class="muted">
        An autonomous coding agent in a sandboxed workspace — it reads, edits,
        and runs files. Describe a task to begin.
      </p>
      <p class="model-hint">
        Model: {codeModelLabel} — change it in ⚙ Settings → Models → Code Mode.
      </p>
    {:else}
      <h2>How can I help today?</h2>
      <p class="muted">
        Tap ⚙ to set custom instructions, or pick a starter below.
      </p>
      <div class="suggestions">
        {#each suggestions as s}
          <button class="suggestion" onclick={() => pickSuggestion(s.text)} disabled={busy}>
            <span class="suggestion-icon" aria-hidden="true">
              {#if s.icon === 'image'}<IconImage size={16} />
              {:else if s.icon === 'file'}<IconFileText size={16} />
              {:else if s.icon === 'brain'}<IconBrain size={16} />
              {:else}<IconSparkles size={16} />{/if}
            </span>
            <span class="suggestion-text">{s.text}</span>
          </button>
        {/each}
      </div>
    {/if}
  </div>
</div>

<Composer {busy} onsend={send} bind:this={composer} />

<style>
  .welcome {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--sp-5);
    overflow-y: auto;
  }
  .hero {
    text-align: center;
    max-width: 560px;
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-3);
  }
  .mark {
    width: 56px;
    height: 56px;
    border-radius: var(--r-pill);
    background: linear-gradient(135deg, var(--accent), #d96030);
    color: var(--accent-fg);
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: var(--shadow-md);
  }
  .mark.code {
    background: linear-gradient(135deg, #4a8fe7, #6b5fd6);
  }
  h2 {
    margin: 0;
    font-size: var(--fs-2xl);
    font-weight: 700;
    letter-spacing: -0.02em;
    color: var(--text-strong);
  }
  .muted {
    color: var(--muted);
    font-size: var(--fs-md);
    margin: 0 0 var(--sp-2);
  }
  .suggestions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--sp-2);
    width: 100%;
    margin-top: var(--sp-2);
  }
  .suggestion {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-4);
    padding: 12px 14px;
    text-align: left;
    color: var(--text);
    cursor: pointer;
    display: flex;
    align-items: flex-start;
    gap: var(--sp-2);
    transition: border-color var(--t-fast), background var(--t-fast);
    line-height: var(--lh-tight);
  }
  .suggestion:hover:not(:disabled) {
    border-color: var(--border-strong);
    background: var(--row-hover);
  }
  .suggestion:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .suggestion-icon {
    color: var(--accent);
    flex-shrink: 0;
    margin-top: 1px;
  }
  .suggestion-text {
    font-size: var(--fs-sm);
    color: var(--text);
  }
  @media (max-width: 480px) {
    .suggestions {
      grid-template-columns: 1fr;
    }
  }
  .model-hint {
    color: var(--muted);
    font-size: var(--fs-sm);
    margin: 0;
    text-align: center;
  }
</style>
