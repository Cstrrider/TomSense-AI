export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
  timeout: number; // ms
}

let nextId = 1;

class ToastQueue {
  items = $state<Toast[]>([]);

  push(kind: ToastKind, message: string, timeout = 3000) {
    const id = nextId++;
    this.items = [...this.items, { id, kind, message, timeout }];
    if (timeout > 0) {
      setTimeout(() => this.dismiss(id), timeout);
    }
    return id;
  }

  success(message: string, timeout = 2500) {
    return this.push('success', message, timeout);
  }
  error(message: string, timeout = 5000) {
    return this.push('error', message, timeout);
  }
  info(message: string, timeout = 3000) {
    return this.push('info', message, timeout);
  }

  dismiss(id: number) {
    this.items = this.items.filter((t) => t.id !== id);
  }
}

export const toast = new ToastQueue();
