<script lang="ts">
  import { confirmQueue } from '$lib/confirm.svelte';

  let dialogEl: HTMLDivElement | undefined = $state();
  let confirmBtn: HTMLButtonElement | undefined = $state();

  const pending = $derived(confirmQueue.current);

  // Focus the confirm button when a dialog opens so Enter/Escape work without
  // a tap first (parity with the native confirm() this replaces).
  $effect(() => {
    if (pending) confirmBtn?.focus();
  });

  function onKeydown(e: KeyboardEvent) {
    if (!pending) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      confirmQueue.answer(false);
    }
  }
</script>

<svelte:window onkeydown={onKeydown} />

{#if pending}
  <div class="layer">
    <!-- Scrim as a real <button> (same pattern as the sidebar overlay) so
         tap-outside-to-dismiss is keyboard-reachable, and the dialog itself
         needs no click handler to stop propagation. Escape is on window. -->
    <button class="scrim" aria-label="Cancel" onclick={() => confirmQueue.answer(false)}
    ></button>
    <div
      class="dialog"
      role="alertdialog"
      aria-modal="true"
      aria-label={pending.message}
      tabindex="-1"
      bind:this={dialogEl}
    >
      <p class="message">{pending.message}</p>
      {#if pending.detail}
        <p class="detail">{pending.detail}</p>
      {/if}
      <div class="actions">
        <button class="cancel" onclick={() => confirmQueue.answer(false)}>
          {pending.cancelLabel ?? 'Cancel'}
        </button>
        <button
          class="confirm"
          class:danger={pending.danger}
          bind:this={confirmBtn}
          onclick={() => confirmQueue.answer(true)}
        >
          {pending.confirmLabel ?? 'Confirm'}
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .layer {
    position: fixed;
    inset: 0;
    z-index: 300; /* above the toaster (200) — it's modal */
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--sp-4);
  }
  .scrim {
    position: absolute;
    inset: 0;
    border: 0;
    padding: 0;
    background: rgba(0, 0, 0, 0.55);
    animation: fade-in 0.12s var(--ease) forwards;
  }
  .dialog {
    position: relative; /* above the scrim within .layer */
    background: var(--panel);
    border: 1px solid var(--border-strong);
    box-shadow: var(--shadow-lg);
    border-radius: var(--r-4);
    padding: var(--sp-5);
    width: 100%;
    max-width: 380px;
    color: var(--text);
    animation: dialog-in 0.16s var(--ease) forwards;
  }
  .message {
    margin: 0;
    font-size: var(--fs-md);
    line-height: var(--lh-tight);
    white-space: pre-line;
  }
  .detail {
    margin: var(--sp-2) 0 0;
    font-size: var(--fs-sm);
    color: var(--muted);
    line-height: var(--lh-tight);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--sp-2);
    margin-top: var(--sp-5);
  }
  .actions button {
    border-radius: var(--r-3);
    padding: 8px 14px;
    font-size: var(--fs-md);
    border: 1px solid var(--border-strong);
    background: var(--panel-2);
    color: var(--text);
  }
  .actions .confirm {
    border-color: transparent;
    background: var(--accent, var(--panel-2));
    color: #fff;
  }
  .actions .confirm.danger {
    background: var(--danger);
    color: #fff;
  }
  .actions button:hover {
    filter: brightness(1.1);
  }
  @keyframes fade-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }
  @keyframes dialog-in {
    from {
      opacity: 0;
      transform: translateY(8px) scale(0.98);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }
</style>
