<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<path d="m12 3 1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5z" /><path d="M19 14l.6 1.8L21 16.5l-1.4.7L19 19l-.6-1.8L17 16.5l1.4-.7z" /><path d="M5 16l.6 1.8L7 18.5l-1.4.7L5 21l-.6-1.8L3 18.5l1.4-.7z" />
</Icon>
