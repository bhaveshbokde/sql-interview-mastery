/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * Vite configuration.
 *
 * The dev server proxies /api to the backend on 8085 rather than relying on
 * CORS. Two reasons: the browser then sees one origin, so nothing about
 * development depends on the CORS configuration being right; and VITE_API_BASE
 * can stay empty, which is also what a production build wants when the
 * frontend is served as static files from the same origin as the API.
 */
export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY ?? 'http://localhost:8085',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_PROXY ?? 'http://localhost:8085',
        changeOrigin: true,
      },
    },
  },

  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        // CodeMirror is most of the bundle and is only needed on the two
        // pages with an editor. Splitting it keeps the first load of the
        // dashboard and the lesson pages small.
        manualChunks: {
          codemirror: ['@uiw/react-codemirror', '@codemirror/lang-sql'],
          markdown: ['react-markdown', 'remark-gfm'],
        },
      },
    },
  },

  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
