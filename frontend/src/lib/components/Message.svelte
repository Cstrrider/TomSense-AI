<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { renderMarkdown } from '$lib/markdown';
  import { fetchTTS } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import { saveImageToDevice } from '$lib/clienttools';
  import {
    IconVolume,
    IconPause,
    IconCopy,
    IconCheck,
    IconFile,
    IconFileText,
    IconSparkles,
    IconRefresh,
    IconEdit,
    IconGitBranch,
    IconX
  } from '$lib/icons';
  import type { Role, UploadRef } from '$lib/types';

  interface Props {
    role: Role;
    content: string;
    uploads?: UploadRef[];
    messageId?: number;
    /** Briefly highlight this message — set when jumped to from chat search. */
    flash?: boolean;
    canRegenerate?: boolean;
    canEdit?: boolean;
    canBranch?: boolean;
    onregenerate?: () => void;
    onedit?: (newContent: string) => Promise<void> | void;
    onbranch?: (messageId: number) => void;
    onlightbox?: (url: string) => void;
    /** Send the user's answer to an ask_user question (a tapped option). */
    onreply?: (text: string) => void;
    /** Whether this message's ask_user card is still live (latest + not busy). */
    askEnabled?: boolean;
  }

  let {
    role,
    content,
    uploads = [],
    messageId,
    flash = false,
    canRegenerate = false,
    canEdit = false,
    canBranch = false,
    onregenerate,
    onedit,
    onbranch,
    onlightbox,
    onreply,
    askEnabled = false
  }: Props = $props();

  let editing = $state(false);
  let editValue = $state('');
  let editing_busy = $state(false);

  // Tap-to-reveal: on touch/mobile, actions are hidden until the bubble is
  // tapped. Desktop still shows them on :hover via CSS — this flag is an
  // additive override, not a replacement.
  let actionsOpen = $state(false);

  let html = $derived(renderMarkdown(content));
  let bodyEl: HTMLDivElement | undefined = $state();

  /** Toggle actions on bubble click. Suppress if the user is actually
   *  selecting text — checking the live selection length is the cheapest
   *  reliable signal that this was a drag, not a tap. */
  function onBubbleClick() {
    const sel = typeof window !== 'undefined' ? window.getSelection() : null;
    if (sel && sel.toString().length > 0) return;
    actionsOpen = !actionsOpen;
  }

  function startEdit() {
    editValue = content;
    editing = true;
  }
  function cancelEdit() {
    editing = false;
    editValue = '';
  }
  async function saveEdit() {
    const next = editValue.trim();
    if (!next || !onedit) return;
    editing_busy = true;
    try {
      await onedit(next);
      editing = false;
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      editing_busy = false;
    }
  }

  async function copyText() {
    try {
      await navigator.clipboard.writeText(content);
      toast.success('Copied');
    } catch {
      toast.error('Could not copy to clipboard');
    }
  }

  // Wire copy buttons into rendered code blocks after each render. Also
  // (re)build ask_user cards — askEnabled is referenced so a busy→idle change
  // refreshes the buttons' enabled state.
  $effect(() => {
    void html;
    void askEnabled;
    void tick().then(() => {
      decorateCodeBlocks();
      decorateAskUser();
    });
  });

  /** Turn ```ask-user payload blocks into an interactive question card. */
  function decorateAskUser() {
    if (!bodyEl) return;
    bodyEl.querySelectorAll('pre.ask-user-raw').forEach((pre) => {
      const code = pre.querySelector('code');
      let data: { question?: string; options?: string[] } = {};
      try {
        data = JSON.parse(code?.textContent || '{}');
      } catch {
        return; // payload still streaming / malformed — leave raw, retry next render
      }
      if (!data.question) return;
      const card = document.createElement('div');
      card.className = 'ask-card';
      const q = document.createElement('div');
      q.className = 'ask-q';
      q.textContent = data.question;
      card.appendChild(q);
      const opts = Array.isArray(data.options) ? data.options.filter(Boolean) : [];
      if (opts.length) {
        const row = document.createElement('div');
        row.className = 'ask-opts';
        opts.forEach((o) => {
          const b = document.createElement('button');
          b.type = 'button';
          b.className = 'ask-opt';
          b.textContent = o;
          b.disabled = !askEnabled;
          b.addEventListener('click', (e) => {
            e.stopPropagation();
            if (!askEnabled || !onreply) return;
            onreply(o);
          });
          row.appendChild(b);
        });
        card.appendChild(row);
      }
      const hint = document.createElement('div');
      hint.className = 'ask-hint';
      hint.textContent = askEnabled
        ? opts.length
          ? 'Tap an option, or type your own reply below.'
          : 'Type your reply below.'
        : 'Answered.';
      card.appendChild(hint);
      pre.replaceWith(card);
    });
    // On re-run (busy/askEnabled changed) the pre is already gone — refresh the
    // enabled state of cards already built.
    bodyEl.querySelectorAll('.ask-card').forEach((card) => {
      card.querySelectorAll('button.ask-opt').forEach((b) => {
        (b as HTMLButtonElement).disabled = !askEnabled;
      });
      if (!askEnabled) {
        const hint = card.querySelector('.ask-hint');
        if (hint) hint.textContent = 'Answered.';
      }
    });
  }

  function decorateCodeBlocks() {
    if (!bodyEl) return;
    const pres = bodyEl.querySelectorAll('pre');
    pres.forEach((pre) => {
      if (pre.classList.contains('ask-user-raw')) return; // handled by decorateAskUser
      if (pre.dataset.decorated) return;
      pre.dataset.decorated = '1';
      const code = pre.querySelector('code');
      const lang = code?.className.match(/language-(\S+)/)?.[1] || '';
      const wrap = document.createElement('div');
      wrap.className = 'code-wrap';
      pre.parentNode?.insertBefore(wrap, pre);
      wrap.appendChild(pre);
      const head = document.createElement('div');
      head.className = 'code-head';
      head.innerHTML = `<span class="code-lang">${lang || 'code'}</span><button class="code-copy" type="button" aria-label="Copy code">Copy</button>`;
      wrap.insertBefore(head, pre);
      const btn = head.querySelector('button')!;
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
          await navigator.clipboard.writeText(code?.innerText || '');
          btn.textContent = '✓ Copied';
          setTimeout(() => (btn.textContent = 'Copy'), 1500);
        } catch {
          toast.error('Could not copy to clipboard');
        }
      });
    });
    // Make rendered <img> tags inside markdown clickable → lightbox.
    if (onlightbox) {
      const imgs = bodyEl.querySelectorAll('img');
      imgs.forEach((img) => {
        if ((img as any).dataset.lightboxBound) return;
        (img as any).dataset.lightboxBound = '1';
        img.style.cursor = 'zoom-in';
        img.addEventListener('click', (e) => {
          e.preventDefault();
          e.stopPropagation();
          if (onlightbox && (img as HTMLImageElement).src) {
            onlightbox((img as HTMLImageElement).src);
          }
        });
      });
    }
    // Overlay a save/download chip on generated images (same pattern as the
    // code-copy button: DOM injected post-render, styled via :global).
    bodyEl.querySelectorAll('img[src*="/generated/"]').forEach((el) => {
      const img = el as HTMLImageElement;
      if ((img as any).dataset.dlBound) return;
      (img as any).dataset.dlBound = '1';
      const wrap = document.createElement('span');
      wrap.className = 'img-wrap';
      img.parentNode?.insertBefore(wrap, img);
      wrap.appendChild(img);
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'img-download';
      btn.setAttribute('aria-label', 'Save image');
      btn.textContent = '↓';
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        btn.textContent = '…';
        try {
          const msg = await saveImageToDevice(img.currentSrc || img.src);
          btn.textContent = '✓';
          toast.success(msg);
        } catch (err) {
          btn.textContent = '↓';
          toast.error(`Couldn't save image: ${(err as Error).message}`);
        }
        setTimeout(() => (btn.textContent = '↓'), 1800);
      });
      wrap.appendChild(btn);
    });
  }

  function formatSize(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  }

  // ─── TTS playback ─────────────────────────────────────────────────────
  // Sentence-chunk, fire fetches in parallel, play sequentially. First
  // chunk starts playing the moment its blob arrives — subsequent chunks
  // are synthesizing in the background.
  let ttsBusy = $state(false);
  let ttsPlaying = $state(false);
  let currentAudio: HTMLAudioElement | null = null;
  let ttsAborter: AbortController | null = null;
  let ttsCancelled = false;

  /**
   * Convert markdown to plain text suitable for TTS.
   * - Strips tool-chip <details> blocks and the trailing stats footer.
   * - Removes markdown syntax (bold/italic/list markers/headings/links/code).
   * - Code blocks are replaced with a short placeholder so Piper doesn't
   *   read them verbatim.
   * - Newlines are preserved so the TTS engine pauses naturally between
   *   bullets and paragraphs.
   */
  function ttsText(s: string): string {
    let out = s;
    // Tool chips
    out = out.replace(/<details[\s\S]*?<\/details>/g, '');
    // Trailing stats footer (---\n*Gemma 4 | ...*)
    out = out.replace(/\n+-{3,}\n+\*[^*\n]+\*\s*$/m, '');
    // Code fences → placeholder (we don't want to read code char-by-char)
    out = out.replace(/```[\s\S]*?```/g, '\n(code block)\n');
    // Inline code: drop the backticks, keep the text
    out = out.replace(/`([^`\n]+)`/g, '$1');
    // Images: ![alt](src) → alt (or drop if no alt)
    out = out.replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1');
    // Links: [text](url) → text
    out = out.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
    // Bold + italic — strongest first so ** doesn't get partially eaten
    out = out.replace(/\*\*([^*]+)\*\*/g, '$1');
    out = out.replace(/__([^_]+)__/g, '$1');
    out = out.replace(/\*([^*\n]+)\*/g, '$1');
    out = out.replace(/(^|[^_\w])_([^_\n]+)_(?=[^_\w]|$)/g, '$1$2');
    // Strikethrough
    out = out.replace(/~~([^~]+)~~/g, '$1');
    // Headings: drop the leading hashes
    out = out.replace(/^#{1,6}\s+/gm, '');
    // List markers: "- foo", "* foo", "+ foo", "1. foo"
    out = out.replace(/^\s*[-*+]\s+/gm, '');
    out = out.replace(/^\s*\d+\.\s+/gm, '');
    // Block quotes
    out = out.replace(/^>\s?/gm, '');
    // Standalone horizontal rules
    out = out.replace(/^\s*-{3,}\s*$/gm, '');
    // Table rows: replace pipes with commas for natural pause
    out = out.replace(/^\s*\|(.+)\|\s*$/gm, (_, inner) => inner.replace(/\|/g, ', '));
    // Collapse 3+ newlines to 2
    out = out.replace(/\n{3,}/g, '\n\n');
    return out.trim();
  }

  function splitSentences(text: string): string[] {
    // Match runs of non-terminator + terminator. Trim and drop empties.
    const matches = text.match(/[^.!?\n]+[.!?\n]+(?:\s+|$)/g) ?? [text];
    return matches.map((s) => s.trim()).filter(Boolean);
  }

  /** Group sentences into chunks no larger than maxLen chars. */
  function chunkText(text: string, maxLen = 280): string[] {
    const sentences = splitSentences(text);
    const out: string[] = [];
    let buf = '';
    for (const s of sentences) {
      if (buf && buf.length + s.length + 1 > maxLen) {
        out.push(buf);
        buf = s;
      } else {
        buf = buf ? buf + ' ' + s : s;
      }
    }
    if (buf) out.push(buf);
    return out.length ? out : [text];
  }

  async function stopSpeaking() {
    ttsCancelled = true;
    ttsAborter?.abort();
    if (currentAudio) {
      try {
        currentAudio.pause();
      } catch {}
      currentAudio.src = '';
      currentAudio = null;
    }
    ttsPlaying = false;
    ttsBusy = false;
  }

  async function toggleSpeak() {
    if (ttsPlaying || ttsBusy) {
      await stopSpeaking();
      return;
    }
    const cleaned = ttsText(content);
    if (!cleaned) return;
    const chunks = chunkText(cleaned);

    ttsCancelled = false;
    ttsAborter = new AbortController();
    ttsBusy = true;

    // 1-lookahead: while audio N plays, audio N+1 synthesizes in the
    // background. Firing all chunks in parallel turned out worse (Piper
    // serializes incoming requests), so we keep at most 2 in flight: the
    // one we're playing and the next.
    const pending: (Promise<Blob> | null)[] = new Array(chunks.length).fill(null);
    const kickOff = (i: number) => {
      if (i >= chunks.length || pending[i]) return;
      pending[i] = fetchTTS(chunks[i], undefined, ttsAborter!.signal, 'mp3');
    };
    kickOff(0);

    try {
      for (let i = 0; i < chunks.length; i++) {
        if (ttsCancelled) break;
        let blob: Blob;
        try {
          blob = await pending[i]!;
        } catch (e) {
          if ((e as Error).name === 'AbortError' || ttsCancelled) break;
          toast.error(`TTS chunk ${i + 1}: ${(e as Error).message}`);
          break;
        }
        if (ttsCancelled) break;
        // Start synthesizing chunk i+1 NOW so it's ready by the time
        // chunk i's audio finishes.
        kickOff(i + 1);

        const url = URL.createObjectURL(blob);
        currentAudio = new Audio(url);
        ttsBusy = false;
        ttsPlaying = true;
        try {
          await new Promise<void>((resolve, reject) => {
            const a = currentAudio!;
            a.onended = () => resolve();
            a.onerror = () => reject(new Error('playback failed'));
            a.play().catch(reject);
          });
        } catch (e) {
          if (!ttsCancelled) toast.error(`Playback: ${(e as Error).message}`);
          URL.revokeObjectURL(url);
          break;
        }
        URL.revokeObjectURL(url);
        currentAudio = null;
      }
    } finally {
      ttsBusy = false;
      ttsPlaying = false;
      ttsAborter = null;
      currentAudio = null;
    }
  }

  onDestroy(() => {
    ttsCancelled = true;
    ttsAborter?.abort();
    if (currentAudio) {
      try {
        currentAudio.pause();
      } catch {}
      currentAudio.src = '';
      currentAudio = null;
    }
  });
</script>

<div
  class="msg"
  class:user={role === 'user'}
  class:assistant={role === 'assistant'}
  class:editing
  class:flash
  data-mid={messageId}
>
  {#if role === 'assistant'}
    <div class="label">
      <span class="brand">
        <span class="brand-dot" aria-hidden="true"><IconSparkles size={11} /></span>
        TomSense
      </span>
      {#if content && content.length > 1}
        <button
          class="icon-btn tts"
          aria-label={ttsPlaying ? 'Pause' : 'Speak'}
          title={ttsPlaying ? 'Pause' : 'Speak'}
          disabled={ttsBusy}
          onclick={toggleSpeak}
        >
          {#if ttsBusy}<span class="spinner" aria-hidden="true"></span>
          {:else if ttsPlaying}<IconPause size={14} />
          {:else}<IconVolume size={14} />{/if}
        </button>
      {/if}
    </div>
  {/if}

  <div class="bubble" onclick={onBubbleClick} role="presentation">
    {#if uploads.length > 0}
      <div class="attachments">
        {#each uploads as u (u.id)}
          {#if u.kind === 'image'}
            {@const src = `/uploads/${u.id}/raw`}
            <button
              type="button"
              class="img-wrap img-btn"
              aria-label={u.filename}
              title={u.filename}
              onclick={(e) => {
                e.stopPropagation();
                onlightbox && onlightbox(src);
              }}
            >
              <img src={src} alt={u.filename} />
            </button>
          {:else}
            <a
              class="file-chip"
              href={`/uploads/${u.id}/raw`}
              target="_blank"
              rel="noopener"
              onclick={(e) => e.stopPropagation()}
            >
              <span class="file-chip-icon" aria-hidden="true">
                {#if u.kind === 'pdf'}<IconFile size={18} />{:else}<IconFileText size={18} />{/if}
              </span>
              <span class="file-chip-meta">
                <span class="file-chip-name">{u.filename}</span>
                <span class="file-chip-size">{formatSize(u.size_bytes)}</span>
              </span>
            </a>
          {/if}
        {/each}
      </div>
    {/if}

    {#if editing}
      <div class="edit" onclick={(e) => e.stopPropagation()} role="presentation">
        <textarea
          bind:value={editValue}
          rows="3"
          onkeydown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              saveEdit();
            } else if (e.key === 'Escape') {
              cancelEdit();
            }
          }}
        ></textarea>
        <div class="edit-actions">
          <button class="btn-text" type="button" onclick={cancelEdit} disabled={editing_busy}>
            Cancel
          </button>
          <button
            class="btn-filled"
            type="button"
            onclick={saveEdit}
            disabled={editing_busy || !editValue.trim()}
          >
            {editing_busy ? 'Saving…' : 'Save & re-run'}
          </button>
        </div>
      </div>
    {:else if content}
      <div class="md body" bind:this={bodyEl}>{@html html}</div>
    {/if}
  </div>

  {#if !editing && content}
    <div
      class="actions"
      class:open={actionsOpen}
      role="toolbar"
      aria-label="Message actions"
    >
      <button class="icon-btn small" title="Copy" aria-label="Copy message" onclick={copyText}>
        <IconCopy size={14} />
      </button>
      {#if canEdit && onedit}
        <button class="icon-btn small" title="Edit and re-run" aria-label="Edit message" onclick={startEdit}>
          <IconEdit size={14} />
        </button>
      {/if}
      {#if canRegenerate && onregenerate}
        <button class="icon-btn small" title="Regenerate" aria-label="Regenerate response" onclick={onregenerate}>
          <IconRefresh size={14} />
        </button>
      {/if}
      {#if canBranch && onbranch && messageId !== undefined}
        <button class="icon-btn small" title="Branch from here" aria-label="Branch" onclick={() => onbranch!(messageId)}>
          <IconGitBranch size={14} />
        </button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .msg {
    max-width: 760px;
    width: 100%;
    align-self: flex-start;
    color: var(--text);
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .bubble {
    min-width: 0;
  }
  /* Briefly ring a message when jumped to from chat search. */
  .msg.flash {
    border-radius: var(--r-lg);
    animation: msg-flash 2.4s ease-out;
  }
  @keyframes msg-flash {
    0%,
    15% {
      box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.55);
    }
    100% {
      box-shadow: 0 0 0 3px rgba(255, 138, 76, 0);
    }
  }
  .msg.user {
    align-self: flex-end;
    max-width: 80%;
    width: fit-content;
    align-items: flex-end;
  }
  .msg.user .bubble {
    background: var(--user-bubble);
    padding: 8px 14px;
    border-radius: var(--r-lg);
    cursor: pointer;
  }
  /* In edit mode the user "bubble" expands and loses its pill chrome so
   * the textarea has room. */
  .msg.user.editing {
    max-width: 760px;
    width: 100%;
  }
  .msg.user.editing .bubble {
    background: transparent;
    padding: 0;
    cursor: default;
  }
  /* Strip <p> margins entirely inside the user bubble — marked wraps "Hi"
   * in a <p> which would otherwise add small default vertical space.
   */
  .msg.user :global(.md p) {
    margin: 0;
  }
  .msg.user .body {
    /* NOTE: do NOT use white-space: pre-wrap here. marked emits a trailing
     * "\n" text node after the final <p>, and pre-wrap renders that as a
     * visible empty line — every user bubble ends up with a phantom blank
     * line below the text. marked + breaks:true already handles single
     * newlines via <br>, so we don't need pre-wrap.
     */
    line-height: 1.4;
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .msg.assistant,
  .msg.assistant .bubble {
    padding: 0;
  }

  .label {
    font-size: var(--fs-xs);
    color: var(--muted);
    margin-bottom: 2px;
    display: flex;
    align-items: center;
    gap: var(--sp-2);
  }
  .brand {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--muted-strong);
    font-weight: 600;
    letter-spacing: 0.01em;
  }
  .brand-dot {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: var(--r-pill);
    background: linear-gradient(135deg, var(--accent), #d96030);
    color: var(--accent-fg);
  }
  .tts {
    width: 24px;
    height: 24px;
    padding: 0;
  }

  /* attachments */
  .attachments {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
    margin-bottom: var(--sp-2);
  }
  .img-wrap {
    display: block;
    transition: transform var(--t-fast);
  }
  .img-wrap:hover {
    transform: scale(1.01);
  }
  .img-wrap img {
    max-width: 260px;
    max-height: 260px;
    border-radius: var(--r-4);
    border: 1px solid var(--border);
    object-fit: cover;
    display: block;
  }
  .img-btn {
    background: transparent;
    border: 0;
    padding: 0;
    cursor: zoom-in;
  }
  .file-chip {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-4);
    padding: 6px 12px 6px 6px;
    max-width: 280px;
    color: var(--text);
    text-decoration: none;
    transition: border-color var(--t-fast);
  }
  .file-chip:hover {
    border-color: var(--border-strong);
    text-decoration: none;
  }
  .file-chip-icon {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--panel-2);
    border-radius: var(--r-2);
    color: var(--muted-strong);
    flex-shrink: 0;
  }
  .file-chip-meta {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .file-chip-name {
    font-size: var(--fs-sm);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 200px;
  }
  .file-chip-size {
    font-size: var(--fs-xs);
    color: var(--muted);
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

  /* per-message action row — sits outside the bubble visually and is hidden
   * until the user taps the bubble (or hovers on a pointing device). */
  .actions {
    display: flex;
    gap: 2px;
    margin-top: 4px;
    opacity: 0;
    pointer-events: none;
    transition: opacity var(--t-fast);
  }
  .actions.open {
    opacity: 1;
    pointer-events: auto;
  }
  /* Desktop hover — keep the long-standing reveal-on-hover behavior. */
  @media (hover: hover) and (pointer: fine) {
    .msg:hover .actions,
    .msg:focus-within .actions {
      opacity: 1;
      pointer-events: auto;
    }
  }
  .msg.user .actions {
    justify-content: flex-end;
  }

  /* inline edit mode */
  .edit {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    width: 100%;
  }
  .edit textarea {
    width: 100%;
    background: var(--panel-2);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    padding: 10px 12px;
    font: inherit;
    font-size: var(--fs-base);
    line-height: var(--lh-base);
    resize: vertical;
    min-height: 72px;
  }
  .edit textarea:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }
  .edit-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--sp-2);
  }
  .msg.user .edit {
    background: transparent;
    padding: 0;
  }

  /* ask_user question card (built by decorateAskUser from a ```ask-user block) */
  :global(.ask-card) {
    margin: 12px 0;
    padding: 12px 14px;
    border: 1px solid var(--accent);
    border-radius: var(--r-4);
    background: color-mix(in srgb, var(--accent) 8%, var(--bg-elevated));
  }
  :global(.ask-card .ask-q) {
    font-weight: 600;
    margin-bottom: 10px;
    line-height: 1.4;
  }
  :global(.ask-card .ask-opts) {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  :global(.ask-card .ask-opt) {
    padding: 7px 14px;
    border: 1px solid var(--accent);
    border-radius: 999px;
    background: var(--bg-elevated);
    color: var(--accent);
    font-size: var(--fs-sm);
    font-weight: 500;
    cursor: pointer;
    transition: background 0.12s, color 0.12s;
  }
  :global(.ask-card .ask-opt:hover:not(:disabled)) {
    background: var(--accent);
    color: var(--accent-fg);
  }
  :global(.ask-card .ask-opt:disabled) {
    opacity: 0.5;
    cursor: default;
  }
  :global(.ask-card .ask-hint) {
    margin-top: 10px;
    font-size: var(--fs-xs);
    color: var(--muted);
  }

  /* Decorated code blocks (added by JS to .md pre) */
  :global(.img-wrap) {
    position: relative;
    display: inline-block;
    max-width: 100%;
  }
  :global(.img-download) {
    position: absolute;
    top: 8px;
    right: 8px;
    width: 32px;
    height: 32px;
    border: 0;
    border-radius: 999px;
    background: rgba(0, 0, 0, 0.55);
    color: #fff;
    font-size: 16px;
    line-height: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    backdrop-filter: blur(4px);
  }
  :global(.img-download:hover) {
    background: rgba(0, 0, 0, 0.75);
  }
  :global(.code-wrap) {
    margin: 10px 0;
    border-radius: var(--r-4);
    overflow: hidden;
    border: 1px solid var(--border);
    background: var(--bg-elevated);
  }
  :global(.code-wrap pre) {
    margin: 0;
    border: 0;
    border-radius: 0;
    background: transparent;
  }
  :global(.code-head) {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 4px 12px;
    border-bottom: 1px solid var(--border);
    background: var(--panel);
    font-size: var(--fs-xs);
    color: var(--muted);
  }
  :global(.code-lang) {
    text-transform: lowercase;
    letter-spacing: 0.04em;
    font-family: var(--font-mono);
  }
  :global(.code-copy) {
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: 2px 8px;
    border-radius: var(--r-2);
    font-size: var(--fs-xs);
    cursor: pointer;
    transition: color var(--t-fast), background var(--t-fast);
  }
  :global(.code-copy:hover) {
    color: var(--text);
    background: var(--panel-2);
  }
</style>
