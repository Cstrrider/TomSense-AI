<script lang="ts">
  import { IconCheck, IconEdit, IconTrash } from '$lib/icons';
  import {
    S,
    armed,
    confirmTap,
    dirty,
    savePersona,
    startCreatePersona,
    startEditPersona,
    cancelPersonaEdit,
    savePersonaForm,
    deletePersonaConfirm,
    applyPersonaToCurrentChat
  } from './state.svelte';
</script>

<p class="muted">
  Added to this chat's system prompt. Use it to set tone, role, or
  domain. The default tool-routing rules stay intact.
</p>
<textarea
  bind:value={S.prompt}
  rows="8"
  placeholder="e.g. You are a no-nonsense Go reviewer. Be terse, point out idiomatic alternatives, and never apologize."
></textarea>
<div class="actions">
  <button class="primary" disabled={S.saving || !dirty()} onclick={savePersona}>
    {S.saving ? 'Saving…' : dirty() ? 'Save' : 'Saved'}
  </button>
</div>

<h3 style="margin-top: var(--sp-3);">
  Saved personas
  {#if S.personas.length}<span class="badge">{S.personas.length}</span>{/if}
</h3>
<p class="muted">
  Reusable custom-instructions + model presets. Create one, then tap
  Apply to set it on this chat (or pick one before sending your first
  message on a new chat).
</p>
{#if S.newPersonaOpen}
  <div class="persona-form">
    <input
      placeholder="Persona name (e.g. Go reviewer)"
      bind:value={S.personaForm.name}
      class="model-select"
    />
    <textarea
      rows="5"
      placeholder="System prompt — describe how the model should behave."
      bind:value={S.personaForm.system_prompt}
    ></textarea>
    <input
      placeholder="Model (optional, e.g. cf::@cf/google/gemma-4-26b-a4b-it)"
      bind:value={S.personaForm.model}
      class="model-select"
    />
    <div class="actions">
      <button class="btn-text" onclick={cancelPersonaEdit}>Cancel</button>
      <button class="primary" onclick={savePersonaForm} disabled={!S.personaForm.name.trim()}>
        {S.editingPersona ? 'Save' : 'Create'}
      </button>
    </div>
  </div>
{:else}
  <button class="btn-tonal" onclick={startCreatePersona}>+ New persona</button>
  {#if S.personasLoading && S.personas.length === 0}
    <p class="muted">Loading…</p>
  {:else if S.personas.length === 0}
    <div class="empty">
      <IconEdit size={36} />
      <p>No personas yet. Save your favorite custom-instructions presets here.</p>
    </div>
  {:else}
    <ul class="rows">
      {#each S.personas as p (p.id)}
        <li>
          <div class="row-meta">
            <div class="row-title">{p.name}</div>
            <div class="row-sub">
              {(p.system_prompt ?? '').slice(0, 60)}{(p.system_prompt ?? '').length > 60 ? '…' : ''}
            </div>
          </div>
          <div class="row-actions">
            {#if S.chatId}
              <button
                class="icon-btn"
                title="Apply to this chat"
                aria-label="Apply"
                onclick={() => applyPersonaToCurrentChat(p)}
              ><IconCheck size={14} /></button>
            {/if}
            <button class="icon-btn" title="Edit" aria-label="Edit" onclick={() => startEditPersona(p)}>
              <IconEdit size={14} />
            </button>
            <button
              class="icon-btn danger"
              class:armed={armed(`persona-${p.id}`)}
              title="Delete"
              aria-label="Delete"
              onclick={() => confirmTap(`persona-${p.id}`, () => void deletePersonaConfirm(p))}
            >{#if armed(`persona-${p.id}`)}Sure?{:else}<IconTrash size={14} />{/if}</button>
          </div>
        </li>
      {/each}
    </ul>
  {/if}
{/if}
