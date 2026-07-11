<script lang="ts">
  import { app } from '$lib/stores.svelte';
  import { exportChat, updatePrefs } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import { IconMenu, IconSettings, IconLayers, IconDownload, IconWaveform, IconGhost } from '$lib/icons';
  import { Capacitor } from '@capacitor/core';

  interface Props {
    title: string;
    chatId?: string | null;
    /** Whether the current chat has artifacts. Controls the artifact button's
     *  disabled state — the button is always rendered for layout stability. */
    hasArtifacts?: boolean;
    onsettings?: () => void;
    onartifacts?: () => void;
    onvoice?: () => void;
    /** Start a temporary (unsaved) chat. Rendered when provided. */
    ontemp?: () => void;
    /** Highlights the ghost when the CURRENT chat is temporary. */
    isTemp?: boolean;
  }

  let {
    title,
    chatId = null,
    hasArtifacts = false,
    onsettings,
    onartifacts,
    onvoice,
    ontemp,
    isTemp = false
  }: Props = $props();

  // Live conversation uses the device STT/TTS plugins — native app only.
  const nativePlatform = Capacitor.isNativePlatform();

  let exportOpen = $state(false);
  let exporting = $state(false);
  let exportBtn: HTMLDivElement | undefined = $state();

  function toggleExport() {
    if (!chatId) return;
    exportOpen = !exportOpen;
  }

  function closeExport() {
    exportOpen = false;
  }

  async function doExport(fmt: 'md' | 'json') {
    if (!chatId || exporting) return;
    exporting = true;
    closeExport();
    try {
      await exportChat(chatId, fmt);
      toast.success(`Exported as ${fmt.toUpperCase()}`);
      // Remember last-used format. Best-effort — don't surface a failure here.
      try {
        const merged = await updatePrefs({ export_format: fmt });
        app.prefs = merged;
      } catch {
        // ignore
      }
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      exporting = false;
    }
  }

  function onWindowClick(e: MouseEvent) {
    if (!exportOpen) return;
    const t = e.target as Node | null;
    if (t && exportBtn && exportBtn.contains(t)) return;
    closeExport();
  }
</script>

<svelte:window onclick={onWindowClick} />

<header>
  <div class="left">
    <button
      class="icon-btn menu-btn"
      aria-label="Open chats"
      onclick={() => (app.sidebarOpen = !app.sidebarOpen)}
    >
      <IconMenu size={18} />
    </button>
    <h1>{title}</h1>
  </div>
  <div class="right">
    {#if ontemp}
      <button
        class="icon-btn temp-btn"
        class:active={isTemp}
        aria-label="Temporary chat"
        aria-pressed={isTemp}
        title={isTemp ? 'This chat is temporary — nothing is saved' : 'New temporary chat (not saved)'}
        onclick={ontemp}
      >
        <IconGhost size={18} />
      </button>
    {/if}
    {#if onvoice && nativePlatform}
      <button
        class="icon-btn"
        aria-label="Live conversation"
        title="Live conversation"
        onclick={onvoice}
      >
        <IconWaveform size={18} />
      </button>
    {/if}
    <button
      class="icon-btn"
      aria-label="Artifacts"
      title={hasArtifacts ? 'Artifacts' : 'Artifacts (none yet)'}
      disabled={!chatId || !hasArtifacts || !onartifacts}
      onclick={onartifacts}
    >
      <IconLayers size={18} />
    </button>
    <div class="export-wrap" bind:this={exportBtn}>
      <button
        class="icon-btn"
        aria-label="Export chat"
        title={chatId ? 'Export chat' : 'Export (start a chat first)'}
        aria-haspopup="menu"
        aria-expanded={exportOpen}
        disabled={!chatId || exporting}
        onclick={toggleExport}
      >
        <IconDownload size={18} />
      </button>
      {#if exportOpen}
        <div class="export-menu" role="menu" aria-label="Export format">
          <button role="menuitem" onclick={() => doExport('md')}>
            <span class="fmt">Markdown</span>
            <span class="ext">.md</span>
          </button>
          <button role="menuitem" onclick={() => doExport('json')}>
            <span class="fmt">JSON</span>
            <span class="ext">.json</span>
          </button>
        </div>
      {/if}
    </div>
    {#if onsettings}
      <button class="icon-btn" aria-label="Settings" title="Settings" onclick={onsettings}>
        <IconSettings size={18} />
      </button>
    {/if}
    <div class="meta">{app.metaText()}</div>
  </div>
</header>

<style>
  header {
    padding: var(--sp-3) var(--sp-4);
    /* targetSdk 35 draws edge-to-edge — pad the bar's content out from under
     * the status bar (app.html sets viewport-fit=cover). */
    padding-top: calc(var(--sp-3) + env(safe-area-inset-top));
    border-bottom: 1px solid var(--border);
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--sp-2);
    background: var(--bg);
  }
  .left {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    min-width: 0;
  }
  h1 {
    margin: 0;
    font-size: var(--fs-md);
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-strong);
    letter-spacing: -0.005em;
  }
  .right {
    display: flex;
    align-items: center;
    gap: 2px;
  }
  .right .meta {
    margin-left: var(--sp-2);
  }
  .meta {
    color: var(--muted);
    font-size: var(--fs-xs);
    flex-shrink: 0;
    padding: 0 var(--sp-1);
  }
  .menu-btn {
    display: none;
  }
  /* Match the Composer's think-toggle convention: accent tint, no border. */
  .temp-btn.active {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .export-wrap {
    position: relative;
  }
  .export-menu {
    position: absolute;
    right: 0;
    top: calc(100% + 4px);
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    box-shadow: var(--shadow-md);
    min-width: 160px;
    z-index: 50;
    display: flex;
    flex-direction: column;
    padding: 4px;
  }
  .export-menu button {
    background: transparent;
    border: 0;
    color: var(--text);
    text-align: left;
    padding: 8px 10px;
    border-radius: var(--r-sm);
    font-size: var(--fs-sm);
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--sp-3);
    cursor: pointer;
  }
  .export-menu button:hover {
    background: var(--row-hover);
  }
  .ext {
    color: var(--muted);
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
  }
  @media (max-width: 768px) {
    .menu-btn {
      display: inline-flex;
    }
    .meta {
      display: none;
    }
  }
</style>
