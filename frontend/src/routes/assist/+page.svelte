<script lang="ts">
  /**
   * Gemini-style assistant overlay. Loaded live (…/assist) inside the Android
   * assistant overlay's transparent WebView — see AssistActivity.
   *
   *  - Transparent page; only the bubble + input pill are opaque.
   *  - ONE bubble: shows the latest assistant turn only. Full context is kept
   *    in `messages` and persisted server-side.
   *  - Swiping up on the bubble opens the full app via the TomSenseNative bridge.
   *  - Replies render through cleanForOverlay() — chips/footer hidden.
   *  - Waveform button → hands-free live conversation (VoiceOverlay).
   *  - "Use screen" chip (only shown when Android handed us a screenshot of
   *    the screen you were on) attaches that screen to your next question.
   */
  import { onDestroy, tick } from 'svelte';
  import Message from '$lib/components/Message.svelte';
  import VoiceOverlay from '$lib/components/VoiceOverlay.svelte';
  import { IconMic, IconSquare, IconSend, IconWaveform, IconMonitor, IconX } from '$lib/icons';
  import { Capacitor } from '@capacitor/core';
  import { createChat, getChat, streamChat, transcribeAudio, uploadFile } from '$lib/api';
  import { speakableText } from '$lib/voice';
  import { toast } from '$lib/toast.svelte';
  import type { Message as Msg } from '$lib/types';

  // Live conversation needs the app's native voice engine. The assist overlay
  // runs in a plain WebView (no Capacitor context, so isNativePlatform is
  // false) but the TomSenseNative bridge is present — either signal means we're
  // in the app and can offer live mode.
  const nativePlatform = Capacitor.isNativePlatform();
  const liveAvailable =
    nativePlatform || (typeof window !== 'undefined' && !!(window as any).TomSenseNative);

  let chatId = $state<string | null>(null);
  let messages = $state<Msg[]>([]);
  let busy = $state(false);
  // Live client-tool confirmation ("Opening Spotify…") shown in the bubble.
  let toolStatus = $state('');
  let input = $state('');
  let liveOpen = $state(false);
  let inputEl: HTMLTextAreaElement | undefined = $state();
  let bubbleEl: HTMLElement | undefined = $state();
  let bubbleScrollEl: HTMLElement | undefined = $state();
  let abortController: AbortController | null = null;

  let lastAssistant = $derived(
    messages.length > 0 && messages[messages.length - 1].role === 'assistant'
      ? messages[messages.length - 1]
      : null
  );
  let bubbleText = $derived(lastAssistant ? cleanForOverlay(lastAssistant.content) : '');
  let thinking = $derived(busy && bubbleText === '');

  function scrollBubbleToBottom() {
    if (bubbleScrollEl) bubbleScrollEl.scrollTop = bubbleScrollEl.scrollHeight;
  }

  /**
   * Strip the overlay reply to just the answer: no tool-call/reasoning
   * <details> chips, no trailing "---\n*stats*" footer. Display-only.
   */
  function cleanForOverlay(s: string): string {
    return s
      .replace(/<details[\s\S]*?<\/details>/g, '')
      .replace(/<details[\s\S]*$/, '')
      .replace(/\n*-{3,}\n+\*[^\n]*$/, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  function nativeBridge(): any {
    return (window as any).TomSenseNative;
  }

  // ─── screen context ─────────────────────────────────────────────────────
  // Android hands the assistant a screenshot of the screen you were on. We
  // only attach it — and only over the app's normal upload — when you tap the
  // "Use screen" chip. It's per-question: the chip resets after each send.
  let screenAvailable = $state(false);
  let useScreen = $state(false);
  let screenBusy = $state(false);
  let screenUploadId: string | null = null;
  let screenPollIv: ReturnType<typeof setInterval> | null = null;

  function stopScreenPoll() {
    if (screenPollIv) {
      clearInterval(screenPollIv);
      screenPollIv = null;
    }
  }

  // The screenshot lands a moment after the overlay opens — poll briefly.
  // Re-run on every fresh invocation: ScreenCapture is per-invocation.
  function pollForScreen() {
    stopScreenPoll();
    screenAvailable = false;
    const n = nativeBridge();
    if (!n?.hasScreen) return;
    if (n.hasScreen()) {
      screenAvailable = true;
      return;
    }
    let tries = 0;
    screenPollIv = setInterval(() => {
      if (nativeBridge()?.hasScreen?.()) {
        screenAvailable = true;
        stopScreenPoll();
      } else if (++tries >= 16) {
        stopScreenPoll();
      }
    }, 300);
  }

  $effect(() => {
    pollForScreen();
    return stopScreenPoll;
  });

  async function toggleScreen() {
    if (screenBusy) return;
    if (useScreen) {
      useScreen = false;
      return;
    }
    if (screenUploadId) {
      useScreen = true; // already uploaded earlier this session
      return;
    }
    const b64: string | null = nativeBridge()?.getScreen?.() ?? null;
    if (!b64) {
      toast.error('No screen capture available');
      return;
    }
    screenBusy = true;
    try {
      const blob = await (await fetch(`data:image/jpeg;base64,${b64}`)).blob();
      const file = new File([blob], 'screen.jpg', { type: 'image/jpeg' });
      screenUploadId = (await uploadFile(file)).id;
      useScreen = true;
    } catch (e) {
      toast.error(`Couldn't attach screen: ${(e as Error).message}`);
    } finally {
      screenBusy = false;
    }
  }

  // ─── chat ───────────────────────────────────────────────────────────────
  async function streamRound(uploadIds: string[] | null) {
    const assistantIdx = messages.length - 1;
    busy = true;
    abortController = new AbortController();
    let acc = '';
    try {
      const history = messages.slice(0, -1).map((m) => ({ role: m.role, content: m.content }));
      for await (const ev of streamChat(history, chatId, uploadIds, abortController.signal)) {
        if (ev.type === 'text' && ev.text) {
          acc += ev.text;
          messages[assistantIdx] = { role: 'assistant', content: acc };
          tick().then(scrollBubbleToBottom);
        } else if (ev.type === 'tool_status') {
          toolStatus = ev.text ?? '';
        } else if (ev.type === 'error' && ev.error) {
          acc += `\n\n*[error: ${ev.error}]*`;
          messages[assistantIdx] = { role: 'assistant', content: acc };
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') {
        acc += `\n\n*[stream error: ${(e as Error).message}]*`;
        messages[assistantIdx] = { role: 'assistant', content: acc };
      }
    } finally {
      toolStatus = '';
      busy = false;
      abortController = null;
    }
  }

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    input = '';
    if (inputEl) inputEl.style.height = 'auto';
    if (!chatId) {
      try {
        chatId = (await createChat()).id;
      } catch (e) {
        toast.error(`Couldn't start chat: ${(e as Error).message}`);
        input = text;
        return;
      }
    }
    // The screen, if armed, attaches to this one message only.
    const uploadIds = useScreen && screenUploadId ? [screenUploadId] : null;
    useScreen = false;
    messages = [...messages, { role: 'user', content: text }];
    messages = [...messages, { role: 'assistant', content: '' }];
    await streamRound(uploadIds);
  }

  function onStop() {
    abortController?.abort();
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }
  function autosize() {
    if (!inputEl) return;
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 110) + 'px';
  }

  // Deliberately no auto-focus on mount: the overlay opens keyboard-free
  // (the activity is also windowSoftInputMode=stateAlwaysHidden). The user
  // taps the field — or the mic — when they're ready. Auto-focusing raced
  // the soft keyboard on a reused overlay and left it covering the input.

  // ─── live conversation mode ─────────────────────────────────────────────
  async function openLive() {
    abortController?.abort();
    if (!chatId) {
      try {
        chatId = (await createChat()).id;
      } catch (e) {
        toast.error(`Couldn't start chat: ${(e as Error).message}`);
        return;
      }
    }
    const n = nativeBridge();
    if (n?.openApp) {
      // Overlay WebView: Capacitor plugins aren't available, so run the voice
      // loop inline using the Web Speech API (SpeechRecognition + speechSynthesis),
      // which Android WebView supports natively.
      openOverlayVoice();
    } else {
      // Full Capacitor app context: use VoiceOverlay with native STT/TTS plugins.
      liveOpen = true;
    }
  }

  // ─── inline Web Speech API voice loop (overlay WebView only) ────────────
  let overlayVoiceOpen = $state(false);
  let overlayVoiceState = $state<'idle' | 'listening' | 'thinking' | 'speaking'>('idle');
  let overlayVoiceCaption = $state('');
  let overlayVoiceActive = false;
  let overlayVoiceAbort: AbortController | null = null;

  function openOverlayVoice() {
    overlayVoiceOpen = true;
    overlayVoiceActive = true;
    runOverlayVoiceLoop();
  }

  function closeOverlayVoice() {
    overlayVoiceActive = false;
    overlayVoiceOpen = false;
    overlayVoiceState = 'idle';
    overlayVoiceCaption = '';
    overlayVoiceAbort?.abort();
    overlayVoiceAbort = null;
    window.speechSynthesis?.cancel();
  }

  function overlayListenOnce(): Promise<string> {
    return new Promise((resolve, reject) => {
      const SR =
        (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
      if (!SR) {
        reject(new Error('unavailable'));
        return;
      }
      const r = new SR();
      r.lang = 'en-US';
      r.interimResults = false;
      r.maxAlternatives = 1;
      let settled = false;
      r.onresult = (e: any) => {
        settled = true;
        resolve(e.results[0][0].transcript);
      };
      r.onerror = (e: any) => {
        if (!settled) { settled = true; reject(new Error(e.error || 'error')); }
      };
      r.onend = () => {
        if (!settled) { settled = true; reject(new Error('no_speech')); }
      };
      r.start();
    });
  }

  function overlaySpeakOnce(text: string): Promise<void> {
    return new Promise((resolve) => {
      if (!text || !window.speechSynthesis) { resolve(); return; }
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      let done = false;
      const finish = () => { if (!done) { done = true; resolve(); } };
      u.onend = finish;
      u.onerror = finish;
      // Poll for early cancellation (user tapped close while speaking).
      const iv = setInterval(() => {
        if (!overlayVoiceActive) { window.speechSynthesis.cancel(); clearInterval(iv); finish(); }
      }, 150);
      u.onend = () => { clearInterval(iv); finish(); };
      u.onerror = () => { clearInterval(iv); finish(); };
      window.speechSynthesis.speak(u);
    });
  }

  async function runOverlayVoiceLoop() {
    while (overlayVoiceActive) {
      // 1. Listen
      overlayVoiceState = 'listening';
      overlayVoiceCaption = '';
      let transcript: string;
      try {
        transcript = await overlayListenOnce();
      } catch (e) {
        const msg = (e as Error).message || '';
        if (msg === 'no_speech') continue; // silence — just listen again
        if (msg === 'unavailable') {
          toast.error('Speech recognition not available in this context');
          closeOverlayVoice();
          return;
        }
        // Other errors (e.g. aborted) — just retry
        continue;
      }
      if (!overlayVoiceActive) break;
      overlayVoiceCaption = transcript;

      // 2. LLM stream
      overlayVoiceState = 'thinking';
      messages = [...messages, { role: 'user', content: transcript }];
      messages = [...messages, { role: 'assistant', content: '' }];
      const assistantIdx = messages.length - 1;
      overlayVoiceAbort = new AbortController();
      let reply = '';
      try {
        const history = messages
          .slice(0, -1)
          .map((m) => ({ role: m.role, content: m.content }));
        for await (const ev of streamChat(
          history, chatId, null, overlayVoiceAbort.signal
        )) {
          if (!overlayVoiceActive) break;
          if (ev.type === 'text' && ev.text) {
            reply += ev.text;
            messages[assistantIdx] = { role: 'assistant', content: reply };
            tick().then(scrollBubbleToBottom);
          } else if (ev.type === 'tool_status' && ev.text) {
            overlayVoiceCaption = ev.text; // "Opening Spotify…" under the dots
          }
        }
      } catch {
        closeOverlayVoice();
        return;
      }
      overlayVoiceAbort = null;
      if (!overlayVoiceActive) break;

      // 3. Speak
      if (reply) {
        overlayVoiceState = 'speaking';
        overlayVoiceCaption = 'Tap × to stop';
        await overlaySpeakOnce(speakableText(reply));
      }
      if (!overlayVoiceActive) break;
    }
    if (overlayVoiceActive) closeOverlayVoice();
  }
  async function syncFromServer() {
    if (!chatId) return;
    try {
      const chat = await getChat(chatId);
      messages = (chat.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({ role: m.role, content: m.content }));
    } catch {
      /* keep whatever we have */
    }
  }

  // ─── native bridge (swipe-up → open app, backdrop tap → dismiss) ────────
  function openInApp() {
    // Hand the current chat to the full app so the conversation continues.
    const path = chatId ? `/c/${chatId}` : '/';
    const n = nativeBridge();
    if (n?.openApp) n.openApp(path);
    else window.location.href = path;
  }
  function closeOverlay() {
    nativeBridge()?.close?.();
  }

  // ─── fresh-invocation reset ─────────────────────────────────────────────
  // The Android overlay's WebView is reused across assist-gesture invocations
  // (a Home-button dismiss keeps it alive). AssistActivity calls this — via
  // window.__assistNewChat — when the gesture re-opens an already-alive
  // overlay, so each invocation is its own conversation. Messages sent within
  // one open overlay still share a chat (chatId is minted once, on first send).
  function resetForNewInvocation() {
    abortController?.abort();
    closeOverlayVoice();
    liveOpen = false;
    chatId = null;
    messages = [];
    input = '';
    busy = false;
    toolStatus = '';
    useScreen = false;
    screenBusy = false;
    screenUploadId = null;
    dragging = false;
    dragDy = 0;
    if (inputEl) inputEl.style.height = 'auto';
    pollForScreen();
  }

  $effect(() => {
    (window as any).__assistNewChat = resetForNewInvocation;
    return () => {
      if ((window as any).__assistNewChat === resetForNewInvocation) {
        delete (window as any).__assistNewChat;
      }
    };
  });

  // ─── bubble gesture: manual scroll + swipe-up-to-open ───────────────────
  // The whole bubble owns touch (touch-action:none) so the browser never
  // hijacks the drag or paints its own overscroll stretch. One pointer drag:
  //   • within the reply     → scrolls the reply
  //   • past the bottom edge → lifts the bubble; a large lift opens the app
  //   • past the top edge    → a damped rubber-band that snaps back
  let dragDy = $state(0); // signed: >0 lifted up (toward open), <0 pushed down
  let dragging = $state(false);
  let dragStartY = 0;
  let dragStartScroll = 0;
  let dragMaxScroll = 0;
  let dragId = -1;
  const OPEN_THRESHOLD = 90; // px lifted past the bottom edge to commit to open
  const TOP_RUBBER = 0.35; // resistance on the (no-op) downward over-pull

  function onBubblePointerDown(e: PointerEvent) {
    if (dragging) return;
    const el = bubbleScrollEl;
    dragging = true;
    dragStartY = e.clientY;
    dragStartScroll = el?.scrollTop ?? 0;
    dragMaxScroll = el ? Math.max(0, el.scrollHeight - el.clientHeight) : 0;
    dragId = e.pointerId;
    dragDy = 0;
    bubbleEl?.setPointerCapture(e.pointerId);
  }
  function onBubblePointerMove(e: PointerEvent) {
    if (!dragging || e.pointerId !== dragId) return;
    const dy = e.clientY - dragStartY; // +down, -up
    const target = dragStartScroll - dy; // where the reply would scroll to
    const el = bubbleScrollEl;
    if (target < 0) {
      // pulled past the top — damped rubber-band, never opens
      if (el) el.scrollTop = 0;
      dragDy = target * TOP_RUBBER;
    } else if (target > dragMaxScroll) {
      // pulled past the bottom — this lift is the open gesture
      if (el) el.scrollTop = dragMaxScroll;
      dragDy = target - dragMaxScroll;
    } else {
      // within the reply — plain scroll, bubble stays put
      if (el) el.scrollTop = target;
      dragDy = 0;
    }
  }
  function onBubblePointerUp(e: PointerEvent) {
    if (!dragging || e.pointerId !== dragId) return;
    dragging = false;
    dragId = -1;
    if (dragDy > OPEN_THRESHOLD) {
      // Hand off immediately — the reply keeps generating server-side and the
      // full app reconnects to it (detached runs), so swiping up mid-reply no
      // longer loses anything.
      dragDy = window.innerHeight; // animate up and out, then hand off
      setTimeout(openInApp, 160);
    } else {
      dragDy = 0; // small swipe — snap back (the .bubble transition animates it)
    }
  }
  function onBubblePointerCancel(e: PointerEvent) {
    if (!dragging || e.pointerId !== dragId) return;
    dragging = false;
    dragId = -1;
    dragDy = 0;
  }

  // ─── voice input (record → VAD auto-stop → transcribe into input) ───────
  let recorder: MediaRecorder | null = null;
  let recording = $state(false);
  let transcribing = $state(false);
  let audioLevel = $state(0);
  let chunks: Blob[] = [];
  let mediaStream: MediaStream | null = null;
  let audioCtx: AudioContext | null = null;
  let vadRaf = 0;

  const VAD_SPEECH_RMS = 0.04;
  const VAD_SILENCE_RMS = 0.018;
  const VAD_SILENCE_MS = 1400;
  const VAD_MAX_MS = 30000;

  function tearDownAudio() {
    cancelAnimationFrame(vadRaf);
    vadRaf = 0;
    try {
      audioCtx?.close();
    } catch {
      /* already closed */
    }
    audioCtx = null;
    mediaStream?.getTracks().forEach((t) => t.stop());
    mediaStream = null;
    audioLevel = 0;
  }

  async function toggleRecord() {
    if (recording) {
      recorder?.stop();
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      toast.error('Microphone not available');
      return;
    }
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      toast.error('Microphone permission denied');
      return;
    }
    chunks = [];
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : MediaRecorder.isTypeSupported('audio/mp4')
        ? 'audio/mp4'
        : '';
    try {
      recorder = mime
        ? new MediaRecorder(mediaStream, { mimeType: mime })
        : new MediaRecorder(mediaStream);
    } catch {
      recorder = new MediaRecorder(mediaStream);
    }
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data);
    };
    recorder.onstop = async () => {
      recording = false;
      tearDownAudio();
      const blob = new Blob(chunks, { type: recorder?.mimeType || 'audio/webm' });
      if (blob.size === 0) return;
      transcribing = true;
      try {
        const ext = (recorder?.mimeType || 'audio/webm').includes('mp4') ? 'm4a' : 'webm';
        const text = await transcribeAudio(blob, `speech.${ext}`);
        if (text) {
          input = input ? `${input.trimEnd()} ${text}` : text;
          autosize();
          inputEl?.focus();
        }
      } catch (e) {
        toast.error(`Transcription failed: ${(e as Error).message}`);
      } finally {
        transcribing = false;
      }
    };

    try {
      audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
      const source = audioCtx.createMediaStreamSource(mediaStream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 1024;
      analyser.smoothingTimeConstant = 0.4;
      source.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);
      const startedAt = performance.now();
      let speechSeen = false;
      let silenceStart = 0;

      const tickVad = () => {
        if (!recording) return;
        analyser.getByteTimeDomainData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) {
          const v = (data[i] - 128) / 128;
          sum += v * v;
        }
        const rms = Math.sqrt(sum / data.length);
        audioLevel = Math.min(1, rms * 8);
        if (rms > VAD_SPEECH_RMS) {
          speechSeen = true;
          silenceStart = 0;
        } else if (speechSeen && rms < VAD_SILENCE_RMS) {
          if (silenceStart === 0) silenceStart = performance.now();
          if (performance.now() - silenceStart > VAD_SILENCE_MS) {
            try {
              recorder?.stop();
            } catch {
              /* ignore */
            }
            return;
          }
        }
        if (performance.now() - startedAt > VAD_MAX_MS) {
          try {
            recorder?.stop();
          } catch {
            /* ignore */
          }
          return;
        }
        vadRaf = requestAnimationFrame(tickVad);
      };
      vadRaf = requestAnimationFrame(tickVad);
    } catch (e) {
      console.warn('VAD setup failed:', e);
    }

    recorder.start();
    recording = true;
  }

  onDestroy(() => {
    closeOverlayVoice();
    try {
      recorder?.stop();
    } catch {
      /* ignore */
    }
    tearDownAudio();
  });
</script>

<svelte:head><title>Ask TomSense</title></svelte:head>

<div class="overlay">
  <button class="backdrop" type="button" aria-label="Dismiss assistant" onclick={closeOverlay}
  ></button>

  <div
    class="bubble"
    class:dragging
    bind:this={bubbleEl}
    style:transform={`translateY(${-dragDy}px)`}
    style:opacity={Math.max(0.35, 1 - Math.max(0, dragDy) / 480)}
    onpointerdown={onBubblePointerDown}
    onpointermove={onBubblePointerMove}
    onpointerup={onBubblePointerUp}
    onpointercancel={onBubblePointerCancel}
  >
    <div class="handle" title="Swipe up to open in TomSense">
      <span class="grip" aria-hidden="true"></span>
    </div>
    <div class="body" bind:this={bubbleScrollEl}>
      {#if thinking}
        <div class="thinking" aria-label="Thinking">
          <span></span><span></span><span></span>
        </div>
      {:else if lastAssistant}
        <Message role="assistant" content={bubbleText} />
      {:else}
        <p class="greeting">Hi — ask me anything.</p>
      {/if}
      {#if toolStatus}
        <p class="tool-status" aria-live="polite">{toolStatus}</p>
      {/if}
    </div>
  </div>

  {#if screenAvailable}
    <div class="chip-row">
      <button
        type="button"
        class="screen-chip"
        class:on={useScreen}
        onclick={toggleScreen}
        disabled={screenBusy}
      >
        {#if screenBusy}<span class="spinner small" aria-hidden="true"></span>
        {:else}<IconMonitor size={14} />{/if}
        <span>{useScreen ? 'Screen attached' : screenBusy ? 'Attaching…' : 'Use screen'}</span>
      </button>
    </div>
  {/if}

  <div class="pill">
    <textarea
      bind:this={inputEl}
      bind:value={input}
      placeholder={recording ? 'Listening…' : transcribing ? 'Transcribing…' : 'Ask TomSense…'}
      rows="1"
      onkeydown={onKeydown}
      oninput={autosize}
    ></textarea>

    <button
      type="button"
      class="circle mic"
      class:rec={recording}
      onclick={toggleRecord}
      disabled={transcribing}
      aria-label={recording ? 'Stop recording' : 'Voice input'}
      style:--audio-level={audioLevel}
    >
      {#if transcribing}<span class="spinner" aria-hidden="true"></span>
      {:else if recording}<IconSquare size={15} />
      {:else}<IconMic size={18} />{/if}
    </button>

    {#if liveAvailable}
      <button
        type="button"
        class="circle live"
        onclick={openLive}
        aria-label="Live conversation"
        title="Live conversation"
      >
        <IconWaveform size={18} />
      </button>
    {/if}

    {#if busy}
      <button class="circle send stop" type="button" onclick={onStop} aria-label="Stop">
        <IconSquare size={14} />
      </button>
    {:else}
      <button
        class="circle send"
        type="button"
        onclick={send}
        disabled={!input.trim()}
        aria-label="Send"
      >
        <IconSend size={17} />
      </button>
    {/if}
  </div>
</div>

{#if overlayVoiceOpen}
  <div class="ov-voice">
    <div class="ov-voice-body">
      {#if overlayVoiceState === 'listening'}
        <div class="ov-pulse listening" aria-hidden="true"></div>
        <span class="ov-label">Listening…</span>
      {:else if overlayVoiceState === 'thinking'}
        <div class="thinking" aria-hidden="true">
          <span></span><span></span><span></span>
        </div>
        <span class="ov-label">{overlayVoiceCaption || 'Thinking…'}</span>
      {:else if overlayVoiceState === 'speaking'}
        <div class="ov-pulse speaking" aria-hidden="true"></div>
        <span class="ov-label">{overlayVoiceCaption || 'Speaking…'}</span>
      {/if}
    </div>
    <button class="ov-close circle" type="button" onclick={closeOverlayVoice} aria-label="End voice">
      <IconX size={16} />
    </button>
  </div>
{/if}

{#if chatId}
  <VoiceOverlay
    chatId={chatId}
    open={liveOpen}
    onclose={() => {
      liveOpen = false;
      syncFromServer();
    }}
  />
{/if}

<style>
  /* The overlay page is transparent — the Android homescreen shows through. */
  :global(html),
  :global(body) {
    background: transparent !important;
  }

  .overlay {
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    height: 100dvh;
    padding: 10px;
    gap: 8px;
    background: transparent;
  }

  .backdrop {
    flex: 1;
    min-height: 40px;
    border: 0;
    padding: 0;
    background: transparent;
    cursor: default;
  }

  .bubble {
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: 480px;
    margin: 0 auto;
    max-height: 56dvh;
    background: #1c2129;
    border: 1px solid var(--border);
    border-radius: 22px;
    box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5);
    overflow: hidden;
    will-change: transform;
    /* The whole bubble owns touch: the gesture handler does the scrolling and
     * the drag-to-open, so the browser never steals the drag or paints its
     * own overscroll stretch (which used to fight the swipe-up). */
    touch-action: none;
  }
  .bubble:not(.dragging) {
    transition:
      transform 0.22s cubic-bezier(0.2, 0.7, 0.2, 1),
      opacity 0.22s ease;
  }

  .handle {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 30px;
    cursor: grab;
    touch-action: none;
  }
  .grip {
    width: 42px;
    height: 5px;
    border-radius: 999px;
    background: var(--border-strong);
  }

  .body {
    overflow-y: auto;
    /* Touch scrolling is driven by the gesture handler; native overscroll is
     * off so there's no stretch animation conflicting with the swipe-up. */
    overscroll-behavior: none;
    padding: 2px 16px 16px;
    min-height: 0;
    touch-action: none;
    /* A drag over the reply text must not become a text selection — that
     * cancels the pointer sequence and kills the swipe-up-to-open gesture. */
    user-select: none;
    -webkit-user-select: none;
  }
  .greeting {
    margin: 6px 0;
    color: var(--muted-strong);
    font-size: var(--fs-base);
  }

  .tool-status {
    margin: 4px 0 0;
    color: var(--muted-strong);
    font-size: calc(var(--fs-base) * 0.88);
    font-style: italic;
  }

  .thinking {
    display: flex;
    gap: 5px;
    padding: 8px 0;
  }
  .thinking span {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--muted);
    animation: bounce 1.2s infinite ease-in-out;
  }
  .thinking span:nth-child(2) {
    animation-delay: 0.15s;
  }
  .thinking span:nth-child(3) {
    animation-delay: 0.3s;
  }
  @keyframes bounce {
    0%, 60%, 100% {
      transform: translateY(0);
      opacity: 0.4;
    }
    30% {
      transform: translateY(-5px);
      opacity: 1;
    }
  }

  /* "Use screen" chip — only shown when a screenshot was handed to us. */
  .chip-row {
    width: 100%;
    max-width: 480px;
    margin: 0 auto;
  }
  .screen-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 12px;
    border-radius: 999px;
    background: var(--panel-2);
    color: var(--muted-strong);
    border: 1px solid var(--border);
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .screen-chip.on {
    background: var(--accent);
    color: var(--accent-fg);
    border-color: transparent;
  }
  .screen-chip:disabled {
    cursor: default;
  }

  /* Floating input pill. */
  .pill {
    display: flex;
    align-items: flex-end;
    gap: 6px;
    width: 100%;
    max-width: 480px;
    margin: 0 auto;
    padding: 6px;
    background: #232934;
    border: 1px solid var(--border);
    border-radius: 26px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
    padding-bottom: max(6px, env(safe-area-inset-bottom));
  }
  .pill textarea {
    flex: 1;
    resize: none;
    background: transparent;
    color: var(--text);
    border: 0;
    padding: 9px 8px 9px 12px;
    font: inherit;
    font-size: var(--fs-base);
    line-height: 1.35;
    max-height: 110px;
    overflow-y: auto;
    /* Rounded so the global :focus-visible ring follows the pill. */
    border-radius: 20px;
  }
  .pill textarea:focus {
    outline: none;
  }
  .pill textarea:focus-visible {
    border-radius: 20px;
  }
  .pill textarea::placeholder {
    color: var(--muted);
  }

  .circle {
    flex-shrink: 0;
    width: 38px;
    height: 38px;
    border-radius: 50%;
    border: 0;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .circle:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .mic,
  .live {
    background: transparent;
    color: var(--muted-strong);
  }
  .mic.rec {
    background: rgba(248, 113, 113, 0.16);
    color: var(--danger);
    box-shadow: 0 0 0 calc(var(--audio-level, 0) * 8px) rgba(248, 113, 113, 0.3);
    transition: box-shadow 80ms linear;
  }
  .send {
    background: var(--accent);
    color: var(--accent-fg);
  }
  .send.stop {
    background: var(--panel-3);
    color: var(--text);
  }
  .spinner {
    width: 15px;
    height: 15px;
    border: 2px solid currentColor;
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }
  .spinner.small {
    width: 12px;
    height: 12px;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  /* Inline voice mode panel — shown inside the overlay instead of handing off. */
  .ov-voice {
    position: fixed;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 24px;
    background: rgba(10, 12, 16, 0.88);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    z-index: 50;
  }
  .ov-voice-body {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
  }
  .ov-pulse {
    width: 72px;
    height: 72px;
    border-radius: 50%;
    animation: ov-pulse 1.4s ease-in-out infinite;
  }
  .ov-pulse.listening {
    background: var(--accent);
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--accent) 60%, transparent);
  }
  .ov-pulse.speaking {
    background: #a78bfa;
    box-shadow: 0 0 0 0 rgba(167, 139, 250, 0.6);
    animation-duration: 0.9s;
  }
  @keyframes ov-pulse {
    0%   { box-shadow: 0 0 0 0 color-mix(in srgb, currentColor 40%, transparent); }
    70%  { box-shadow: 0 0 0 18px transparent; }
    100% { box-shadow: 0 0 0 0 transparent; }
  }
  .ov-label {
    color: var(--text);
    font-size: var(--fs-base);
    text-align: center;
    max-width: 240px;
  }
  .ov-close {
    background: var(--panel-3);
    color: var(--muted-strong);
    margin-top: 8px;
  }
</style>
