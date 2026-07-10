<script lang="ts">
  import {
    IconBrain,
    IconEdit,
    IconFileText,
    IconKey,
    IconLink,
    IconMonitor,
    IconShare,
    IconVolume,
    IconX
  } from '$lib/icons';
  import { S, load, loadInstanceUrl } from './settings/state.svelte';
  import PersonaTab from './settings/PersonaTab.svelte';
  import MemoryTab from './settings/MemoryTab.svelte';
  import ProvidersTab from './settings/ProvidersTab.svelte';
  import CoderTab from './settings/CoderTab.svelte';
  import SecretsTab from './settings/SecretsTab.svelte';
  import VoiceTab from './settings/VoiceTab.svelte';
  import GeneralTab from './settings/GeneralTab.svelte';
  import ShareTab from './settings/ShareTab.svelte';
  import './settings/settings.css';

  interface Props {
    chatId: string | null;
    open: boolean;
    onclose: () => void;
  }

  let { chatId, open = $bindable(), onclose }: Props = $props();

  $effect(() => {
    if (open) {
      S.chatId = chatId;
      void load();
      void loadInstanceUrl(); // no-op outside the Android app
    }
  });
</script>

{#if open}
  <div class="cs">
    <button class="scrim" aria-label="Close settings" onclick={onclose}></button>
    <div class="drawer" role="dialog" aria-label="Chat settings">
      <header>
        <h2>Settings</h2>
        <button class="icon-btn close" onclick={onclose} aria-label="Close">
          <IconX size={18} />
        </button>
      </header>

      <nav class="tabs">
        <button class:active={S.tab === 'providers'} onclick={() => (S.tab = 'providers')}>
          <IconLink size={14} /> Providers
          {#if S.providersList.filter((p) => !p.builtin).length}
            <span class="badge">{S.providersList.filter((p) => !p.builtin).length}</span>
          {/if}
        </button>
        <button class:active={S.tab === 'persona'} onclick={() => (S.tab = 'persona')}>
          <IconEdit size={14} /> Persona
        </button>
        <button class:active={S.tab === 'memory'} onclick={() => (S.tab = 'memory')}>
          <IconBrain size={14} /> Memory
          {#if S.memories.length}<span class="badge">{S.memories.length}</span>{/if}
        </button>
        <button class:active={S.tab === 'coder'} onclick={() => (S.tab = 'coder')}>
          <IconFileText size={14} /> Coder
        </button>
        <button class:active={S.tab === 'secrets'} onclick={() => (S.tab = 'secrets')}>
          <IconKey size={14} /> Secrets
          {#if S.secrets.length}<span class="badge">{S.secrets.length}</span>{/if}
        </button>
        <button class:active={S.tab === 'voice'} onclick={() => (S.tab = 'voice')}>
          <IconVolume size={14} /> Voice
        </button>
        <button class:active={S.tab === 'app'} onclick={() => (S.tab = 'app')}>
          <IconMonitor size={14} /> General
        </button>
        {#if chatId}
          <button class:active={S.tab === 'share'} onclick={() => (S.tab = 'share')}>
            <IconShare size={14} /> Share
          </button>
        {/if}
      </nav>

      <div class="body">
        {#if S.loading}
          <p class="muted">Loading…</p>
        {:else if S.tab === 'persona'}
          <PersonaTab />
        {:else if S.tab === 'memory'}
          <MemoryTab />
        {:else if S.tab === 'providers'}
          <ProvidersTab />
        {:else if S.tab === 'coder'}
          <CoderTab />
        {:else if S.tab === 'secrets'}
          <SecretsTab />
        {:else if S.tab === 'voice'}
          <VoiceTab />
        {:else if S.tab === 'app'}
          <GeneralTab />
        {:else if S.tab === 'share' && chatId}
          <ShareTab />
        {/if}
      </div>
    </div>
  </div>
{/if}
