<script lang="ts">
  /**
   * Live conversation mode — hands-free loop: listen → think → speak → repeat.
   *
   * STT and TTS run on-device via the Android system recognizer / TextToSpeech
   * (@capacitor-community plugins) — no server round-trip, so there's no lag
   * and `TextToSpeech.speak()` resolving on completion means a turn can't start
   * before the previous one finishes speaking. Only the "think" step hits the
   * server (the LLM). This screen is native-app only; the trigger buttons are
   * hidden in the browser.
   */
  import { onDestroy } from 'svelte';
  import { getChat, streamChat } from '$lib/api';
  import { speakableText } from '$lib/voice';
  import { toast } from '$lib/toast.svelte';
  import { IconX } from '$lib/icons';
  import { SpeechRecognition } from '@capacitor-community/speech-recognition';
  import { TextToSpeech } from '@capacitor-community/text-to-speech';

  interface Props {
    chatId: string;
    open: boolean;
    onclose: () => void;
    /** Called after each completed turn so the parent chat view can reload. */
    onturn?: () => void;
  }
  let { chatId, open, onclose, onturn }: Props = $props();

  type VState = 'idle' | 'listening' | 'thinking' | 'speaking';
  let vstate = $state<VState>('idle');
  let caption = $state(''); // transient status / last transcript line

  // Loop control — plain (non-reactive) refs.
  let active = false;
  let skip = false; // set when the user taps to skip the assistant's speech
  let history: { role: 'user' | 'assistant'; content: string }[] = [];
  let abort: AbortController | null = null;

  // Streaming speech: sentences are queued as they complete DURING the LLM
  // stream and a concurrent speaker drains the queue — first audio starts
  // after the first sentence, not after the whole reply.
  let speechQueue: string[] = [];
  let rawSpoken = 0; // chars of the raw reply already queued for speech
  let streamingDone = false;

  $effect(() => {
    if (open) start();
    else void stop();
  });
  onDestroy(() => void stop());

  async function start() {
    if (active) return;
    active = true;
    caption = '';
    // The device recognizer uses RECORD_AUDIO, which the app already holds —
    // request best-effort and carry on.
    try {
      await SpeechRecognition.requestPermissions();
    } catch {
      /* ignore — app-level RECORD_AUDIO is already granted */
    }
    try {
      const chat = await getChat(chatId);
      history = (chat.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content }));
    } catch {
      history = [];
    }
    void loop();
  }

  async function stop() {
    active = false;
    try {
      await SpeechRecognition.stop();
    } catch {
      /* not listening */
    }
    try {
      await TextToSpeech.stop();
    } catch {
      /* not speaking */
    }
    abort?.abort();
    abort = null;
    vstate = 'idle';
  }

  /** One utterance from the device recognizer. Returns '' on silence/error so
   *  the loop simply listens again. */
  async function listenOnce(): Promise<string> {
    try {
      const res = await SpeechRecognition.start({
        language: 'en-US',
        maxResults: 1,
        partialResults: false,
        popup: false
      });
      return (res?.matches?.[0] ?? '').trim();
    } catch {
      return '';
    }
  }

  async function loop() {
    while (active) {
      // 1 — LISTEN (device recognizer auto-stops on end of speech)
      vstate = 'listening';
      caption = 'Listening…';
      const text = await listenOnce();
      if (!active) break;
      if (!text) {
        // Nothing heard — brief gap so a misfiring recognizer can't tight-loop.
        await new Promise((r) => setTimeout(r, 200));
        continue;
      }
      caption = text;

      // 2+3 — THINK & SPEAK concurrently: sentences stream into the speech
      // queue as they complete, a concurrent speaker voices them. First
      // audio lands right after sentence one instead of after the whole
      // reply — the perceived-latency win.
      vstate = 'thinking';
      history = [...history, { role: 'user', content: text }];
      let reply = '';
      skip = false;
      speechQueue = [];
      rawSpoken = 0;
      streamingDone = false;
      abort = new AbortController();
      const speaking = speaker();
      try {
        for await (const ev of streamChat(
          history.map((m) => ({ role: m.role, content: m.content })),
          chatId,
          null,
          abort.signal
        )) {
          if (ev.type === 'text' && ev.text) {
            reply += ev.text;
            drainSentences(reply);
          } else if (ev.type === 'tool_status' && ev.text) {
            caption = ev.text; // "Opening Spotify…" while the device tool runs
          } else if (ev.type === 'error' && ev.error) reply += `\n[error: ${ev.error}]`;
        }
      } catch (e) {
        if ((e as Error).name !== 'AbortError') toast.error('Reply failed');
      }
      streamingDone = true;
      drainSentences(reply, true);
      abort = null;
      history = [...history, { role: 'assistant', content: reply }];
      onturn?.();
      await speaking; // wait for speech to finish (or be interrupted)
      if (!active) break;
    }
    vstate = 'idle';
  }

  /** Queue completed sentences from the raw reply for speech. Waits when the
   *  tail sits inside an unclosed code fence or tool chip (speakableText
   *  strips those once complete — speaking half of one would read markup
   *  aloud). `flush` queues whatever remains at stream end. */
  function drainSentences(reply: string, flush = false) {
    const pending = reply.slice(rawSpoken);
    if (!pending) return;
    if (!flush) {
      // Inside an unclosed ``` fence or <details> chip → wait for the close.
      if ((pending.split('```').length - 1) % 2 === 1) return;
      const opens = (pending.match(/<details/g) ?? []).length;
      const closes = (pending.match(/<\/details>/g) ?? []).length;
      if (opens > closes) return;
    }
    let cut = pending.length;
    if (!flush) {
      cut = -1;
      const re = /[.!?][)"'”\]]?(?=\s)/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(pending))) cut = m.index + m[0].length;
      if (cut < 30) return; // too little — wait for more text
    }
    const rawChunk = pending.slice(0, cut);
    rawSpoken += cut;
    const clean = speakableText(rawChunk).trim();
    if (clean) speechQueue.push(clean);
  }

  /** Concurrent speaker — drains the sentence queue while the stream is
   *  still producing. Ends when the queue is empty AND streaming finished,
   *  or on interrupt. */
  async function speaker() {
    while (active && !skip) {
      const chunk = speechQueue.shift();
      if (chunk === undefined) {
        if (streamingDone) return;
        await new Promise((r) => setTimeout(r, 120));
        continue;
      }
      vstate = 'speaking';
      caption = chunk.slice(0, 240);
      try {
        await TextToSpeech.speak({ text: chunk, lang: 'en-US', rate: 1.0 });
      } catch {
        return; // stopped / interrupted
      }
    }
  }

  /** Tap the orb — barge-in: stops speech AND the in-flight generation, so
   *  the loop goes straight back to listening. */
  function onOrbTap() {
    if (vstate === 'speaking' || vstate === 'thinking') {
      skip = true;
      speechQueue = [];
      abort?.abort();
      TextToSpeech.stop().catch(() => {});
    }
  }

  const STATE_LABEL: Record<VState, string> = {
    idle: '',
    listening: 'Listening',
    thinking: 'Thinking',
    speaking: 'Speaking'
  };
</script>

{#if open}
  <div class="voice-overlay" role="dialog" aria-label="Voice mode">
    <button class="vo-close" aria-label="Exit voice mode" onclick={onclose}>
      <IconX size={22} />
    </button>

    <div class="vo-center">
      <button
        class="orb {vstate}"
        onclick={onOrbTap}
        aria-label={vstate === 'speaking' ? 'Skip speech' : 'Voice orb'}
      >
        <span class="orb-core"></span>
      </button>
      <div class="vo-state">{STATE_LABEL[vstate]}</div>
      {#if caption}
        <div class="vo-caption">{caption}</div>
      {/if}
    </div>

    <div class="vo-hint">
      {#if vstate === 'speaking' || vstate === 'thinking'}Tap the orb to interrupt · ✕ to exit{:else}Speak naturally — it auto-detects when you stop · ✕ to exit{/if}
    </div>
  </div>
{/if}

<style>
  .voice-overlay {
    position: fixed;
    inset: 0;
    z-index: 200;
    background: var(--bg);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    animation: fade-in 0.15s var(--ease);
  }
  @keyframes fade-in {
    from { opacity: 0; }
    to { opacity: 1; }
  }
  .vo-close {
    position: absolute;
    top: calc(var(--sp-4) + env(safe-area-inset-top));
    right: var(--sp-4);
    background: var(--panel);
    border: 1px solid var(--border);
    color: var(--text);
    width: 44px;
    height: 44px;
    border-radius: var(--r-pill);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
  }
  .vo-center {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-4);
    padding: var(--sp-5);
    max-width: 560px;
  }
  .orb {
    width: 180px;
    height: 180px;
    border-radius: var(--r-pill);
    border: 0;
    background: transparent;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
  }
  .orb-core {
    width: 140px;
    height: 140px;
    border-radius: var(--r-pill);
    background: linear-gradient(135deg, var(--accent), #d96030);
    box-shadow: 0 0 60px rgba(255, 138, 76, 0.4);
    transition: transform 80ms linear, box-shadow 0.3s var(--ease);
  }
  /* Listening — slow, gentle breathing. */
  .orb.listening .orb-core {
    animation: breathe 2.4s ease-in-out infinite;
  }
  /* Thinking — quicker breathing pulse. */
  .orb.thinking .orb-core {
    animation: breathe 1.6s ease-in-out infinite;
  }
  @keyframes breathe {
    0%, 100% { transform: scale(0.92); opacity: 0.7; }
    50% { transform: scale(1.06); opacity: 1; }
  }
  /* Speaking — faster, brighter pulse. */
  .orb.speaking .orb-core {
    animation: speak-pulse 0.6s ease-in-out infinite;
  }
  @keyframes speak-pulse {
    0%, 100% { transform: scale(1); }
    50% { transform: scale(1.12); box-shadow: 0 0 90px rgba(255, 138, 76, 0.6); }
  }
  .vo-state {
    font-size: var(--fs-lg);
    font-weight: 600;
    color: var(--text-strong);
    letter-spacing: 0.02em;
  }
  .vo-caption {
    font-size: var(--fs-md);
    color: var(--muted);
    text-align: center;
    line-height: var(--lh-base);
    max-height: 5.5em;
    overflow: hidden;
  }
  .vo-hint {
    position: absolute;
    bottom: calc(var(--sp-5) + env(safe-area-inset-bottom));
    left: 0;
    right: 0;
    text-align: center;
    color: var(--muted);
    font-size: var(--fs-xs);
    padding: 0 var(--sp-4);
  }
</style>
