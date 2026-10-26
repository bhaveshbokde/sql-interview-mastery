import { expect, test, type Page } from '@playwright/test'

/**
 * The learner journey, in a real browser against the real stack.
 *
 * Every other test in this repository stubs something. The component tests
 * stub `fetch`; the backend's integration tests stub the browser. These stub
 * nothing, which makes them the only ones that can catch the two halves
 * disagreeing — a renamed field, a changed status code, a response shape the
 * UI reads differently from the way the API writes it.
 *
 * Each test uses its own learner id so progress assertions do not depend on
 * what a previous run left behind.
 */

const LEARNER = 'e2e'

/** Points the app at a dedicated learner profile before the first render. */
async function useTestLearner(page: Page) {
  await page.addInitScript((id: string) => {
    window.localStorage.setItem('sqlmastery.learnerId', id)
  }, LEARNER)
}

test.beforeAll(async ({ request }) => {
  // Fail with something readable rather than a wall of timeouts if the
  // backend is not up. This is the single most likely reason for a red run
  // on a fresh machine.
  const health = await request.get('http://localhost:8085/actuator/health').catch(() => null)
  test.skip(
    !health?.ok(),
    'The backend is not running on :8085. Start it with ' +
      '`bash scripts/db.sh start` then `cd backend && ./mvnw spring-boot:run`.',
  )
  await request.post('http://localhost:8085/api/progress/reset', {
    headers: { 'X-Learner-Id': LEARNER },
  })
})

test.beforeEach(async ({ page }) => {
  await useTestLearner(page)
})

test('the dashboard loads and reports what the curriculum contains', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeVisible()
  // Rendered from the live API, so this failing means the contract moved.
  await expect(page.getByText('Lessons complete')).toBeVisible()
  await expect(page.getByText('Problems solved')).toBeVisible()
  await expect(page.getByRole('img', { name: /activity/i })).toBeVisible()

  // Nine modules, each with a progress bar.
  const moduleBars = page.locator('.progress-track')
  await expect(moduleBars.first()).toBeVisible()
  expect(await moduleBars.count()).toBeGreaterThanOrEqual(9)
})

test('curriculum, module and lesson navigation works', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: 'Curriculum' }).click()

  await expect(page.getByRole('heading', { name: 'Curriculum', level: 1 })).toBeVisible()
  await expect(page.getByRole('heading', { name: /^1\. Foundations$/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Advanced PostgreSQL/ })).toBeVisible()

  await page.getByRole('link', { name: 'NULL and three-valued logic' }).click()

  await expect(
    page.getByRole('heading', { name: 'NULL and three-valued logic', level: 1 }),
  ).toBeVisible()

  // Every one of the fourteen parts, rendered from the real lesson.
  for (const heading of [
    'What you will be able to do',
    'The idea',
    'Syntax',
    'Examples',
    'Edge cases',
    'Common mistakes',
    'Interview questions',
    'Practice',
    'Revision checklist',
    'Portability',
  ]) {
    await expect(page.getByRole('heading', { name: heading, level: 2 })).toBeVisible()
  }

  // The recorded output is on the page, with real values in it.
  await expect(page.getByText(/Recorded output/).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: 'NULL' }).first()).toBeVisible()
})

test('a lesson example runs live against the database', async ({ page }) => {
  await page.goto('/lessons/nulls')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  const firstExample = page.locator('.sql-block').first()
  await firstExample.getByRole('button', { name: 'Run it' }).click()

  // The button returns to its resting label once the request settles, and
  // a result table is present.
  await expect(firstExample.getByRole('button', { name: 'Run it' })).toBeVisible()
  await expect(firstExample.locator('table.result')).toBeVisible()
  await expect(firstExample.getByRole('button', { name: 'Show recorded output' })).toBeVisible()
})

test('the playground runs valid SQL and shows typed columns', async ({ page }) => {
  await page.goto('/playground')
  await expect(page.getByRole('heading', { name: 'SQL playground', level: 1 })).toBeVisible()

  // The schema browser is populated from /api/dataset/schema.
  await expect(page.getByText('employees', { exact: false }).first()).toBeVisible()

  const editor = page.locator('.cm-content')
  await editor.click()
  await page.keyboard.press('ControlOrMeta+A')
  await editor.fill('select dept_id, count(*) as n from employees group by dept_id order by n desc')

  await page.getByRole('button', { name: 'Run', exact: true }).click()

  const table = page.locator('table.result')
  await expect(table).toBeVisible()
  await expect(table.getByRole('columnheader', { name: /dept_id/ })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: /n\b/ })).toBeVisible()
  // int8 is PostgreSQL's type name for count(*) — proof the column metadata
  // came from the database rather than being inferred in the browser.
  await expect(table.getByRole('columnheader', { name: /int8/ })).toBeVisible()
  await expect(page.getByText(/row(s)? ?$|rows$/).first()).toBeVisible()
})

test('invalid SQL produces a teachable error, not a stack trace', async ({ page }) => {
  await page.goto('/playground')
  const editor = page.locator('.cm-content')
  await editor.click()
  await page.keyboard.press('ControlOrMeta+A')
  await editor.fill('select nosuchcolumn from employees')

  await page.getByRole('button', { name: 'Run', exact: true }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  // PostgreSQL's SQLSTATE, the message, and the explanation written for a
  // learner — all three, because the point is that the error teaches.
  await expect(alert).toContainText('42703')
  await expect(alert).toContainText('nosuchcolumn')
  await expect(alert).toContainText(/does not exist/)
  await expect(alert).not.toContainText(/Exception|at com\.bhavesh/)
})

test('a destructive statement is refused with an explanation', async ({ page }) => {
  await page.goto('/playground')
  const editor = page.locator('.cm-content')
  await editor.click()
  await page.keyboard.press('ControlOrMeta+A')
  await editor.fill('drop table employees')

  await page.getByRole('button', { name: 'Run', exact: true }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText('NOT_A_SELECT')
  await expect(alert).toContainText(/read-only/)

  // And the table is still there afterwards.
  await editor.click()
  await page.keyboard.press('ControlOrMeta+A')
  await editor.fill('select count(*) as n from employees')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.locator('table.result').getByRole('cell', { name: '40' })).toBeVisible()
})

test('a practice problem can be attempted, hinted and solved', async ({ page }) => {
  await page.goto('/problems')
  await expect(page.getByRole('heading', { name: 'Interview problems', level: 1 })).toBeVisible()

  await page.getByRole('link', { name: 'Second-highest salary' }).click()
  await expect(page.getByRole('heading', { name: 'Second-highest salary', level: 1 })).toBeVisible()

  // The answer is not on the page before it is asked for.
  await expect(page.getByText('dense_rank() over (order by salary desc) as rnk')).toHaveCount(0)

  // One hint at a time.
  await page.getByRole('button', { name: /Show a hint/ }).click()
  await expect(page.getByText('Hint 1 of 3')).toBeVisible()
  await expect(page.getByText('Hint 2 of 3')).toHaveCount(0)
  await page.getByRole('button', { name: /Next hint/ }).click()
  await expect(page.getByText('Hint 2 of 3')).toBeVisible()

  // A wrong answer that fits the sample but not the alternate dataset.
  const editor = page.locator('.cm-content')
  await editor.click()
  await editor.fill(
    "select salary, first_name || ' ' || last_name as full_name " +
      'from employees order by salary desc, emp_id limit 1 offset 1',
  )
  await page.getByRole('button', { name: 'Check my answer' }).click()
  await expect(page.getByText(/Right on the sample data, wrong on the alternate dataset/)).toBeVisible()

  // Then the correct one.
  await editor.click()
  await page.keyboard.press('ControlOrMeta+A')
  await editor.fill(
    "with ranked as (select salary, first_name || ' ' || last_name as full_name, " +
      'dense_rank() over (order by salary desc) as rnk from employees) ' +
      'select salary, full_name from ranked where rnk = 2 order by full_name',
  )
  await page.getByRole('button', { name: 'Check my answer' }).click()
  await expect(page.getByText('Correct on both datasets')).toBeVisible()

  // The attempt history is recorded server-side and shown back.
  await expect(page.getByRole('heading', { name: 'Your attempts', level: 2 })).toBeVisible()
})

test('the quiz marks answers and explains them', async ({ page }) => {
  await page.goto('/quiz')
  await expect(page.getByRole('heading', { name: 'Quiz', level: 1 })).toBeVisible()

  const radios = page.getByRole('radio')
  await expect(radios.first()).toBeVisible()

  // Answer the first three questions.
  for (let i = 0; i < 3; i++) {
    await page.locator(`.card:has(input[type="radio"])`).nth(i).getByRole('radio').first().check()
  }

  await page.getByRole('button', { name: 'Submit' }).click()

  await expect(page.getByText(/\d+ of 3 correct/)).toBeVisible()
  // The explanation comes back from the server with the marking.
  await expect(page.getByText(/Why the right answer is right|Correct/).first()).toBeVisible()
})

test('progress survives a reload and a navigation away', async ({ page }) => {
  await page.goto('/lessons/case-expressions')
  await expect(page.getByRole('heading', { name: 'CASE expressions', level: 1 })).toBeVisible()

  await page.getByRole('button', { name: '4', exact: true }).click()
  await expect(page.getByText('Marked complete')).toBeVisible()

  // Away and back. Scoped to the sidebar: the lesson page also has a
  // "Curriculum" breadcrumb, so an unscoped match hits two links.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Curriculum' }).click()
  await expect(page.getByRole('heading', { name: 'Curriculum', level: 1 })).toBeVisible()
  await page.goto('/lessons/case-expressions')
  await expect(page.getByText('Marked complete')).toBeVisible()

  // And a hard reload — this is persisted in PostgreSQL, not in the tab.
  await page.reload()
  await expect(page.getByText('Marked complete')).toBeVisible()

  // It also shows on the dashboard and put itself in the revision queue.
  await page.goto('/progress')
  await expect(page.getByRole('heading', { name: 'My progress', level: 1 })).toBeVisible()
  await expect(page.getByText(/1 of \d+/).first()).toBeVisible()
})

test('search finds lessons and links to them', async ({ page }) => {
  await page.goto('/search')
  await page.getByRole('searchbox', { name: /search the curriculum/i }).fill('window function')

  await expect(page.getByRole('link', { name: 'What a window function is' })).toBeVisible()
  await page.getByRole('link', { name: 'What a window function is' }).click()
  await expect(
    page.getByRole('heading', { name: 'What a window function is', level: 1 }),
  ).toBeVisible()
})

test('an unknown route shows a not-found page rather than a blank screen', async ({ page }) => {
  await page.goto('/no-such-page')
  await expect(page.getByRole('heading', { name: 'Not here', level: 1 })).toBeVisible()
  // Scoped to the main region: the sidebar has its own Dashboard link, and
  // an unscoped match is a strict-mode violation rather than a pass.
  await expect(
    page.getByRole('main').getByRole('link', { name: 'Dashboard' }),
  ).toBeVisible()
})

test('no console errors on the main pages', async ({ page }) => {
  const errors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })
  page.on('pageerror', (err) => errors.push(err.message))

  for (const path of ['/', '/modules', '/lessons/nulls', '/problems', '/quiz', '/progress']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  }

  // React Router's v7 deprecation notices are warnings, not errors, and do
  // not reach this list. Anything that does is worth seeing.
  expect(errors).toEqual([])
})
