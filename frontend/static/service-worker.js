// PWA service worker. Minimal cache-the-shell + pass-through-everything-else.
// Install criteria require that the SW can serve start_url offline, so we
// pre-cache '/' on install and serve from cache when the network fails on
// a top-level navigation.
//
// NOTE: bump CACHE when changing fetch-handler behavior so existing installs
// pick up the new logic on next activation.

const CACHE = 'tomsense-shell-v4';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) =>
      fetch('/', { cache: 'reload' })
        .then((res) => (res && res.ok ? c.put('/', res) : null))
        .catch(() => null)
    )
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          // Pass redirects through unmodified. When the CF Access session
          // cookie expires, navigations get a 302 to cloudflareaccess.com
          // (which fetch surfaces as type='opaqueredirect' for navigate-mode
          // requests). Falling back to the cached shell here would put the
          // user in a "logged-in-looking" UI where every API call still 401s
          // — the ghost-shell trap. Let the browser see the redirect and
          // bounce to login instead.
          if (!res || res.type === 'opaqueredirect' || res.redirected) return res;
          // 2xx → cache as the offline shell.
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put('/', copy)).catch(() => {});
          }
          return res;
        })
        .catch(() =>
          // True network failure (offline) → serve the cached shell.
          caches
            .match('/', { ignoreSearch: true })
            .then((cached) => cached || new Response('offline', { status: 503 }))
        )
    );
  }
});
