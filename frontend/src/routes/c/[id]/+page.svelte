<script lang="ts">
  import { tick } from 'svelte';
  import { page } from '$app/stores';
  import { afterNavigate, goto } from '$app/navigation';
  import AppHeader from '$lib/components/AppHeader.svelte';
  import ArtifactsPanel from '$lib/components/ArtifactsPanel.svelte';
  import ChatSettings from '$lib/components/ChatSettings.svelte';
  import Composer from '$lib/components/Composer.svelte';
  import Lightbox from '$lib/components/Lightbox.svelte';
  import Message from '$lib/components/Message.svelte';
  import VoiceOverlay from '$lib/components/VoiceOverlay.svelte';
  import {
    attachToRun,
    branchChat,
    getChat,
    getFollowups,
    getLiveRun,
    listArtifacts,
    regenerateLast,
    streamChat,
    truncateFrom,
    type ApproveEdit
  } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import type { Message as Msg, UploadRef, UploadResponse } from '$lib/types';
  import { app } from '$lib/stores.svelte';

  let settingsOpen = $state(false);
  let artifactsOpen = $state(false);
  let artifactsRefreshKey = $state(0);
  let artifactsCount = $state(0);
  let voiceOpen = $state(false);
  let lightboxSrc = $state<string | null>(null);

  async function refreshArtifactCount(id: string) {
    try {
      const arts = await listArtifacts(id);
      artifactsCount = arts.length;
    } catch {
      // non-fatal — header button just stays disabled until next refresh
    }
  }

  // Suggested follow-up questions shown after a reply (cheap title model).
  let followups = $state<string[]>([]);
  async function loadFollowups() {
    if (!chatId) return;
    followups = await getFollowups(chatId);
  }

  // Temporary (incognito) chat: /c/temp — nothing is persisted server-side
  // (streamChat sends chat_id null), history lives only in this component.
  let isTemp = $derived($page.params.id === 'temp');
  let chatId = $derived(isTemp ? null : ($page.params.id ?? null));
  let title = $state<string | null>(null);
  let messages = $state<Msg[]>([]);
  let busy = $state(false);

  // Diff-approval gate: a pending edit awaiting Apply/Reject. Set by onApprove
  // (called from the stream when the backend emits an approve_edit event);
  // cleared when the user decides, which resolves the awaiting turn.
  let pendingApproval = $state<{
    tool: string;
    paths: string[];
    diff: string;
    resolve: (decision: string) => void;
  } | null>(null);

  function onApprove(e: ApproveEdit): Promise<string> {
    return new Promise((resolve) => {
      pendingApproval = {
        tool: e.tool,
        paths: e.paths,
        diff: e.diff,
        resolve: (decision) => {
          pendingApproval = null;
          resolve(decision);
        }
      };
    });
  }

  function decideApproval(decision: 'approve' | 'reject') {
    pendingApproval?.resolve(decision);
  }

  /** Render a unified diff as colored lines (+ green / - red / @@ muted). */
  function diffLineClass(line: string): string {
    if (line.startsWith('+') && !line.startsWith('+++')) return 'add';
    if (line.startsWith('-') && !line.startsWith('---')) return 'del';
    if (line.startsWith('@@')) return 'hunk';
    if (line.startsWith('+++') || line.startsWith('---')) return 'file';
    return '';
  }
  let scrollEl: HTMLElement | undefined = $state();
  let abortController: AbortController | null = null;
  // "Think" toggle (Composer brain button) — sticky until turned off.
  let thinkMode = $state(false);

  // Live "working" indicator — shown when the stream falls quiet mid-reply so
  // a long agentic run (especially code mode) never just looks stuck.
  let streamActivityAt = $state(0); // ms timestamp of the last streamed token
  let busyStartedAt = $state(0);
  let nowTick = $state(0);

  $effect(() => {
    if (!busy) return;
    const t0 = Date.now();
    busyStartedAt = t0;
    streamActivityAt = t0;
    nowTick = t0;
    const iv = setInterval(() => (nowTick = Date.now()), 300);
    return () => clearInterval(iv);
  });

  // Show it only during dead air (>0.7s since the last token) — while text is
  // actively streaming the user already sees progress.
  let showWorking = $derived(busy && nowTick - streamActivityAt > 700);
  // Live client-tool confirmation ("Opening Spotify…") — shown immediately,
  // not just during dead air, so device actions feel instant.
  let toolStatus = $state('');
  let workingSecs = $derived(
    busyStartedAt ? Math.max(0, Math.floor((nowTick - busyStartedAt) / 1000)) : 0
  );
  // Seconds of continuous dead air. Past ~20s this is no longer normal
  // model latency — escalate the indicator copy so the user knows the model
  // (not the app) is being slow. The backend's stall guard swaps to a
  // fallback model at FIRST_TOKEN_TIMEOUT_S (45s default), so this state is
  // usually short-lived.
  let silenceSecs = $derived(
    streamActivityAt ? Math.max(0, Math.floor((nowTick - streamActivityAt) / 1000)) : 0
  );

  $effect(() => {
    if (showWorking) scrollToBottom();
  });

  async function load(id: string) {
    try {
      // Check for an in-progress reply BEFORE fetching the chat, so the
      // ordering can't miss a run that finishes mid-load.
      const liveRunId = await getLiveRun(id);
      const chat = await getChat(id);
      title = chat.title;
      followups = [];
      messages = (chat.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({
          role: m.role,
          content: m.content,
          uploads: m.uploads ?? [],
          dbId: m.id
        }));
      // A reply is still generating — drop any trailing assistant turn the
      // fetch happened to catch; the run re-streams it in full below.
      if (
        liveRunId &&
        messages.length > 0 &&
        messages[messages.length - 1].role === 'assistant'
      ) {
        messages = messages.slice(0, -1);
      }
      await tick();
      scrollToBottom();

      // Arrived from chat search (/c/{id}?m={messageId}) — jump to that message.
      const jumpParam = new URLSearchParams(location.search).get('m');
      if (jumpParam) void jumpToMessage(Number(jumpParam));

      const pendingMsg = sessionStorage.getItem(`pending:${id}`);
      const pendingUploadsRaw = sessionStorage.getItem(`pending-uploads:${id}`);
      let pendingUploads: UploadRef[] = [];
      if (pendingUploadsRaw) {
        try {
          pendingUploads = JSON.parse(pendingUploadsRaw);
        } catch {
          pendingUploads = [];
        }
        sessionStorage.removeItem(`pending-uploads:${id}`);
      }
      if (pendingMsg || pendingUploads.length > 0) {
        sessionStorage.removeItem(`pending:${id}`);
        await send(
          pendingMsg ?? '',
          pendingUploads.map((u) => u.id),
          pendingUploads
        );
      }
      // Open live conversation when arriving from the welcome screen's
      // "Live conversation" button, or handed off from the assist overlay's
      // live button (which appends ?voice=1).
      const wantsVoice =
        !!sessionStorage.getItem(`pending-voice:${id}`) ||
        new URLSearchParams(location.search).get('voice') === '1';
      if (wantsVoice) {
        sessionStorage.removeItem(`pending-voice:${id}`);
        voiceOpen = true;
        // Strip ?voice=1 so a later reload doesn't reopen live mode.
        if (location.search) history.replaceState(null, '', location.pathname);
      }

      // Reconnect to a reply still being generated server-side — e.g. handed
      // off from the assist overlay's swipe-up, or a reload mid-reply.
      if (liveRunId) void attachRound(liveRunId);
    } catch (e) {
      console.error('load chat failed', e);
      const err = (e as Error).message ?? '';
      if (err.startsWith('404')) {
        const { goto } = await import('$app/navigation');
        await goto('/');
      }
    }
  }

  $effect(() => {
    if (isTemp) {
      title = 'Temporary chat';
      messages = [];
      followups = [];
      return;
    }
    if (chatId) {
      load(chatId);
      refreshArtifactCount(chatId);
    }
  });

  // Jump-to-message: arriving from chat search appends ?m={messageId}.
  // Cross-chat jumps are handled at the end of load() once the target chat's
  // messages have rendered; same-chat jumps (query-only navigation) are caught
  // here, where the messages are already present.
  let flashMid = $state<number | null>(null);

  afterNavigate(({ from, to }) => {
    const m = to?.url.searchParams.get('m');
    if (!m) return;
    if (from?.url.pathname === to?.url.pathname) {
      void jumpToMessage(Number(m));
    }
    // else: a different chat — load() jumps once its messages render.
  });

  async function jumpToMessage(mid: number) {
    await tick();
    const el = scrollEl?.querySelector(`[data-mid="${mid}"]`) as HTMLElement | null;
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    flashMid = mid;
    setTimeout(() => {
      if (flashMid === mid) flashMid = null;
    }, 2400);
    // Drop ?m= so a reload or later navigation doesn't re-trigger the jump.
    history.replaceState(null, '', location.pathname);
  }

  function scrollToBottom() {
    if (scrollEl) scrollEl.scrollTop = scrollEl.scrollHeight;
  }

  /**
   * After a stream completes, reload from server so dbIds + auto-title +
   * persisted assistant content are in sync.
   */
  async function refreshFromServer() {
    if (!chatId) return;
    try {
      const chat = await getChat(chatId);
      title = chat.title;
      messages = (chat.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({
          role: m.role,
          content: m.content,
          uploads: m.uploads ?? [],
          dbId: m.id
        }));
    } catch (e) {
      console.warn('refresh failed', e);
    }
  }

  /** Core streaming pass. Assumes the local messages array already contains
   *  the new user message at index N and an empty assistant placeholder at
   *  index N+1. Streams into the placeholder. */
  async function streamRound(uploadIds: string[] | null) {
    const assistantIdx = messages.length - 1;
    busy = true;
    abortController = new AbortController();
    let acc = '';
    try {
      const history = messages.slice(0, -1).map((m) => ({
        role: m.role,
        content: m.content
      }));
      for await (const ev of streamChat(
        history,
        chatId,
        uploadIds && uploadIds.length ? uploadIds : null,
        abortController.signal,
        undefined,
        onApprove,
        { think: thinkMode }
      )) {
        if (ev.type === 'text' && ev.text) {
          acc += ev.text;
          streamActivityAt = Date.now();
          messages[assistantIdx] = { role: 'assistant', content: acc };
          scrollToBottom();
        } else if (ev.type === 'tool_status') {
          toolStatus = ev.text ?? '';
          streamActivityAt = Date.now();
          if (toolStatus) scrollToBottom();
        } else if (ev.type === 'error' && ev.error) {
          acc += `\n\n*[error: ${ev.error}]*`;
          messages[assistantIdx] = { role: 'assistant', content: acc };
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') {
        acc += `\n\n*[stream error: ${(e as Error).message}]*`;
        messages[assistantIdx] = { role: 'assistant', content: acc };
      }
    } finally {
      // A turn that ended/errored while an approval card was open: drop it.
      pendingApproval?.resolve('reject');
      toolStatus = '';
      busy = false;
      abortController = null;
      artifactsRefreshKey++;
      if (chatId) void refreshArtifactCount(chatId);
      setTimeout(() => loadFollowups(), 900);
      app.refreshMe();
      app.refreshNeurons();
      setTimeout(() => app.refreshNeurons(), 4000);
      setTimeout(() => {
        app.refreshChats().then(() => {
          const c = app.chats.find((c) => c.id === chatId);
          if (c) title = c.title;
        });
      }, 1800);
      // Pick up the saved dbId for this assistant message so the user can
      // immediately edit prior turns without a manual reload.
      setTimeout(() => refreshFromServer(), 600);
    }
  }

  /** Stream a reply that's already generating server-side into a fresh
   *  assistant message — used on load when the chat has a live run. */
  async function attachRound(runId: string) {
    followups = [];
    messages = [...messages, { role: 'assistant', content: '' }];
    const assistantIdx = messages.length - 1;
    busy = true;
    abortController = new AbortController();
    let acc = '';
    try {
      for await (const ev of attachToRun(runId, abortController.signal, onApprove)) {
        if (ev.type === 'text' && ev.text) {
          acc += ev.text;
          streamActivityAt = Date.now();
          messages[assistantIdx] = { role: 'assistant', content: acc };
          scrollToBottom();
        } else if (ev.type === 'tool_status') {
          toolStatus = ev.text ?? '';
          streamActivityAt = Date.now();
          if (toolStatus) scrollToBottom();
        } else if (ev.type === 'error' && ev.error) {
          acc += `\n\n*[error: ${ev.error}]*`;
          messages[assistantIdx] = { role: 'assistant', content: acc };
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') {
        acc += `\n\n*[stream error: ${(e as Error).message}]*`;
        messages[assistantIdx] = { role: 'assistant', content: acc };
      }
    } finally {
      pendingApproval?.resolve('reject');
      toolStatus = '';
      busy = false;
      abortController = null;
      artifactsRefreshKey++;
      if (chatId) void refreshArtifactCount(chatId);
      setTimeout(() => loadFollowups(), 900);
      setTimeout(() => refreshFromServer(), 600);
    }
  }

  async function send(text: string, uploadIds: string[], uploadsForRender: UploadRef[] = []) {
    followups = [];
    messages = [...messages, { role: 'user', content: text, uploads: uploadsForRender }];
    messages = [...messages, { role: 'assistant', content: '' }];
    await tick();
    scrollToBottom();
    await streamRound(uploadIds);
  }

  async function onRegenerate() {
    if (busy || !chatId) return;
    followups = [];
    // Reuse the reactive index; snapshot it before the await so a concurrent
    // messages update can't shift the slice point under us.
    const lastIdx = lastAssistantIdx;
    if (lastIdx < 0) return;
    try {
      await regenerateLast(chatId);
    } catch (e) {
      toast.error((e as Error).message);
      return;
    }
    // Drop the local assistant message, re-stream
    messages = messages.slice(0, lastIdx);
    messages = [...messages, { role: 'assistant', content: '' }];
    await tick();
    scrollToBottom();
    await streamRound(null);
  }

  async function onBranch(messageId: number) {
    if (!chatId) return;
    try {
      const newChat = await branchChat(chatId, messageId);
      await app.refreshChats();
      const { goto } = await import('$app/navigation');
      await goto(`/c/${newChat.id}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function onEditMessage(idx: number, newContent: string) {
    if (busy || !chatId) return;
    const target = messages[idx];
    if (target?.role !== 'user' || target.dbId === undefined) {
      toast.error('This message can\'t be edited yet (reload the chat first).');
      return;
    }
    // Truncate from the original user message on the server
    try {
      await truncateFrom(chatId, target.dbId);
    } catch (e) {
      toast.error((e as Error).message);
      return;
    }
    // Local: drop this message + everything after, then send the new content
    messages = messages.slice(0, idx);
    await send(newContent, []);
  }

  function onStop() {
    pendingApproval?.resolve('reject');
    abortController?.abort();
  }

  function onComposerSend(text: string, uploads: UploadResponse[]) {
    const refs: UploadRef[] = uploads.map((u) => ({
      id: u.id,
      kind: u.kind,
      filename: u.filename,
      mime: u.mime,
      size_bytes: u.size_bytes
    }));
    send(text, refs.map((r) => r.id), refs);
  }

  // Pre-compute the index of the last assistant message so we only show
  // Regenerate on that one.
  let lastAssistantIdx = $derived.by(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant') return i;
    }
    return -1;
  });
</script>

<AppHeader
  title={title ?? 'New chat'}
  {chatId}
  hasArtifacts={artifactsCount > 0}
  onsettings={() => (settingsOpen = true)}
  onartifacts={() => (artifactsOpen = true)}
  onvoice={isTemp ? undefined : () => (voiceOpen = true)}
  ontemp={() => goto('/c/temp')}
  {isTemp}
/>

{#if isTemp}
  <div class="temp-banner">
    👻 Temporary chat — nothing here is saved, remembered, or used for auto-memory.
  </div>
{/if}

{#if chatId}
  <VoiceOverlay
    {chatId}
    open={voiceOpen}
    onclose={() => (voiceOpen = false)}
    onturn={() => { if (chatId) refreshFromServer(); }}
  />
{/if}

<ChatSettings {chatId} bind:open={settingsOpen} onclose={() => (settingsOpen = false)} />
<ArtifactsPanel
  {chatId}
  open={artifactsOpen}
  refreshKey={artifactsRefreshKey}
  onclose={() => (artifactsOpen = false)}
/>

<div class="chat" bind:this={scrollEl}>
  {#each messages as msg, i (i)}
    <Message
      role={msg.role}
      content={msg.content}
      uploads={msg.uploads ?? []}
      messageId={msg.dbId}
      flash={msg.dbId !== undefined && msg.dbId === flashMid}
      canRegenerate={!busy && i === lastAssistantIdx && msg.role === 'assistant'}
      canEdit={!busy && msg.role === 'user' && msg.dbId !== undefined}
      canBranch={!busy && msg.role === 'assistant' && msg.dbId !== undefined}
      onregenerate={i === lastAssistantIdx ? onRegenerate : undefined}
      onedit={msg.role === 'user' ? (next) => onEditMessage(i, next) : undefined}
      onbranch={msg.role === 'assistant' ? onBranch : undefined}
      onlightbox={(src) => (lightboxSrc = src)}
      onreply={(t) => send(t, [])}
      askEnabled={!busy && i === lastAssistantIdx && msg.role === 'assistant'}
    />
  {/each}

  {#if toolStatus && !pendingApproval}
    <div class="working" aria-live="polite">
      <span class="dots" aria-hidden="true"><span></span><span></span><span></span></span>
      {toolStatus}
    </div>
  {:else if showWorking && !pendingApproval}
    <div class="working" aria-live="polite">
      <span class="dots" aria-hidden="true"><span></span><span></span><span></span></span>
      {#if silenceSecs > 20}
        Model is being slow — {silenceSecs}s without output ({workingSecs}s total)
      {:else}
        Working… {workingSecs}s
      {/if}
    </div>
  {/if}

  {#if followups.length > 0 && !busy}
    <div class="followups">
      {#each followups as f}
        <button class="followup" type="button" onclick={() => send(f, [])}>{f}</button>
      {/each}
    </div>
  {/if}
</div>

{#if pendingApproval}
  <!-- Pinned ABOVE the composer (outside the scroll area) so it's always fully
       visible on mobile — never buried behind the keyboard/composer. -->
  <div class="approval" aria-live="polite">
    <div class="approval-head">
      <span class="approval-title">{pendingApproval.tool === 'deploy_project' ? '🚀 Approve deploy' : 'Review edit'}</span>
      <span class="approval-path">{pendingApproval.paths.join(', ') || pendingApproval.tool}</span>
    </div>
    <pre class="approval-diff"><code
        >{#each pendingApproval.diff.split('\n') as line}<span
            class={'dl ' + diffLineClass(line)}>{line}
</span>{/each}</code></pre>
    <div class="approval-actions">
      <button class="btn-reject" type="button" onclick={() => decideApproval('reject')}>Reject</button>
      <button class="btn-apply" type="button" onclick={() => decideApproval('approve')}>Apply</button>
    </div>
  </div>
{/if}

<Lightbox src={lightboxSrc} onclose={() => (lightboxSrc = null)} />

<Composer {busy} onsend={onComposerSend} onstop={onStop} bind:think={thinkMode} />

<style>
  .chat {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 16px 18px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }
  /* Diff-approval gate card — pinned above the composer (a flex sibling of the
     scroll area), so it's always fully visible, never buried by the keyboard. */
  .approval {
    flex: none;
    margin: 0 10px 8px;
    border: 1px solid var(--accent);
    border-radius: var(--r-4);
    background: var(--bg-elevated);
    overflow: hidden;
    box-shadow: 0 -3px 14px rgba(0, 0, 0, 0.3);
  }
  .approval-head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--border);
    background: color-mix(in srgb, var(--accent) 8%, var(--bg-elevated));
  }
  .approval-title {
    font-weight: 600;
    font-size: var(--fs-sm);
  }
  .approval-path {
    font-family: var(--font-mono, monospace);
    font-size: var(--fs-xs);
    color: var(--muted-strong);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .approval-diff {
    margin: 0;
    max-height: min(300px, 32vh);
    overflow: auto;
    padding: 8px 0;
    font-family: var(--font-mono, monospace);
    font-size: var(--fs-xs);
    line-height: 1.5;
    background: var(--bg);
  }
  .approval-diff .dl {
    display: block;
    padding: 0 12px;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .approval-diff .dl.add {
    background: color-mix(in srgb, #2ea043 22%, transparent);
  }
  .approval-diff .dl.del {
    background: color-mix(in srgb, #f85149 22%, transparent);
  }
  .approval-diff .dl.hunk {
    color: var(--accent);
  }
  .approval-diff .dl.file {
    color: var(--muted);
  }
  .approval-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 10px 12px;
    border-top: 1px solid var(--border);
  }
  .approval-actions button {
    padding: 7px 18px;
    border-radius: var(--r-3, 8px);
    font-size: var(--fs-sm);
    font-weight: 600;
    cursor: pointer;
    border: 1px solid var(--border);
  }
  .approval-actions .btn-reject {
    background: var(--bg-elevated);
    color: var(--muted-strong);
  }
  .approval-actions .btn-apply {
    background: var(--accent);
    color: var(--accent-fg);
    border-color: var(--accent);
  }

  /* Live "working" indicator — shown during dead air mid-reply. */
  .working {
    display: flex;
    align-items: center;
    gap: 8px;
    align-self: flex-start;
    color: var(--muted);
    font-size: var(--fs-sm);
    padding: 4px 2px;
  }
  .dots {
    display: inline-flex;
    gap: 3px;
  }
  .dots span {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--accent);
    animation: working-pulse 1.2s ease-in-out infinite;
  }
  .dots span:nth-child(2) {
    animation-delay: 0.2s;
  }
  .dots span:nth-child(3) {
    animation-delay: 0.4s;
  }
  @keyframes working-pulse {
    0%,
    80%,
    100% {
      opacity: 0.25;
      transform: scale(0.8);
    }
    40% {
      opacity: 1;
      transform: scale(1);
    }
  }

  /* Suggested follow-up question chips, shown after a reply. */
  .followups {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 2px;
  }
  .followup {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-pill);
    padding: 7px 13px;
    color: var(--text);
    font-size: var(--fs-sm);
    line-height: var(--lh-tight);
    text-align: left;
    cursor: pointer;
    transition: border-color var(--t-fast), background var(--t-fast);
  }
  .followup:hover {
    border-color: var(--accent);
    background: var(--row-hover);
  }
  .temp-banner {
    text-align: center;
    font-size: var(--fs-xs);
    color: var(--muted);
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    padding: 4px var(--sp-3);
  }
</style>
