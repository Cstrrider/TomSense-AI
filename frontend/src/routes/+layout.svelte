<script lang="ts">
  import '../app.css';
  import { onMount } from 'svelte';
  import { page } from '$app/stores';
  import { goto } from '$app/navigation';
  import Sidebar from '$lib/components/Sidebar.svelte';
  import Toaster from '$lib/components/Toaster.svelte';
  import { app } from '$lib/stores.svelte';
  import { initNotifications } from '$lib/notifications';

  let { children } = $props();

  // Share routes /s/<token> are public; /assist is the compact assistant
  // overlay panel (loaded in the Android assistant WebView); /setup is the
  // first-run wizard. All three render without the chat-list sidebar shell.
  let routeId = $derived($page.route.id ?? '');
  let isPublic = $derived(routeId.startsWith('/s/'));
  let isSetup = $derived(routeId.startsWith('/setup'));
  let isBare = $derived(isPublic || routeId.startsWith('/assist') || isSetup);

  // First-run redirect: after prefs + chats load, if the user has never
  // dismissed the wizard AND has zero chats (= they've genuinely never
  // used TomSense), push them to /setup. Existing users get auto-marked
  // dismissed instead so they're never dragged through.
  let _redirected = $state(false);
  $effect(() => {
    if (_redirected) return;
    if (isBare) return;
    if (!app.prefsLoaded || !app.chatsLoaded) return;   // wait for both
    if (app.prefs.setup_dismissed) return;
    if (app.chats.length > 0) {
      // Existing user — mark dismissed so this never fires again.
      _redirected = true;
      app.savePrefs({ setup_dismissed: true }).catch(() => {});
      return;
    }
    _redirected = true;
    goto('/setup');
  });

  onMount(() => {
    if (isBare) {
      app.refreshInfo();
      return;
    }
    app.refreshInfo();
    app.refreshChats();
    app.refreshProjects();
    app.refreshMe();
    app.refreshNeurons();
    app.refreshUsage();
    app.refreshPrefs();
    // Integrated push: drain the server notification queue (now + on every
    // resume) and mirror scheduled prompts into OS-level notifications.
    initNotifications();
    // Wait a moment then prime Piper so the first 🔊 tap is snappy.
    setTimeout(() => app.warmupTTS(), 1500);
    // Capture Chrome's deferred install event so we can offer an explicit
    // "Install app" button instead of relying on the browser's heuristic-driven
    // banner (which won't appear right after an uninstall + cooldown).
    const onBeforeInstall = (e: Event) => {
      e.preventDefault();
      app.installPrompt = e;
    };
    window.addEventListener('beforeinstallprompt', onBeforeInstall);
    window.addEventListener('appinstalled', () => (app.installPrompt = null));
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall);
    };
  });
</script>

<svelte:head>
  <title>TomSense</title>
</svelte:head>

{#if isBare}
  {@render children?.()}
{:else}
  <div class="shell" class:sidebar-open={app.sidebarOpen}>
    <Sidebar />
    <main class="content">
      {@render children?.()}
    </main>
    {#if app.sidebarOpen}
      <button
        class="scrim"
        aria-label="Close chat list"
        onclick={() => (app.sidebarOpen = false)}
      ></button>
    {/if}
  </div>
{/if}

<Toaster />

<style>
  .shell {
    display: flex;
    height: 100%;
    width: 100%;
  }
  .content {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .scrim {
    display: none;
  }
  @media (max-width: 768px) {
    /* Target only the chat-list sidebar (direct child of .shell), not any
       other <aside> elements that components might use. */
    .shell > :global(aside) {
      position: fixed;
      left: 0;
      top: 0;
      bottom: 0;
      transform: translateX(-100%);
      transition: transform 0.2s ease;
      z-index: 100;
      box-shadow: 4px 0 24px rgba(0, 0, 0, 0.4);
    }
    .shell.sidebar-open > :global(aside) {
      transform: translateX(0);
    }
    .scrim {
      display: block;
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.5);
      z-index: 99;
      border: 0;
      padding: 0;
    }
  }
</style>
