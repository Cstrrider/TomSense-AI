<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<path d="M12 19v4" /><path d="M19 11a7 7 0 1 1-14 0" /><rect x="9" y="2" width="6" height="13" rx="3" />
</Icon>
