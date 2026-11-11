import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { legalNotices } from './build/legal-notices.js';

export default defineConfig({
  // Relative assets work at /traficinfo/ on Pages and at / on a custom domain.
  base: './',
  plugins: [legalNotices()],
  build: {
    rollupOptions: {
      input: {
        map: fileURLToPath(new URL('./index.html', import.meta.url)),
        licences: fileURLToPath(new URL('./licences.html', import.meta.url)),
      },
    },
    // Keep the deployed JavaScript readable while the prototype is small.
    minify: false,
  },
});
