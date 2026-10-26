import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

/**
 * ESLint flat config.
 *
 * Type-aware linting is enabled (`recommendedTypeChecked`) rather than the
 * syntax-only preset. The rules that actually catch things here — a floating
 * promise from a fetch nobody awaited, an unnecessary condition on a value
 * the types say is never null — all need type information, and without it
 * the linter mostly duplicates what the compiler already said.
 *
 * The rules below are turned down rather than off where the strict default
 * fires on a pattern this codebase uses deliberately. Each one says why,
 * because an unexplained disable is indistinguishable from an unnoticed bug.
 */
export default tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**', '*.timestamp-*'],
  },

  js.configs.recommended,

  // Scoped to TypeScript files. Without the `files` restriction the
  // type-aware rules are also applied to this config file, which is plain
  // JavaScript and belongs to no tsconfig project — ESLint then fails to
  // start rather than reporting a lint error, which is a confusing way to
  // discover the problem.
  ...tseslint.configs.recommendedTypeChecked.map((config) => ({
    ...config,
    files: ['**/*.{ts,tsx}'],
  })),

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,

      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],

      // An unused parameter prefixed with _ is a documented signature, not
      // dead code.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // Fire-and-forget is used deliberately in two places — recording a
      // lesson view, and saving history to localStorage — and both are
      // written with an explicit `void` or a `.catch()`. This rule keeps
      // that honest by failing on a promise with neither.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': [
        'error',
        // An async function passed to onClick is the normal React idiom and
        // the return value is genuinely unused.
        { checksVoidReturn: { attributes: false } },
      ],

      // The API client deliberately handles `unknown` from JSON.parse and
      // from caught errors, narrowing before use. Banning the type would
      // push the code towards `any`, which is worse.
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',

      // Template literals interpolating a number or a boolean are fine and
      // are everywhere in the class-name and title strings.
      '@typescript-eslint/restrict-template-expressions': [
        'error',
        { allowNumber: true, allowBoolean: true, allowNullish: true },
      ],
    },
  },

  // Test files run under Vitest globals and legitimately assert on values
  // the types describe loosely.
  {
    files: ['src/test/**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/unbound-method': 'off',

      // `await act(async () => { ... })` is Testing Library's documented way
      // to flush pending effects and microtasks. The callback often has
      // nothing to await — passing an async function is itself the signal
      // that makes act flush — so the rule fires on the correct idiom.
      '@typescript-eslint/require-await': 'off',
    },
  },

  // The Vite config is a Node module, not browser code.
  {
    files: ['vite.config.ts'],
    languageOptions: { globals: globals.node },
  },
)
