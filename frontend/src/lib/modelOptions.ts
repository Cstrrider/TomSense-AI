// Shared model-option builder — ONE precedence rule for every model picker
// (the Settings → Models rows and the new-code-chat picker), so custom CF
// models and provider models behave identically everywhere:
//
//   1. The user's custom CF model list (prefs.cf_models), filtered by each
//      model's per-tool `tools` tags. Any model tagged for this tool
//      REPLACES the hardcoded CF defaults for it — that's how models are
//      added AND removed.
//   2. Otherwise the bundled defaults for the tool. The fallback is
//      PER-TOOL so one stray custom tag can't blank out other pickers.
//   3. Plus models registered on custom (non-builtin) providers, tagged
//      for this tool.
//
// Bundled non-CF defaults (ids already `provider::model`-qualified, e.g.
// `anthropic::claude-sonnet-4-6`) always show, independent of cf_models —
// the custom CF list only governs the Cloudflare set.
import type { Provider, ProviderModel, ToolKey } from './types';

export type ToolModelOption = { id: string; label: string; note?: string };

const CF_BUILTIN_ID = 'cf';

export function buildToolOptions(
  toolKey: ToolKey,
  defaults: ToolModelOption[],
  cfModels?: ProviderModel[] | null,
  providers?: Provider[] | null
): ToolModelOption[] {
  const out: ToolModelOption[] = [];
  const nonCfBundled = defaults.filter((m) => typeof m.id === 'string' && m.id.includes('::'));
  const cfDefaults = defaults.filter((m) => !nonCfBundled.includes(m));

  let cfSource: ToolModelOption[] = [];
  if (cfModels && cfModels.length > 0) {
    cfSource = cfModels.filter((m) => Array.isArray(m.tools) && m.tools.includes(toolKey));
  }
  if (cfSource.length === 0) {
    cfSource = cfDefaults;
  }
  for (const m of cfSource) {
    out.push({
      id: `${CF_BUILTIN_ID}::${m.id}`,
      label: m.label,
      note: m.note ? `Cloudflare · ${m.note}` : 'Cloudflare'
    });
  }
  for (const m of nonCfBundled) {
    out.push({ id: m.id, label: m.label, note: m.note ?? '' });
  }
  for (const p of providers ?? []) {
    if (p.builtin) continue;
    for (const pm of p.models || []) {
      if (!Array.isArray(pm.tools) || !pm.tools.includes(toolKey)) continue;
      out.push({
        id: `${p.id}::${pm.id}`,
        label: pm.label || pm.id,
        note: pm.note ? `${p.name} · ${pm.note}` : p.name
      });
    }
  }
  return out;
}

/** Compare model ids ignoring the builtin-CF prefix — `cf::@cf/x` ≡ `@cf/x`
 *  (older prefs stored the bare form; both parse identically server-side). */
export function sameModelId(a?: string | null, b?: string | null): boolean {
  const n = (s: string | null | undefined) => (s ?? '').replace(/^cf::/, '');
  return n(a) === n(b);
}
