import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

export default defineConfig([
  globalIgnores(['dist/']),
  js.configs.recommended,
  {
    files: ['src/**/*.js'],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ['*.config.js', 'build/**/*.js'],
    languageOptions: {
      globals: globals.node,
    },
  },
  // Formatting belongs to Prettier; disable conflicting ESLint rules.
  prettier,
]);
