<script lang="ts">
  import { onMount } from 'svelte';
  import { IconX, IconTrash } from '$lib/icons';

  interface Props {
    src: string;
    onsave: (blob: Blob) => Promise<void> | void;
    oncancel: () => void;
  }

  let { src, onsave, oncancel }: Props = $props();

  let canvas: HTMLCanvasElement | undefined = $state();
  let img: HTMLImageElement | null = null;
  let drawing = false;
  let lastX = 0;
  let lastY = 0;
  let color = $state('#ff3b3b');
  let stroke = $state(6);
  let busy = $state(false);
  const PALETTE = ['#ff3b3b', '#ffd62a', '#3aa6ff', '#34d399', '#ffffff', '#0a0a0a'];

  onMount(() => {
    if (!canvas) return;
    const ctx = canvas.getContext('2d')!;
    img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      const maxW = Math.min(window.innerWidth - 32, 1024);
      const maxH = Math.min(window.innerHeight - 200, 1024);
      const scale = Math.min(1, maxW / img!.width, maxH / img!.height);
      const w = Math.round(img!.width * scale);
      const h = Math.round(img!.height * scale);
      canvas!.width = w;
      canvas!.height = h;
      ctx.drawImage(img!, 0, 0, w, h);
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
    };
    img.src = src;
  });

  function getPoint(e: MouseEvent | TouchEvent) {
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    const t = (e as TouchEvent).touches?.[0];
    const cx = t ? t.clientX : (e as MouseEvent).clientX;
    const cy = t ? t.clientY : (e as MouseEvent).clientY;
    return {
      x: ((cx - rect.left) / rect.width) * canvas.width,
      y: ((cy - rect.top) / rect.height) * canvas.height
    };
  }

  function start(e: MouseEvent | TouchEvent) {
    if (!canvas) return;
    e.preventDefault();
    drawing = true;
    const { x, y } = getPoint(e);
    lastX = x;
    lastY = y;
  }
  function move(e: MouseEvent | TouchEvent) {
    if (!drawing || !canvas) return;
    e.preventDefault();
    const ctx = canvas.getContext('2d')!;
    const { x, y } = getPoint(e);
    ctx.strokeStyle = color;
    ctx.lineWidth = stroke;
    ctx.beginPath();
    ctx.moveTo(lastX, lastY);
    ctx.lineTo(x, y);
    ctx.stroke();
    lastX = x;
    lastY = y;
  }
  function end() {
    drawing = false;
  }

  function reset() {
    if (!canvas || !img) return;
    const ctx = canvas.getContext('2d')!;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  }

  async function save() {
    if (!canvas) return;
    busy = true;
    canvas.toBlob(async (blob) => {
      if (!blob) {
        busy = false;
        return;
      }
      try {
        await onsave(blob);
      } finally {
        busy = false;
      }
    }, 'image/png');
  }
</script>

<div class="overlay" role="dialog" aria-label="Annotate image">
  <header>
    <h2>Annotate</h2>
    <button class="close" aria-label="Close" onclick={oncancel}>
      <IconX size={18} />
    </button>
  </header>

  <div class="canvas-wrap">
    <canvas
      bind:this={canvas}
      onmousedown={start}
      onmousemove={move}
      onmouseup={end}
      onmouseleave={end}
      ontouchstart={start}
      ontouchmove={move}
      ontouchend={end}
    ></canvas>
  </div>

  <div class="tools">
    <div class="palette">
      {#each PALETTE as c}
        <button
          class="swatch"
          class:selected={color === c}
          style:background={c}
          aria-label={'Color ' + c}
          onclick={() => (color = c)}
        ></button>
      {/each}
    </div>
    <div class="stroke">
      <input type="range" min="2" max="20" step="1" bind:value={stroke} aria-label="Stroke width" />
    </div>
    <button class="btn-outlined" onclick={reset} disabled={busy}>
      <IconTrash size={14} /> Clear
    </button>
    <button class="btn-text" onclick={oncancel} disabled={busy}>Cancel</button>
    <button class="btn-filled" onclick={save} disabled={busy}>
      {busy ? 'Saving…' : 'Save'}
    </button>
  </div>
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(10, 13, 21, 0.95);
    z-index: 210;
    display: flex;
    flex-direction: column;
    padding: 12px;
    gap: 8px;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    color: var(--text-strong);
  }
  header h2 {
    margin: 0;
    font-size: var(--fs-lg);
  }
  .close {
    background: rgba(255, 255, 255, 0.08);
    border: 0;
    color: var(--text);
    width: 36px;
    height: 36px;
    border-radius: 999px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .canvas-wrap {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  canvas {
    background: #000;
    border-radius: 8px;
    max-width: 100%;
    max-height: 100%;
    touch-action: none;
    cursor: crosshair;
  }
  .tools {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    padding-top: 6px;
  }
  .palette {
    display: flex;
    gap: 6px;
  }
  .swatch {
    width: 26px;
    height: 26px;
    border-radius: 999px;
    border: 2px solid transparent;
    cursor: pointer;
  }
  .swatch.selected {
    border-color: var(--text);
  }
  .stroke {
    flex: 1;
    min-width: 80px;
  }
  .stroke input {
    width: 100%;
  }
</style>
