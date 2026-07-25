/**
 * Promise-based confirmation dialog.
 *
 * Replaces `window.confirm()`. In the Capacitor WebView a native confirm()
 * renders as an unstyled system dialog, and under some WebView configurations
 * it is suppressed entirely and returns false — which silently turned every
 * "Delete?" into a no-op. This renders in-app, so it always works and always
 * looks like the rest of the UI.
 *
 * Usage:
 *   if (!(await confirmDialog({ message: 'Delete this chat?', danger: true }))) return;
 */

export interface ConfirmOptions {
  message: string;
  /** Optional second line, for consequences ("This cannot be undone"). */
  detail?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Style the confirm button as destructive. */
  danger?: boolean;
}

interface PendingConfirm extends ConfirmOptions {
  id: number;
  resolve: (ok: boolean) => void;
}

let nextId = 1;

class ConfirmQueue {
  /** The dialog currently on screen, if any. One at a time. */
  current = $state<PendingConfirm | null>(null);

  ask(opts: ConfirmOptions): Promise<boolean> {
    // A second request while one is open resolves the first as cancelled
    // rather than stacking modals.
    this.current?.resolve(false);
    return new Promise<boolean>((resolve) => {
      this.current = { id: nextId++, ...opts, resolve };
    });
  }

  answer(ok: boolean) {
    const pending = this.current;
    this.current = null;
    pending?.resolve(ok);
  }
}

export const confirmQueue = new ConfirmQueue();

/** Ask the user to confirm. Resolves true when they accept. */
export function confirmDialog(opts: ConfirmOptions | string): Promise<boolean> {
  return confirmQueue.ask(typeof opts === 'string' ? { message: opts } : opts);
}
