<script lang="ts">
  import { app } from '$lib/stores.svelte';
  import {
    S,
    reviewEdits,
    verifyEdits,
    toggleReviewEdits,
    toggleVerifyEdits,
    saveMaxRounds,
    saveMaxTokens
  } from './state.svelte';

  // Seed the number inputs from prefs once loaded (empty = use default).
  $effect(() => {
    const mr = S.prefs.max_rounds_code ?? app.prefs?.max_rounds_code;
    S.maxRoundsCode = mr != null ? String(mr) : '';
    const mt = S.prefs.max_tokens_coder ?? app.prefs?.max_tokens_coder;
    S.maxTokensCoder = mt != null ? String(mt) : '';
  });
</script>

<p class="muted">
  Settings for agentic code chats. Pick the code-mode model under the
  <strong>Providers</strong> tab (Code Mode slot).
</p>
<div class="switch-row">
  <span class="switch-text">
    <span class="switch-label">Review edits before applying</span>
    <span class="switch-hint">
      When on, every file edit in a code chat pauses and shows you the
      diff — nothing is written until you tap Apply. Reject sends the
      agent back to revise. Off (default) applies edits immediately.
    </span>
  </span>
  <button
    class="switch"
    class:on={reviewEdits()}
    role="switch"
    aria-checked={reviewEdits()}
    aria-label="Review edits before applying"
    disabled={S.prefsSaving}
    onclick={toggleReviewEdits}
  >
    <span class="knob"></span>
  </button>
</div>

<div class="switch-row">
  <span class="switch-text">
    <span class="switch-label">Auto-verify edits</span>
    <span class="switch-hint">
      After an edit, run the project's build / type-check (tsc, py_compile,
      go build) and push the agent to fix any errors before it finishes.
      On by default — turn off for speed, or if a pre-existing repo error
      causes false failures.
    </span>
  </span>
  <button
    class="switch"
    class:on={verifyEdits()}
    role="switch"
    aria-checked={verifyEdits()}
    aria-label="Auto-verify edits"
    disabled={S.prefsSaving}
    onclick={toggleVerifyEdits}
  >
    <span class="knob"></span>
  </button>
</div>

<div class="switch-row">
  <span class="switch-text">
    <span class="switch-label">Max steps per turn</span>
    <span class="switch-hint">
      How many tool rounds the agent may take before it must stop (1–100).
      Higher lets it grind through bigger tasks; lower reins it in. Blank
      uses the default (40).
    </span>
  </span>
  <input
    class="num-input"
    type="number"
    min="1"
    max="100"
    placeholder="40"
    bind:value={S.maxRoundsCode}
    disabled={S.prefsSaving}
    onchange={saveMaxRounds}
    onblur={saveMaxRounds}
  />
</div>

<div class="switch-row">
  <span class="switch-text">
    <span class="switch-label">Response length cap</span>
    <span class="switch-hint">
      Max tokens the coder model may emit per round (512–32768). Lower it
      if the model rambles; raise it for long files. Blank uses the
      default.
    </span>
  </span>
  <input
    class="num-input"
    type="number"
    min="512"
    max="32768"
    step="512"
    placeholder="default"
    bind:value={S.maxTokensCoder}
    disabled={S.prefsSaving}
    onchange={saveMaxTokens}
    onblur={saveMaxTokens}
  />
</div>
