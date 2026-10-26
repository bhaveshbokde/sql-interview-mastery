import { defineConfig, devices } from '@playwright/test'

/**
 * Browser tests against the running application.
 *
 * These are the only tests here that exercise the real thing end to end —
 * a real Chrome, the real Vite build, the real Spring Boot API and the real
 * PostgreSQL behind it. The component tests stub `fetch`, which means they
 * cannot catch a contract drift between the two halves; these can.
 *
 * `channel: 'chrome'` uses the Chrome already installed on the machine
 * rather than downloading Playwright's own bundle. That keeps the install
 * small and avoids a 400 MB download in a repository whose point is SQL —
 * the cost is that the tests need a local Chrome, which is checked by the
 * runner and reported clearly when it is missing.
 *
 * The backend is NOT started by this config. It needs a provisioned database
 * and a curriculum load, both of which take longer than a webServer timeout
 * should wait and both of which have their own scripts. The tests check for
 * it and skip with an explanatory message rather than failing obscurely.
 *
 *   bash scripts/db.sh start
 *   cd backend && ./mvnw spring-boot:run
 *   cd frontend && npm run test:e2e
 */
export default defineConfig({
  testDir: './e2e',
  // One worker: the tests share one backend and one database, and progress
  // state is global to a learner id. Parallel runs would interleave writes.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: process.env['CI'] ? 'line' : [['list']],
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    },
    {
      // A real phone viewport, because the layout is a two-column grid that
      // collapses at 860px and nothing else checks that it collapses.
      name: 'mobile',
      use: { ...devices['Pixel 7'], channel: 'chrome' },
    },
  ],

  // Vite is started for the run if it is not already up. The backend is not;
  // see above.
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
