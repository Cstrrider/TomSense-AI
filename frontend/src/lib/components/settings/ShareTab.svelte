<script lang="ts">
  import { IconCopy, IconLink } from '$lib/icons';
  import { S, shareUrl, generateShare, copyShareUrl, revoke } from './state.svelte';
</script>

<p class="muted">
  Generate a public read-only URL. Anyone with the link can read
  the chat (no composer). Revoke to invalidate.
</p>
{#if S.shareToken}
  <div class="share-row">
    <input readonly value={shareUrl()} onclick={(e) => (e.currentTarget as HTMLInputElement).select()} />
    <button class="primary inline" onclick={copyShareUrl}>
      <IconCopy size={14} /> Copy
    </button>
  </div>
  <button class="danger-link" onclick={revoke} disabled={S.saving}>
    Revoke share link
  </button>
{:else}
  <button class="primary" onclick={generateShare} disabled={S.saving}>
    <IconLink size={14} /> {S.saving ? 'Creating…' : 'Create share link'}
  </button>
{/if}
