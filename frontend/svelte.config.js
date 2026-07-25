import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({
      pages: 'build',
      assets: 'build',
      fallback: 'index.html',
      precompress: false,
      strict: false
    }),
    prerender: {
      handleHttpError: 'warn'
    },
    // Defence-in-depth behind the markdown sanitizer (lib/markdown.ts): even if
    // a renderer regression let raw HTML through again, `script-src 'self'`
    // turns an injected inline handler into a no-op. Hash mode lets SvelteKit
    // fingerprint its own inline scripts (theme-before-paint, SW registration,
    // hydration start) so those keep working.
    //
    // style-src allows 'unsafe-inline': KaTeX positions every glyph with inline
    // style attributes. Inline styles are a far weaker vector than scripts.
    csp: {
      mode: 'hash',
      directives: {
        'default-src': ['self'],
        'script-src': ['self'],
        'style-src': ['self', 'unsafe-inline'],
        'img-src': ['self', 'data:', 'blob:'],
        'media-src': ['self', 'data:', 'blob:'],
        'font-src': ['self', 'data:'],
        'connect-src': ['self'],
        'object-src': ['none'],
        'base-uri': ['self'],
        'form-action': ['self']
      }
    }
  }
};

export default config;
