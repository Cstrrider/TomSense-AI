// Code-mode model registry — sourced from /info's `code_models_catalog`
// (the backend's tool_registry.py is the single source of truth). The
// fallback below is used only if /info hasn't loaded yet; it should match
// the backend's CODE_MODELS_CATALOG exactly, but if the two drift the
// backend wins on the next info refresh.
//
// To add a model: edit `backend/app/tool_registry.py:CODE_MODELS_CATALOG`,
// redeploy the backend. The frontend picks it up on next info load — no
// rebuild required.
import type { InfoResponse } from './types';

export type CodeModelOption = {
  id: string;
  label: string;
  hint: string;
};

const STATIC_FALLBACK: readonly CodeModelOption[] = [
  {
    id: '@cf/qwen/qwen3-30b-a3b-fp8',
    label: 'Qwen3 30B',
    hint: 'Most efficient — fewest rounds & tokens (recommended)',
  },
  {
    id: '@cf/zai-org/glm-4.7-flash',
    label: 'GLM-4.7',
    hint: 'Fastest wall-clock, reliable — chattier on tokens',
  },
  {
    id: '@cf/moonshotai/kimi-k2.6',
    label: 'Kimi K2.6',
    hint: 'Tight reasoning when warm; CF latency can spike',
  },
  {
    id: '@cf/openai/gpt-oss-120b',
    label: 'gpt-oss 120B',
    hint: 'Fails multi-step loops (no edit_file) — comparison only',
  },
  {
    id: 'anthropic::claude-haiku-4-5',
    label: 'Claude Haiku',
    hint: 'Frontier fast tier (~5× Qwen3 cost) — capable & quick',
  },
  {
    id: 'anthropic::claude-sonnet-4-6',
    label: 'Claude Sonnet',
    hint: 'Frontier workhorse (~25× Qwen3 cost) — best general coding',
  },
  {
    id: 'anthropic::claude-opus-4-7',
    label: 'Claude Opus',
    hint: 'Frontier top tier (~125× Qwen3 cost) — hardest tasks only',
  },
] as const;

/** Resolve the picker entries — backend catalog if /info loaded, else
 *  the static fallback. Components wrap this in `$derived` for reactivity. */
export function getCodeModeModels(info: InfoResponse | null): readonly CodeModelOption[] {
  const c = info?.code_models_catalog;
  if (Array.isArray(c) && c.length > 0) return c;
  return STATIC_FALLBACK;
}

/** Default model id when the user has no saved pref. */
export function getDefaultCodeModeModelId(info: InfoResponse | null): string {
  return getCodeModeModels(info)[0].id;
}

// Re-exports preserved for backward-compat (so existing imports keep working).
// Components that need reactive behavior should call getCodeModeModels(app.info)
// inside a $derived() instead of importing this constant directly.
export const CODE_MODE_MODELS = STATIC_FALLBACK;
export const DEFAULT_CODE_MODE_MODEL_ID = STATIC_FALLBACK[0].id;
