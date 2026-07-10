<script lang="ts">
  import { IconBrain, IconTrash } from '$lib/icons';
  import { S, onAddMemory, onDeleteMemory } from './state.svelte';
  import SchemaFields from './SchemaFields.svelte';
</script>

<p class="muted">
  Facts the model has saved about you (across all chats). Add or
  remove them by hand here, or say "remember that I…" in any chat.
</p>
<SchemaFields section="memory" />
{#if S.memoriesLoading && S.memories.length === 0}
  <p class="muted">Loading…</p>
{:else if S.memories.length === 0}
  <div class="empty">
    <IconBrain size={36} />
    <p>No memories yet.</p>
  </div>
{:else}
  <ul class="rows">
    {#each S.memories as m (m.id)}
      <li>
        <span class="row-content">{m.content}</span>
        <button
          class="icon-btn danger"
          aria-label="Forget"
          title="Forget"
          onclick={() => onDeleteMemory(m.id)}
        >
          <IconTrash size={14} />
        </button>
      </li>
    {/each}
  </ul>
{/if}
<div class="add-row">
  <input
    placeholder="Add a fact about yourself…"
    bind:value={S.newMemory}
    onkeydown={(e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onAddMemory();
      }
    }}
  />
  <button class="primary" onclick={onAddMemory} disabled={S.addingMemory || !S.newMemory.trim()}>
    Add
  </button>
</div>
