// Voice-activity-detection recorder. Lifted from the Composer's inline VAD so
// both the single-shot mic button and continuous voice mode can share it.
//
// recordUtterance() starts capturing immediately, watches the mic RMS level,
// and auto-stops after a stretch of trailing silence (once speech was heard).
// Resolves with the recorded audio Blob — or an empty Blob if cancelled or
// nothing was captured.

const VAD_SPEECH_RMS = 0.04; // confirm "user is talking"
const VAD_SILENCE_RMS = 0.018; // back to silence after speech
const VAD_SILENCE_MS = 1400; // auto-stop after this much trailing silence
const VAD_MAX_MS = 30000; // hard cap

export interface Utterance {
  /** Resolves with the captured audio (empty Blob when cancelled / silent). */
  promise: Promise<Blob>;
  /** Stop now and resolve with whatever was captured. */
  stop: () => void;
  /** Abandon — resolve with an empty Blob. */
  cancel: () => void;
}

export function recordUtterance(onLevel?: (level: number) => void): Utterance {
  let resolve!: (b: Blob) => void;
  const promise = new Promise<Blob>((res) => (resolve = res));

  let recorder: MediaRecorder | null = null;
  let stream: MediaStream | null = null;
  let audioCtx: AudioContext | null = null;
  let raf = 0;
  let chunks: Blob[] = [];
  let done = false;
  let cancelled = false;

  function teardown() {
    cancelAnimationFrame(raf);
    raf = 0;
    try {
      audioCtx?.close();
    } catch {
      /* ignore */
    }
    audioCtx = null;
    stream?.getTracks().forEach((t) => t.stop());
    stream = null;
  }

  function finish(blob: Blob) {
    if (done) return;
    done = true;
    teardown();
    resolve(blob);
  }

  (async () => {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      finish(new Blob());
      return;
    }
    chunks = [];
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : MediaRecorder.isTypeSupported('audio/mp4')
        ? 'audio/mp4'
        : '';
    try {
      recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
    } catch {
      recorder = new MediaRecorder(stream);
    }
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data);
    };
    recorder.onstop = () => {
      if (cancelled) {
        finish(new Blob());
        return;
      }
      finish(new Blob(chunks, { type: recorder?.mimeType || 'audio/webm' }));
    };

    try {
      audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 1024;
      analyser.smoothingTimeConstant = 0.4;
      source.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);

      const startedAt = performance.now();
      let speechSeen = false;
      let silenceStart = 0;

      const tick = () => {
        if (done) return;
        analyser.getByteTimeDomainData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) {
          const v = (data[i] - 128) / 128;
          sum += v * v;
        }
        const rms = Math.sqrt(sum / data.length);
        onLevel?.(Math.min(1, rms * 8));

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
        raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    } catch {
      // VAD setup failed — recording still works, just no auto-stop.
    }

    recorder.start();
  })();

  return {
    promise,
    stop: () => {
      try {
        recorder?.stop();
      } catch {
        /* ignore */
      }
    },
    cancel: () => {
      cancelled = true;
      try {
        recorder?.stop();
      } catch {
        /* ignore */
      }
      finish(new Blob());
    }
  };
}

/** Strip markdown / tool-chip / image noise so TTS reads clean prose. */
export function speakableText(s: string): string {
  let out = s;
  out = out.replace(/<details[\s\S]*?<\/details>/g, '');
  out = out.replace(/\n+-{3,}\n+\*[^*\n]+\*\s*$/m, '');
  out = out.replace(/```[\s\S]*?```/g, '\n(code block)\n');
  out = out.replace(/`([^`\n]+)`/g, '$1');
  out = out.replace(/!\[[^\]]*\]\([^)]+\)/g, '');
  out = out.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
  out = out.replace(/\*\*([^*]+)\*\*/g, '$1');
  out = out.replace(/\*([^*\n]+)\*/g, '$1');
  out = out.replace(/^#{1,6}\s+/gm, '');
  out = out.replace(/^\s*[-*+]\s+/gm, '');
  out = out.replace(/\n{3,}/g, '\n\n');
  return out.trim();
}
