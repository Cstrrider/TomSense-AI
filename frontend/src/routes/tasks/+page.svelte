<script lang="ts">
  // Scheduled prompts — server-side "run this every morning at 07:00".
  // Results land in the chat list as fresh chats titled "⏰ <task> — <date>".
  import { onMount } from 'svelte';
  import AppHeader from '$lib/components/AppHeader.svelte';
  import {
    listSchedules,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    type Schedule,
  } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import { syncScheduleNotifications } from '$lib/notifications';
  import { IconPlus, IconTrash, IconCheck, IconX } from '$lib/icons';

  let schedules = $state<Schedule[]>([]);
  let tz = $state('UTC');
  let loading = $state(true);
  let showForm = $state(false);
  let saving = $state(false);

  // Form state
  let fTitle = $state('');
  let fPrompt = $state('');
  let fTime = $state('07:00');
  let fDays = $state<boolean[]>([true, true, true, true, true, true, true]); // Mon..Sun

  const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  function daysToMask(days: boolean[]): number {
    return days.reduce((m, on, i) => (on ? m | (1 << i) : m), 0);
  }
  function maskToLabel(mask: number): string {
    if (mask === 127) return 'every day';
    if (mask === 31) return 'weekdays';
    if (mask === 96) return 'weekends';
    return DAY_LABELS.filter((_, i) => mask & (1 << i)).join(' ');
  }

  async function refresh() {
    try {
      const r = await listSchedules();
      schedules = r.schedules;
      tz = r.tz;
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      loading = false;
    }
  }

  async function submit() {
    const mask = daysToMask(fDays);
    if (!fTitle.trim() || !fPrompt.trim() || mask === 0) {
      toast.error('Title, prompt and at least one day are required');
      return;
    }
    saving = true;
    try {
      await createSchedule({
        title: fTitle.trim(),
        prompt: fPrompt.trim(),
        run_at: fTime,
        weekdays: mask,
      });
      fTitle = '';
      fPrompt = '';
      showForm = false;
      await refresh();
      void syncScheduleNotifications();
      toast.success('Scheduled');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      saving = false;
    }
  }

  async function toggle(s: Schedule) {
    try {
      const updated = await updateSchedule(s.id, { enabled: !s.enabled });
      schedules = schedules.map((x) => (x.id === s.id ? updated : x));
      void syncScheduleNotifications();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function remove(s: Schedule) {
    if (!confirm(`Delete "${s.title}"?`)) return;
    try {
      await deleteSchedule(s.id);
      schedules = schedules.filter((x) => x.id !== s.id);
      void syncScheduleNotifications();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  onMount(refresh);
</script>

<AppHeader title="Scheduled tasks" />

<div class="page">
  <p class="hint">
    Prompts that run automatically — each run creates a new chat in the
    sidebar. Times are in <code>{tz}</code>.
  </p>

  {#if loading}
    <div class="empty">Loading…</div>
  {:else if schedules.length === 0 && !showForm}
    <div class="empty">No scheduled tasks yet.</div>
  {/if}

  {#each schedules as s (s.id)}
    <div class="card" class:disabled={!s.enabled}>
      <div class="row">
        <div class="meta">
          <div class="title">{s.title}</div>
          <div class="sub">
            {s.run_at} · {maskToLabel(s.weekdays)}
            {#if s.last_run_date}· last ran {s.last_run_date}{/if}
          </div>
          <div class="prompt">{s.prompt}</div>
        </div>
        <div class="actions">
          <button
            class="chip"
            class:on={s.enabled}
            onclick={() => toggle(s)}
            title={s.enabled ? 'Disable' : 'Enable'}
          >
            {#if s.enabled}<IconCheck size={13} /> On{:else}<IconX size={13} /> Off{/if}
          </button>
          <button class="chip danger" onclick={() => remove(s)} title="Delete">
            <IconTrash size={13} />
          </button>
        </div>
      </div>
    </div>
  {/each}

  {#if showForm}
    <div class="card form">
      <input placeholder="Title (e.g. Morning brief)" bind:value={fTitle} maxlength="120" />
      <textarea
        placeholder="Prompt (e.g. Search for today's top tech news and summarize the 5 most important stories.)"
        bind:value={fPrompt}
        rows="4"
        maxlength="4000"
      ></textarea>
      <div class="form-row">
        <input type="time" bind:value={fTime} />
        <div class="days">
          {#each DAY_LABELS as d, i}
            <button
              class="chip day"
              class:on={fDays[i]}
              onclick={() => (fDays[i] = !fDays[i])}
            >
              {d}
            </button>
          {/each}
        </div>
      </div>
      <div class="form-actions">
        <button class="chip" onclick={() => (showForm = false)}>Cancel</button>
        <button class="chip primary" onclick={submit} disabled={saving}>
          {saving ? 'Saving…' : 'Create'}
        </button>
      </div>
    </div>
  {:else}
    <button class="chip primary add" onclick={() => (showForm = true)}>
      <IconPlus size={14} /> New scheduled task
    </button>
  {/if}
</div>

<style>
  .page {
    max-width: 720px;
    margin: 0 auto;
    padding: var(--sp-4);
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .hint {
    color: var(--muted);
    font-size: var(--fs-sm);
    margin: 0;
  }
  .empty {
    color: var(--muted);
    text-align: center;
    padding: var(--sp-6) 0;
  }
  .card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-4);
    padding: var(--sp-3);
  }
  .card.disabled { opacity: 0.55; }
  .row { display: flex; justify-content: space-between; gap: var(--sp-3); }
  .meta { min-width: 0; }
  .title { font-weight: 600; color: var(--text-strong); }
  .sub { color: var(--muted); font-size: var(--fs-xs); margin: 2px 0 6px; }
  .prompt {
    color: var(--muted-strong);
    font-size: var(--fs-sm);
    white-space: pre-wrap;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .actions { display: flex; gap: var(--sp-1); align-items: flex-start; flex-shrink: 0; }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 5px 10px;
    border-radius: var(--r-pill);
    border: 1px solid var(--border);
    background: transparent;
    color: var(--text);
    font-size: var(--fs-xs);
    cursor: pointer;
    transition: border-color var(--t-fast), background var(--t-fast);
  }
  .chip:hover:not(:disabled) { background: var(--row-hover); }
  .chip.on { border-color: var(--accent); color: var(--accent); }
  .chip.primary {
    background: var(--accent);
    color: var(--accent-fg);
    border: none;
    font-weight: 600;
  }
  .chip.primary:hover:not(:disabled) { background: var(--accent-hover); }
  .chip.danger:hover { border-color: var(--danger, #e05555); color: var(--danger, #e05555); }
  .chip.day { padding: 4px 8px; }
  .chip.add { align-self: flex-start; }
  .form { display: flex; flex-direction: column; gap: var(--sp-2); }
  .form input:not([type='time']),
  .form textarea {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    padding: 8px 10px;
    color: var(--text);
    font: inherit;
    width: 100%;
    box-sizing: border-box;
  }
  .form input[type='time'] {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--r-2);
    padding: 6px 8px;
    color: var(--text);
  }
  .form-row { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; }
  .days { display: flex; gap: 4px; flex-wrap: wrap; }
  .form-actions { display: flex; gap: var(--sp-2); justify-content: flex-end; }
</style>
