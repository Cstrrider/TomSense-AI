<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<path d="M22 2 11 13" /><path d="M22 2 15 22l-4-9-9-4Z" />
</Icon>
