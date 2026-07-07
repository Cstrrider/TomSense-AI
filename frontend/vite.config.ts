import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    port: 5173,
    proxy: {
      '/chats': 'http://localhost:8001',
      '/chat': 'http://localhost:8001',
      '/info': 'http://localhost:8001',
      '/tools': 'http://localhost:8001',
      '/healthz': 'http://localhost:8001',
      '/generated': 'http://localhost:8001'
    }
  }
});
