<script lang="ts">
  /**
   * Base wrapper for every Lucide-style icon in this app. Renders an
   * <svg> with currentColor strokes; the actual paths come from a slot.
   *
   * Usage: import an icon like IconMenu — under the hood it renders this
   * wrapper with the right paths.
   */
  interface Props {
    size?: number;
    stroke?: number;
    class?: string;
    title?: string;
    children?: import('svelte').Snippet;
  }

  let { size = 18, stroke = 2, class: className = '', title, children }: Props = $props();
</script>

<svg
  xmlns="http://www.w3.org/2000/svg"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width={stroke}
  stroke-linecap="round"
  stroke-linejoin="round"
  class={className}
  role={title ? 'img' : 'presentation'}
  aria-label={title}
  aria-hidden={!title}
>
  {#if title}<title>{title}</title>{/if}
  {@render children?.()}
</svg>
