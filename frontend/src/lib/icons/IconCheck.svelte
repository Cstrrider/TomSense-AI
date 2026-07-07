<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<polyline points="20 6 9 17 4 12" />
</Icon>
