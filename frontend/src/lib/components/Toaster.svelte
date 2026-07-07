<script lang="ts">
  import { toast } from '$lib/toast.svelte';
  import { IconCheck, IconX } from '$lib/icons';
</script>

<div class="toaster" aria-live="polite" aria-atomic="false">
  {#each toast.items as t (t.id)}
    <div class="toast" class:success={t.kind === 'success'} class:error={t.kind === 'error'}>
      <span class="dot" aria-hidden="true">
        {#if t.kind === 'success'}<IconCheck size={14} stroke={2.5} />{:else if t.kind === 'error'}<IconX size={14} stroke={2.5} />{:else}·{/if}
      </span>
      <span class="msg">{t.message}</span>
      <button class="close" aria-label="Dismiss" onclick={() => toast.dismiss(t.id)}>
        <IconX size={14} />
      </button>
    </div>
  {/each}
</div>

<style>
  .toaster {
    position: fixed;
    right: var(--sp-4);
    bottom: var(--sp-4);
    z-index: 200;
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    pointer-events: none;
    max-width: calc(100vw - var(--sp-7));
  }
  .toast {
    pointer-events: auto;
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    background: var(--panel);
    border: 1px solid var(--border-strong);
    box-shadow: var(--shadow-lg);
    border-radius: var(--r-4);
    padding: 10px 12px 10px 14px;
    min-width: 240px;
    max-width: 380px;
    color: var(--text);
    font-size: var(--fs-md);
    animation: toast-in 0.18s var(--ease) forwards;
  }
  .toast.success {
    border-left: 3px solid var(--success);
  }
  .toast.error {
    border-left: 3px solid var(--danger);
  }
  .dot {
    width: 22px;
    height: 22px;
    border-radius: var(--r-pill);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    background: var(--panel-2);
    color: var(--muted-strong);
  }
  .toast.success .dot {
    background: rgba(74, 222, 128, 0.15);
    color: var(--success);
  }
  .toast.error .dot {
    background: rgba(248, 113, 113, 0.15);
    color: var(--danger);
  }
  .msg {
    flex: 1;
    line-height: var(--lh-tight);
  }
  .close {
    background: transparent;
    border: 0;
    color: var(--muted);
    padding: var(--sp-1);
    border-radius: var(--r-2);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }
  .close:hover {
    color: var(--text);
    background: var(--panel-2);
  }
  @keyframes toast-in {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  @media (max-width: 480px) {
    .toaster {
      right: var(--sp-3);
      bottom: var(--sp-3);
      left: var(--sp-3);
    }
    .toast {
      width: 100%;
      max-width: none;
      min-width: 0;
    }
  }
</style>
