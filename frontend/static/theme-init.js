// Apply the saved theme before first paint to avoid a light/dark flash.
// Mirrors lib/theme.ts (kept tiny on purpose).
//
// External rather than inline so the Content-Security-Policy can stay at
// `script-src 'self'` with no 'unsafe-inline' and no hand-maintained hash
// (see svelte.config.js). Loaded as a blocking <script src> in <head>, so it
// still runs before the first paint.
(function () {
  try {
    var c = localStorage.getItem('tomsense-theme') || 'system';
    var light =
      c === 'light' ||
      (c === 'system' && window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches);
    document.documentElement.dataset.theme = light ? 'light' : 'dark';
  } catch (e) {
    document.documentElement.dataset.theme = 'dark';
  }
})();
