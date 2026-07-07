<script lang="ts">
  // Unified file manager — three sources side by side. The Settings → Docs
  // tab was removed; this is now the canonical place for uploads, chat-
  // generated artifacts, and the code-mode sandbox workspace.
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import AppHeader from '$lib/components/AppHeader.svelte';
  import {
    listUserUploads,
    deleteUserUpload,
    reindexUpload,
    listUserArtifacts,
    getArtifact,
    deleteArtifact,
    sandboxList,
    sandboxRead,
    sandboxWrite,
    sandboxRename,
    sandboxDelete,
    sandboxMkdir,
    listMounts,
    addMount,
    deleteMount,
    applyMounts,
    listProjects,
    setUploadProject,
  } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import {
    IconCheck,
    IconCopy,
    IconEdit,
    IconFile,
    IconFileText,
    IconFolder,
    IconGitBranch,
    IconImage,
    IconLayers,
    IconPlus,
    IconRefresh,
    IconTrash,
    IconX,
  } from '$lib/icons';
  import type {
    Artifact,
    Project,
    ProjectMount,
    SandboxEntry,
    UserArtifact,
    UserUpload,
  } from '$lib/types';

  type Tab = 'uploads' | 'artifacts' | 'sandbox' | 'mounts';
  let tab = $state<Tab>('uploads');

  // ─── Multi-select ───────────────────────────────────────────────────────
  // One mode toggle, three per-tab selection sets. Cancelling clears all
  // three. Selecting in one tab and switching tabs preserves the selection
  // so users can do bulk ops across visits without restarting.
  let selectMode = $state(false);
  let selUploads = $state<Set<string>>(new Set());
  let selArtifacts = $state<Set<number>>(new Set());
  let selSandbox = $state<Set<string>>(new Set()); // full paths
  let bulkBusy = $state(false);

  function toggleSelectMode() {
    selectMode = !selectMode;
    if (!selectMode) {
      selUploads = new Set();
      selArtifacts = new Set();
      selSandbox = new Set();
    }
  }
  function exitSelectMode() {
    selectMode = false;
    selUploads = new Set();
    selArtifacts = new Set();
    selSandbox = new Set();
  }
  function toggleSel<T>(set: Set<T>, key: T): Set<T> {
    const next = new Set(set);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  }
  // Count for the current tab — drives the bulk action bar's enabled state.
  let activeSelCount = $derived(
    tab === 'uploads' ? selUploads.size
    : tab === 'artifacts' ? selArtifacts.size
    : selSandbox.size
  );

  // ─── Uploads ────────────────────────────────────────────────────────────
  let uploads = $state<UserUpload[]>([]);
  let uploadsLoading = $state(false);
  let reindexing = $state<Set<string>>(new Set());
  let projects = $state<Project[]>([]);

  async function refreshUploads() {
    uploadsLoading = true;
    try {
      uploads = await listUserUploads();
      projects = await listProjects();
    } catch (e) {
      toast.error(`Could not load uploads: ${(e as Error).message}`);
    } finally {
      uploadsLoading = false;
    }
  }

  /** Attach/detach an upload to a project (knowledge file for its chats). */
  async function onUploadProject(u: UserUpload, projectId: string) {
    const pid = projectId || null;
    try {
      await setUploadProject(u.id, pid);
      uploads = uploads.map((x) => (x.id === u.id ? { ...x, project_id: pid } : x));
      toast.success(pid ? 'Added to project knowledge' : 'Removed from project');
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function onDeleteUpload(u: UserUpload) {
    if (!confirm(`Delete ${u.filename}? Removes the file and its index.`)) return;
    try {
      await deleteUserUpload(u.id);
      uploads = uploads.filter((x) => x.id !== u.id);
    } catch (e) {
      toast.error(`Delete failed: ${(e as Error).message}`);
    }
  }

  async function onReindex(u: UserUpload) {
    if (reindexing.has(u.id)) return;
    reindexing = new Set([...reindexing, u.id]);
    try {
      const r = await reindexUpload(u.id);
      toast.info(`Re-indexed ${u.filename}: ${r.chunks} chunks`);
      uploads = uploads.map((x) => (x.id === u.id ? { ...x, indexed: true } : x));
    } catch (e) {
      toast.error(`Re-index failed: ${(e as Error).message}`);
    } finally {
      const n = new Set(reindexing);
      n.delete(u.id);
      reindexing = n;
    }
  }

  let fileInput: HTMLInputElement | undefined = $state();
  let uploadingNew = $state(false);
  async function onPickUpload() {
    fileInput?.click();
  }
  async function onUploadSelected(e: Event) {
    const target = e.target as HTMLInputElement;
    const file = target.files?.[0];
    target.value = '';
    if (!file) return;
    uploadingNew = true;
    try {
      const fd = new FormData();
      fd.append('file', file);
      const r = await fetch('/uploads', { method: 'POST', body: fd });
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
      toast.info(`Uploaded ${file.name}`);
      await refreshUploads();
    } catch (e) {
      toast.error(`Upload failed: ${(e as Error).message}`);
    } finally {
      uploadingNew = false;
    }
  }

  // ─── Artifacts ──────────────────────────────────────────────────────────
  let artifacts = $state<UserArtifact[]>([]);
  let artifactsLoading = $state(false);
  let selectedArtifact = $state<(Artifact & { chat_id: string; chat_title: string | null }) | null>(null);
  let artifactLoading = $state(false);

  async function refreshArtifacts() {
    artifactsLoading = true;
    try {
      artifacts = await listUserArtifacts();
    } catch (e) {
      toast.error(`Could not load artifacts: ${(e as Error).message}`);
    } finally {
      artifactsLoading = false;
    }
  }

  async function openArtifact(a: UserArtifact) {
    artifactLoading = true;
    selectedArtifact = null;
    try {
      selectedArtifact = await getArtifact(a.id);
    } catch (e) {
      toast.error(`Could not open: ${(e as Error).message}`);
    } finally {
      artifactLoading = false;
    }
  }

  // ─── Bulk actions ───────────────────────────────────────────────────────

  async function bulkDeleteUploads() {
    if (selUploads.size === 0) return;
    if (!confirm(`Delete ${selUploads.size} upload${selUploads.size === 1 ? '' : 's'}? Removes files and indexes.`)) return;
    bulkBusy = true;
    const ids = [...selUploads];
    let ok = 0, fail = 0;
    for (const id of ids) {
      try { await deleteUserUpload(id); ok++; }
      catch { fail++; }
    }
    bulkBusy = false;
    exitSelectMode();
    await refreshUploads();
    if (fail) toast.error(`Deleted ${ok}; ${fail} failed`);
    else toast.info(`Deleted ${ok}`);
  }

  async function bulkDeleteArtifacts() {
    if (selArtifacts.size === 0) return;
    if (!confirm(`Delete ${selArtifacts.size} artifact${selArtifacts.size === 1 ? '' : 's'}?`)) return;
    bulkBusy = true;
    const ids = [...selArtifacts];
    let ok = 0, fail = 0;
    for (const id of ids) {
      try { await deleteArtifact(id); ok++; }
      catch { fail++; }
    }
    bulkBusy = false;
    exitSelectMode();
    await refreshArtifacts();
    if (fail) toast.error(`Deleted ${ok}; ${fail} failed`);
    else toast.info(`Deleted ${ok}`);
  }

  async function bulkCopyArtifacts() {
    if (selArtifacts.size === 0) return;
    bulkBusy = true;
    const ids = [...selArtifacts];
    const parts: string[] = [];
    let skipped = 0;
    for (const id of ids) {
      try {
        const a = await getArtifact(id);
        if (a.kind === 'code' && a.content) {
          parts.push(`─── ${a.title ?? `artifact ${a.id}`} ───\n${a.content}`);
        } else {
          skipped++; // images — clipboard text doesn't apply
        }
      } catch {
        skipped++;
      }
    }
    bulkBusy = false;
    if (parts.length === 0) {
      toast.error('Nothing to copy (selection had no code artifacts).');
      return;
    }
    try {
      await navigator.clipboard.writeText(parts.join('\n\n'));
      toast.info(`Copied ${parts.length} artifact${parts.length === 1 ? '' : 's'}${skipped ? ` (${skipped} skipped — not text)` : ''}`);
    } catch (e) {
      toast.error(`Clipboard write failed: ${(e as Error).message}`);
    }
  }

  async function bulkDeleteSandbox() {
    if (selSandbox.size === 0) return;
    if (!confirm(`Delete ${selSandbox.size} item${selSandbox.size === 1 ? '' : 's'} from the sandbox? Directories will be removed recursively.`)) return;
    bulkBusy = true;
    const paths = [...selSandbox];
    let ok = 0, fail = 0;
    for (const p of paths) {
      try { await sandboxDelete(p, true); ok++; }
      catch { fail++; }
    }
    bulkBusy = false;
    exitSelectMode();
    await refreshSandbox();
    if (fail) toast.error(`Deleted ${ok}; ${fail} failed`);
    else toast.info(`Deleted ${ok}`);
  }

  async function bulkCopySandbox() {
    if (selSandbox.size === 0) return;
    bulkBusy = true;
    const paths = [...selSandbox];
    const parts: string[] = [];
    let skipped = 0;
    for (const p of paths) {
      // Skip directories — we can't meaningfully concatenate their contents.
      const entry = sandboxEntries.find((e) => pathJoin(sandboxPath, e.name) === p);
      if (entry && entry.type === 'dir') {
        skipped++;
        continue;
      }
      try {
        const r = await sandboxRead(p, 0, 5000);
        parts.push(`─── ${p} ───\n${r.content}`);
      } catch {
        skipped++;
      }
    }
    bulkBusy = false;
    if (parts.length === 0) {
      toast.error('Nothing to copy.');
      return;
    }
    try {
      await navigator.clipboard.writeText(parts.join('\n\n'));
      toast.info(`Copied ${parts.length} file${parts.length === 1 ? '' : 's'}${skipped ? ` (${skipped} skipped)` : ''}`);
    } catch (e) {
      toast.error(`Clipboard write failed: ${(e as Error).message}`);
    }
  }

  async function onDeleteArtifact(a: UserArtifact) {
    if (!confirm(`Delete this artifact?`)) return;
    try {
      await deleteArtifact(a.id);
      artifacts = artifacts.filter((x) => x.id !== a.id);
      if (selectedArtifact?.id === a.id) selectedArtifact = null;
    } catch (e) {
      toast.error(`Delete failed: ${(e as Error).message}`);
    }
  }

  function copyArtifact(text: string) {
    navigator.clipboard?.writeText(text);
    toast.info('Copied');
  }

  // ─── Sandbox ────────────────────────────────────────────────────────────
  let sandboxPath = $state('.');
  let sandboxEntries = $state<SandboxEntry[]>([]);
  let sandboxLoading = $state(false);

  // Drilldown — when set, the right pane is the file editor.
  let sandboxOpenPath = $state<string | null>(null);
  let sandboxOpenContent = $state('');
  let sandboxOpenDirty = $state(false);
  let sandboxOpenLoading = $state(false);
  let sandboxSaving = $state(false);

  async function refreshSandbox(path: string = sandboxPath) {
    sandboxLoading = true;
    try {
      const r = await sandboxList(path);
      sandboxPath = r.path;
      sandboxEntries = r.entries;
    } catch (e) {
      toast.error(`Sandbox: ${(e as Error).message}`);
    } finally {
      sandboxLoading = false;
    }
  }

  function pathJoin(base: string, name: string): string {
    if (base === '.' || base === '') return name;
    return `${base.replace(/\/$/, '')}/${name}`;
  }

  function pathUp(path: string): string {
    if (path === '.' || path === '') return '.';
    const i = path.lastIndexOf('/');
    return i < 0 ? '.' : path.slice(0, i) || '.';
  }

  async function enterDir(name: string) {
    await refreshSandbox(pathJoin(sandboxPath, name));
  }

  async function goUp() {
    await refreshSandbox(pathUp(sandboxPath));
  }

  async function openFile(name: string) {
    const full = pathJoin(sandboxPath, name);
    sandboxOpenPath = full;
    sandboxOpenLoading = true;
    sandboxOpenContent = '';
    sandboxOpenDirty = false;
    try {
      const r = await sandboxRead(full, 0, 5000);
      sandboxOpenContent = r.content;
      if (r.truncated) {
        toast.info(`Showing first ${r.returned_lines} lines (file is larger).`);
      }
    } catch (e) {
      toast.error(`Read failed: ${(e as Error).message}`);
      sandboxOpenPath = null;
    } finally {
      sandboxOpenLoading = false;
    }
  }

  function closeOpenFile() {
    if (sandboxOpenDirty && !confirm('Discard unsaved changes?')) return;
    sandboxOpenPath = null;
    sandboxOpenContent = '';
    sandboxOpenDirty = false;
  }

  async function saveOpenFile() {
    if (!sandboxOpenPath) return;
    sandboxSaving = true;
    try {
      await sandboxWrite(sandboxOpenPath, sandboxOpenContent);
      sandboxOpenDirty = false;
      toast.info('Saved');
      // Refresh listing so size updates.
      await refreshSandbox();
    } catch (e) {
      toast.error(`Save failed: ${(e as Error).message}`);
    } finally {
      sandboxSaving = false;
    }
  }

  async function onSandboxRename(entry: SandboxEntry) {
    const next = prompt(`Rename "${entry.name}" to:`, entry.name);
    if (!next || next === entry.name) return;
    try {
      await sandboxRename(pathJoin(sandboxPath, entry.name), pathJoin(sandboxPath, next));
      await refreshSandbox();
    } catch (e) {
      toast.error(`Rename failed: ${(e as Error).message}`);
    }
  }

  async function onSandboxDelete(entry: SandboxEntry) {
    const isDir = entry.type === 'dir';
    if (!confirm(`Delete ${isDir ? 'directory' : 'file'} "${entry.name}"${isDir ? ' and everything inside it' : ''}?`)) return;
    try {
      await sandboxDelete(pathJoin(sandboxPath, entry.name), isDir);
      if (sandboxOpenPath === pathJoin(sandboxPath, entry.name)) {
        sandboxOpenPath = null;
      }
      await refreshSandbox();
    } catch (e) {
      toast.error(`Delete failed: ${(e as Error).message}`);
    }
  }

  async function newFile() {
    const name = prompt('New file name (relative to current dir):');
    if (!name) return;
    const full = pathJoin(sandboxPath, name);
    try {
      await sandboxWrite(full, '');
      await refreshSandbox();
      await openFile(name);
    } catch (e) {
      toast.error(`Create failed: ${(e as Error).message}`);
    }
  }

  async function newFolder() {
    const name = prompt('New folder name:');
    if (!name) return;
    try {
      await sandboxMkdir(pathJoin(sandboxPath, name));
      await refreshSandbox();
    } catch (e) {
      toast.error(`Create failed: ${(e as Error).message}`);
    }
  }

  // ─── Mounts ─────────────────────────────────────────────────────────────
  let mounts = $state<ProjectMount[]>([]);
  let applySupported = $state(true);
  let mountsLoading = $state(false);
  let newMountName = $state('');
  let newMountPath = $state('');
  let savingMount = $state(false);
  let applyingMounts = $state(false);
  let applyLog = $state<string | null>(null);

  async function refreshMounts() {
    mountsLoading = true;
    try {
      const r = await listMounts();
      mounts = r.mounts;
      applySupported = r.apply_supported;
    } catch (e) {
      toast.error(`Could not load mounts: ${(e as Error).message}`);
    } finally {
      mountsLoading = false;
    }
  }

  async function onAddMount() {
    const name = newMountName.trim();
    const path = newMountPath.trim();
    if (!name || !path) return;
    savingMount = true;
    try {
      mounts = await addMount(name, path);
      newMountName = '';
      newMountPath = '';
      toast.info(`Added ${name} — click Apply to mount it in the sandbox`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      savingMount = false;
    }
  }

  async function onRemoveMount(name: string) {
    if (!confirm(`Remove the "${name}" mount? The sandbox loses access on next Apply; your host files are not touched.`)) return;
    try {
      mounts = await deleteMount(name);
      toast.info(`Removed ${name} — click Apply to unmount in the sandbox`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function onApplyMounts() {
    applyingMounts = true;
    applyLog = null;
    try {
      const r = await applyMounts();
      applyLog = r.log;
      if (r.ok) {
        toast.success('Mounts applied — sandbox recreated');
      } else {
        toast.error('Apply failed — see log below');
      }
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      applyingMounts = false;
    }
  }

  // ─── Init ───────────────────────────────────────────────────────────────
  onMount(() => {
    refreshUploads();
  });

  // Lazy-load each tab the first time it's opened, so we don't pay for all
  // three on every visit.
  let loadedTabs = $state<Set<Tab>>(new Set(['uploads']));
  $effect(() => {
    if (loadedTabs.has(tab)) return;
    loadedTabs = new Set([...loadedTabs, tab]);
    if (tab === 'artifacts') refreshArtifacts();
    else if (tab === 'sandbox') refreshSandbox('.');
    else if (tab === 'mounts') refreshMounts();
  });

  // ─── Display helpers ────────────────────────────────────────────────────
  function formatSize(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} kB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  }
  function formatDate(s: string | null): string {
    if (!s) return '';
    try {
      const d = new Date(s);
      return d.toLocaleString();
    } catch {
      return s;
    }
  }
</script>

<AppHeader title="Files" chatId={null} />

<input
  type="file"
  bind:this={fileInput}
  onchange={onUploadSelected}
  style="display:none"
/>

<div class="files">
  <div class="tabs" role="tablist">
    <button
      class="tab"
      class:active={tab === 'uploads'}
      role="tab"
      aria-selected={tab === 'uploads'}
      onclick={() => (tab = 'uploads')}
    >
      <IconFileText size={14} /> Uploads
      {#if uploads.length > 0}<span class="count">{uploads.length}</span>{/if}
    </button>
    <button
      class="tab"
      class:active={tab === 'artifacts'}
      role="tab"
      aria-selected={tab === 'artifacts'}
      onclick={() => (tab = 'artifacts')}
    >
      <IconLayers size={14} /> Artifacts
      {#if artifacts.length > 0}<span class="count">{artifacts.length}</span>{/if}
    </button>
    <button
      class="tab"
      class:active={tab === 'sandbox'}
      role="tab"
      aria-selected={tab === 'sandbox'}
      onclick={() => (tab = 'sandbox')}
    >
      <IconFolder size={14} /> Sandbox
    </button>
    <button
      class="tab"
      class:active={tab === 'mounts'}
      role="tab"
      aria-selected={tab === 'mounts'}
      onclick={() => (tab = 'mounts')}
    >
      <IconGitBranch size={14} /> Mounts
      {#if mounts.length > 0}<span class="count">{mounts.length}</span>{/if}
    </button>
  </div>

  <!-- Bulk action bar (any tab) -->
  {#if selectMode}
    <div class="bulk-bar">
      <span class="bulk-count">{activeSelCount} selected</span>
      <div class="bulk-actions">
        {#if tab === 'artifacts'}
          <button class="bulk-btn" disabled={activeSelCount === 0 || bulkBusy} onclick={bulkCopyArtifacts}>
            <IconCopy size={13} /> Copy
          </button>
          <button class="bulk-btn danger" disabled={activeSelCount === 0 || bulkBusy} onclick={bulkDeleteArtifacts}>
            <IconTrash size={13} /> Delete
          </button>
        {:else if tab === 'sandbox'}
          <button class="bulk-btn" disabled={activeSelCount === 0 || bulkBusy} onclick={bulkCopySandbox}>
            <IconCopy size={13} /> Copy
          </button>
          <button class="bulk-btn danger" disabled={activeSelCount === 0 || bulkBusy} onclick={bulkDeleteSandbox}>
            <IconTrash size={13} /> Delete
          </button>
        {:else}
          <button class="bulk-btn danger" disabled={activeSelCount === 0 || bulkBusy} onclick={bulkDeleteUploads}>
            <IconTrash size={13} /> Delete
          </button>
        {/if}
        <button class="bulk-btn subtle" disabled={bulkBusy} onclick={exitSelectMode}>Cancel</button>
      </div>
    </div>
  {/if}

  <!-- ──────── UPLOADS ──────── -->
  {#if tab === 'uploads'}
    <div class="pane">
      <div class="pane-head">
        <p class="muted">
          Files you've uploaded. Text and PDFs are auto-indexed for retrieval
          (the <strong>indexed</strong> tag). Use <IconRefresh size={12} /> to
          re-index, <IconTrash size={12} /> to remove.
        </p>
        <div class="row-actions">
          <button class="icon-btn" onclick={toggleSelectMode} title={selectMode ? 'Exit select' : 'Select multiple'}>
            <IconCheck size={14} />
          </button>
          <button class="primary" onclick={onPickUpload} disabled={uploadingNew}>
            <IconPlus size={14} /> {uploadingNew ? 'Uploading…' : 'Upload'}
          </button>
        </div>
      </div>

      {#if uploadsLoading && uploads.length === 0}
        <p class="muted">Loading…</p>
      {:else if uploads.length === 0}
        <div class="empty">
          <IconFile size={36} />
          <p>No uploads yet.</p>
        </div>
      {:else}
        <ul class="rows">
          {#each uploads as u (u.id)}
            <li
              class:selectable={selectMode}
              class:selected={selectMode && selUploads.has(u.id)}
              onclick={() => { if (selectMode) selUploads = toggleSel(selUploads, u.id); }}
            >
              {#if selectMode}
                <span class="row-check" class:on={selUploads.has(u.id)} aria-hidden="true">
                  {#if selUploads.has(u.id)}<IconCheck size={12} />{/if}
                </span>
              {/if}
              <span class="row-icon">
                {#if u.kind === 'image'}<IconImage size={18} />
                {:else if u.kind === 'pdf'}<IconFile size={18} />
                {:else}<IconFileText size={18} />{/if}
              </span>
              <div class="row-meta">
                <a
                  class="row-title"
                  href={`/uploads/${u.id}/raw`}
                  target="_blank"
                  rel="noopener"
                  onclick={(e) => { if (selectMode) e.preventDefault(); }}
                >
                  {u.filename}
                </a>
                <div class="row-sub">
                  {formatSize(u.size_bytes)}
                  {#if u.indexed}<span class="tag indexed">indexed</span>{/if}
                  <span class="dim">{formatDate(u.created_at)}</span>
                </div>
              </div>
              {#if !selectMode}
                <div class="row-actions">
                  {#if u.kind !== 'image' && projects.length}
                    <!-- Project knowledge: chats in the project auto-search this file -->
                    <select
                      class="proj-select"
                      title="Attach as project knowledge"
                      value={u.project_id ?? ''}
                      onclick={(e) => e.stopPropagation()}
                      onchange={(e) => onUploadProject(u, (e.currentTarget as HTMLSelectElement).value)}
                    >
                      <option value="">No project</option>
                      {#each projects as p (p.id)}
                        <option value={p.id}>{p.name}</option>
                      {/each}
                    </select>
                  {/if}
                  {#if u.kind !== 'image'}
                    <button
                      class="icon-btn"
                      aria-label="Re-index"
                      title="Re-index"
                      disabled={reindexing.has(u.id)}
                      onclick={() => onReindex(u)}
                    >
                      {#if reindexing.has(u.id)}<span class="spinner"></span>
                      {:else}<IconRefresh size={14} />{/if}
                    </button>
                  {/if}
                  <button
                    class="icon-btn danger"
                    aria-label="Delete"
                    title="Delete"
                    onclick={() => onDeleteUpload(u)}
                  >
                    <IconTrash size={14} />
                  </button>
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  {/if}

  <!-- ──────── ARTIFACTS ──────── -->
  {#if tab === 'artifacts'}
    <div class="pane">
      {#if selectedArtifact}
        <!-- Detail view -->
        <div class="detail-head">
          <button class="icon-btn" aria-label="Close" onclick={() => (selectedArtifact = null)}>
            <IconX size={16} />
          </button>
          <div class="detail-title">
            <strong>{selectedArtifact.title ?? '(untitled)'}</strong>
            <div class="row-sub">
              from
              <a href={`/c/${selectedArtifact.chat_id}`}>
                {selectedArtifact.chat_title || 'chat'}
              </a>
            </div>
          </div>
          <div class="row-actions">
            {#if selectedArtifact.kind === 'code' && selectedArtifact.content}
              <button
                class="icon-btn"
                aria-label="Copy"
                title="Copy"
                onclick={() => copyArtifact(selectedArtifact!.content!)}
              >
                <IconCopy size={14} />
              </button>
            {/if}
            <button
              class="icon-btn danger"
              aria-label="Delete"
              title="Delete"
              onclick={() => onDeleteArtifact(selectedArtifact as any)}
            >
              <IconTrash size={14} />
            </button>
          </div>
        </div>
        {#if selectedArtifact.kind === 'image' && selectedArtifact.url}
          <div class="image-wrap">
            <img src={selectedArtifact.url} alt={selectedArtifact.title || 'artifact'} />
          </div>
        {:else if selectedArtifact.kind === 'code'}
          <pre class="code-view">{selectedArtifact.content}</pre>
        {/if}
      {:else}
        <div class="pane-head">
          <p class="muted">
            Generated images and ≥12-line code blocks pulled automatically from
            your chat replies (code-mode chats are excluded).
          </p>
          <div class="row-actions">
            <button class="icon-btn" onclick={toggleSelectMode} title={selectMode ? 'Exit select' : 'Select multiple'}>
              <IconCheck size={14} />
            </button>
          </div>
        </div>

        {#if artifactsLoading && artifacts.length === 0}
          <p class="muted">Loading…</p>
        {:else if artifacts.length === 0}
          <div class="empty">
            <IconLayers size={36} />
            <p>No artifacts yet.</p>
          </div>
        {:else}
          <ul class="rows">
            {#each artifacts as a (a.id)}
              <li
                class:clickable={!selectMode}
                class:selectable={selectMode}
                class:selected={selectMode && selArtifacts.has(a.id)}
                onclick={() => {
                  if (selectMode) selArtifacts = toggleSel(selArtifacts, a.id);
                  else openArtifact(a);
                }}
                role="button"
                tabindex="0"
                onkeydown={(e) => {
                  if (e.key === 'Enter') {
                    if (selectMode) selArtifacts = toggleSel(selArtifacts, a.id);
                    else openArtifact(a);
                  }
                }}
              >
                {#if selectMode}
                  <span class="row-check" class:on={selArtifacts.has(a.id)} aria-hidden="true">
                    {#if selArtifacts.has(a.id)}<IconCheck size={12} />{/if}
                  </span>
                {/if}
                <span class="row-icon">
                  {#if a.kind === 'image'}<IconImage size={18} />
                  {:else}<IconFileText size={18} />{/if}
                </span>
                <div class="row-meta">
                  <span class="row-title">{a.title ?? '(untitled)'}</span>
                  <div class="row-sub">
                    {#if a.kind === 'code'}
                      <span class="tag">{a.language || 'code'}</span>
                      {formatSize(a.content_len)}
                    {:else}
                      <span class="tag">image</span>
                    {/if}
                    <span class="dim">{a.chat_title || 'chat'} · {formatDate(a.created_at)}</span>
                  </div>
                </div>
                {#if !selectMode}
                  <div class="row-actions">
                    <button
                      class="icon-btn danger"
                      aria-label="Delete"
                      title="Delete"
                      onclick={(e) => { e.stopPropagation(); onDeleteArtifact(a); }}
                    >
                      <IconTrash size={14} />
                    </button>
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      {/if}
    </div>
  {/if}

  <!-- ──────── SANDBOX ──────── -->
  {#if tab === 'sandbox'}
    <div class="pane">
      {#if sandboxOpenPath}
        <!-- Editor view -->
        <div class="detail-head">
          <button class="icon-btn" aria-label="Close" onclick={closeOpenFile}>
            <IconX size={16} />
          </button>
          <div class="detail-title">
            <strong class="mono">{sandboxOpenPath}</strong>
            {#if sandboxOpenDirty}<span class="dim">· unsaved</span>{/if}
          </div>
          <div class="row-actions">
            <button
              class="icon-btn"
              aria-label="Save"
              title="Save"
              disabled={!sandboxOpenDirty || sandboxSaving}
              onclick={saveOpenFile}
            >
              {#if sandboxSaving}<span class="spinner"></span>
              {:else}<IconCheck size={14} />{/if}
            </button>
          </div>
        </div>
        {#if sandboxOpenLoading}
          <p class="muted">Loading…</p>
        {:else}
          <textarea
            class="editor"
            bind:value={sandboxOpenContent}
            oninput={() => (sandboxOpenDirty = true)}
            spellcheck="false"
          ></textarea>
        {/if}
      {:else}
        <div class="pane-head">
          <div class="path">
            <IconFolder size={14} />
            <span class="mono">{sandboxPath === '.' ? '/' : `/${sandboxPath}`}</span>
          </div>
          <div class="row-actions">
            <button class="icon-btn" onclick={toggleSelectMode} title={selectMode ? 'Exit select' : 'Select multiple'} aria-label="Select multiple">
              <IconCheck size={14} />
            </button>
            <button class="icon-btn" title="Refresh" aria-label="Refresh" onclick={() => refreshSandbox()}>
              <IconRefresh size={14} />
            </button>
            <button class="primary small" onclick={newFile}>
              <IconPlus size={12} /> File
            </button>
            <button class="primary small" onclick={newFolder}>
              <IconPlus size={12} /> Folder
            </button>
          </div>
        </div>

        {#if sandboxLoading && sandboxEntries.length === 0}
          <p class="muted">Loading…</p>
        {:else}
          <ul class="rows">
            {#if sandboxPath !== '.' && sandboxPath !== ''}
              <li class="clickable" onclick={goUp} role="button" tabindex="0"
                  onkeydown={(e) => { if (e.key === 'Enter') goUp(); }}>
                <span class="row-icon">↑</span>
                <div class="row-meta">
                  <span class="row-title">..</span>
                  <div class="row-sub dim">Up one directory</div>
                </div>
              </li>
            {/if}
            {#each sandboxEntries as entry (entry.name)}
              {@const entryPath = pathJoin(sandboxPath, entry.name)}
              <li
                class:clickable={!selectMode}
                class:selectable={selectMode}
                class:selected={selectMode && selSandbox.has(entryPath)}
                onclick={() => {
                  if (selectMode) {
                    selSandbox = toggleSel(selSandbox, entryPath);
                  } else if (entry.type === 'dir') {
                    enterDir(entry.name);
                  } else {
                    openFile(entry.name);
                  }
                }}
                role="button"
                tabindex="0"
                onkeydown={(e) => {
                  if (e.key !== 'Enter') return;
                  if (selectMode) {
                    selSandbox = toggleSel(selSandbox, entryPath);
                  } else if (entry.type === 'dir') {
                    enterDir(entry.name);
                  } else {
                    openFile(entry.name);
                  }
                }}
              >
                {#if selectMode}
                  <span class="row-check" class:on={selSandbox.has(entryPath)} aria-hidden="true">
                    {#if selSandbox.has(entryPath)}<IconCheck size={12} />{/if}
                  </span>
                {/if}
                <span class="row-icon">
                  {#if entry.type === 'dir'}<IconFolder size={18} />
                  {:else}<IconFile size={18} />{/if}
                </span>
                <div class="row-meta">
                  <span class="row-title mono">{entry.name}</span>
                  <div class="row-sub dim">
                    {#if entry.type === 'file'}{formatSize(entry.size)}{:else}directory{/if}
                  </div>
                </div>
                {#if !selectMode}
                  <div class="row-actions">
                    <button
                      class="icon-btn"
                      aria-label="Rename"
                      title="Rename"
                      onclick={(e) => { e.stopPropagation(); onSandboxRename(entry); }}
                    >
                      <IconEdit size={14} />
                    </button>
                    <button
                      class="icon-btn danger"
                      aria-label="Delete"
                      title="Delete"
                      onclick={(e) => { e.stopPropagation(); onSandboxDelete(entry); }}
                    >
                      <IconTrash size={14} />
                    </button>
                  </div>
                {/if}
              </li>
            {/each}
            {#if sandboxEntries.length === 0 && sandboxPath === '.'}
              <div class="empty">
                <IconFolder size={36} />
                <p>Workspace is empty. Code-mode chats will populate this.</p>
              </div>
            {/if}
          </ul>
        {/if}
      {/if}
    </div>
  {/if}

  <!-- ──────── MOUNTS ──────── -->
  {#if tab === 'mounts'}
    <div class="pane">
      <div class="pane-head">
        <p class="muted">
          Bind-mount host directories into the code-mode sandbox at
          <code class="mono">/workspace/projects/&lt;name&gt;/</code>. The agent
          gets live read/write access to your real source. Changes don't take
          effect until you click <strong>Apply</strong> (which recreates the
          sandbox container — any in-flight code chat will fail and need a
          retry).
        </p>
      </div>

      {#if mountsLoading && mounts.length === 0}
        <p class="muted">Loading…</p>
      {:else}
        <ul class="rows">
          {#each mounts as m (m.name)}
            <li>
              <span class="row-icon"><IconGitBranch size={18} /></span>
              <div class="row-meta">
                <span class="row-title mono">{m.name}</span>
                <div class="row-sub dim">
                  <span class="mono">{m.host_path}</span>
                  <span>→</span>
                  <span class="mono">/workspace/projects/{m.name}</span>
                </div>
              </div>
              <div class="row-actions">
                <button
                  class="icon-btn danger"
                  aria-label="Remove"
                  title="Remove"
                  onclick={() => onRemoveMount(m.name)}
                >
                  <IconTrash size={14} />
                </button>
              </div>
            </li>
          {/each}
          {#if mounts.length === 0}
            <div class="empty">
              <IconGitBranch size={36} />
              <p>No project mounts. Add one below.</p>
            </div>
          {/if}
        </ul>

        <div class="mount-add">
          <input
            type="text"
            placeholder="name (e.g. arcane)"
            bind:value={newMountName}
            spellcheck="false"
            autocomplete="off"
          />
          <input
            type="text"
            placeholder="/host/absolute/path"
            bind:value={newMountPath}
            spellcheck="false"
            autocomplete="off"
          />
          <button
            class="primary"
            disabled={!newMountName.trim() || !newMountPath.trim() || savingMount}
            onclick={onAddMount}
          >
            <IconPlus size={14} /> {savingMount ? 'Adding…' : 'Add'}
          </button>
        </div>

        <div class="apply-bar">
          {#if applySupported}
            <button
              class="primary big"
              disabled={applyingMounts}
              onclick={onApplyMounts}
            >
              {#if applyingMounts}<span class="spinner"></span> Applying…
              {:else}<IconRefresh size={14} /> Apply changes — recreate sandbox{/if}
            </button>
            <p class="muted small">
              Apply syncs your mount list to the running sandbox. Takes
              ~5s; code-mode chats interrupted during the recreate need to
              retry their last message.
            </p>
          {:else}
            <p class="muted small">
              <strong>Manual apply required:</strong> backend can't restart
              the sandbox automatically. Run on the host after editing
              mounts:
            </p>
            <pre class="apply-cmd">cd &lt;your tomsense-ai directory&gt; && \
docker compose up -d --no-deps --force-recreate tomsense-sandbox</pre>
          {/if}
          {#if applyLog}
            <details class="apply-log">
              <summary>Apply log</summary>
              <pre>{applyLog}</pre>
            </details>
          {/if}
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .files {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    padding: 0 var(--sp-4) var(--sp-4);
  }
  .tabs {
    display: flex;
    gap: var(--sp-1);
    border-bottom: 1px solid var(--border);
    margin-bottom: var(--sp-3);
    overflow-x: auto;
  }
  .tab {
    background: none;
    border: none;
    color: var(--muted);
    font-size: var(--fs-md);
    padding: var(--sp-2) var(--sp-3);
    cursor: pointer;
    border-bottom: 2px solid transparent;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: color var(--t-fast), border-color var(--t-fast);
    white-space: nowrap;
  }
  .tab.active {
    color: var(--text-strong);
    border-bottom-color: var(--accent);
  }
  .tab:hover:not(.active) {
    color: var(--text);
  }
  .count {
    background: var(--panel);
    border-radius: var(--r-pill);
    padding: 1px 7px;
    font-size: var(--fs-xs);
    color: var(--muted);
  }
  .tab.active .count {
    color: var(--text-strong);
  }

  .pane {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .pane-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--sp-2);
    margin-bottom: var(--sp-3);
    flex-wrap: wrap;
  }
  .muted {
    color: var(--muted);
    font-size: var(--fs-sm);
    margin: 0;
  }
  .dim {
    color: var(--muted);
  }

  .rows {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    flex: 1;
  }
  .rows li {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-2) var(--sp-2);
    border-bottom: 1px solid var(--border);
  }
  .rows li.clickable {
    cursor: pointer;
    transition: background var(--t-fast);
  }
  .rows li.clickable:hover {
    background: var(--row-hover);
  }
  .row-icon {
    color: var(--muted);
    width: 24px;
    display: flex;
    justify-content: center;
  }
  .row-meta {
    flex: 1;
    min-width: 0;
  }
  .row-title {
    color: var(--text-strong);
    display: block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  a.row-title {
    text-decoration: none;
  }
  a.row-title:hover {
    text-decoration: underline;
  }
  .row-sub {
    font-size: var(--fs-xs);
    color: var(--muted);
    display: flex;
    gap: 6px;
    align-items: center;
    flex-wrap: wrap;
    margin-top: 2px;
  }
  .row-actions {
    display: flex;
    gap: 4px;
  }
  .tag {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-pill);
    padding: 0 6px;
    font-size: var(--fs-xs);
    color: var(--muted);
  }
  .tag.indexed {
    color: var(--accent);
    border-color: var(--accent);
  }
  .proj-select {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    color: var(--muted);
    font-size: var(--fs-xs);
    padding: 3px 4px;
    max-width: 110px;
  }

  .icon-btn {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    padding: 6px;
    border-radius: var(--r-2);
    display: inline-flex;
    align-items: center;
    transition: color var(--t-fast), background var(--t-fast);
  }
  .icon-btn:hover:not(:disabled) {
    color: var(--text);
    background: var(--row-hover);
  }
  .icon-btn.danger:hover:not(:disabled) {
    color: #d63d3d;
  }
  .icon-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
  .empty {
    text-align: center;
    padding: var(--sp-6) var(--sp-3);
    color: var(--muted);
  }
  .empty p {
    margin-top: var(--sp-2);
  }
  .spinner {
    width: 12px;
    height: 12px;
    border: 2px solid var(--muted);
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* Detail / editor */
  .detail-head {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding-bottom: var(--sp-2);
    border-bottom: 1px solid var(--border);
    margin-bottom: var(--sp-2);
  }
  .detail-title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .detail-title strong {
    color: var(--text-strong);
  }
  .mono {
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
  }
  .code-view {
    flex: 1;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-3);
    overflow: auto;
    white-space: pre;
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
    color: var(--text);
    margin: 0;
  }
  .image-wrap {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: auto;
    padding: var(--sp-3);
  }
  .image-wrap img {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
  }
  .editor {
    flex: 1;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-3);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
    color: var(--text);
    resize: none;
    outline: none;
    min-height: 300px;
  }
  .editor:focus {
    border-color: var(--accent);
  }

  /* Sandbox header path */
  .path {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--muted);
  }

  /* Buttons */
  .primary {
    background: var(--accent);
    border: 1px solid var(--accent);
    color: var(--accent-fg);
    padding: 6px 12px;
    border-radius: var(--r-pill);
    cursor: pointer;
    font-size: var(--fs-sm);
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
  .primary.small {
    padding: 4px 10px;
    font-size: var(--fs-xs);
  }

  /* Multi-select */
  .bulk-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--sp-2);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: 8px 12px;
    margin-bottom: var(--sp-3);
  }
  .bulk-count {
    color: var(--text-strong);
    font-size: var(--fs-sm);
  }
  .bulk-actions {
    display: flex;
    gap: var(--sp-1);
    flex-wrap: wrap;
  }
  .bulk-btn {
    background: var(--row-hover);
    border: 1px solid var(--border);
    border-radius: var(--r-pill);
    color: var(--text);
    cursor: pointer;
    padding: 4px 10px;
    font-size: var(--fs-xs);
    display: inline-flex;
    align-items: center;
    gap: 4px;
    transition: background var(--t-fast), color var(--t-fast), border-color var(--t-fast);
  }
  .bulk-btn:hover:not(:disabled) {
    border-color: var(--border-strong);
    color: var(--text-strong);
  }
  .bulk-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .bulk-btn.danger:not(:disabled) {
    color: #d63d3d;
    border-color: rgba(214, 61, 61, 0.4);
  }
  .bulk-btn.subtle {
    background: transparent;
    color: var(--muted);
  }

  .row-check {
    width: 18px;
    height: 18px;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: var(--accent-fg);
    background: transparent;
    flex-shrink: 0;
  }
  .row-check.on {
    background: var(--accent);
    border-color: var(--accent);
  }
  .rows li.selectable {
    cursor: pointer;
  }
  .rows li.selected {
    background: rgba(74, 143, 231, 0.12);
  }
  .rows li.selected:hover {
    background: rgba(74, 143, 231, 0.18);
  }

  /* Mounts tab */
  .mount-add {
    display: flex;
    gap: var(--sp-1);
    margin-top: var(--sp-3);
    flex-wrap: wrap;
    align-items: center;
  }
  .mount-add input {
    flex: 1;
    min-width: 140px;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    padding: 8px 10px;
    color: var(--text);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-sm);
  }
  .mount-add input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .apply-bar {
    margin-top: var(--sp-4);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .apply-bar .primary.big {
    align-self: flex-start;
    padding: 10px 18px;
    font-size: var(--fs-md);
  }
  .apply-cmd {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-2) var(--sp-3);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-xs);
    color: var(--text);
    white-space: pre-wrap;
    overflow-x: auto;
    margin: 0;
  }
  .apply-log {
    margin-top: var(--sp-2);
  }
  .apply-log summary {
    cursor: pointer;
    color: var(--muted);
    font-size: var(--fs-sm);
  }
  .apply-log pre {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-3);
    padding: var(--sp-2);
    font-family: var(--font-mono, ui-monospace, SFMono-Regular, monospace);
    font-size: var(--fs-xs);
    color: var(--text);
    margin: var(--sp-1) 0 0;
    max-height: 240px;
    overflow: auto;
    white-space: pre-wrap;
  }
</style>
