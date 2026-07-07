<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { transcribeAudio, uploadFile } from '$lib/api';
  import { toast } from '$lib/toast.svelte';
  import ImageAnnotator from './ImageAnnotator.svelte';
  import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
  import {
    IconPaperclip,
    IconCamera,
    IconMic,
    IconSquare,
    IconSend,
    IconX,
    IconFile,
    IconFileText,
    IconImage,
    IconSparkles,
    IconBrain,
    IconEdit
  } from '$lib/icons';
  import type { UploadResponse } from '$lib/types';

  // ─── slash commands ──────────────────────────────────────────────────
  interface SlashCommand {
    name: string;
    label: string;
    hint: string;
    /** Builds the actual prompt sent to the model. */
    rewrite: (args: string) => string;
  }

  const COMMANDS: SlashCommand[] = [
    {
      name: '/image',
      label: 'image',
      hint: 'Generate a new image (text-to-image)',
      rewrite: (a) => `Generate an image of: ${a}. Call generate_image now.`
    },
    {
      name: '/edit',
      label: 'edit image',
      hint: 'Edit the attached image(s) (img2img)',
      rewrite: (a) => `Edit the attached image(s): ${a}. Call edit_image now.`
    },
    {
      name: '/web',
      label: 'web search',
      hint: 'Search the web with SearXNG',
      rewrite: (a) => `Search the web for: ${a}. Call web_search now.`
    },
    {
      name: '/docs',
      label: 'doc search',
      hint: 'Search your uploaded documents (RAG)',
      rewrite: (a) => `Search my uploaded documents for: ${a}. Call search_docs now.`
    },
    {
      name: '/research',
      label: 'deep research',
      hint: 'Multi-source synthesis via gpt-oss-120b',
      rewrite: (a) => `Research thoroughly: ${a}. Call deep_research now.`
    },
    {
      name: '/code',
      label: 'code',
      hint: 'Write a full program with gpt-oss-120b',
      rewrite: (a) => `Write complete code: ${a}. Call consult_coder now.`
    },
    {
      name: '/remember',
      label: 'remember',
      hint: 'Save a fact about yourself',
      rewrite: (a) => `Remember this about me: ${a}. Call remember now.`
    }
  ];

  function maybeRewrite(text: string): string {
    if (!text.startsWith('/')) return text;
    const space = text.indexOf(' ');
    const head = space === -1 ? text : text.slice(0, space);
    const body = space === -1 ? '' : text.slice(space + 1).trim();
    const cmd = COMMANDS.find((c) => c.name === head);
    if (!cmd || !body) return text;
    return cmd.rewrite(body);
  }

  interface PendingAttachment {
    key: string;
    file: File;
    objectUrl?: string;
    uploading: boolean;
    error?: string;
    uploaded?: UploadResponse;
  }

  interface Props {
    busy: boolean;
    onsend: (text: string, uploads: UploadResponse[]) => void;
    onstop?: () => void;
    /** "Think" toggle — parent sends the turn on the reasoning model. */
    think?: boolean;
  }

  let { busy, onsend, onstop, think = $bindable(false) }: Props = $props();
  let value = $state('');
  let textarea: HTMLTextAreaElement | undefined = $state();
  let fileInput: HTMLInputElement | undefined = $state();
  let pending = $state<PendingAttachment[]>([]);

  function uploading(): boolean {
    return pending.some((p) => p.uploading);
  }

  function canSend(): boolean {
    if (busy) return false;
    if (uploading()) return false;
    return value.trim().length > 0 || pending.some((p) => p.uploaded);
  }

  function addFile(file: File) {
    const key = crypto.randomUUID();
    const objectUrl = file.type.startsWith('image/')
      ? URL.createObjectURL(file)
      : undefined;
    const entry: PendingAttachment = { key, file, objectUrl, uploading: true };
    pending = [...pending, entry];
    uploadOne(entry);
  }

  async function addFiles(files: FileList | null) {
    if (!files) return;
    for (const file of Array.from(files)) addFile(file);
  }

  /** Pre-fill the composer — used by "Share to TomSense" on the welcome screen. */
  export function prefill(text: string, files: File[]) {
    if (text) value = text;
    for (const f of files) addFile(f);
    tick().then(() => {
      autoresize();
      textarea?.focus();
    });
  }

  /** Open the device camera and attach the photo as a normal image upload.
   *  Native Android uses @capacitor/camera's native UI; in the PWA the plugin
   *  falls back to a file picker. */
  async function takePhoto() {
    try {
      const photo = await Camera.getPhoto({
        quality: 85,
        resultType: CameraResultType.Uri,
        source: CameraSource.Camera,
        allowEditing: false,
        saveToGallery: false
      });
      if (!photo.webPath) return;
      const blob = await (await fetch(photo.webPath)).blob();
      const ext = (photo.format || 'jpg').replace('jpeg', 'jpg');
      const file = new File([blob], `photo-${Date.now()}.${ext}`, {
        type: blob.type || `image/${ext}`
      });
      addFile(file);
    } catch (e) {
      const msg = (e as Error)?.message || '';
      if (/cancel/i.test(msg)) return; // user backed out — not an error
      toast.error(`Camera unavailable: ${msg}`);
    }
  }

  async function uploadOne(entry: PendingAttachment) {
    try {
      const res = await uploadFile(entry.file);
      pending = pending.map((p) =>
        p.key === entry.key ? { ...p, uploaded: res, uploading: false } : p
      );
    } catch (e) {
      const msg = (e as Error).message;
      pending = pending.map((p) =>
        p.key === entry.key ? { ...p, uploading: false, error: msg } : p
      );
      toast.error(`Upload failed: ${msg}`);
    }
  }

  function removeAttachment(key: string) {
    const p = pending.find((x) => x.key === key);
    if (p?.objectUrl) URL.revokeObjectURL(p.objectUrl);
    pending = pending.filter((x) => x.key !== key);
  }

  // ─── annotation ───────────────────────────────────────────────────────
  let annotatingKey = $state<string | null>(null);
  let annotatingSrc = $derived.by(() => {
    if (!annotatingKey) return null;
    return pending.find((p) => p.key === annotatingKey)?.objectUrl ?? null;
  });

  function startAnnotate(key: string) {
    annotatingKey = key;
  }
  function cancelAnnotate() {
    annotatingKey = null;
  }
  async function saveAnnotation(blob: Blob) {
    if (!annotatingKey) return;
    const target = pending.find((p) => p.key === annotatingKey);
    if (!target) return;
    const file = new File([blob], `annotated-${target.file.name.replace(/\.[^.]+$/, '')}.png`, {
      type: 'image/png'
    });
    // Revoke old preview, build new one
    if (target.objectUrl) URL.revokeObjectURL(target.objectUrl);
    const objectUrl = URL.createObjectURL(file);
    pending = pending.map((p) =>
      p.key === annotatingKey
        ? { key: p.key, file, objectUrl, uploading: true, uploaded: undefined, error: undefined }
        : p
    );
    annotatingKey = null;
    // Re-upload the annotated image
    try {
      const res = await uploadFile(file);
      pending = pending.map((p) =>
        p.key === target.key ? { ...p, uploaded: res, uploading: false } : p
      );
      toast.success('Annotated image saved');
    } catch (e) {
      pending = pending.map((p) =>
        p.key === target.key
          ? { ...p, uploading: false, error: (e as Error).message }
          : p
      );
      toast.error(`Upload failed: ${(e as Error).message}`);
    }
  }

  function submit() {
    if (!canSend()) return;
    const text = maybeRewrite(value.trim());
    const uploaded = pending.filter((p) => p.uploaded).map((p) => p.uploaded!);
    for (const p of pending) if (p.objectUrl) URL.revokeObjectURL(p.objectUrl);
    onsend(text, uploaded);
    value = '';
    pending = [];
    autoresize();
    closeSlashMenu();
  }

  // ─── slash menu state ────────────────────────────────────────────────
  let slashOpen = $state(false);
  let slashIdx = $state(0);
  let slashMatches = $derived.by(() => {
    if (!slashOpen) return [] as SlashCommand[];
    const head = value.split(/\s/)[0] ?? '';
    if (!head.startsWith('/')) return [];
    const q = head.slice(1).toLowerCase();
    return COMMANDS.filter((c) => c.name.slice(1).startsWith(q)).slice(0, 7);
  });

  function maybeOpenSlash() {
    // Only open if the textarea starts with '/' and there's no space yet
    if (value.startsWith('/') && !value.includes(' ')) {
      slashOpen = true;
      slashIdx = 0;
    } else {
      slashOpen = false;
    }
  }
  function closeSlashMenu() {
    slashOpen = false;
    slashIdx = 0;
  }
  function pickSlash(cmd: SlashCommand) {
    value = cmd.name + ' ';
    closeSlashMenu();
    textarea?.focus();
    // Move cursor to end
    requestAnimationFrame(() => {
      if (textarea) textarea.selectionStart = textarea.selectionEnd = value.length;
    });
  }
  function slashIcon(name: string) {
    if (name === '/image' || name === '/edit') return 'image';
    if (name === '/remember') return 'brain';
    return 'sparkles';
  }

  function onKeydown(e: KeyboardEvent) {
    if (slashOpen && slashMatches.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        slashIdx = (slashIdx + 1) % slashMatches.length;
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        slashIdx = (slashIdx - 1 + slashMatches.length) % slashMatches.length;
        return;
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey && !value.includes(' '))) {
        e.preventDefault();
        pickSlash(slashMatches[slashIdx]);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        closeSlashMenu();
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  function autoresize() {
    if (!textarea) return;
    textarea.style.height = 'auto';
    const max = 240;
    textarea.style.height = Math.min(textarea.scrollHeight, max) + 'px';
  }

  $effect(() => {
    void value;
    autoresize();
    maybeOpenSlash();
  });

  // ─── voice (with VAD-based auto-stop) ────────────────────────────────
  let recorder: MediaRecorder | null = $state(null);
  let recording = $state(false);
  let transcribing = $state(false);
  let audioLevel = $state(0);
  let chunks: Blob[] = [];
  let mediaStream: MediaStream | null = null;
  let audioCtx: AudioContext | null = null;
  let vadRaf = 0;

  const VAD_SPEECH_RMS = 0.04;       // confirm "user is talking"
  const VAD_SILENCE_RMS = 0.018;     // back to silence after speech
  const VAD_SILENCE_MS = 1400;       // auto-stop after this much trailing silence
  const VAD_MAX_RECORDING_MS = 30000; // hard cap

  function tearDownAudio() {
    cancelAnimationFrame(vadRaf);
    vadRaf = 0;
    try {
      audioCtx?.close();
    } catch {}
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
    if (!('mediaDevices' in navigator) || !navigator.mediaDevices.getUserMedia) {
      toast.error('Microphone not supported on this browser');
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
      recorder = mime ? new MediaRecorder(mediaStream, { mimeType: mime }) : new MediaRecorder(mediaStream);
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
          value = value ? `${value.trimEnd()} ${text}` : text;
          autoresize();
          textarea?.focus();
        }
      } catch (e) {
        toast.error(`Transcription failed: ${(e as Error).message}`);
      } finally {
        transcribing = false;
      }
    };

    // VAD setup — run in parallel with MediaRecorder
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

      const tick = () => {
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
            } catch {}
            return;
          }
        }
        if (performance.now() - startedAt > VAD_MAX_RECORDING_MS) {
          try {
            recorder?.stop();
          } catch {}
          return;
        }
        vadRaf = requestAnimationFrame(tick);
      };
      vadRaf = requestAnimationFrame(tick);
    } catch (e) {
      // VAD failed — fall through; user still has the manual stop button.
      console.warn('VAD setup failed:', e);
    }

    recorder.start();
    recording = true;
  }

  onDestroy(() => {
    try {
      recorder?.stop();
    } catch {}
    tearDownAudio();
  });

  function formatSize(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  }

  function attachmentIcon(file: File): 'image' | 'pdf' | 'text' {
    const t = file.type || '';
    if (t.startsWith('image/')) return 'image';
    if (t === 'application/pdf') return 'pdf';
    return 'text';
  }
</script>

{#if annotatingSrc}
  <ImageAnnotator src={annotatingSrc} onsave={saveAnnotation} oncancel={cancelAnnotate} />
{/if}

<form
  onsubmit={(e) => {
    e.preventDefault();
    submit();
  }}
>
  {#if pending.length > 0}
    <div class="attachments">
      {#each pending as p (p.key)}
        <div class="chip" class:errored={!!p.error}>
          {#if p.objectUrl}
            <img src={p.objectUrl} alt={p.file.name} />
          {:else}
            <span class="chip-icon" aria-hidden="true">
              {#if attachmentIcon(p.file) === 'pdf'}<IconFile size={18} />{:else}<IconFileText size={18} />{/if}
            </span>
          {/if}
          <div class="chip-meta">
            <div class="chip-name" title={p.file.name}>{p.file.name}</div>
            <div class="chip-sub">
              {#if p.uploading}
                <span class="spinner" aria-hidden="true"></span>uploading…
              {:else if p.error}
                error
              {:else}
                {formatSize(p.file.size)}
              {/if}
            </div>
          </div>
          {#if p.objectUrl && !p.uploading}
            <button
              type="button"
              class="icon-btn small"
              aria-label="Annotate"
              title="Annotate"
              onclick={() => startAnnotate(p.key)}
            >
              <IconEdit size={14} />
            </button>
          {/if}
          <button
            type="button"
            class="icon-btn small"
            aria-label="Remove"
            onclick={() => removeAttachment(p.key)}
          >
            <IconX size={14} />
          </button>
        </div>
      {/each}
    </div>
  {/if}

  {#if slashOpen && slashMatches.length > 0}
    <div class="slash-menu" role="listbox" aria-label="Slash commands">
      {#each slashMatches as cmd, i (cmd.name)}
        <button
          type="button"
          class="slash-item"
          class:active={i === slashIdx}
          onclick={() => pickSlash(cmd)}
          onmouseenter={() => (slashIdx = i)}
        >
          <span class="slash-icon" aria-hidden="true">
            {#if slashIcon(cmd.name) === 'image'}<IconImage size={14} />
            {:else if slashIcon(cmd.name) === 'brain'}<IconBrain size={14} />
            {:else}<IconSparkles size={14} />{/if}
          </span>
          <span class="slash-name">{cmd.name}</span>
          <span class="slash-hint">{cmd.hint}</span>
        </button>
      {/each}
    </div>
  {/if}

  <!-- M3 chat input: textarea on top, action row below -->
  <div class="bar">
    <textarea
      bind:this={textarea}
      bind:value
      rows="2"
      placeholder={recording
        ? 'Listening…'
        : transcribing
        ? 'Transcribing…'
        : 'Message TomSense…'}
      onkeydown={onKeydown}
    ></textarea>

    <div class="actions">
      <div class="actions-left">
        <button
          type="button"
          class="icon-btn"
          aria-label="Attach file"
          title="Attach"
          onclick={() => fileInput?.click()}
        >
          <IconPaperclip size={20} />
        </button>
        <button
          type="button"
          class="icon-btn think-btn"
          class:active={think}
          aria-label="Think longer"
          aria-pressed={think}
          title={think ? 'Thinking mode ON — reasoning model, slower' : 'Think longer (reasoning model)'}
          onclick={() => (think = !think)}
        >
          <IconBrain size={20} />
        </button>
        <input
          type="file"
          bind:this={fileInput}
          multiple
          accept="image/*,text/*,audio/*,.md,.txt,.csv,.json,.yaml,.yml,.html,.css,.py,.js,.ts,.tsx,.jsx,.sh,.go,.rs,.java,.c,.cpp,.h,.sql,.toml,.ini,.log,.pdf,application/pdf,.docx,.xlsx,.xlsm,.pptx,.mp3,.wav,.m4a,.ogg,.flac,.aac,.opus,.webm"
          style="display:none"
          onchange={(e) => {
            addFiles((e.currentTarget as HTMLInputElement).files);
            (e.currentTarget as HTMLInputElement).value = '';
          }}
        />
        <button
          type="button"
          class="icon-btn"
          aria-label="Take photo"
          title="Camera"
          onclick={takePhoto}
        >
          <IconCamera size={20} />
        </button>
        <button
          type="button"
          class="icon-btn"
          class:mic-rec={recording}
          aria-label={recording ? 'Stop recording' : 'Record voice'}
          title={recording ? 'Auto-stops after silence' : 'Voice input'}
          disabled={transcribing}
          onclick={toggleRecord}
          style:--audio-level={audioLevel}
        >
          {#if transcribing}<span class="spinner" aria-hidden="true"></span>
          {:else if recording}<IconSquare size={16} />
          {:else}<IconMic size={20} />{/if}
        </button>
      </div>

      {#if busy && onstop}
        <button
          type="button"
          class="icon-btn filled send-btn stop-btn"
          onclick={onstop}
          aria-label="Stop generating"
          title="Stop"
        >
          <IconSquare size={16} />
        </button>
      {:else}
        <button
          type="submit"
          class="icon-btn filled send-btn"
          disabled={!canSend()}
          aria-label="Send"
        >
          <IconSend size={20} />
        </button>
      {/if}
    </div>
  </div>
</form>

<style>
  form {
    display: flex;
    flex-direction: column;
    padding: var(--sp-2) var(--sp-3) var(--sp-3);
    /* edge-to-edge (targetSdk 35): clear the gesture-nav bar. */
    padding-bottom: calc(var(--sp-3) + env(safe-area-inset-bottom));
    border-top: 1px solid var(--border);
    background: var(--bg);
    gap: var(--sp-2);
  }

  .bar {
    display: flex;
    flex-direction: column;
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-xl);
    padding: var(--sp-3) var(--sp-3) var(--sp-2);
    transition: border-color var(--t-fast), box-shadow var(--t-fast);
  }
  .bar:focus-within {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(255, 138, 76, 0.15);
  }

  textarea {
    width: 100%;
    min-height: 44px;
    max-height: 240px;
    resize: none;
    background: transparent;
    color: var(--text);
    border: 0;
    padding: 4px 8px;
    font-size: var(--fs-base);
    line-height: var(--lh-base);
    overflow-y: auto;
  }
  textarea:focus {
    outline: none;
    box-shadow: none;
  }
  textarea::placeholder {
    color: var(--muted);
  }

  .actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: var(--sp-1);
  }
  .actions-left {
    display: flex;
    gap: var(--sp-1);
  }
  .send-btn {
    width: 44px;
    height: 44px;
  }
  .stop-btn {
    background: var(--danger) !important;
    color: #1a0606 !important;
  }
  .stop-btn:hover {
    background: #ff8989 !important;
  }

  .mic-rec {
    color: var(--danger) !important;
    background: rgba(248, 113, 113, 0.14) !important;
    /* Live audio-level ring — grows with the detected RMS while recording.
     * VAD auto-stops after ~1.4s of silence. */
    box-shadow: 0 0 0 calc(var(--audio-level, 0) * 10px) rgba(248, 113, 113, 0.35);
    transition: box-shadow 80ms linear;
  }

  /* slash command menu */
  .slash-menu {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    overflow: hidden;
    box-shadow: var(--shadow-md);
  }
  .slash-item {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    width: 100%;
    padding: 8px 12px;
    background: transparent;
    border: 0;
    color: var(--text);
    text-align: left;
    font-size: var(--fs-sm);
    cursor: pointer;
    transition: background var(--t-fast);
  }
  .slash-item.active,
  .slash-item:hover {
    background: var(--row-hover);
  }
  .slash-icon {
    width: 22px;
    height: 22px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: var(--accent);
    flex-shrink: 0;
  }
  .slash-name {
    font-family: var(--font-mono);
    color: var(--text-strong);
    font-size: var(--fs-sm);
    min-width: 80px;
  }
  .slash-hint {
    color: var(--muted);
    font-size: var(--fs-xs);
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  /* attachment chips */
  .attachments {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
  }
  .chip {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: var(--r-md);
    padding: 4px 6px 4px 4px;
    max-width: 280px;
  }
  .chip.errored {
    border-color: var(--danger);
  }
  .chip img {
    width: 36px;
    height: 36px;
    object-fit: cover;
    border-radius: var(--r-sm);
    flex-shrink: 0;
  }
  .chip-icon {
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--panel-2);
    border-radius: var(--r-sm);
    color: var(--muted-strong);
    flex-shrink: 0;
  }
  .chip-meta {
    min-width: 0;
    flex: 1;
  }
  .chip-name {
    font-size: var(--fs-sm);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text);
  }
  .chip-sub {
    font-size: var(--fs-xs);
    color: var(--muted);
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .spinner {
    width: 14px;
    height: 14px;
    border: 1.5px solid var(--muted);
    border-top-color: transparent;
    border-radius: 50%;
    display: inline-block;
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  .think-btn.active {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    border-radius: var(--r-pill);
  }
</style>
