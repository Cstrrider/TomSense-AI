<script lang="ts">
  import { app } from '$lib/stores.svelte';
  import {
    S,
    CUSTOM_PREFIX,
    customProviders,
    currentTtsVoices,
    currentTtsVoice,
    ttsCustom,
    ttsIsCustom,
    ttsSelectValue,
    sttCustom,
    sttIsCustom,
    sttSelectValue,
    savePrefs,
    onAudioProviderChange,
    onAudioModelChange
  } from './state.svelte';
</script>

<p class="muted">
  Choose the TTS engine for assistant speech and the STT engine
  for the mic. Cloudflare options run on CF's GPUs and are much
  faster but consume CF neurons.
</p>

<h3 style="margin-top: var(--sp-2);">Text-to-speech</h3>
<label class="field">
  <span class="field-label">Provider</span>
  <select
    class="model-select"
    value={ttsSelectValue()}
    onchange={(e) => onAudioProviderChange('tts', (e.currentTarget as HTMLSelectElement).value, ttsCustom().model)}
    disabled={S.prefsSaving}
  >
    {#each app.info?.tts_providers ?? [] as p}
      <option value={p.id}>{p.label}</option>
    {/each}
    {#if customProviders().length}
      <optgroup label="Custom (OpenAI-compatible)">
        {#each customProviders() as p (p.id)}
          <option value={`${CUSTOM_PREFIX}${p.id}`}>{p.name} — custom</option>
        {/each}
      </optgroup>
    {/if}
  </select>
</label>
{#if ttsIsCustom()}
  <label class="field">
    <span class="field-label">Model</span>
    <input type="text" placeholder="e.g. tts-1 / gpt-4o-mini-tts"
      value={ttsCustom().model}
      onchange={(e) => onAudioModelChange('tts', ttsCustom().pid, (e.currentTarget as HTMLInputElement).value)}
      disabled={S.prefsSaving} />
  </label>
  <label class="field">
    <span class="field-label">Voice</span>
    <input type="text" placeholder="e.g. alloy" value={S.prefs.tts_voice ?? ''}
      onchange={(e) => savePrefs({ tts_voice: (e.currentTarget as HTMLInputElement).value })}
      disabled={S.prefsSaving} />
  </label>
  <p class="muted small">Calls <code>POST {'{base_url}'}/audio/speech</code> — set the API key on its Providers card.</p>
{:else if currentTtsVoices().length > 0}
  <label class="field">
    <span class="field-label">Voice</span>
    <select
      class="model-select"
      value={currentTtsVoice()}
      onchange={(e) => savePrefs({ tts_voice: (e.currentTarget as HTMLSelectElement).value })}
      disabled={S.prefsSaving}
    >
      {#each currentTtsVoices() as v}
        <option value={v}>{v}</option>
      {/each}
    </select>
  </label>
{/if}

<h3 style="margin-top: var(--sp-3);">Speech-to-text</h3>
<label class="field">
  <span class="field-label">Provider</span>
  <select
    class="model-select"
    value={sttSelectValue()}
    onchange={(e) => onAudioProviderChange('stt', (e.currentTarget as HTMLSelectElement).value, sttCustom().model)}
    disabled={S.prefsSaving}
  >
    {#each app.info?.stt_providers ?? [] as p}
      <option value={p.id}>{p.label}</option>
    {/each}
    {#if customProviders().length}
      <optgroup label="Custom (OpenAI-compatible)">
        {#each customProviders() as p (p.id)}
          <option value={`${CUSTOM_PREFIX}${p.id}`}>{p.name} — custom</option>
        {/each}
      </optgroup>
    {/if}
  </select>
</label>
{#if sttIsCustom()}
  <label class="field">
    <span class="field-label">Model</span>
    <input type="text" placeholder="e.g. whisper-1"
      value={sttCustom().model}
      onchange={(e) => onAudioModelChange('stt', sttCustom().pid, (e.currentTarget as HTMLInputElement).value)}
      disabled={S.prefsSaving} />
  </label>
  <p class="muted small">Calls <code>POST {'{base_url}'}/audio/transcriptions</code> — set the API key on its Providers card.</p>
{/if}
