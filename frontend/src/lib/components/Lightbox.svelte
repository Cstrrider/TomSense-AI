<script lang="ts">
  import { IconX, IconDownload } from '$lib/icons';
  import { saveImageToDevice } from '$lib/clienttools';
  import { toast } from '$lib/toast.svelte';

  interface Props {
    src: string | null;
    onclose: () => void;
  }

  let { src, onclose }: Props = $props();
  let saving = $state(false);

  // ── Zoom / pan state ──
  const MIN = 1;
  const MAX = 5;
  let scale = $state(1);
  let tx = $state(0); // translate px
  let ty = $state(0);
  let imgEl: HTMLImageElement | undefined = $state();

  // Active pointers for pinch/pan tracking, keyed by pointerId.
  let pointers = new Map<number, { x: number; y: number }>();
  let startDist = 0;
  let startScale = 1;
  let startTx = 0;
  let startTy = 0;
  let panStart = { x: 0, y: 0 };
  let moved = false; // distinguishes a pan/pinch from a tap
  let lastTap = 0;

  function reset() {
    scale = 1;
    tx = 0;
    ty = 0;
    pointers.clear();
  }

  /** Keep the image from being dragged entirely off-screen: clamp the pan so
   *  the scaled image still overlaps the viewport. */
  function clamp() {
    if (!imgEl) return;
    const r = imgEl.getBoundingClientRect();
    // r is the already-transformed box; derive the base (unscaled) size.
    const baseW = r.width / scale;
    const baseH = r.height / scale;
    const maxX = Math.max(0, (baseW * scale - baseW) / 2);
    const maxY = Math.max(0, (baseH * scale - baseH) / 2);
    tx = Math.max(-maxX, Math.min(tx, maxX));
    ty = Math.max(-maxY, Math.min(ty, maxY));
  }

  function setScale(next: number, cx?: number, cy?: number) {
    const clamped = Math.max(MIN, Math.min(next, MAX));
    if (imgEl && cx !== undefined && cy !== undefined) {
      // Zoom toward the focal point (cx,cy) in viewport coords.
      const r = imgEl.getBoundingClientRect();
      const ox = cx - (r.left + r.width / 2);
      const oy = cy - (r.top + r.height / 2);
      const ratio = clamped / scale;
      tx = tx - ox * (ratio - 1);
      ty = ty - oy * (ratio - 1);
    }
    scale = clamped;
    if (scale === 1) {
      tx = 0;
      ty = 0;
    } else {
      clamp();
    }
  }

  function onWheel(e: WheelEvent) {
    e.preventDefault();
    setScale(scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), e.clientX, e.clientY);
  }

  function dist(a: { x: number; y: number }, b: { x: number; y: number }) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }
  function mid(a: { x: number; y: number }, b: { x: number; y: number }) {
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  }

  function onPointerDown(e: PointerEvent) {
    (e.target as Element).setPointerCapture?.(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    moved = false;
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      startDist = dist(a, b);
      startScale = scale;
    } else if (pointers.size === 1) {
      panStart = { x: e.clientX, y: e.clientY };
      startTx = tx;
      startTy = ty;
    }
  }

  function onPointerMove(e: PointerEvent) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const d = dist(a, b);
      if (startDist > 0) {
        const m = mid(a, b);
        setScale(startScale * (d / startDist), m.x, m.y);
      }
      moved = true;
    } else if (pointers.size === 1 && scale > 1) {
      tx = startTx + (e.clientX - panStart.x);
      ty = startTy + (e.clientY - panStart.y);
      clamp();
      moved = true;
    } else if (pointers.size === 1) {
      if (Math.hypot(e.clientX - panStart.x, e.clientY - panStart.y) > 8) moved = true;
    }
  }

  function onPointerUp(e: PointerEvent) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) startDist = 0;
    // Tap (no drag) at scale 1 → let the backdrop click close; a tap on the
    // image toggles zoom via double-tap detection below.
    if (!moved) {
      const now = Date.now();
      if (now - lastTap < 300) {
        // double-tap: toggle between fit and 2.5x at the tap point
        setScale(scale > 1 ? 1 : 2.5, e.clientX, e.clientY);
        lastTap = 0;
      } else {
        lastTap = now;
      }
    }
  }

  function onImgClick(e: MouseEvent) {
    // Swallow single clicks on the image so the backdrop's close handler only
    // fires for clicks OUTSIDE the image — unless we're at fit scale and it
    // was a clean tap (handled by pointerup double-tap logic).
    e.stopPropagation();
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') onclose();
    else if (e.key === '+' || e.key === '=') setScale(scale * 1.25);
    else if (e.key === '-') setScale(scale / 1.25);
    else if (e.key === '0') reset();
  }

  async function save(e: MouseEvent) {
    e.stopPropagation();
    if (!src || saving) return;
    saving = true;
    try {
      toast.success(await saveImageToDevice(src));
    } catch (err) {
      toast.error(`Couldn't save image: ${(err as Error).message}`);
    } finally {
      saving = false;
    }
  }

  // Reset transform whenever a new image opens; wire the Escape/zoom keys.
  $effect(() => {
    if (src) {
      reset();
      window.addEventListener('keydown', onKeydown);
      return () => window.removeEventListener('keydown', onKeydown);
    }
  });
</script>

{#if src}
  <div class="lightbox" role="dialog" aria-label="Image preview" onclick={onclose}>
    <button class="close" aria-label="Close" onclick={onclose}>
      <IconX size={20} />
    </button>
    <button class="save" aria-label="Save image" disabled={saving} onclick={save}>
      {#if saving}<span class="spin" aria-hidden="true"></span>{:else}<IconDownload size={18} />{/if}
    </button>
    {#if scale > 1}
      <button
        class="zoom-reset"
        aria-label="Reset zoom"
        onclick={(e) => { e.stopPropagation(); reset(); }}
      >{Math.round(scale * 100)}% ✕</button>
    {/if}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <img
      bind:this={imgEl}
      {src}
      alt=""
      class:zoomed={scale > 1}
      style="transform: translate({tx}px, {ty}px) scale({scale});"
      draggable="false"
      onclick={onImgClick}
      onwheel={onWheel}
      onpointerdown={onPointerDown}
      onpointermove={onPointerMove}
      onpointerup={onPointerUp}
      onpointercancel={onPointerUp}
    />
  </div>
{/if}

<style>
  .lightbox {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.85);
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 16px;
    cursor: zoom-out;
    animation: fade 0.15s ease;
    overflow: hidden;
    touch-action: none; /* we handle pinch/pan ourselves */
  }
  .lightbox img {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    border-radius: 8px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
    cursor: zoom-in;
    touch-action: none;
    will-change: transform;
    transition: transform 0.08s ease-out;
    user-select: none;
    -webkit-user-drag: none;
  }
  .lightbox img.zoomed {
    cursor: grab;
    transition: none; /* immediate during active pan/pinch */
  }
  .close,
  .save,
  .zoom-reset {
    position: absolute;
    top: 16px;
    background: rgba(255, 255, 255, 0.1);
    border: 0;
    color: white;
    height: 40px;
    border-radius: 999px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    z-index: 1;
  }
  .close,
  .save {
    width: 40px;
  }
  .close {
    right: 16px;
  }
  .save {
    right: 68px;
  }
  .zoom-reset {
    left: 16px;
    padding: 0 14px;
    font-size: 13px;
    font-weight: 600;
    gap: 4px;
  }
  .close:hover,
  .save:hover,
  .zoom-reset:hover {
    background: rgba(255, 255, 255, 0.2);
  }
  .save:disabled {
    opacity: 0.6;
    cursor: default;
  }
  .spin {
    width: 16px;
    height: 16px;
    border: 2px solid rgba(255, 255, 255, 0.35);
    border-top-color: #fff;
    border-radius: 50%;
    animation: rot 0.8s linear infinite;
  }
  @keyframes rot {
    to { transform: rotate(360deg); }
  }
  @keyframes fade {
    from { opacity: 0; }
    to { opacity: 1; }
  }
</style>
