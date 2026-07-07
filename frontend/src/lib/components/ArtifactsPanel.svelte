<script lang="ts">
  import { listArtifacts, updateArtifact } from '$lib/api';
  import { renderMarkdown } from '$lib/markdown';
  import { toast } from '$lib/toast.svelte';
  import { IconX, IconCopy, IconChevronDown, IconLayers, IconImage, IconEdit, IconMonitor } from '$lib/icons';
  import type { Artifact } from '$lib/types';

  interface Props {
    chatId: string | null;
    open: boolean;
    /** Bumping this number triggers a refetch (e.g. after a turn finishes). */
    refreshKey: number;
    onclose: () => void;
  }

  let { chatId, open, refreshKey, onclose }: Props = $props();

  let artifacts = $state<Artifact[]>([]);
  let loading = $state(false);
  let expanded = $state<Set<number>>(new Set());

  async function load() {
    if (!chatId) return;
    loading = true;
    try {
      artifacts = await listArtifacts(chatId);
    } catch (e) {
      console.error('listArtifacts failed', e);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    void chatId;
    void refreshKey;
    if (open) load();
  });

  function toggle(id: number) {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    expanded = next;
  }

  function renderCode(a: Artifact): string {
    if (!a.content) return '';
    return renderMarkdown('```' + (a.language ?? '') + '\n' + a.content + '\n```');
  }

  function preview(a: Artifact): string {
    if (!a.content) return '';
    return a.content.split('\n').slice(0, 4).join('\n');
  }

  async function copyCode(a: Artifact) {
    if (!a.content) return;
    try {
      await navigator.clipboard.writeText(a.content);
      toast.success('Code copied');
    } catch {
      window.prompt('Copy this code:', a.content);
    }
  }

  // ── Canvas editing + live preview ─────────────────────────────────────
  let editingId = $state<number | null>(null);
  let editBuf = $state('');
  let saving = $state(false);
  let previewId = $state<number | null>(null);

  /** HTML/SVG artifacts get a sandboxed live preview. */
  function previewable(a: Artifact): boolean {
    const lang = (a.language ?? '').toLowerCase();
    if (['html', 'svg', 'xml'].includes(lang)) return true;
    const head = (a.content ?? '').trimStart().slice(0, 200).toLowerCase();
    return head.startsWith('<!doctype html') || head.startsWith('<html') || head.startsWith('<svg');
  }

  function startEdit(a: Artifact) {
    editingId = a.id;
    editBuf = a.content ?? '';
  }

  async function saveEdit(a: Artifact) {
    saving = true;
    try {
      const updated = await updateArtifact(a.id, { content: editBuf });
      artifacts = artifacts.map((x) => (x.id === a.id ? { ...x, ...updated } : x));
      editingId = null;
      toast.success('Saved');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      saving = false;
    }
  }
</script>

{#if open}
  <button class="scrim" aria-label="Close artifacts" onclick={onclose}></button>
  <div class="panel" aria-label="Artifacts" role="dialog">
    <header>
      <h2>Artifacts</h2>
      <button class="icon-btn close" onclick={onclose} aria-label="Close">
        <IconX size={18} />
      </button>
    </header>
    {#if loading && artifacts.length === 0}
      <p class="muted">Loading…</p>
    {:else if artifacts.length === 0}
      <div class="empty">
        <IconLayers size={40} />
        <p>
          No artifacts yet.<br />
          Generated images and long code blocks land here automatically.
        </p>
      </div>
    {:else}
      <ul class="list">
        {#each artifacts as a (a.id)}
          <li class="card">
            {#if a.kind === 'image' && a.url}
              <a href={a.url} target="_blank" rel="noopener" class="img-wrap">
                <img src={a.url} alt={a.title ?? 'generated image'} />
              </a>
              <div class="meta">
                <span class="kind"><IconImage size={11} /> image</span>
                <a class="open" href={a.url} target="_blank" rel="noopener">Open</a>
              </div>
            {:else if a.kind === 'report'}
              <button class="row" onclick={() => toggle(a.id)} aria-expanded={expanded.has(a.id)}>
                <span class="caret" class:expanded={expanded.has(a.id)} aria-hidden="true">
                  <IconChevronDown size={14} />
                </span>
                <span class="title">📄 {a.title ?? 'research report'}</span>
              </button>
              {#if expanded.has(a.id)}
                <div class="md code-full">{@html renderMarkdown(a.content ?? '')}</div>
                <div class="actions">
                  <button class="copy-btn" onclick={() => copyCode(a)}>
                    <IconCopy size={12} /> Copy
                  </button>
                </div>
              {:else}
                <pre class="preview">{preview(a)}</pre>
              {/if}
            {:else if a.kind === 'code'}
              <button class="row" onclick={() => toggle(a.id)} aria-expanded={expanded.has(a.id)}>
                <span class="caret" class:expanded={expanded.has(a.id)} aria-hidden="true">
                  <IconChevronDown size={14} />
                </span>
                <span class="title">{a.title ?? 'code'}</span>
              </button>
              {#if expanded.has(a.id)}
                {#if editingId === a.id}
                  <textarea class="edit-area" bind:value={editBuf} rows="14" spellcheck="false"></textarea>
                {:else if previewId === a.id && previewable(a)}
                  <!-- sandboxed: scripts run, but no same-origin access,
                       no top-navigation, no downloads -->
                  <iframe
                    class="live-preview"
                    title={a.title ?? 'preview'}
                    sandbox="allow-scripts"
                    srcdoc={a.content ?? ''}
                  ></iframe>
                {:else}
                  <div class="md code-full">{@html renderCode(a)}</div>
                {/if}
                <div class="actions">
                  {#if editingId === a.id}
                    <button class="copy-btn" onclick={() => (editingId = null)}>Cancel</button>
                    <button class="copy-btn accent" disabled={saving} onclick={() => saveEdit(a)}>
                      {saving ? 'Saving…' : 'Save'}
                    </button>
                  {:else}
                    {#if previewable(a)}
                      <button
                        class="copy-btn"
                        class:accent={previewId === a.id}
                        onclick={() => (previewId = previewId === a.id ? null : a.id)}
                      >
                        <IconMonitor size={12} /> {previewId === a.id ? 'Code' : 'Preview'}
                      </button>
                    {/if}
                    <button class="copy-btn" onclick={() => startEdit(a)}>
                      <IconEdit size={12} /> Edit
                    </button>
                    <button class="copy-btn" onclick={() => copyCode(a)}>
                      <IconCopy size={12} /> Copy
                    </button>
                  {/if}
                </div>
              {:else}
                <pre class="preview">{preview(a)}</pre>
              {/if}
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </div>
{/if}

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    z-index: 100;
    border: 0;
    padding: 0;
    animation: fade-in 0.15s var(--ease);
  }
  .panel {
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
    overflow: hidden;
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
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }
  h2 {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: 600;
  }
  .muted {
    color: var(--muted);
    font-size: var(--fs-sm);
    padding: var(--sp-3) var(--sp-4);
    margin: 0;
  }
  .list {
    list-style: none;
    padding: var(--sp-3);
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
    overflow-y: auto;
  }
  .card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-4);
    padding: var(--sp-2);
    display: flex;
    flex-direction: column;
    gap: var(--sp-1);
    transition: border-color var(--t-fast);
  }
  .card:hover {
    border-color: var(--border-strong);
  }
  .img-wrap {
    display: block;
  }
  .img-wrap img {
    width: 100%;
    border-radius: var(--r-3);
    border: 1px solid var(--border);
    display: block;
  }
  .meta {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: var(--fs-xs);
    padding: 4px 4px 0;
  }
  .kind {
    color: var(--muted);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-weight: 600;
  }
  .open {
    color: var(--accent);
  }
  .row {
    background: transparent;
    border: 0;
    color: var(--text);
    display: flex;
    gap: var(--sp-2);
    align-items: center;
    text-align: left;
    width: 100%;
    padding: 6px 4px;
    font-size: var(--fs-md);
    cursor: pointer;
  }
  .caret {
    color: var(--muted);
    display: inline-flex;
    transform: rotate(-90deg);
    transition: transform var(--t-fast);
  }
  .caret.expanded {
    transform: rotate(0deg);
  }
  .title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text);
  }
  .preview {
    background: var(--bg-elevated);
    padding: var(--sp-2) var(--sp-3);
    border-radius: var(--r-2);
    font: 11px var(--font-mono);
    color: var(--muted);
    overflow: hidden;
    max-height: 4.5em;
    margin: 0;
  }
  .code-full :global(pre),
  .code-full :global(.code-wrap) {
    margin: 0;
    max-height: 60vh;
    overflow: auto;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
  }
  .copy-btn {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: var(--r-2);
    padding: 4px 10px;
    font-size: var(--fs-xs);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    transition: background var(--t-fast);
  }
  .copy-btn:hover {
    background: var(--row-hover);
  }
  .copy-btn.accent {
    border-color: var(--accent);
    color: var(--accent);
  }
  .edit-area {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    color: var(--text);
    font: 12px var(--font-mono);
    padding: var(--sp-2);
    resize: vertical;
    max-height: 60vh;
  }
  .live-preview {
    width: 100%;
    height: 50vh;
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    background: #fff;
  }
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-3);
    color: var(--muted);
    padding: var(--sp-7) var(--sp-4);
    text-align: center;
  }
  .empty p {
    margin: 0;
    font-size: var(--fs-sm);
    line-height: var(--lh-base);
  }
</style>
