<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<path d="M21 12a9 9 0 0 1-9 9 9 9 0 0 1-6.7-3" /><path d="M3 12a9 9 0 0 1 9-9 9 9 0 0 1 6.7 3" /><polyline points="21 3 21 9 15 9" /><polyline points="3 21 3 15 9 15" />
</Icon>
