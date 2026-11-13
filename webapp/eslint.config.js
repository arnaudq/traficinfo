import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import prettier from 'eslint-config-prettier';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig([
  globalIgnores(['dist/', '.cache/']),
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ['src/**/*.ts'],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ['*.config.{js,ts}', 'build/**/*.ts', 'test/**/*.ts'],
    languageOptions: {
      globals: globals.node,
    },
  },
  // Formatting belongs to Prettier; disable conflicting ESLint rules.
  prettier,
]);
