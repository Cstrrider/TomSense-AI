<script lang="ts">
  import Icon from './Icon.svelte';
  let { size = 18, stroke = 2, class: className = '', title }: { size?: number; stroke?: number; class?: string; title?: string } = $props();
</script>

<Icon {size} {stroke} class={className} {title}>
<line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
</Icon>
