import { defineConfig } from 'vite';

export default defineConfig({
  // Relative assets work at /traficinfo/ on Pages and at / on a custom domain.
  base: './',
  build: {
    // Keep the deployed JavaScript readable while the prototype is small.
    minify: false,
  },
});
