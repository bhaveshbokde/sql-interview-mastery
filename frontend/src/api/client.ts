import type {
  Attempt,
  Evaluation,
  Hint,
  Lesson,
  LessonProgress,
  LessonSummary,
  ModuleWithLessons,
  PlaygroundLimits,
  Problem,
  ProblemProgress,
  ProblemSummary,
  ProgressOverview,
  QueryError,
  QueryResult,
  QuizMarking,
  QuizQuestion,
  QuizTopicAccuracy,
  RevisionItem,
  RevisionKind,
  ReviewOutcome,
  SearchHit,
  Solution,
  Statistics,
  TableInfo,
  Topics,
} from './types'

/**
 * The API base.
 *
 * Empty by default, which means same-origin — the Vite dev server proxies
 * /api to the backend, and a production build is served from the same origin
 * as the API. VITE_API_BASE_URL exists for the case where they are genuinely
 * split.
 */
const BASE = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * Identifies the learner for progress tracking.
 *
 * Not a credential — see the backend's LearnerId for why. It is stored in
 * localStorage so that progress survives a reload, and it is a plain value
 * the user could change, which is fine for a single-person study tool.
 */
const LEARNER_KEY = 'sqlmastery.learnerId'

export function getLearnerId(): string {
  try {
    return localStorage.getItem(LEARNER_KEY) ?? 'local'
  } catch {
    // localStorage throws in some privacy modes. Falling back to the default
    // profile is better than the whole application failing to render.
    return 'local'
  }
}

export function setLearnerId(id: string): void {
  try {
    localStorage.setItem(LEARNER_KEY, id)
  } catch {
    /* ignore — progress will just be attributed to the default profile */
  }
}

/**
 * An error carrying the backend's structured detail.
 *
 * The playground and the evaluator return a `code` the UI acts on, so losing
 * it in a generic Error would mean re-parsing a message string.
 */
export class ApiError extends Error {
  readonly status: number
  readonly detail: QueryError | null

  constructor(status: number, message: string, detail: QueryError | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(BASE + path, {
      ...init,
      headers: {
        Accept: 'application/json',
        'X-Learner-Id': getLearnerId(),
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    })
  } catch {
    // A network failure is the one error whose cause is almost always "the
    // backend is not running", so say that rather than "Failed to fetch".
    // The underlying cause is deliberately dropped: "TypeError: Failed to
    // fetch" tells the reader nothing the replacement does not.
    throw new ApiError(
      0,
      'Could not reach the server. Is the backend running on port 8085?',
      null,
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const body: unknown = text ? safeParse(text) : null

  if (!response.ok) {
    const detail = isQueryError(body) ? body : null
    const message =
      detail?.message ??
      (typeof body === 'object' && body !== null && 'message' in body
        ? String(body.message)
        : `Request failed with status ${response.status}`)
    throw new ApiError(response.status, message, detail)
  }

  return body as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function isQueryError(value: unknown): value is QueryError {
  return typeof value === 'object' && value !== null && 'code' in value && 'message' in value
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? '{}' : JSON.stringify(body),
  })
}

/** Encodes a path segment, so a slug with a slash cannot escape its position. */
function seg(value: string): string {
  return encodeURIComponent(value)
}

export const api = {
  // -------------------------------------------------------- curriculum --
  modules: () => request<ModuleWithLessons[]>('/api/curriculum/modules'),
  lessons: () => request<LessonSummary[]>('/api/curriculum/lessons'),
  lesson: (idOrSlug: string) => request<Lesson>(`/api/curriculum/lessons/${seg(idOrSlug)}`),
  search: (q: string, limit = 25) =>
    request<SearchHit[]>(`/api/curriculum/search?q=${encodeURIComponent(q)}&limit=${limit}`),
  statistics: () => request<Statistics>('/api/curriculum/statistics'),
  topics: () => request<Topics>('/api/curriculum/topics'),

  // ---------------------------------------------------------- problems --
  problems: (filters: { topic?: string; difficulty?: string; tag?: string; q?: string } = {}) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(filters)) {
      if (value) params.set(key, value)
    }
    const query = params.toString()
    return request<ProblemSummary[]>(`/api/problems${query ? `?${query}` : ''}`)
  },
  problem: (idOrSlug: string) => request<Problem>(`/api/problems/${seg(idOrSlug)}`),
  hintCount: (idOrSlug: string) =>
    request<{ total: number }>(`/api/problems/${seg(idOrSlug)}/hints/count`),
  /** One hint. Asking for the first does not hand over the third. */
  hint: (idOrSlug: string, ordinal: number) =>
    request<Hint>(`/api/problems/${seg(idOrSlug)}/hints/${ordinal}`),
  solution: (idOrSlug: string) => request<Solution>(`/api/problems/${seg(idOrSlug)}/solution`),
  evaluate: (idOrSlug: string, sql: string, hintsUsed = 0) =>
    post<Evaluation>(`/api/problems/${seg(idOrSlug)}/evaluate`, { sql, hintsUsed }),

  // -------------------------------------------------------- playground --
  run: (sql: string, schema?: string) =>
    post<QueryResult>('/api/playground/query', schema ? { sql, schema } : { sql }),
  limits: () => request<PlaygroundLimits>('/api/playground/limits'),

  // ----------------------------------------------------------- dataset --
  schema: (schema = 'learning') =>
    request<TableInfo[]>(`/api/dataset/schema?schema=${encodeURIComponent(schema)}`),
  resetDataset: () => post<{ tables: number; durationMs: number }>('/api/dataset/reset'),

  // -------------------------------------------------------------- quiz --
  quizQuestions: (opts: { topic?: string; difficulty?: string; count?: number } = {}) => {
    const params = new URLSearchParams()
    if (opts.topic) params.set('topic', opts.topic)
    if (opts.difficulty) params.set('difficulty', opts.difficulty)
    params.set('count', String(opts.count ?? 10))
    return request<QuizQuestion[]>(`/api/quiz/questions?${params}`)
  },
  submitQuiz: (answers: Record<string, number>) =>
    post<QuizMarking>('/api/quiz/submit', { answers }),

  // ---------------------------------------------------------- progress --
  progress: () => request<ProgressOverview>('/api/progress'),
  lessonProgress: () => request<LessonProgress[]>('/api/progress/lessons'),
  problemProgress: () => request<ProblemProgress[]>('/api/progress/problems'),
  attempts: (idOrSlug: string, limit = 20) =>
    request<Attempt[]>(`/api/progress/problems/${seg(idOrSlug)}/attempts?limit=${limit}`),
  quizAccuracy: () => request<QuizTopicAccuracy[]>('/api/progress/quiz'),
  viewLesson: (idOrSlug: string) =>
    post<{ status: string }>(`/api/progress/lessons/${seg(idOrSlug)}/view`),
  setLessonStatus: (idOrSlug: string, completed: boolean, confidence?: number) =>
    post<{ status: string }>(`/api/progress/lessons/${seg(idOrSlug)}/status`, {
      completed,
      confidence: confidence ?? null,
    }),
  dueRevisions: (limit = 25) =>
    request<RevisionItem[]>(`/api/progress/revision/due?limit=${limit}`),
  review: (kind: RevisionKind, idOrSlug: string, quality: number) =>
    post<ReviewOutcome>(`/api/progress/revision/${kind}/${seg(idOrSlug)}/review`, { quality }),
  resetProgress: () =>
    post<{ learnerId: string; rowsDeleted: number }>('/api/progress/reset'),
}
