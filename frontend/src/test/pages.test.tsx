import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LessonPage } from '../pages/LessonPage'
import { ProblemPage } from '../pages/ProblemPage'
import { Dashboard } from '../pages/Dashboard'
import type { Lesson, Problem, ProgressOverview, QueryResult } from '../api/types'

/**
 * Page-level tests against a stubbed fetch.
 *
 * These check the behaviours that are properties of this application rather
 * than of React: that a lesson renders every one of its sections, that a
 * problem page does not display an answer nobody asked for, and that hints
 * arrive one at a time. The backend has its own tests for the API; these are
 * about what the browser does with it.
 */

const emptyResult: QueryResult = {
  columns: [{ name: 'n', typeName: 'int4', alignment: 'numeric' }],
  rows: [[1]],
  rowCount: 1,
  truncated: false,
  rowLimit: 500,
  durationMs: 1,
  schema: 'learning',
}

const lesson: Lesson = {
  id: 'f-nulls',
  moduleId: 'foundations',
  position: 40,
  title: 'NULL and three-valued logic',
  slug: 'nulls',
  difficulty: 'Beginner',
  estimatedMinutes: 25,
  summary: 'NULL is not a value.',
  tags: ['null'],
  prerequisites: [],
  objectives: ['State the three truth values'],
  // The markdown heading is deliberately not "The idea": that is also the
  // section heading the page renders, and a fixture that duplicates it makes
  // the section assertion below ambiguous for a reason that has nothing to
  // do with the component.
  concept: '## NULL means unknown\n\nNot zero, not an empty string.',
  syntax: [{ form: 'expr IS NULL', notes: 'The only correct way to test for NULL.' }],
  sampleTables: ['employees'],
  examples: [
    {
      title: 'The three truth values',
      sql: 'select null = null',
      schema: null,
      walkthrough: 'Six of the nine columns come back empty.',
      result: emptyResult,
    },
  ],
  edgeCases: [
    {
      title: 'NOT IN with a NULL returns nothing',
      body: 'The first count is zero.',
      sql: 'select 1',
      schema: null,
      result: emptyResult,
    },
  ],
  pitfalls: [{ mistake: 'Treating NULL as zero', why: 'Clients render them alike.', fix: 'Use IS NULL.' }],
  performance: 'IS NULL is sargable in PostgreSQL.',
  interviewQuestions: [{ question: 'What is NULL?', answer: 'The absence of a known value.' }],
  exercises: [
    {
      prompt: 'Return every employee with a department label.',
      difficulty: 'Easy',
      hint: 'A LEFT JOIN keeps the employees with no department.',
      solutionSql: 'select 1',
      result: emptyResult,
    },
  ],
  checklist: ['NULL means unknown'],
  related: ['f-filtering'],
  dialect: { standard: 'Standard.', postgres: 'PostgreSQL.', mysql: 'MySQL.' },
}

const problem: Problem = {
  id: 'p01-second-highest-salary',
  position: 10,
  title: 'Second-highest salary',
  slug: 'second-highest-salary',
  difficulty: 'Easy',
  topic: 'Window functions',
  tags: ['ranking'],
  orderSensitive: false,
  statement: 'Return the second-highest distinct salary.',
  tables: ['employees'],
  expectedColumns: ['salary', 'full_name'],
  edgeCases: ['Several employees may share the second-highest salary.'],
}

const overview: ProgressOverview = {
  learnerId: 'local',
  totalLessons: 36,
  lessonsCompleted: 2,
  lessonsStarted: 1,
  minutesCompleted: 45,
  minutesTotal: 905,
  totalProblems: 17,
  problemsSolved: 1,
  problemAttempts: 3,
  quizAnswered: 4,
  quizCorrect: 3,
  revisionsDue: 2,
  modules: [{ id: 'foundations', title: 'Foundations', position: 1, lessons: 7, completed: 2 }],
  recentActivity: Array.from({ length: 14 }, (_, i) => ({
    day: `2026-10-${String(13 + i).padStart(2, '0')}`,
    problems: i === 13 ? 3 : 0,
    quiz: 0,
    lessons: i === 13 ? 1 : 0,
  })),
}

/**
 * The URL a fetch argument refers to.
 *
 * `String(input)` is wrong for a `Request`, which stringifies to
 * `[object Object]` — the stub would then match no route and every test
 * would silently receive the fallback body.
 */
function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.href
  return input.url
}

/** Routes a stubbed fetch by path, so each test declares only what it needs. */
function stubFetch(routes: Record<string, unknown>) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = requestUrl(input)
    for (const [prefix, body] of Object.entries(routes)) {
      if (url.startsWith(prefix)) {
        return Promise.resolve(
          new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }
    }
    return Promise.resolve(new Response('{}', { status: 200 }))
  })
}

describe('LessonPage', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      stubFetch({
        '/api/curriculum/lessons/nulls': lesson,
        '/api/progress/lessons': [],
      }),
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  it('renders every required section of a lesson', async () => {
    render(
      <MemoryRouter initialEntries={['/lessons/nulls']}>
        <Routes>
          <Route path="/lessons/:idOrSlug" element={<LessonPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await screen.findByRole('heading', { name: lesson.title, level: 1 })

    // The fourteen parts, by their headings. A lesson missing one would not
    // have loaded on the backend, so this checks the page shows what it was
    // given rather than quietly dropping a section.
    for (const heading of [
      'What you will be able to do',
      'The idea',
      'Syntax',
      'Examples',
      'Edge cases',
      'Common mistakes',
      'Performance',
      'Interview questions',
      'Practice',
      'Revision checklist',
      'Portability',
      'Next',
    ]) {
      expect(screen.getByRole('heading', { name: heading, level: 2 })).toBeInTheDocument()
    }
  })

  it('shows the recorded output of an example', async () => {
    render(
      <MemoryRouter initialEntries={['/lessons/nulls']}>
        <Routes>
          <Route path="/lessons/:idOrSlug" element={<LessonPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByRole('heading', { name: lesson.title, level: 1 })
    expect(screen.getAllByText(/Recorded output/).length).toBeGreaterThan(0)
  })

  it('keeps an exercise answer hidden until it is asked for', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/lessons/nulls']}>
        <Routes>
          <Route path="/lessons/:idOrSlug" element={<LessonPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByRole('heading', { name: lesson.title, level: 1 })

    const hintButton = screen.getByRole('button', { name: /show hint/i })
    expect(screen.queryByText(lesson.exercises[0]!.hint)).not.toBeInTheDocument()
    await user.click(hintButton)
    expect(screen.getByText(lesson.exercises[0]!.hint)).toBeInTheDocument()
  })
})

describe('ProblemPage', () => {
  it('does not display the solution until it is requested', async () => {
    const solution = {
      sql: 'select salary from employees',
      explanation: {
        lineByLine: [{ clause: 'dense_rank()', note: 'Ranks distinct values.' }],
        walkthrough: 'The distinct salaries in descending order…',
        alternatives: [],
        performance: 'Sorts the whole table once.',
        wrongApproaches: [],
        extraTests: [],
        followUps: [],
        related: [],
        expected: emptyResult,
      },
    }
    vi.stubGlobal(
      'fetch',
      stubFetch({
        '/api/problems/second-highest-salary/hints/count': { total: 3 },
        '/api/problems/second-highest-salary/solution': solution,
        '/api/problems/second-highest-salary': problem,
        '/api/dataset/schema': [],
        '/api/progress/problems': [],
      }),
    )

    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/problems/second-highest-salary']}>
        <Routes>
          <Route path="/problems/:idOrSlug" element={<ProblemPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await screen.findByRole('heading', { name: problem.title, level: 1 })

    // The answer is not on the page, and no request for it has been made.
    expect(screen.queryByText(solution.sql)).not.toBeInTheDocument()
    expect(screen.queryByText(solution.explanation.walkthrough)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /reveal the solution/i }))
    await waitFor(() =>
      expect(screen.getByText(solution.explanation.walkthrough)).toBeInTheDocument(),
    )

    vi.unstubAllGlobals()
  })

  it('reveals hints one at a time', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input)
      const body = url.includes('/hints/count')
        ? { total: 3 }
        : url.includes('/hints/1')
          ? { ordinal: 1, total: 3, hint: 'First hint.' }
          : url.includes('/hints/2')
            ? { ordinal: 2, total: 3, hint: 'Second hint.' }
            : url.includes('/api/problems/second-highest-salary')
              ? problem
              : []
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/problems/second-highest-salary']}>
        <Routes>
          <Route path="/problems/:idOrSlug" element={<ProblemPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByRole('heading', { name: problem.title, level: 1 })

    await user.click(await screen.findByRole('button', { name: /show a hint/i }))
    await waitFor(() => expect(screen.getByText('First hint.')).toBeInTheDocument())
    // Asking for the first hint must not hand over the second.
    expect(screen.queryByText('Second hint.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /next hint/i }))
    await waitFor(() => expect(screen.getByText('Second hint.')).toBeInTheDocument())

    vi.unstubAllGlobals()
  })
})

describe('Dashboard', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('summarises progress and renders a bar for every day including the empty ones', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({
        '/api/progress/revision/due': [],
        '/api/progress': overview,
        '/api/curriculum/statistics': {
          modules: 9,
          lessons: 36,
          problems: 17,
          quizQuestions: 43,
          problemsByDifficulty: { Easy: 5 },
          problemsByTopic: {},
          contentChecksum: 'abc123def456',
          contentLoadedAt: '2026-10-26T14:00:00Z',
        },
      }),
    )

    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    )

    await screen.findByRole('heading', { name: 'Dashboard', level: 1 })
    expect(screen.getByText('2/36')).toBeInTheDocument()
    expect(screen.getByText('1/17')).toBeInTheDocument()

    const spark = await screen.findByRole('img', { name: /activity/i })
    // Fourteen bars, not four — the days with nothing on them are drawn,
    // because omitting them misrepresents the shape of the week.
    expect(spark.children).toHaveLength(14)
  })

  it('shows an actionable message when the backend is unreachable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    )
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    )
    await screen.findByRole('alert')
    expect(screen.getByText(/Cannot reach the server/)).toBeInTheDocument()
    expect(screen.getByText(/mvnw spring-boot:run/)).toBeInTheDocument()
  })
})
