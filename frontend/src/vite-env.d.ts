/// <reference types="vite/client" />

/**
 * The environment variables this application reads.
 *
 * Declaring them rather than relying on the index signature means a typo in
 * `import.meta.env.VITE_API_BAES_URL` is a compile error instead of an
 * `undefined` that silently falls back to same-origin.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
