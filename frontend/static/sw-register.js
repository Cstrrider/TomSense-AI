// Register the PWA service worker once the page has loaded.
//
// External rather than inline so the Content-Security-Policy can stay at
// `script-src 'self'` — see svelte.config.js.
if ('serviceWorker' in navigator) {
  window.addEventListener('load', function () {
    navigator.serviceWorker
      .register('/service-worker.js')
      .then(function (reg) {
        console.info('[sw] registered', reg.scope);
      })
      .catch(function (err) {
        console.warn('[sw] registration failed:', err);
      });
  });
}
