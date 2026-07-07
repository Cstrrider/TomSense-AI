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

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') onclose();
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

  $effect(() => {
    if (src) {
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
    <img src={src} alt="" onclick={(e) => e.stopPropagation()} />
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
  }
  .lightbox img {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    cursor: default;
    border-radius: 8px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
  }
  .close,
  .save {
    position: absolute;
    top: 16px;
    background: rgba(255, 255, 255, 0.1);
    border: 0;
    color: white;
    width: 40px;
    height: 40px;
    border-radius: 999px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    z-index: 1;
  }
  .close {
    right: 16px;
  }
  .save {
    right: 68px;
  }
  .close:hover,
  .save:hover {
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
