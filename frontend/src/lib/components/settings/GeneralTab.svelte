<script lang="ts">
  import { IconRefresh } from '$lib/icons';
  import { getThemeChoice, type ThemeChoice } from '$lib/theme';
  import { S, applyTheme, savePrefsPatch, saveInstanceUrl } from './state.svelte';

  // Seed the theme select from device-local storage on first render (kept out
  // of module init so SSR/prerender never touches localStorage).
  $effect(() => {
    S.themeChoice = getThemeChoice();
  });
</script>

<div class="tool-row" style="margin-bottom: var(--sp-2);">
  <div class="tool-meta">
    <div class="tool-label">Theme</div>
    <div class="tool-hint">System follows your device's light/dark setting.</div>
  </div>
  <select
    class="model-select compact"
    value={S.themeChoice}
    onchange={(e) => applyTheme((e.currentTarget as HTMLSelectElement).value as ThemeChoice)}
  >
    <option value="system">System</option>
    <option value="dark">Dark</option>
    <option value="light">Light</option>
  </select>
</div>

<div class="tool-row" style="margin-bottom: var(--sp-2);">
  <div class="tool-meta">
    <div class="tool-label">Usage counter</div>
    <div class="tool-hint">
      What the sidebar shows. Auto follows the active provider —
      Cloudflare neurons, or tokens + $ cost for OpenRouter etc.
    </div>
  </div>
  <select
    class="model-select compact"
    value={S.prefs?.usage_display ?? 'auto'}
    onchange={(e) =>
      savePrefsPatch({ usage_display: (e.currentTarget as HTMLSelectElement).value as any })}
  >
    <option value="auto">Auto (per provider)</option>
    <option value="neurons">Neurons (Cloudflare)</option>
    <option value="tokens">Tokens &amp; cost</option>
  </select>
</div>

{#if S.instanceUrl}
  <p class="muted" style="margin-top: var(--sp-3);">
    This Android app is a shell around a TomSense server. Point it at
    a different instance here — the app reconnects immediately.
    {#if !S.instanceUrl.isSet}
      Currently using the URL baked into the APK.
    {/if}
  </p>
  <label class="field">
    <span class="field-label">Server URL</span>
    <input
      type="url"
      placeholder="https://tomsense.your-domain.com"
      bind:value={S.instanceUrlDraft}
      disabled={S.instanceUrlSaving}
    />
  </label>
  <button
    class="primary"
    onclick={saveInstanceUrl}
    disabled={S.instanceUrlSaving || S.instanceUrlDraft.trim() === S.instanceUrl.url}
  >
    <IconRefresh size={14} /> {S.instanceUrlSaving ? 'Reconnecting…' : 'Save & reconnect'}
  </button>
{/if}
