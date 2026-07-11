<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { app } from '$lib/stores.svelte';
  import {
    deleteChat,
    deleteChats,
    searchChats,
    setChatPinned,
    setChatProject,
    createProject,
    updateProject,
    deleteProject
  } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import {
    IconPlus,
    IconTrash,
    IconLogOut,
    IconSparkles,
    IconX,
    IconShare,
    IconStar,
    IconLayers,
    IconFolder,
    IconEdit,
    IconCheck,
    IconGitBranch,
    IconMoreVertical,
    IconRefresh
  } from '$lib/icons';
  import type { Chat, ChatSearchResult, Project } from '$lib/types';

  let currentId = $derived($page.params.id ?? null);

  // ─── search (server-side: title + message content, with hit snippets) ──
  let search = $state('');
  let searchResults = $state<ChatSearchResult[]>([]);
  /** The query that produced `searchResults` — used to highlight matches. */
  let resultQuery = $state('');
  let searching = $state(false);
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let searchActive = $derived(search.trim().length > 0);

  $effect(() => {
    const q = search.trim();
    clearTimeout(searchTimer);
    if (!q) {
      searchResults = [];
      searching = false;
      return;
    }
    searching = true;
    searchTimer = setTimeout(async () => {
      try {
        searchResults = await searchChats(q);
        resultQuery = q;
      } catch (e) {
        console.warn('chat search failed', e);
        searchResults = [];
      } finally {
        searching = false;
      }
    }, 250);
  });

  /** Split `text` into runs, flagging the ones that match `q` (for <mark>). */
  function highlightParts(text: string, q: string): { t: string; hit: boolean }[] {
    if (!q) return [{ t: text, hit: false }];
    const out: { t: string; hit: boolean }[] = [];
    const low = text.toLowerCase();
    const ql = q.toLowerCase();
    let i = 0;
    while (i <= text.length) {
      const idx = low.indexOf(ql, i);
      if (idx < 0) {
        if (i < text.length) out.push({ t: text.slice(i), hit: false });
        break;
      }
      if (idx > i) out.push({ t: text.slice(i, idx), hit: false });
      out.push({ t: text.slice(idx, idx + ql.length), hit: true });
      i = idx + ql.length;
    }
    return out;
  }

  function openAt(id: string, messageId: number) {
    app.sidebarOpen = false;
    menuFor = null;
    goto(`/c/${id}?m=${messageId}`);
  }

  // ─── multi-select ──────────────────────────────────────────────────────
  let selectMode = $state(false);
  let selected = $state<Set<string>>(new Set());
  let deleting = $state(false);
  // Two-tap arm for "delete all non-pinned" (WebView-friendly, no confirm()).
  let clearArmed = $state(false);
  let clearTimer: ReturnType<typeof setTimeout> | null = null;

  function toggleSelectMode() {
    selectMode = !selectMode;
    selected = new Set();
    menuFor = null;
  }
  function toggleSelected(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    selected = next;
  }
  async function deleteSelected() {
    const ids = [...selected];
    if (ids.length === 0) return;
    if (!confirm(`Delete ${ids.length} chat${ids.length === 1 ? '' : 's'}? This cannot be undone.`)) return;
    deleting = true;
    try {
      await deleteChats(ids);
      await app.refreshChats();
      if (currentId && ids.includes(currentId)) await goto('/');
      selectMode = false;
      selected = new Set();
      toast.success(`Deleted ${ids.length} chat${ids.length === 1 ? '' : 's'}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      deleting = false;
    }
  }

  /** Pinned chats (always shown at top) — search has its own result list. */
  let pinnedChats = $derived(app.chats.filter((c) => c.is_pinned));
  let unpinnedChats = $derived(app.chats.filter((c) => !c.is_pinned));

  /** Delete every non-pinned chat. Two-tap: first tap arms ("Sure?"),
   *  second within 3s executes. Pinned chats are untouched. */
  async function deleteAllUnpinned() {
    const ids = unpinnedChats.map((c) => c.id);
    if (ids.length === 0) return;
    if (!clearArmed) {
      clearArmed = true;
      if (clearTimer) clearTimeout(clearTimer);
      clearTimer = setTimeout(() => (clearArmed = false), 3000);
      return;
    }
    if (clearTimer) clearTimeout(clearTimer);
    clearArmed = false;
    deleting = true;
    try {
      await deleteChats(ids);
      await app.refreshChats();
      if (currentId && ids.includes(currentId)) await goto('/');
      toast.success(`Deleted ${ids.length} chat${ids.length === 1 ? '' : 's'}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      deleting = false;
    }
  }

  function projectName(id: string): string {
    return app.projects.find((p) => p.id === id)?.name ?? 'Project';
  }

  /** Group unpinned chats by project (null → "Other", shown last) */
  let groupedUnpinned = $derived.by(() => {
    const groups: Record<string, Chat[]> = {};
    for (const c of app.chats) {
      if (c.is_pinned) continue;
      const p = c.project_id || '';
      if (!groups[p]) groups[p] = [];
      groups[p].push(c);
    }
    // Real projects alphabetical first, then ungrouped chats last
    const named = Object.keys(groups)
      .filter((k) => k !== '')
      .sort((a, b) => projectName(a).localeCompare(projectName(b)));
    const order: string[] = [...named, ''];
    return order
      .map((k) => ({ id: k, name: k ? projectName(k) : '', chats: groups[k] || [] }))
      .filter((g) => g.chats.length);
  });

  /** Which project sections are collapsed (default open), keyed by project id */
  let collapsed = $state<Set<string>>(new Set());

  function shortNum(n: number): string {
    if (n < 1000) return String(n);
    if (n < 1_000_000) return (n / 1000).toFixed(n < 10_000 ? 1 : 0) + 'k';
    return (n / 1_000_000).toFixed(1) + 'M';
  }

  let usagePct = $derived.by(() => {
    const n = app.neurons;
    if (!n || !n.limit) return 0;
    return Math.min(100, Math.round((n.used / n.limit) * 100));
  });
  let usageDanger = $derived(usagePct >= 90);
  let usageWarn = $derived(usagePct >= 70 && !usageDanger);
  let avatarLetter = $derived((app.me?.email ?? '?')[0].toUpperCase());

  // Sidebar usage counter mode. 'auto' follows the active provider — CF shows
  // neurons, any other provider shows tokens + $ cost; the toggle in Settings
  // can force one or the other.
  let usageMode = $derived.by(() => {
    // Pure BYO deployment (no Cloudflare) has no neurons — always tokens/cost.
    if (app.info?.cf_configured === false) return 'tokens';
    const pref = app.prefs.usage_display ?? 'auto';
    if (pref === 'neurons' || pref === 'tokens') return pref;
    return (app.usage?.active_provider ?? 'cf') === 'cf' ? 'neurons' : 'tokens';
  });
  // Tokens-mode figures: the active provider's row when it has usage today,
  // else the all-provider total. Cost is only shown when the provider reports
  // it (OpenRouter does; CF/Anthropic don't).
  let usageTokens = $derived.by(() => {
    const u = app.usage;
    if (!u) return null;
    const row = u.providers.find((p) => p.provider_id === u.active_provider);
    if (row) return { name: row.provider_name ?? 'Provider', tokens: row.tokens_in + row.tokens_out, cost: row.cost };
    return { name: 'today', tokens: u.totals.tokens, cost: u.totals.cost };
  });
  let usageBreakdown = $derived.by(() => {
    const ps = app.usage?.providers ?? [];
    if (!ps.length) return 'No token usage recorded today.';
    return 'Token usage today (per provider):\n' + ps.map((p) =>
      `${p.provider_name ?? p.provider_id}: ${shortNum(p.tokens_in + p.tokens_out)} tok`
      + (p.cost > 0 ? ` · $${p.cost.toFixed(p.cost < 0.01 ? 4 : 2)}` : '')
    ).join('\n');
  });
  function fmtCost(c: number): string {
    return c < 0.01 ? `$${c.toFixed(4)}` : `$${c.toFixed(2)}`;
  }

  // ─── chat row menu ────────────────────────────────────────────────────
  let menuFor = $state<string | null>(null);

  function openMenu(id: string, e: MouseEvent) {
    e.stopPropagation();
    menuFor = menuFor === id ? null : id;
  }

  async function togglePin(c: Chat, e: MouseEvent) {
    e.stopPropagation();
    menuFor = null;
    try {
      await setChatPinned(c.id, !c.is_pinned);
      await app.refreshChats();
    } catch (err) {
      toast.error((err as Error).message);
    }
  }

  // ─── move-to-project picker ───────────────────────────────────────────
  let movingChat = $state<Chat | null>(null);

  function openMoveTo(c: Chat, e: MouseEvent) {
    e.stopPropagation();
    menuFor = null;
    movingChat = c;
  }

  async function assignProject(projectId: string | null) {
    const c = movingChat;
    if (!c) return;
    movingChat = null;
    try {
      await setChatProject(c.id, projectId);
      await app.refreshChats();
    } catch (err) {
      toast.error((err as Error).message);
    }
  }

  // ─── project editor ───────────────────────────────────────────────────
  let projEditor = $state<{ editing: Project | null } | null>(null);
  let projName = $state('');
  let projPrompt = $state('');
  let projSaving = $state(false);

  function openNewProject() {
    projEditor = { editing: null };
    projName = '';
    projPrompt = '';
  }
  function openEditProject(p: Project) {
    projEditor = { editing: p };
    projName = p.name;
    projPrompt = p.system_prompt ?? '';
  }
  function closeProjEditor() {
    projEditor = null;
  }

  async function saveProject() {
    const name = projName.trim();
    if (!name) {
      toast.error('Project name is required');
      return;
    }
    projSaving = true;
    try {
      const prompt = projPrompt.trim() || null;
      if (projEditor?.editing) {
        await updateProject(projEditor.editing.id, { name, system_prompt: prompt });
      } else {
        await createProject(name, prompt);
      }
      await app.refreshProjects();
      projEditor = null;
    } catch (err) {
      toast.error((err as Error).message);
    } finally {
      projSaving = false;
    }
  }

  async function removeProject() {
    const p = projEditor?.editing;
    if (!p) return;
    if (!confirm(`Delete project "${p.name}"? Chats inside it are kept and become ungrouped.`)) return;
    projSaving = true;
    try {
      await deleteProject(p.id);
      await app.refreshProjects();
      await app.refreshChats();
      projEditor = null;
    } catch (err) {
      toast.error((err as Error).message);
    } finally {
      projSaving = false;
    }
  }

  async function onDelete(c: Chat, e: MouseEvent) {
    e.stopPropagation();
    menuFor = null;
    if (!confirm('Delete this chat? This cannot be undone.')) return;
    try {
      await deleteChat(c.id);
      await app.refreshChats();
      if (c.id === currentId) await goto('/');
    } catch (err) {
      toast.error((err as Error).message);
    }
  }

  function newChat() {
    app.sidebarOpen = false;
    goto('/');
  }

  // Code chat: the welcome screen reads ?code=1 and creates a code-mode chat.
  function newCodeChat() {
    app.sidebarOpen = false;
    goto('/?code=1');
  }

  function openFiles() {
    app.sidebarOpen = false;
    goto('/files');
  }

  function openTasks() {
    app.sidebarOpen = false;
    goto('/tasks');
  }


  function open(id: string) {
    if (selectMode) {
      toggleSelected(id);
      return;
    }
    if (menuFor) {
      menuFor = null;
      return;
    }
    app.sidebarOpen = false;
    goto(`/c/${id}`);
  }

  function toggleProject(id: string) {
    const next = new Set(collapsed);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    collapsed = next;
  }
</script>

<aside>
  <div class="brand">
    <span class="brand-mark" aria-hidden="true"><IconSparkles size={16} /></span>
    <span class="brand-name">TomSense</span>
  </div>

  <div class="top">
    <button class="new" onclick={newChat}>
      <IconPlus size={16} /> New chat
    </button>
    <button class="new-code" onclick={newCodeChat}>
      <IconGitBranch size={15} /> Code chat
    </button>
  </div>

  {#if app.installPrompt}
    <div class="install">
      <button class="install-btn" onclick={() => app.promptInstall()}>
        <IconShare size={14} /> Install TomSense app
      </button>
    </div>
  {/if}

  <div class="search">
    <input
      type="search"
      placeholder="Search chats &amp; messages"
      bind:value={search}
      aria-label="Search chats and messages"
    />
    {#if search}
      <button class="search-clear" aria-label="Clear search" onclick={() => (search = '')}>
        <IconX size={12} />
      </button>
    {/if}
  </div>

  <div class="select-bar">
    {#if selectMode}
      <span class="select-count">{selected.size} selected</span>
      <button
        class="select-action danger"
        disabled={selected.size === 0 || deleting}
        onclick={deleteSelected}
      >
        <IconTrash size={13} /> {deleting ? 'Deleting…' : 'Delete'}
      </button>
      <button class="select-action" onclick={toggleSelectMode}>Cancel</button>
    {:else}
      <button class="select-action subtle" onclick={toggleSelectMode}>
        <IconCheck size={13} /> Select
      </button>
      <button class="select-action subtle" onclick={openNewProject}>
        <IconLayers size={13} /> New project
      </button>
      <button class="select-action subtle" onclick={openFiles}>
        <IconFolder size={13} /> Files
      </button>
      <button class="select-action subtle" onclick={openTasks}>
        <IconRefresh size={13} /> Tasks
      </button>
      {#if unpinnedChats.length > 0}
        <button
          class="select-action subtle clear-all"
          class:armed={clearArmed}
          disabled={deleting}
          onclick={deleteAllUnpinned}
          title="Delete every non-pinned chat"
        >
          <IconTrash size={13} />
          {#if clearArmed}
            Delete {unpinnedChats.length}? Tap again
          {:else}
            Clear unpinned
          {/if}
        </button>
      {/if}
    {/if}
  </div>

  <div class="list">
    {#if searchActive}
      {#if searching && searchResults.length === 0}
        <div class="empty">Searching…</div>
      {:else if searchResults.length === 0}
        <div class="empty">No matches for "{search}".</div>
      {:else}
        {#each searchResults as r (r.id)}
          {@render searchResult(r)}
        {/each}
      {/if}
    {:else}
      {#if pinnedChats.length > 0}
        <div class="section-label">
          <IconStar size={11} stroke={2.5} /> Pinned
        </div>
        {#each pinnedChats as chat (chat.id)}
          {@render chatRow(chat)}
        {/each}
      {/if}

      {#each groupedUnpinned as group (group.id)}
        {#if group.id !== ''}
          <div class="project-head">
            <button
              class="section-toggle"
              aria-expanded={!collapsed.has(group.id)}
              onclick={() => toggleProject(group.id)}
            >
              <IconLayers size={12} />
              <span>{group.name}</span>
              <span class="count">{group.chats.length}</span>
            </button>
            {#if app.projects.some((p) => p.id === group.id)}
              <button
                class="project-edit"
                title="Edit project"
                aria-label="Edit project"
                onclick={() => openEditProject(app.projects.find((p) => p.id === group.id)!)}
              >
                <IconEdit size={12} />
              </button>
            {/if}
          </div>
        {:else if pinnedChats.length > 0 || groupedUnpinned.length > 1}
          <div class="section-label">Other</div>
        {/if}
        {#if group.id === '' || !collapsed.has(group.id)}
          {#each group.chats as chat (chat.id)}
            {@render chatRow(chat)}
          {/each}
        {/if}
      {/each}

      {#if app.chats.length === 0}
        <div class="empty">No chats yet.</div>
      {/if}
    {/if}
  </div>

  {#if app.me}
    <div class="footer">
      <div class="user">
        <div class="avatar" aria-hidden="true">{avatarLetter}</div>
        <div class="email" title={app.me.email}>{app.me.email}</div>
        <a class="icon-btn signout" href="/cdn-cgi/access/logout" title="Sign out" aria-label="Sign out">
          <IconLogOut size={14} />
        </a>
      </div>
      {#if usageMode === 'tokens'}
        <div class="usage" title={usageBreakdown}>
          <div class="usage-text">
            {shortNum(usageTokens?.tokens ?? 0)} tokens today
            {#if (usageTokens?.cost ?? 0) > 0}
              <span class="usage-dollars nonzero">· {fmtCost(usageTokens?.cost ?? 0)}</span>
            {/if}
          </div>
        </div>
      {:else if app.neurons}
        {@const d = app.neurons.dollars}
        <div
          class="usage"
          title={d
            ? `Cloudflare usage today (account-wide)\nWorkers AI overage: $${d.neurons.toFixed(2)} (free until ${shortNum(app.neurons.limit)} neurons)\nAI Gateway (Imagen / gpt-image / Nano Banana …): $${d.gateway.toFixed(2)}`
            : 'Cloudflare neurons used today (account-wide)'}
        >
          <div class="bar">
            <div
              class="fill"
              class:warn={usageWarn}
              class:danger={usageDanger}
              style:width="{usagePct}%"
            ></div>
          </div>
          <div class="usage-text">
            {shortNum(app.neurons.used)} / {shortNum(app.neurons.limit)} neurons today
            {#if d}
              <span class="usage-dollars" class:nonzero={d.total > 0}>
                · ${d.total.toFixed(2)}
              </span>
            {/if}
          </div>
        </div>
      {/if}
    </div>
  {/if}
</aside>

{#snippet chatRow(chat: Chat)}
  <div
    class="row"
    class:active={chat.id === currentId && !selectMode}
    class:selected={selectMode && selected.has(chat.id)}
    role="button"
    tabindex="0"
    onclick={() => open(chat.id)}
    onkeydown={(e) => (e.key === 'Enter' ? open(chat.id) : null)}
  >
    {#if selectMode}
      <span class="row-check" class:on={selected.has(chat.id)} aria-hidden="true">
        {#if selected.has(chat.id)}<IconCheck size={12} stroke={3} />{/if}
      </span>
    {:else if chat.is_pinned}
      <span class="row-pin" aria-hidden="true"><IconStar size={11} stroke={2.5} /></span>
    {/if}
    {#if !selectMode && chat.is_code}
      <span class="row-code" aria-hidden="true" title="Code chat">
        <IconGitBranch size={11} />
      </span>
    {/if}
    <div class="title" class:untitled={!chat.title}>
      {chat.title ?? 'New chat'}
    </div>
    {#if !selectMode}
      <button
        class="row-menu"
        title="Chat options"
        aria-label="Chat options"
        onclick={(e) => openMenu(chat.id, e)}
      >
        <IconMoreVertical size={14} />
      </button>
    {/if}
    {#if menuFor === chat.id && !selectMode}
      <div class="menu" role="menu" onclick={(e) => e.stopPropagation()}>
        <button onclick={(e) => togglePin(chat, e)} role="menuitem">
          <IconStar size={14} /> {chat.is_pinned ? 'Unpin' : 'Pin'}
        </button>
        <button onclick={(e) => openMoveTo(chat, e)} role="menuitem">
          <IconLayers size={14} /> Move to project…
        </button>
        <button class="menu-danger" onclick={(e) => onDelete(chat, e)} role="menuitem">
          <IconTrash size={14} /> Delete
        </button>
      </div>
    {/if}
  </div>
{/snippet}

{#snippet searchResult(r: ChatSearchResult)}
  <div class="result">
    <button
      class="result-title"
      class:active={r.id === currentId && !selectMode}
      onclick={() => open(r.id)}
    >
      {#if r.is_pinned}
        <span class="row-pin" aria-hidden="true"><IconStar size={11} stroke={2.5} /></span>
      {/if}
      <span class="rt-text" class:untitled={!r.title}>
        {#each highlightParts(r.title ?? 'New chat', resultQuery) as p}{#if p.hit}<mark
            >{p.t}</mark
          >{:else}{p.t}{/if}{/each}
      </span>
    </button>
    {#each r.hits as h (h.message_id)}
      <button class="result-hit" onclick={() => openAt(r.id, h.message_id)}>
        <span class="hit-role" class:user={h.role === 'user'}>
          {h.role === 'user' ? 'You' : 'TomSense'}
        </span>
        <span class="hit-snip">
          {#each highlightParts(h.snippet, resultQuery) as p}{#if p.hit}<mark>{p.t}</mark
            >{:else}{p.t}{/if}{/each}
        </span>
      </button>
    {/each}
  </div>
{/snippet}

{#if movingChat}
  <div class="modal-scrim" role="presentation" onclick={() => (movingChat = null)}>
    <div
      class="modal"
      role="dialog"
      aria-label="Move to project"
      onclick={(e) => e.stopPropagation()}
    >
      <div class="modal-head">
        <h3>Move to project</h3>
        <button class="icon-btn" aria-label="Close" onclick={() => (movingChat = null)}>
          <IconX size={14} />
        </button>
      </div>
      <div class="proj-picker">
        <button
          class="proj-opt"
          class:current={!movingChat.project_id}
          onclick={() => assignProject(null)}
        >
          No project
        </button>
        {#each app.projects as p (p.id)}
          <button
            class="proj-opt"
            class:current={movingChat.project_id === p.id}
            onclick={() => assignProject(p.id)}
          >
            <IconLayers size={13} /> {p.name}
          </button>
        {/each}
        {#if app.projects.length === 0}
          <div class="proj-empty">No projects yet.</div>
        {/if}
      </div>
      <button
        class="proj-newlink"
        onclick={() => {
          movingChat = null;
          openNewProject();
        }}
      >
        <IconPlus size={13} /> New project
      </button>
    </div>
  </div>
{/if}

{#if projEditor}
  <div class="modal-scrim" role="presentation" onclick={closeProjEditor}>
    <div
      class="modal"
      role="dialog"
      aria-label="Project editor"
      onclick={(e) => e.stopPropagation()}
    >
      <div class="modal-head">
        <h3>{projEditor.editing ? 'Edit project' : 'New project'}</h3>
        <button class="icon-btn" aria-label="Close" onclick={closeProjEditor}>
          <IconX size={14} />
        </button>
      </div>
      <label class="field">
        <span>Name</span>
        <input type="text" bind:value={projName} placeholder="Project name" />
      </label>
      <label class="field">
        <span>Shared system prompt <em>(optional)</em></span>
        <textarea
          bind:value={projPrompt}
          rows="5"
          placeholder="Prepended to the system prompt of every chat in this project."
        ></textarea>
      </label>
      <div class="modal-actions">
        {#if projEditor.editing}
          <button class="btn-danger" disabled={projSaving} onclick={removeProject}>
            <IconTrash size={13} /> Delete
          </button>
        {/if}
        <span class="spacer"></span>
        <button class="btn-ghost" disabled={projSaving} onclick={closeProjEditor}>Cancel</button>
        <button class="btn-primary" disabled={projSaving} onclick={saveProject}>
          {projSaving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  aside {
    width: 280px;
    background: var(--bg-elevated);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
    /* edge-to-edge (targetSdk 35): keep brand/footer off the system bars. */
    padding-top: env(safe-area-inset-top);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-4) var(--sp-4) var(--sp-2);
  }
  .brand-mark {
    width: 26px;
    height: 26px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, var(--accent), #d96030);
    color: var(--accent-fg);
    border-radius: var(--r-md);
    flex-shrink: 0;
  }
  .brand-name {
    font-weight: 700;
    font-size: var(--fs-lg);
    letter-spacing: -0.01em;
    color: var(--text-strong);
  }
  .top {
    padding: var(--sp-2) var(--sp-3);
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .new {
    width: 100%;
    background: var(--accent);
    color: var(--accent-fg);
    border: 0;
    padding: 10px 14px;
    border-radius: var(--r-md);
    font-weight: 600;
    font-size: var(--fs-md);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--sp-2);
    transition: background var(--t-fast);
  }
  .new:hover {
    background: var(--accent-hover);
  }
  .new-code {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border-strong);
    padding: 8px 14px;
    border-radius: var(--r-md);
    font-weight: 600;
    font-size: var(--fs-sm);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--sp-2);
    cursor: pointer;
    transition: background var(--t-fast), border-color var(--t-fast);
  }
  .new-code:hover {
    background: var(--row-hover);
    border-color: var(--accent);
  }
  .install {
    padding: 0 var(--sp-3) var(--sp-2);
  }
  .install-btn {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border-strong);
    border-radius: var(--r-md);
    padding: 8px 12px;
    font-size: var(--fs-sm);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
  }
  .install-btn:hover {
    background: var(--row-hover);
  }
  .search {
    position: relative;
    padding: 0 var(--sp-3) var(--sp-2);
  }
  .search input {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    padding: 7px 28px 7px 12px;
    font: inherit;
    font-size: var(--fs-sm);
    -webkit-appearance: none;
    appearance: none;
  }
  .search input:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }
  .search input::-webkit-search-cancel-button {
    display: none;
  }
  .search-clear {
    position: absolute;
    right: 18px;
    top: 50%;
    transform: translateY(-50%);
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: 4px;
    border-radius: var(--r-sm);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
  }
  .select-bar {
    display: flex;
    align-items: center;
    flex-wrap: wrap; /* 4 action chips must never overflow the sidebar */
    gap: var(--sp-2);
    padding: 0 var(--sp-3) var(--sp-2);
    min-height: 26px;
  }
  .select-count {
    font-size: var(--fs-xs);
    color: var(--muted-strong);
    font-weight: 600;
    margin-right: auto;
  }
  .select-action {
    background: transparent;
    border: 0;
    color: var(--accent);
    font-size: var(--fs-xs);
    font-weight: 600;
    padding: 4px 8px;
    border-radius: var(--r-sm);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
  .select-action.subtle {
    color: var(--muted);
    margin-left: auto;
  }
  .select-action:hover:not(:disabled) {
    background: var(--row-hover);
  }
  .select-action.danger {
    color: var(--danger);
  }
  .select-action:disabled {
    color: var(--muted);
    cursor: not-allowed;
  }
  .list {
    flex: 1;
    overflow-y: auto;
    padding: var(--sp-2);
    overflow-x: visible;
  }
  .row-check {
    width: 16px;
    height: 16px;
    border: 1.5px solid var(--border-strong);
    border-radius: 4px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: var(--accent-fg);
  }
  .row-check.on {
    background: var(--accent);
    border-color: var(--accent);
  }
  .row.selected {
    background: rgba(255, 138, 76, 0.12);
  }
  .section-label,
  .section-toggle {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px 4px;
    color: var(--muted);
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    background: transparent;
    border: 0;
    width: 100%;
    text-align: left;
    cursor: default;
  }
  .section-toggle {
    cursor: pointer;
  }
  .section-toggle:hover {
    color: var(--text-strong);
  }
  .section-toggle .count {
    margin-left: auto;
    background: var(--panel);
    color: var(--muted-strong);
    border-radius: 999px;
    padding: 1px 6px;
    font-size: 9px;
    font-weight: 700;
  }
  .row {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--sp-1);
    padding: 8px 10px;
    border-radius: var(--r-md);
    font-size: var(--fs-md);
    user-select: none;
    cursor: pointer;
    color: var(--text);
    transition: background var(--t-fast);
  }
  .row:hover {
    background: var(--row-hover);
  }
  .row.active {
    background: var(--row-active);
  }
  .row-pin {
    color: var(--accent);
    flex-shrink: 0;
    margin-right: 2px;
  }
  .row-code {
    color: var(--muted-strong);
    flex-shrink: 0;
    display: inline-flex;
    margin-right: 2px;
  }
  .title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .title.untitled {
    color: var(--muted);
    font-style: italic;
  }
  .row-menu {
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: 4px 6px;
    border-radius: var(--r-sm);
    opacity: 0;
    cursor: pointer;
    transition: opacity var(--t-fast), color var(--t-fast), background var(--t-fast);
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .row:hover .row-menu,
  .row:focus-within .row-menu {
    opacity: 1;
  }
  .row-menu:hover {
    color: var(--text);
    background: var(--panel-2);
  }
  .menu {
    position: absolute;
    right: 8px;
    top: 100%;
    margin-top: 4px;
    background: var(--panel-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--r-md);
    box-shadow: var(--shadow-md);
    z-index: 50;
    min-width: 180px;
    padding: 4px;
  }
  .menu button {
    width: 100%;
    background: transparent;
    border: 0;
    color: var(--text);
    text-align: left;
    padding: 7px 10px;
    border-radius: var(--r-sm);
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .menu button:hover {
    background: var(--row-hover);
  }
  .menu .menu-danger:hover {
    color: var(--danger);
  }
  .empty {
    color: var(--muted);
    padding: var(--sp-3) var(--sp-2);
    font-size: var(--fs-sm);
    text-align: center;
  }
  /* Destructive chip in the toolbar row — subtle until hovered/armed so it
     reads as secondary to Select/Files/etc, then goes danger-red. */
  .select-action.clear-all:hover:not(:disabled) {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 8%, transparent);
  }
  .select-action.clear-all.armed {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }

  /* ─── search results (chat title + matching message snippets) ───────── */
  .result {
    margin-bottom: 6px;
  }
  .result-title {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 4px;
    background: transparent;
    border: 0;
    color: var(--text);
    text-align: left;
    padding: 7px 10px;
    border-radius: var(--r-md);
    font-size: var(--fs-md);
    font-weight: 600;
    cursor: pointer;
  }
  .result-title:hover {
    background: var(--row-hover);
  }
  .result-title.active {
    background: var(--row-active);
  }
  .rt-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .rt-text.untitled {
    color: var(--muted);
    font-style: italic;
  }
  .result-hit {
    width: calc(100% - 14px);
    margin-left: 14px;
    display: flex;
    flex-direction: column;
    gap: 1px;
    background: transparent;
    border: 0;
    border-left: 2px solid var(--border-strong);
    border-radius: 0 var(--r-sm) var(--r-sm) 0;
    padding: 5px 9px;
    text-align: left;
    cursor: pointer;
    transition: background var(--t-fast), border-color var(--t-fast);
  }
  .result-hit:hover {
    background: var(--row-hover);
    border-left-color: var(--accent);
  }
  .hit-role {
    font-size: 9px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--muted);
  }
  .hit-role.user {
    color: var(--accent);
  }
  .hit-snip {
    font-size: var(--fs-xs);
    color: var(--muted-strong);
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .result mark {
    background: rgba(255, 138, 76, 0.28);
    color: var(--text-strong);
    border-radius: 2px;
    padding: 0 1px;
  }
  .footer {
    border-top: 1px solid var(--border);
    padding: var(--sp-3);
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .user {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
  }
  .avatar {
    width: 30px;
    height: 30px;
    border-radius: var(--r-full);
    background: linear-gradient(135deg, var(--accent), #d96030);
    color: var(--accent-fg);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    font-size: var(--fs-md);
    flex-shrink: 0;
  }
  .email {
    flex: 1;
    font-size: var(--fs-sm);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text);
  }
  .signout {
    width: 28px;
    height: 28px;
    padding: 0;
  }
  .signout:hover {
    color: var(--danger);
  }
  .usage {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .bar {
    height: 4px;
    background: var(--panel-2);
    border-radius: 2px;
    overflow: hidden;
  }
  .fill {
    height: 100%;
    background: var(--accent);
    transition: width 0.3s ease;
  }
  .fill.warn { background: var(--warn); }
  .fill.danger { background: var(--danger); }
  .usage-text {
    font-size: var(--fs-xs);
    color: var(--muted);
  }
  .usage-dollars.nonzero {
    color: var(--warn);
    font-weight: 600;
  }

  /* ─── projects ──────────────────────────────────────────────────────── */
  .project-head {
    display: flex;
    align-items: center;
  }
  .project-head .section-toggle {
    width: auto;
    flex: 1;
    min-width: 0;
  }
  .project-head .section-toggle span:not(.count) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .project-edit {
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: 4px 8px 0;
    cursor: pointer;
    opacity: 0;
    transition: opacity var(--t-fast), color var(--t-fast);
    display: inline-flex;
    align-items: center;
  }
  .project-head:hover .project-edit {
    opacity: 1;
  }
  .project-edit:hover {
    color: var(--text-strong);
  }

  /* ─── modal (move-to-project + project editor) ──────────────────────── */
  .modal-scrim {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 200;
    padding: var(--sp-4);
  }
  .modal {
    background: var(--bg-elevated);
    border: 1px solid var(--border-strong);
    border-radius: var(--r-lg);
    box-shadow: var(--shadow-md);
    width: 100%;
    max-width: 380px;
    max-height: 80vh;
    overflow-y: auto;
    padding: var(--sp-4);
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .modal-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .modal-head h3 {
    margin: 0;
    font-size: var(--fs-md);
    font-weight: 700;
    color: var(--text-strong);
  }
  .proj-picker {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .proj-opt {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    text-align: left;
    background: transparent;
    border: 0;
    color: var(--text);
    padding: 9px 10px;
    border-radius: var(--r-md);
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .proj-opt:hover {
    background: var(--row-hover);
  }
  .proj-opt.current {
    background: rgba(255, 138, 76, 0.12);
    color: var(--accent);
    font-weight: 600;
  }
  .proj-empty {
    color: var(--muted);
    font-size: var(--fs-sm);
    padding: 8px 10px;
  }
  .proj-newlink {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: transparent;
    border: 1px dashed var(--border-strong);
    color: var(--muted-strong);
    padding: 8px 10px;
    border-radius: var(--r-md);
    font-size: var(--fs-sm);
    font-weight: 600;
    cursor: pointer;
  }
  .proj-newlink:hover {
    color: var(--accent);
    border-color: var(--accent);
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }
  .field span {
    font-size: var(--fs-xs);
    font-weight: 600;
    color: var(--muted-strong);
  }
  .field span em {
    font-weight: 400;
    font-style: normal;
    color: var(--muted);
  }
  .field input,
  .field textarea {
    width: 100%;
    background: var(--panel);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    padding: 8px 10px;
    font: inherit;
    font-size: var(--fs-sm);
    resize: vertical;
  }
  .field input:focus,
  .field textarea:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }
  .modal-actions {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
  }
  .modal-actions .spacer {
    flex: 1;
  }
  .btn-primary,
  .btn-ghost,
  .btn-danger {
    border-radius: var(--r-md);
    padding: 8px 14px;
    font-size: var(--fs-sm);
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .btn-primary {
    background: var(--accent);
    color: var(--accent-fg);
    border: 0;
  }
  .btn-primary:hover:not(:disabled) {
    background: var(--accent-hover);
  }
  .btn-ghost {
    background: transparent;
    color: var(--text);
    border: 1px solid var(--border-strong);
  }
  .btn-ghost:hover:not(:disabled) {
    background: var(--row-hover);
  }
  .btn-danger {
    background: transparent;
    color: var(--danger);
    border: 1px solid var(--border-strong);
  }
  .btn-danger:hover:not(:disabled) {
    background: rgba(220, 80, 80, 0.12);
  }
  .btn-primary:disabled,
  .btn-ghost:disabled,
  .btn-danger:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  @media (max-width: 768px) {
    .row-menu { opacity: 1; }
    .project-edit { opacity: 1; }
  }
</style>
