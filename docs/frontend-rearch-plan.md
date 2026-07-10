# Frontend re-architecture plan — `dev/frontend-rearch`

Motivation: the visible UI is good; the layer between the API and the pixels is
where the bugs live. Every recent frontend bug (untrack infinite-effect loop,
tool_models sibling-wipe, fallback snap-back, datalist-covers-screen) traces to
one of the structural issues below. Full write-up: session 2026-07-10.

## Phases

### Phase 1 — Split `ChatSettings.svelte` (~3,300 lines) into per-tab components
**Status: done**

- `ChatSettings.svelte` stays as the drawer shell (same name + props so parents
  don't change): header, tab bar, section-collapse chrome.
- New `src/lib/components/settings/` with one component per tab:
  `PersonaTab`, `MemoryTab`, `ProvidersTab` (models + providers + CF-models
  editor — the big one), `VoiceTab`, `ShareTab`, `CoderTab`, `SecretsTab`,
  `GeneralTab`.
- Shared state moves to `settings/state.svelte.ts` — a `SettingsState` class
  using `$state` fields (Svelte 5 pattern), instantiated by the shell and
  passed to tabs as a prop. No behavior change; this is mechanical.
- Verify: `npm run build` + drawer smoke test.

### Phase 2 — `savedPref()` helper: one save-plumbing path
**Status: done**

- Every settings control hand-rolls disable → await → resync → toast
  (`savePersistentTool`, `saveCoderPref`, credential rows, …).
- One helper in `settings/state.svelte.ts`: takes a patch + success label,
  handles saving-flag, server-merge resync, toast, error rollback.
- Collapse the existing save functions onto it.

### Phase 3 — Kill the per-chat/global model duality
**Status: done**

- Today: `chatModel` (per-chat pin, via `applyChatModel`) vs `toolModels.chat`
  (profile default) share one dropdown; the Chat row is special-cased and the
  `cf::` legacy-id shim papers over early bare ids.
- Change: the Chat row gets two explicit controls — "this chat" (only when a
  chat is open) and "my default" — one state shape, no special-casing in
  `toolRow`. Normalization stays at the API boundary only (api.ts), not
  scattered through components.

### Phase 4 — Mobile-native patterns
**Status: done**

- Replace `confirm()` dialogs (provider delete, etc.) with inline two-tap
  confirmation (button morphs to "Sure?") — WebView confirm() is janky.
- Settings drawer becomes a full-screen sheet on narrow viewports (the 440px
  drawer is acknowledged-in-CSS to fit nowhere).
- Audit remaining desktop-isms (native pickers, hover-only affordances).

### Phase 5 — Server-driven settings schema
**Status: pending (own session)**

- Extend the backend registry pattern (`tool_models_catalog` already drives
  the model rows) to a full settings schema: `/info` emits sections → fields
  (type, options, hint); frontend renders generically.
- Kills the whitelist-drop / forgot-to-load-a-key bug class permanently —
  adding a slot or pref becomes one backend entry.
- Prereq: Phase 1 (schema renderer replaces tab internals one at a time).

### Phase 6 — Typed stream events
**Status: pending (own session; backend + frontend)**

- Today notices/chips/stats-footer are markdown mixed into the content stream;
  the client regex-parses them back out and persisted messages carry footers.
- Change: typed SSE events (`text`, `reasoning`, `notice`, `tool_start`,
  `tool_end`, `stats`); client renders components; persisted content = only
  the model's words. Backend: `run_chat` yields structured events end-to-end
  (the `LiveRun` chunk format grows a type field, with back-compat for old
  persisted messages).
- Biggest + riskiest; last deliberately.

## Ground rules

- All work on `dev/frontend-rearch`; prod containers stay on `main` builds.
- One commit per phase, `npm run build` green before each commit.
- No behavior changes inside Phase 1 (pure mechanical split) so the diff is
  reviewable; behavior changes start at Phase 2.
