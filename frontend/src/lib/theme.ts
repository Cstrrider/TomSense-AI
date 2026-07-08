/** Theme controller. The user picks System / Dark / Light; "System" follows
 *  the OS (and updates live if the OS flips). We resolve the choice to a
 *  concrete value and set it on <html data-theme=…>, which drives the CSS
 *  palette in app.css. The choice persists in localStorage (per-device, no
 *  server round-trip and no first-paint flash — see the inline init in
 *  app.html which applies it before the app boots). */

export type ThemeChoice = 'system' | 'dark' | 'light';

const KEY = 'tomsense-theme';
const DARK_BAR = '#0d1015'; // matches --bg (dark)
const LIGHT_BAR = '#faf9f7'; // matches --bg (light)

export function getThemeChoice(): ThemeChoice {
  const v = (typeof localStorage !== 'undefined' && localStorage.getItem(KEY)) || 'system';
  return v === 'dark' || v === 'light' ? v : 'system';
}

function systemPrefersLight(): boolean {
  return typeof matchMedia !== 'undefined'
    && matchMedia('(prefers-color-scheme: light)').matches;
}

/** Resolve a choice to the concrete theme that should be on the DOM now. */
function resolve(choice: ThemeChoice): 'dark' | 'light' {
  if (choice === 'system') return systemPrefersLight() ? 'light' : 'dark';
  return choice;
}

/** Apply a choice: set <html data-theme>, sync the status-bar meta color. */
export function applyTheme(choice: ThemeChoice): void {
  const concrete = resolve(choice);
  document.documentElement.dataset.theme = concrete;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', concrete === 'light' ? LIGHT_BAR : DARK_BAR);
}

export function setThemeChoice(choice: ThemeChoice): void {
  try {
    localStorage.setItem(KEY, choice);
  } catch {
    /* private mode / storage disabled — still apply for this session */
  }
  applyTheme(choice);
}

let _mediaBound = false;

/** Call once on app start: apply the saved choice and, while it's "system",
 *  react to live OS light/dark switches. */
export function initTheme(): void {
  applyTheme(getThemeChoice());
  if (_mediaBound || typeof matchMedia === 'undefined') return;
  _mediaBound = true;
  matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
    if (getThemeChoice() === 'system') applyTheme('system');
  });
}
