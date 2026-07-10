import { getInfo, getMe, getNeurons, getUsageToday, getPrefs, listChats, listProjects, updatePrefs } from './api';
import type { Chat, InfoResponse, MeResponse, NeuronUsage, Project, UsageToday, UserPrefs } from './types';

class AppState {
  chats = $state<Chat[]>([]);
  chatsLoaded = $state(false);
  projects = $state<Project[]>([]);
  info = $state<InfoResponse | null>(null);
  infoError = $state<string | null>(null);
  me = $state<MeResponse | null>(null);
  neurons = $state<NeuronUsage | null>(null);
  usage = $state<UsageToday | null>(null);
  prefs = $state<UserPrefs>({});
  prefsLoaded = $state(false);
  sidebarOpen = $state(false);
  /** Chrome's deferred PWA install event — set when capturable. */
  installPrompt = $state<any | null>(null);

  async promptInstall() {
    const p = this.installPrompt;
    if (!p) return;
    try {
      p.prompt();
      await p.userChoice;
    } catch {
      // ignore
    } finally {
      this.installPrompt = null;
    }
  }

  async refreshChats() {
    try {
      this.chats = await listChats();
    } catch (e) {
      console.error('listChats failed', e);
    } finally {
      this.chatsLoaded = true;
    }
  }

  async refreshProjects() {
    try {
      this.projects = await listProjects();
    } catch (e) {
      console.error('listProjects failed', e);
    }
  }

  async refreshInfo() {
    try {
      this.info = await getInfo();
      this.infoError = null;
    } catch (e) {
      this.infoError = String(e);
    }
  }

  async refreshMe() {
    try {
      this.me = await getMe();
    } catch (e) {
      console.error('getMe failed', e);
    }
  }

  async refreshNeurons() {
    // Neurons are a Cloudflare-only metric — skip the fetch on a pure BYO
    // deployment so it doesn't hit CF GraphQL or show a neuron bar.
    if (this.info && this.info.cf_configured === false) {
      this.neurons = null;
      return;
    }
    try {
      this.neurons = await getNeurons();
    } catch (e) {
      console.error('getNeurons failed', e);
    }
  }

  async refreshUsage() {
    try {
      this.usage = await getUsageToday();
    } catch (e) {
      console.error('getUsageToday failed', e);
    }
  }

  async refreshPrefs() {
    try {
      this.prefs = await getPrefs();
    } catch (e) {
      // Falls back to env defaults — non-fatal.
      console.warn('getPrefs failed', e);
    } finally {
      // Flip the loaded flag whether the fetch succeeded or not — failed
      // fetch shouldn't trap the user in a "still loading" state. Layout's
      // first-run redirect waits on this so it doesn't fire too early.
      this.prefsLoaded = true;
    }
  }

  /** Save prefs and update local cache. `tool_models` and other nested objects
   *  are merged shallowly on the server (JSONB `||`), so callers patching one
   *  nested key must send the whole nested object themselves.
   */
  async savePrefs(patch: UserPrefs): Promise<void> {
    const next = await updatePrefs(patch);
    this.prefs = next;
  }

  /** Tiny TTS call to load Piper's voice model into memory before the user
   *  taps the first 🔊 button. Discards the result.
   */
  async warmupTTS() {
    try {
      await fetch('/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: 'ok', format: 'mp3' })
      });
    } catch {
      // best-effort, ignore
    }
  }

  metaText(): string {
    if (this.infoError) return 'backend unreachable';
    if (!this.info) return 'connecting…';
    const m =
      this.info.chat_model_short ??
      this.info.chat_model.split('/').pop() ??
      this.info.chat_model;
    return `${m} • ${this.info.tools.length} tools`;
  }
}

export const app = new AppState();
