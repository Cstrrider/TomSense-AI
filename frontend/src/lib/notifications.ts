/**
 * Integrated push notifications — no FCM, no third-party app.
 *
 * Two mechanisms:
 *  1. OS-scheduled local notifications for scheduled prompts: synced from
 *     /me/schedules into LocalNotifications with `repeats` — the OS fires
 *     them at the exact time even when the app is fully closed.
 *  2. A server-side queue (/me/notifications) for async events (budget mode,
 *     long code runs, scheduled-run results): drained on app open/resume and
 *     surfaced as toasts + tray notifications.
 *
 * Native-only: everything no-ops in a plain browser tab.
 */
import { Capacitor } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';
import { toast } from '$lib/toast.svelte';

const native = () => Capacitor.isNativePlatform();

/** Deterministic int32 id from a string (schedule uuid + weekday). */
function hashId(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
  return Math.abs(h) % 2_000_000_000;
}

async function ensurePermission(): Promise<boolean> {
  try {
    let p = await LocalNotifications.checkPermissions();
    if (p.display !== 'granted') p = await LocalNotifications.requestPermissions();
    return p.display === 'granted';
  } catch {
    return false;
  }
}

/** Drain the server queue: show each unseen event, then ack. Called on app
 *  start and on resume (visibilitychange → visible). */
export async function drainNotificationQueue(): Promise<void> {
  try {
    const r = await fetch('/me/notifications');
    if (!r.ok) return;
    const { notifications } = (await r.json()) as {
      notifications: { id: number; title: string; body: string; click_path: string | null }[];
    };
    if (!notifications.length) return;

    // Surface in-app (we're visible right now — a toast reads better than a
    // tray popup), and ALSO drop into the tray on native so there's history.
    for (const n of notifications.slice(-3)) {
      toast.success(`${n.title}${n.body ? ' — ' + n.body.slice(0, 80) : ''}`);
    }
    if (native() && (await ensurePermission())) {
      await LocalNotifications.schedule({
        notifications: notifications.map((n) => ({
          id: hashId(`queue-${n.id}`),
          title: n.title,
          body: (n.body || '').slice(0, 300),
          extra: { click_path: n.click_path }
        }))
      });
    }
    const maxId = Math.max(...notifications.map((n) => n.id));
    await fetch('/me/notifications/ack', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ up_to_id: maxId })
    });
  } catch {
    /* offline / auth redirect — try again next resume */
  }
}

/** Mirror the user's scheduled prompts into OS-level repeating notifications
 *  (fires app-closed, exact-ish time). Re-run after any /tasks change. */
export async function syncScheduleNotifications(): Promise<void> {
  if (!native()) return;
  if (!(await ensurePermission())) return;
  try {
    const r = await fetch('/me/schedules');
    if (!r.ok) return;
    const { schedules } = (await r.json()) as {
      schedules: {
        id: string; title: string; run_at: string; weekdays: number; enabled: boolean;
      }[];
    };

    // Clear our previously-scheduled entries (tagged via extra.tomsenseSchedule)
    // so removed/edited schedules don't keep firing.
    try {
      const pending = await LocalNotifications.getPending();
      const ours = pending.notifications.filter(
        (n) => (n.extra as { tomsenseSchedule?: boolean } | undefined)?.tomsenseSchedule
      );
      if (ours.length) {
        await LocalNotifications.cancel({ notifications: ours.map((n) => ({ id: n.id })) });
      }
    } catch {
      /* getPending unsupported → ids are deterministic, re-schedule overwrites */
    }

    const toSchedule: Parameters<typeof LocalNotifications.schedule>[0]['notifications'] = [];
    for (const s of schedules) {
      if (!s.enabled) continue;
      const [hh, mm] = s.run_at.split(':').map(Number);
      // +2 min buffer so the reply has landed by the time the user taps.
      const minute = (mm + 2) % 60;
      const hour = (hh + Math.floor((mm + 2) / 60)) % 24;
      const base = {
        title: `⏰ ${s.title}`,
        body: 'Your scheduled result is ready — tap to open.',
        extra: { tomsenseSchedule: true, click_path: '/' },
        schedule: undefined as unknown
      };
      if (s.weekdays === 127) {
        toSchedule.push({
          ...base,
          id: hashId(`sched-${s.id}-daily`),
          schedule: { on: { hour, minute }, repeats: true, allowWhileIdle: true }
        });
      } else {
        // Our bitmask: Mon=1<<0 … Sun=1<<6. Capacitor weekday: 1=Sun … 7=Sat.
        for (let i = 0; i < 7; i++) {
          if (!(s.weekdays & (1 << i))) continue;
          const capWeekday = i === 6 ? 1 : i + 2;
          toSchedule.push({
            ...base,
            id: hashId(`sched-${s.id}-${i}`),
            schedule: {
              on: { weekday: capWeekday, hour, minute },
              repeats: true,
              allowWhileIdle: true
            }
          });
        }
      }
    }
    if (toSchedule.length) {
      await LocalNotifications.schedule({ notifications: toSchedule });
    }
  } catch {
    /* best-effort */
  }
}

let wired = false;

/** One-time wiring: tap-to-navigate + resume-drain. Call from the layout. */
export function initNotifications(): void {
  if (wired) return;
  wired = true;
  if (native()) {
    LocalNotifications.addListener('localNotificationActionPerformed', (e) => {
      const path = (e.notification.extra as { click_path?: string } | undefined)?.click_path;
      if (path) window.location.href = path;
    }).catch(() => {});
  }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void drainNotificationQueue();
  });
  void drainNotificationQueue();
  void syncScheduleNotifications();
}
