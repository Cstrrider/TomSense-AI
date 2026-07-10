<script lang="ts">
  /**
   * Generic prefs renderer (P5) — renders every ui:"auto" field the backend
   * declares for a section (app.info.prefs_schema, from prefs_registry.py).
   * Adding a simple pref = one backend registry entry; it appears here with
   * no frontend change and can never drift from the server whitelist.
   */
  import { app } from '$lib/stores.svelte';
  import type { PrefField, UserPrefs } from '$lib/types';
  import { S, savedPref } from './state.svelte';

  let { section }: { section: string } = $props();

  let fields = $derived(
    (app.info?.prefs_schema ?? []).filter((f) => f.ui === 'auto' && f.section === section)
  );

  function current(f: PrefField): unknown {
    const v = (S.prefs as Record<string, unknown>)[f.key];
    return v === undefined || v === null ? f.default : v;
  }

  function saveField(f: PrefField, value: unknown, okMsg?: string) {
    void savedPref({ [f.key]: value } as UserPrefs, okMsg ?? `${f.label ?? f.key} saved`);
  }

  function onIntChange(f: PrefField, raw: string) {
    if (raw.trim() === '') {
      saveField(f, null);
      return;
    }
    const n = parseInt(raw, 10);
    if (!Number.isFinite(n)) return;
    const clamped = Math.max(f.min ?? -Infinity, Math.min(n, f.max ?? Infinity));
    saveField(f, clamped);
  }
</script>

{#each fields as f (f.key)}
  {#if f.type === 'bool'}
    {@const on = !!current(f)}
    <div class="switch-row">
      <span class="switch-text">
        <span class="switch-label">{f.label}</span>
        {#if f.hint}<span class="switch-hint">{f.hint}</span>{/if}
      </span>
      <button
        class="switch"
        class:on
        role="switch"
        aria-checked={on}
        aria-label={f.label}
        disabled={S.prefsSaving}
        onclick={() => saveField(f, !on, `${f.label} ${!on ? 'on' : 'off'}`)}
      >
        <span class="knob"></span>
      </button>
    </div>
  {:else if f.type === 'int'}
    <div class="switch-row">
      <span class="switch-text">
        <span class="switch-label">{f.label}</span>
        {#if f.hint}<span class="switch-hint">{f.hint}</span>{/if}
      </span>
      <input
        class="num-input"
        type="number"
        min={f.min}
        max={f.max}
        step={f.step ?? 1}
        placeholder={f.placeholder ?? ''}
        value={(S.prefs as Record<string, unknown>)[f.key] ?? ''}
        disabled={S.prefsSaving}
        onchange={(e) => onIntChange(f, (e.currentTarget as HTMLInputElement).value)}
      />
    </div>
  {:else if f.type === 'enum'}
    <div class="tool-row schema-enum">
      <div class="tool-meta">
        <div class="tool-label">{f.label}</div>
        {#if f.hint}<div class="tool-hint">{f.hint}</div>{/if}
      </div>
      <select
        class="model-select compact"
        value={current(f)}
        disabled={S.prefsSaving}
        onchange={(e) => saveField(f, (e.currentTarget as HTMLSelectElement).value)}
      >
        {#each f.options ?? [] as o (o.value)}
          <option value={o.value}>{o.label}</option>
        {/each}
      </select>
    </div>
  {/if}
{/each}
