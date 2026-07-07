<script lang="ts">
  import { page } from '$app/stores';
  import { getSharedChat } from '$lib/api';
  import Message from '$lib/components/Message.svelte';
  import type { ChatWithMessages, Message as Msg } from '$lib/types';

  let token = $derived($page.params.token);
  let chat = $state<ChatWithMessages | null>(null);
  let error = $state<string | null>(null);

  async function load(t: string) {
    error = null;
    chat = null;
    try {
      chat = await getSharedChat(t);
    } catch (e) {
      const msg = (e as Error).message;
      error = msg.startsWith('404') ? 'This share link is no longer valid.' : msg;
    }
  }

  $effect(() => {
    if (token) load(token);
  });

  let messages = $derived<Msg[]>(
    (chat?.messages ?? [])
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map((m) => ({ role: m.role, content: m.content, uploads: m.uploads ?? [] }))
  );
</script>

<svelte:head>
  {#if chat?.title}<title>{chat.title} · TomSense</title>{:else}<title>Shared chat · TomSense</title>{/if}
</svelte:head>

<div class="shell">
  <header>
    <h1>{chat?.title ?? 'Shared chat'}</h1>
    <a class="brand" href="/">TomSense</a>
  </header>

  <main class="chat">
    {#if error}
      <div class="error">{error}</div>
    {:else if !chat}
      <div class="muted">Loading…</div>
    {:else if messages.length === 0}
      <div class="muted">(empty chat)</div>
    {:else}
      {#each messages as msg, i (i)}
        <Message role={msg.role} content={msg.content} uploads={msg.uploads ?? []} />
      {/each}
    {/if}
  </main>

  <footer>
    <span class="muted">Read-only — view of a TomSense chat</span>
  </footer>
</div>

<style>
  .shell {
    display: flex;
    flex-direction: column;
    height: 100%;
    width: 100%;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 14px 18px;
    border-bottom: 1px solid var(--border);
  }
  header h1 {
    margin: 0;
    font-size: 16px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .brand {
    color: var(--accent);
    font-weight: 600;
    text-decoration: none;
    font-size: 14px;
  }
  .chat {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 16px 18px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 0;
  }
  .muted {
    color: var(--muted);
    text-align: center;
    padding: 32px;
  }
  .error {
    color: var(--danger);
    text-align: center;
    padding: 32px;
  }
  footer {
    padding: 8px 18px;
    border-top: 1px solid var(--border);
    text-align: center;
    background: var(--panel);
  }
</style>
