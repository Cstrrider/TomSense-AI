/**
 * Client-fulfilled tools.
 *
 * The backend model can call get_location / get_calendar / get_health. The
 * backend emits a `client_tool` SSE event and blocks; streamChat() (api.ts)
 * runs the matching function here and POSTs the result back to
 * /chat/tool_result, and the streaming turn resumes.
 *
 * Each function returns a short plain-text string the model reads as the tool
 * result — so phrase it for the model, not the user.
 *
 * @capacitor/geolocation falls back to the browser Geolocation API when this
 * runs as a PWA, so location works in the app *and* in a desktop browser.
 */
import { registerPlugin } from '@capacitor/core';
import { Geolocation } from '@capacitor/geolocation';
import { LocalNotifications } from '@capacitor/local-notifications';

// Calendar — custom local Capacitor plugin (android/.../CalendarPlugin.java).
// registerPlugin returns a proxy; on web (no native impl) calls reject, which
// getCalendar() catches and turns into a friendly message.
interface CalEvent {
  title: string;
  begin: number;
  end: number;
  location: string;
  allDay: boolean;
  calendar: string;
}
const Calendar = registerPlugin<{
  getEvents(options: { days: number }): Promise<{ events: CalEvent[] }>;
}>('Calendar');

// Health — custom local Kotlin plugin (android/.../HealthPlugin.kt), backed by
// Android Health Connect.
interface HealthSummary {
  available: boolean;
  granted?: boolean;
  reason?: string;
  days?: number;
  // activity
  steps?: number;
  distanceMeters?: number;
  activeKcal?: number;
  totalKcal?: number;
  floors?: number;
  elevationGainMeters?: number;
  workoutCount?: number;
  workoutMinutes?: number;
  // vitals
  heartRateAvg?: number;
  heartRateMin?: number;
  heartRateMax?: number;
  restingHeartRate?: number;
  hrvMs?: number;
  vo2Max?: number;
  oxygenSaturationPct?: number;
  respiratoryRate?: number;
  bodyTempC?: number;
  bloodPressureSystolic?: number;
  bloodPressureDiastolic?: number;
  bloodGlucoseMgDl?: number;
  // body
  weightKg?: number;
  heightCm?: number;
  bodyFatPct?: number;
  bmrKcal?: number;
  // sleep / nutrition / cycle
  sleepMinutes?: number;
  sleepSessions?: number;
  hydrationLiters?: number;
  nutritionKcal?: number;
  lastPeriodStart?: string;
}
const Health = registerPlugin<{
  getSummary(options: { days: number }): Promise<HealthSummary>;
}>('Health');

// Actions — custom local Capacitor plugin (android/.../ActionsPlugin.java):
// fires the calendar new-event editor, clock timer/alarm intents, and all
// Android device-action intents (app launch, dialer, SMS, maps, browser, share, settings).
const Actions = registerPlugin<{
  createCalendarEvent(o: {
    title: string;
    startMs: number;
    endMs: number;
    location?: string;
    notes?: string;
  }): Promise<{ ok: boolean }>;
  startTimer(o: { seconds: number; label?: string }): Promise<{ ok: boolean }>;
  setAlarm(o: { hour: number; minute: number; label?: string }): Promise<{ ok: boolean }>;
  consumeSharedContent(): Promise<{
    type: 'text' | 'image' | 'none';
    text?: string;
    imageBase64?: string;
    mimeType?: string;
  }>;
  launchApp(o: { appName: string }): Promise<{ ok: boolean }>;
  makeCall(o: { phoneNumber: string }): Promise<{ ok: boolean }>;
  sendSms(o: { phoneNumber: string; message?: string }): Promise<{ ok: boolean }>;
  openMaps(o: { destination: string; mode?: string }): Promise<{ ok: boolean }>;
  openUrl(o: { url: string }): Promise<{ ok: boolean }>;
  shareText(o: { text: string; title?: string }): Promise<{ ok: boolean }>;
  openSettings(o: { panel?: string }): Promise<{ ok: boolean }>;
  setVolume(o: { level?: number; action?: string }): Promise<{ ok: boolean; detail?: string }>;
  setBrightness(o: { level: number }): Promise<{ ok: boolean; detail?: string }>;
  mediaControl(o: { action: string }): Promise<{ ok: boolean; detail?: string }>;
  getDeviceStatus(): Promise<{ ok: boolean; detail?: string }>;
  playMusic(o: { query: string; kind?: string; app?: string }): Promise<{ ok: boolean; detail?: string }>;
  saveImage(o: { base64: string; mime: string; filename: string }): Promise<{ ok: boolean; detail?: string }>;
  getServerUrl(): Promise<{ url: string; isSet: boolean }>;
  setServerUrl(o: { url: string }): Promise<{ url: string }>;
}>('Actions');

// Contacts — custom local Capacitor plugin (android/.../ContactsPlugin.java).
const Contacts = registerPlugin<{
  getContacts(o: { query: string }): Promise<{ contacts: Array<{ name: string; phone: string }> }>;
}>('Contacts');


/** A pending "Share to TomSense" payload from the Android share sheet. */
export type SharedContent =
  | { type: 'text'; text: string }
  | { type: 'image'; imageBase64: string; mimeType: string };

/** Drain the pending shared payload (one-shot). Returns null if there's none,
 *  or when not running in the native app. */
export async function consumeSharedContent(): Promise<SharedContent | null> {
  try {
    const r = await Actions.consumeSharedContent();
    if (r.type === 'text' && r.text) return { type: 'text', text: r.text };
    if (r.type === 'image' && r.imageBase64) {
      return { type: 'image', imageBase64: r.imageBase64, mimeType: r.mimeType || 'image/jpeg' };
    }
    return null;
  } catch {
    return null;
  }
}

/** Hard client-side cap: a stuck native call (e.g. an ignored permission
 *  prompt) must not hang the chat stream. The backend has its own, longer
 *  timeout — this one should fire first so we can POST a real message. */
const TOOL_TIMEOUT_MS = 75_000;

function withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    p,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out`)), ms)
    )
  ]);
}

/** Coerce a tool argument (which the model may send as a number or string)
 *  to an integer, falling back to a default. */
function asInt(v: unknown, dflt: number): number {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? parseInt(v, 10) : NaN;
  return Number.isFinite(n) ? Math.round(n) : dflt;
}

/** Dispatch a client tool by name. Always resolves to a string — never throws
 *  — so the model always gets a usable tool result. */
export async function runClientTool(
  name: string,
  args: Record<string, unknown>
): Promise<string> {
  try {
    switch (name) {
      case 'get_location':
        return await withTimeout(getLocation(), TOOL_TIMEOUT_MS, 'Location');
      case 'get_calendar':
        return await withTimeout(getCalendar(asInt(args.days, 7)), TOOL_TIMEOUT_MS, 'Calendar');
      case 'get_health':
        return await withTimeout(getHealth(asInt(args.days, 1)), TOOL_TIMEOUT_MS, 'Health');
      case 'create_calendar_event':
        return await withTimeout(createCalendarEvent(args), TOOL_TIMEOUT_MS, 'Calendar');
      case 'set_reminder':
        return await withTimeout(setReminder(args), TOOL_TIMEOUT_MS, 'Reminder');
      case 'start_timer':
        return await withTimeout(startTimer(args), TOOL_TIMEOUT_MS, 'Timer');
      case 'set_alarm':
        return await withTimeout(setAlarm(args), TOOL_TIMEOUT_MS, 'Alarm');
      case 'launch_app':
        return await withTimeout(launchApp(String(args.app_name ?? '')), TOOL_TIMEOUT_MS, 'App launch');
      case 'make_call':
        return await withTimeout(makeCall(String(args.phone_number ?? '')), TOOL_TIMEOUT_MS, 'Call');
      case 'send_sms':
        return await withTimeout(
          sendSms(String(args.phone_number ?? ''), args.message ? String(args.message) : undefined),
          TOOL_TIMEOUT_MS, 'SMS'
        );
      case 'open_maps':
        return await withTimeout(
          openMaps(String(args.destination ?? ''), args.mode ? String(args.mode) : undefined),
          TOOL_TIMEOUT_MS, 'Maps'
        );
      case 'open_url':
        return await withTimeout(openUrl(String(args.url ?? '')), TOOL_TIMEOUT_MS, 'Browser');
      case 'share_text':
        return await withTimeout(
          shareText(String(args.text ?? ''), args.title ? String(args.title) : undefined),
          TOOL_TIMEOUT_MS, 'Share'
        );
      case 'get_contacts':
        return await withTimeout(getContacts(String(args.query ?? '')), TOOL_TIMEOUT_MS, 'Contacts');
      case 'open_settings':
        return await withTimeout(
          openSettings(args.panel ? String(args.panel) : undefined),
          TOOL_TIMEOUT_MS, 'Settings'
        );
      case 'set_volume':
        return await withTimeout(
          setVolume(asInt(args.level, -1), args.action ? String(args.action) : ''),
          TOOL_TIMEOUT_MS, 'Volume'
        );
      case 'set_brightness':
        return await withTimeout(setBrightness(asInt(args.level, 50)), TOOL_TIMEOUT_MS, 'Brightness');
      case 'media_control':
        return await withTimeout(mediaControl(String(args.action ?? '')), TOOL_TIMEOUT_MS, 'Media');
      case 'get_device_status':
        return await withTimeout(getDeviceStatus(), TOOL_TIMEOUT_MS, 'Device status');
      case 'play_music':
        return await withTimeout(
          playMusic(
            String(args.query ?? ''),
            args.kind ? String(args.kind) : '',
            args.app ? String(args.app) : ''
          ),
          TOOL_TIMEOUT_MS, 'Music'
        );
      default:
        return `(unknown client tool: ${name})`;
    }
  } catch (e) {
    return `(couldn't complete ${name}: ${(e as Error).message})`;
  }
}

// ─── location ───────────────────────────────────────────────────────────────

async function getLocation(): Promise<string> {
  try {
    const perm = await Geolocation.checkPermissions();
    if (perm.location !== 'granted' && perm.coarseLocation !== 'granted') {
      const req = await Geolocation.requestPermissions();
      if (req.location !== 'granted' && req.coarseLocation !== 'granted') {
        return 'The user declined location access — answer without it.';
      }
    }
  } catch {
    // checkPermissions isn't supported on every web target; getCurrentPosition
    // will surface its own prompt/error below.
  }

  const pos = await Geolocation.getCurrentPosition({
    enableHighAccuracy: false,
    timeout: 20_000,
    maximumAge: 60_000
  });
  const { latitude, longitude, accuracy } = pos.coords;
  const coords =
    `latitude ${latitude.toFixed(5)}, longitude ${longitude.toFixed(5)} ` +
    `(±${Math.round(accuracy)}m)`;

  const place = await reverseGeocode(latitude, longitude);
  return place
    ? `The user is near ${place} — ${coords}.`
    : `The user's coordinates: ${coords}.`;
}

/** Best-effort coordinates → place name. BigDataCloud's client endpoint is
 *  free, keyless and CORS-open; failure just means we return coords only. */
async function reverseGeocode(lat: number, lon: number): Promise<string | null> {
  try {
    const r = await withTimeout(
      fetch(
        `https://api.bigdatacloud.net/data/reverse-geocode-client` +
          `?latitude=${lat}&longitude=${lon}&localityLanguage=en`
      ),
      8_000,
      'Reverse geocode'
    );
    if (!r.ok) return null;
    const j = (await r.json()) as Record<string, string>;
    const parts = [
      j.city || j.locality,
      j.principalSubdivision,
      j.countryName
    ].filter(Boolean);
    return parts.length ? parts.join(', ') : null;
  } catch {
    return null;
  }
}

// ─── calendar ────────────────────────────────────────────────────────────────

async function getCalendar(days: number): Promise<string> {
  const d = Math.min(30, Math.max(1, days));
  let events: CalEvent[];
  try {
    ({ events } = await Calendar.getEvents({ days: d }));
  } catch (e) {
    const msg = (e as Error).message || '';
    if (/not implemented|unavailable|not available/i.test(msg)) {
      return 'Calendar access is only available in the installed Android app.';
    }
    if (/denied/i.test(msg)) {
      return 'The user declined calendar access — answer without it.';
    }
    return `Couldn't read the calendar: ${msg}`;
  }
  if (!events || events.length === 0) {
    return `The user has no calendar events in the next ${d} day(s).`;
  }
  events.sort((a, b) => a.begin - b.begin);
  const lines = events.slice(0, 50).map((e) => {
    const start = new Date(e.begin);
    const when = e.allDay
      ? start.toLocaleDateString(undefined, {
          weekday: 'short',
          month: 'short',
          day: 'numeric'
        }) + ', all day'
      : start.toLocaleString(undefined, {
          weekday: 'short',
          month: 'short',
          day: 'numeric',
          hour: 'numeric',
          minute: '2-digit'
        });
    const loc = e.location ? ` (at ${e.location})` : '';
    return `- ${when} — ${e.title}${loc}`;
  });
  return `The user's upcoming calendar events (next ${d} day(s)):\n${lines.join('\n')}`;
}

// ─── health ──────────────────────────────────────────────────────────────────

async function getHealth(days: number): Promise<string> {
  const d = Math.min(14, Math.max(1, days));
  let r: HealthSummary;
  try {
    r = await Health.getSummary({ days: d });
  } catch (e) {
    const msg = (e as Error).message || '';
    if (/not implemented|unavailable|not available/i.test(msg)) {
      return 'Health data is only available in the installed Android app.';
    }
    return `Couldn't read health data: ${msg}`;
  }
  if (!r.available) {
    return r.reason || 'Health Connect is not available on this device.';
  }
  if (r.granted === false) {
    return 'The user has not granted health-data access — answer without it.';
  }

  const num = (v: unknown): v is number => typeof v === 'number';
  const sections: string[] = [];

  const activity: string[] = [];
  if (num(r.steps)) activity.push(`${r.steps.toLocaleString()} steps`);
  if (num(r.distanceMeters)) activity.push(`${(r.distanceMeters / 1000).toFixed(2)} km`);
  if (num(r.floors)) activity.push(`${Math.round(r.floors)} floors climbed`);
  if (num(r.elevationGainMeters)) activity.push(`${Math.round(r.elevationGainMeters)} m elevation gain`);
  if (num(r.activeKcal)) activity.push(`${Math.round(r.activeKcal)} active kcal`);
  if (num(r.totalKcal)) activity.push(`${Math.round(r.totalKcal)} total kcal burned`);
  if (num(r.workoutCount) && r.workoutCount > 0) {
    activity.push(
      `${r.workoutCount} workout(s)${num(r.workoutMinutes) ? ` (${r.workoutMinutes} min)` : ''}`
    );
  }
  if (activity.length) sections.push(`Activity — ${activity.join(', ')}`);

  const vitals: string[] = [];
  if (num(r.heartRateAvg)) {
    const hr = [`avg ${r.heartRateAvg}`];
    if (num(r.heartRateMin)) hr.push(`min ${r.heartRateMin}`);
    if (num(r.heartRateMax)) hr.push(`max ${r.heartRateMax}`);
    vitals.push(`heart rate ${hr.join(' / ')} bpm`);
  }
  if (num(r.restingHeartRate)) vitals.push(`resting HR ${r.restingHeartRate} bpm`);
  if (num(r.hrvMs)) vitals.push(`HRV ${Math.round(r.hrvMs)} ms`);
  if (num(r.bloodPressureSystolic) && num(r.bloodPressureDiastolic)) {
    vitals.push(
      `blood pressure ${Math.round(r.bloodPressureSystolic)}/${Math.round(r.bloodPressureDiastolic)} mmHg`
    );
  }
  if (num(r.bloodGlucoseMgDl)) vitals.push(`blood glucose ${Math.round(r.bloodGlucoseMgDl)} mg/dL`);
  if (num(r.oxygenSaturationPct)) vitals.push(`blood oxygen ${Math.round(r.oxygenSaturationPct)}%`);
  if (num(r.respiratoryRate)) vitals.push(`respiratory rate ${Math.round(r.respiratoryRate)}/min`);
  if (num(r.bodyTempC)) vitals.push(`body temp ${r.bodyTempC.toFixed(1)} °C`);
  if (num(r.vo2Max)) vitals.push(`VO2 max ${r.vo2Max.toFixed(1)}`);
  if (vitals.length) sections.push(`Vitals — ${vitals.join(', ')}`);

  const body: string[] = [];
  if (num(r.weightKg)) body.push(`weight ${r.weightKg.toFixed(1)} kg`);
  if (num(r.bodyFatPct)) body.push(`body fat ${r.bodyFatPct.toFixed(1)}%`);
  if (num(r.heightCm)) body.push(`height ${Math.round(r.heightCm)} cm`);
  if (num(r.bmrKcal)) body.push(`BMR ${Math.round(r.bmrKcal)} kcal/day`);
  if (body.length) sections.push(`Body — ${body.join(', ')}`);

  if (num(r.sleepMinutes) && r.sleepMinutes > 0) {
    const h = Math.floor(r.sleepMinutes / 60);
    const m = r.sleepMinutes % 60;
    sections.push(`Sleep — ${h}h ${m}m across ${r.sleepSessions ?? 0} session(s)`);
  }

  const nutrition: string[] = [];
  if (num(r.nutritionKcal)) nutrition.push(`${Math.round(r.nutritionKcal)} kcal eaten`);
  if (num(r.hydrationLiters)) nutrition.push(`${r.hydrationLiters.toFixed(2)} L water`);
  if (nutrition.length) sections.push(`Nutrition — ${nutrition.join(', ')}`);

  if (typeof r.lastPeriodStart === 'string') {
    const dt = new Date(r.lastPeriodStart);
    if (!Number.isNaN(dt.getTime())) {
      sections.push(`Cycle — last period started ${dt.toLocaleDateString()}`);
    }
  }

  if (sections.length === 0) {
    return `No health data was recorded in the last ${d} day(s) — or no Health Connect categories are granted.`;
  }
  return (
    `The user's health summary for the last ${d} day(s):\n` +
    sections.map((s) => `• ${s}`).join('\n')
  );
}

// ─── actions: calendar write, reminders, timers, alarms ──────────────────────

/** If the error means "this device API isn't reachable" (web / assist
 *  overlay), return a friendly message; otherwise null. */
function deviceOnly(e: unknown, what: string): string | null {
  const msg = (e as Error)?.message || '';
  return /not implemented|unavailable|not available/i.test(msg)
    ? `${what} are only available in the installed Android app.`
    : null;
}

async function createCalendarEvent(args: Record<string, unknown>): Promise<string> {
  const title = String(args.title ?? '').trim();
  if (!title) return 'No event title was provided.';
  const start = new Date(String(args.start ?? ''));
  if (Number.isNaN(start.getTime())) return "Couldn't read the event start time.";
  let end = args.end ? new Date(String(args.end)) : null;
  if (!end || Number.isNaN(end.getTime())) {
    end = new Date(start.getTime() + 60 * 60 * 1000);
  }
  try {
    await Actions.createCalendarEvent({
      title,
      startMs: start.getTime(),
      endMs: end.getTime(),
      location: args.location ? String(args.location) : undefined,
      notes: args.notes ? String(args.notes) : undefined
    });
  } catch (e) {
    return (
      deviceOnly(e, 'Calendar actions') ??
      `Couldn't open the calendar editor: ${(e as Error).message}`
    );
  }
  return (
    `Opened a draft event "${title}" for ${start.toLocaleString()} — tell the ` +
    `user it's ready in their calendar to review and save.`
  );
}

async function setReminder(args: Record<string, unknown>): Promise<string> {
  const message = String(args.message ?? '').trim();
  if (!message) return 'No reminder text was provided.';
  let at: Date;
  if (args.in_minutes !== undefined && args.in_minutes !== null) {
    const mins = asInt(args.in_minutes, 0);
    if (mins < 1) return 'The reminder delay must be at least 1 minute.';
    at = new Date(Date.now() + mins * 60_000);
  } else {
    at = new Date(String(args.time ?? ''));
    if (Number.isNaN(at.getTime())) return "Couldn't read the reminder time.";
  }
  if (at.getTime() < Date.now() + 3_000) return 'That reminder time is in the past.';
  try {
    let perm = await LocalNotifications.checkPermissions();
    if (perm.display !== 'granted') {
      perm = await LocalNotifications.requestPermissions();
      if (perm.display !== 'granted') {
        return 'The user declined notification permission — the reminder was not set.';
      }
    }
    await LocalNotifications.schedule({
      notifications: [
        {
          id: Date.now() % 2_000_000_000,
          title: 'Reminder',
          body: message,
          schedule: { at, allowWhileIdle: true }
        }
      ]
    });
  } catch (e) {
    return deviceOnly(e, 'Reminders') ?? `Couldn't set the reminder: ${(e as Error).message}`;
  }
  return `Reminder set for ${at.toLocaleString()}: "${message}".`;
}

async function startTimer(args: Record<string, unknown>): Promise<string> {
  const minutes = asInt(args.minutes, 0);
  if (minutes < 1) return 'A timer needs a length of at least 1 minute.';
  try {
    await Actions.startTimer({
      seconds: minutes * 60,
      label: args.label ? String(args.label) : undefined
    });
  } catch (e) {
    return deviceOnly(e, 'Timers') ?? `Couldn't start the timer: ${(e as Error).message}`;
  }
  return `Started a ${minutes}-minute timer on the device.`;
}

async function setAlarm(args: Record<string, unknown>): Promise<string> {
  const at = new Date(String(args.time ?? ''));
  if (Number.isNaN(at.getTime())) return "Couldn't read the alarm time.";
  try {
    await Actions.setAlarm({
      hour: at.getHours(),
      minute: at.getMinutes(),
      label: args.label ? String(args.label) : undefined
    });
  } catch (e) {
    return deviceOnly(e, 'Alarms') ?? `Couldn't set the alarm: ${(e as Error).message}`;
  }
  const hh = String(at.getHours()).padStart(2, '0');
  const mm = String(at.getMinutes()).padStart(2, '0');
  return `Set an alarm on the device for ${hh}:${mm}.`;
}

// ─── Android device-action tools ─────────────────────────────────────────────

/** Returns the TomSenseNative bridge when running inside AssistActivity's
 *  plain WebView (no Capacitor). Each method is synchronous and returns
 *  "ok" or "error: <message>". */
function nb(): Record<string, (...a: any[]) => string> | null {
  const n = (window as any).TomSenseNative;
  return (n && typeof n === 'object') ? n : null;
}

function nbResult(r: string | undefined, success: string, failPrefix: string): string {
  if (!r || r === 'ok') return success;
  return r.startsWith('error:') ? `${failPrefix}: ${r.slice(7).trim()}` : success;
}

async function launchApp(appName: string): Promise<string> {
  if (!appName) return 'No app name was provided.';
  try {
    await Actions.launchApp({ appName });
    return `Opened ${appName} on the device.`;
  } catch {
    const n = nb();
    if (n?.launchApp) return nbResult(n.launchApp(appName), `Opened ${appName} on the device.`, `Couldn't open ${appName}`);
    return 'App launching is only available in the installed Android app.';
  }
}

async function makeCall(phoneNumber: string): Promise<string> {
  if (!phoneNumber) return 'No phone number was provided.';
  try {
    await Actions.makeCall({ phoneNumber });
    return `Opened the dialer with ${phoneNumber} — tell the user to tap the call button to connect.`;
  } catch {
    const n = nb();
    if (n?.makeCall) return nbResult(n.makeCall(phoneNumber), `Opened the dialer with ${phoneNumber} — tell the user to tap call.`, "Couldn't open the dialer");
    return 'Calling is only available in the installed Android app.';
  }
}

async function sendSms(phoneNumber: string, message?: string): Promise<string> {
  if (!phoneNumber) return 'No phone number was provided.';
  const ok = message
    ? `Opened the messaging app with a draft to ${phoneNumber} — tell the user to review and send it.`
    : `Opened the messaging app pre-addressed to ${phoneNumber}.`;
  try {
    await Actions.sendSms({ phoneNumber, message });
    return ok;
  } catch {
    const n = nb();
    if (n?.sendSms) return nbResult(n.sendSms(phoneNumber, message ?? ''), ok, "Couldn't open the messaging app");
    return 'SMS is only available in the installed Android app.';
  }
}

async function openMaps(destination: string, mode?: string): Promise<string> {
  if (!destination) return 'No destination was provided.';
  const ok = mode ? `Opened navigation to "${destination}" (${mode}).` : `Opened maps to "${destination}".`;
  try {
    await Actions.openMaps({ destination, mode });
    return ok;
  } catch {
    const n = nb();
    if (n?.openMaps) return nbResult(n.openMaps(destination, mode ?? ''), ok, "Couldn't open maps");
    return 'Maps is only available in the installed Android app.';
  }
}

async function openUrl(url: string): Promise<string> {
  if (!url) return 'No URL was provided.';
  try {
    await Actions.openUrl({ url });
    return `Opened ${url}.`;
  } catch {
    const n = nb();
    if (n?.openUrl) return nbResult(n.openUrl(url), `Opened ${url}.`, "Couldn't open the URL");
    return 'Opening URLs is only available in the installed Android app.';
  }
}

async function shareText(text: string, title?: string): Promise<string> {
  if (!text) return 'No text was provided to share.';
  try {
    await Actions.shareText({ text, title });
    return 'Opened the share sheet — tell the user to pick where to send it.';
  } catch {
    const n = nb();
    if (n?.shareText) return nbResult(n.shareText(text, title ?? ''), 'Opened the share sheet — tell the user to pick where to send it.', "Couldn't open the share sheet");
    return 'Sharing is only available in the installed Android app.';
  }
}

async function getContacts(query: string): Promise<string> {
  if (!query) return 'No search query was provided.';
  try {
    const { contacts } = await Contacts.getContacts({ query });
    if (!contacts || contacts.length === 0) return `No contacts found matching "${query}".`;
    return `Contacts matching "${query}":\n` + contacts.map((c) => `- ${c.name}: ${c.phone}`).join('\n');
  } catch (e) {
    const msg = (e as Error).message || '';
    if (/denied/i.test(msg)) return 'The user declined contacts access — ask for a phone number directly.';
    return 'Contact search is only available in the main TomSense app, not the overlay.';
  }
}

async function openSettings(panel?: string): Promise<string> {
  const ok = panel ? `Opened the ${panel} settings screen.` : 'Opened the Settings app.';
  try {
    await Actions.openSettings({ panel });
    return ok;
  } catch {
    const n = nb();
    if (n?.openSettings) return nbResult(n.openSettings(panel ?? ''), ok, "Couldn't open settings");
    return 'Settings is only available in the installed Android app.';
  }
}

// ── volume / brightness / media / status / music ─────────────────────────────

/** The native side returns a full model-readable string ("Media volume is now
 *  50%.") or "error: <message>" — pass it through either way. */
function detailResult(r: string | undefined, failPrefix: string): string {
  if (!r) return failPrefix + ': no response from the device.';
  return r.startsWith('error:') ? `${failPrefix}: ${r.slice(6).trim()}` : r;
}

async function setVolume(level: number, action: string): Promise<string> {
  if (level < 0 && !action) return 'Give a volume level (0-100) or an action (up/down/mute/unmute).';
  try {
    const r = await Actions.setVolume({ level: level >= 0 ? level : undefined, action: action || undefined });
    return r.detail ?? 'Volume changed.';
  } catch (e) {
    const n = nb();
    if (n?.setVolume) return detailResult(n.setVolume(level, action), "Couldn't change the volume");
    return deviceOnly(e, 'Volume controls') ?? `Couldn't change the volume: ${(e as Error).message}`;
  }
}

async function setBrightness(level: number): Promise<string> {
  try {
    const r = await Actions.setBrightness({ level });
    return r.detail ?? `Brightness set to ${level}%.`;
  } catch (e) {
    const n = nb();
    if (n?.setBrightness) return detailResult(n.setBrightness(level), "Couldn't change the brightness");
    return deviceOnly(e, 'Brightness controls') ?? `Couldn't change the brightness: ${(e as Error).message}`;
  }
}

async function mediaControl(action: string): Promise<string> {
  if (!action) return 'No playback action was provided.';
  try {
    const r = await Actions.mediaControl({ action });
    return r.detail ?? `Sent '${action}' to the media player.`;
  } catch (e) {
    const n = nb();
    if (n?.mediaControl) return detailResult(n.mediaControl(action), "Couldn't control playback");
    return deviceOnly(e, 'Media controls') ?? `Couldn't control playback: ${(e as Error).message}`;
  }
}

async function getDeviceStatus(): Promise<string> {
  try {
    const r = await Actions.getDeviceStatus();
    return r.detail ?? 'No status was returned.';
  } catch (e) {
    const n = nb();
    if (n?.getDeviceStatus) return detailResult(n.getDeviceStatus(), "Couldn't read the device status");
    return deviceOnly(e, 'Device status') ?? `Couldn't read the device status: ${(e as Error).message}`;
  }
}

async function playMusic(query: string, kind: string, app: string): Promise<string> {
  if (!query) return 'No song, artist, album, or playlist was provided.';
  try {
    const r = await Actions.playMusic({ query, kind: kind || undefined, app: app || undefined });
    return r.detail ?? `Started playing "${query}".`;
  } catch (e) {
    const n = nb();
    if (n?.playMusic) return detailResult(n.playMusic(query, kind, app), "Couldn't start playback");
    return deviceOnly(e, 'Music playback') ?? `Couldn't start playback: ${(e as Error).message}`;
  }
}

// ── image download / save ────────────────────────────────────────────────────

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => {
      const s = String(r.result);
      resolve(s.slice(s.indexOf(',') + 1)); // strip data:<mime>;base64, prefix
    };
    r.onerror = () => reject(new Error('could not read image data'));
    r.readAsDataURL(blob);
  });
}

/** Save an image (generated or otherwise) to the device. In the Android app
 *  it lands in Photos via MediaStore (Pictures/TomSense); in a browser it falls
 *  back to a normal download. Returns a user-facing success message; throws
 *  on failure so callers can toast the error. */
export async function saveImageToDevice(src: string): Promise<string> {
  const res = await fetch(src, { credentials: 'include' });
  if (!res.ok) throw new Error(`couldn't fetch the image (${res.status})`);
  const blob = await res.blob();
  const mime = blob.type || 'image/png';
  const filename = (src.split('/').pop() || 'image.png').split('?')[0];
  try {
    const base64 = await blobToBase64(blob);
    const r = await Actions.saveImage({ base64, mime, filename });
    return r.detail ?? 'Saved to Photos.';
  } catch (e) {
    // Only fall back to a browser download when the native bridge is absent
    // (web/desktop). A real native failure must surface — in the Android
    // WebView the anchor-download fallback silently does nothing.
    if (deviceOnly(e, 'x') === null) throw e;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
    return 'Downloaded.';
  }
}

// ── instance URL (Android app setting) ───────────────────────────────────────

/** The APK's configured instance URL, or null when not running in the native
 *  app (browser/PWA — the concept doesn't apply there). */
export async function getInstanceUrl(): Promise<{ url: string; isSet: boolean } | null> {
  try {
    return await Actions.getServerUrl();
  } catch {
    return null; // no native bridge
  }
}

/** Save a new instance URL. The native side validates, persists, and then
 *  RELAUNCHES the WebView against the new URL — callers won't run much code
 *  after this resolves. Throws on invalid input or when not in the app. */
export async function setInstanceUrl(url: string): Promise<void> {
  await Actions.setServerUrl({ url });
}

// ── tool-status labels (streaming confirmations) ─────────────────────────────

/** Short user-facing "what's happening" line shown in the chat bubble while a
 *  client tool runs — "Opening Spotify…", "Setting volume…". Phrased for the
 *  USER (unlike tool results, which are phrased for the model). */
export function toolStatusLabel(name: string, args: Record<string, unknown>): string {
  const s = (k: string) => (args[k] !== undefined && args[k] !== null ? String(args[k]) : '');
  switch (name) {
    case 'get_location':          return 'Getting your location…';
    case 'get_calendar':          return 'Checking your calendar…';
    case 'get_health':            return 'Reading your health data…';
    case 'create_calendar_event': return `Drafting event "${s('title')}"…`;
    case 'set_reminder':          return 'Setting a reminder…';
    case 'start_timer':           return `Starting a ${s('minutes')}-minute timer…`;
    case 'set_alarm':             return 'Setting an alarm…';
    case 'launch_app':            return `Opening ${s('app_name') || 'the app'}…`;
    case 'make_call':             return `Opening the dialer (${s('phone_number')})…`;
    case 'send_sms':              return 'Drafting a text message…';
    case 'open_maps':             return `Getting directions to ${s('destination')}…`;
    case 'open_url':              return 'Opening a link…';
    case 'share_text':            return 'Opening the share sheet…';
    case 'get_contacts':          return `Searching contacts for "${s('query')}"…`;
    case 'open_settings':         return `Opening ${s('panel') || 'device'} settings…`;
    case 'set_volume':            return s('level') ? `Setting volume to ${s('level')}%…` : 'Adjusting the volume…';
    case 'set_brightness':        return `Setting brightness to ${s('level')}%…`;
    case 'media_control':         return `Sending ${s('action') || 'playback'} command…`;
    case 'get_device_status':     return 'Checking the device status…';
    case 'play_music':            return `Playing ${s('query')}${s('app') === 'youtube music' ? ' on YouTube Music' : ' on Spotify'}…`;
    default:                      return 'Working on your device…';
  }
}
