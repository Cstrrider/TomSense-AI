<script lang="ts">
  import { IconKey, IconTrash } from '$lib/icons';
  import { S, onAddSecret, onDeleteSecret } from './state.svelte';
</script>

<p class="muted">
  Encrypted secrets the code-mode agent can <strong>use</strong> (API
  tokens, keys) without ever seeing or printing the value. They're
  injected into its sandbox shell as <code>$NAME</code> at runtime and
  redacted from any output. Stored encrypted; values are write-only —
  you can replace one but never read it back.
</p>
{#if S.secretsLoading && S.secrets.length === 0}
  <p class="muted">Loading…</p>
{:else if S.secrets.length === 0}
  <div class="empty">
    <IconKey size={36} />
    <p>No secrets yet.</p>
  </div>
{:else}
  <ul class="rows">
    {#each S.secrets as s (s.name)}
      <li>
        <span class="row-content"><code>${s.name}</code></span>
        <button
          class="icon-btn danger"
          aria-label="Delete secret"
          title="Delete"
          onclick={() => onDeleteSecret(s.name)}
        >
          <IconTrash size={14} />
        </button>
      </li>
    {/each}
  </ul>
{/if}
<div class="add-row" style="flex-wrap: wrap;">
  <input
    style="flex: 1 1 130px; text-transform: uppercase;"
    placeholder="NAME (e.g. CLOUDFLARE_API_TOKEN)"
    bind:value={S.newSecretName}
  />
  <input
    style="flex: 2 1 180px;"
    type="password"
    autocomplete="off"
    placeholder="value (hidden, write-only)"
    bind:value={S.newSecretValue}
    onkeydown={(e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onAddSecret();
      }
    }}
  />
  <button class="primary" onclick={onAddSecret} disabled={S.savingSecret || !S.newSecretName.trim() || !S.newSecretValue}>
    Save
  </button>
</div>
