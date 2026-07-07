<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<path d="M9.5 2a3.5 3.5 0 0 0-3.5 3.5v.06A3.5 3.5 0 0 0 3 9.06v.94A3.5 3.5 0 0 0 5 13a3.5 3.5 0 0 0 1 6.93 3.5 3.5 0 0 0 3.5 2.07A3.5 3.5 0 0 0 13 18.5V5.5A3.5 3.5 0 0 0 9.5 2Z" /><path d="M14.5 2a3.5 3.5 0 0 1 3.5 3.5v.06A3.5 3.5 0 0 1 21 9.06v.94A3.5 3.5 0 0 1 19 13a3.5 3.5 0 0 1-1 6.93 3.5 3.5 0 0 1-3.5 2.07A3.5 3.5 0 0 1 11 18.5" />
</Icon>
