/**
 * The shapes the backend returns.
 *
 * These are hand-written rather than generated from the OpenAPI document, and
 * that is a trade worth naming: generated types cannot drift, but they also
 * cannot carry the explanation of *why* a field exists, and most of the
 * subtlety in this API is in fields whose names do not explain themselves —
 * `truncated`, `orderSensitive`, `verdict`, `expected`. The integration tests
 * on the Java side are what actually pin the contract; these are the reading
 * of it.
 */

// ---------------------------------------------------------------- query ----

export interface ColumnMeta {
  name: string
  typeName: string
  /** `numeric` or `text` — the grid right-aligns numbers without knowing PostgreSQL's type catalogue. */
  alignment: 'numeric' | 'text'
}

/** A value in a result cell. `null` is a real NULL and is rendered as such. */
export type CellValue = string | number | boolean | null | CellValue[]

export interface QueryResult {
  columns: ColumnMeta[]
  rows: CellValue[][]
  rowCount: number
  /** True when more rows existed than the limit. Shown, never hidden. */
  truncated: boolean
  rowLimit: number
  durationMs: number
  schema: string
}

export interface QueryError {
  /** A rejection reason such as NOT_A_SELECT, or a PostgreSQL SQLSTATE such as 42703. */
  code: string
  message: string
  /** What the error usually means, for the recognised kinds. Null otherwise. */
  explanation: string | null
  /** 1-based character offset, when PostgreSQL reports one. */
  position: number | null
  hint: string | null
}

export interface PlaygroundLimits {
  statementTimeoutMs: number
  maxRows: number
  maxSqlLength: number
  allowedSchemas: string[]
  maxConcurrent: number
  ratePerMinute: number
}

// ----------------------------------------------------------- curriculum ----

export type Difficulty = 'Beginner' | 'Intermediate' | 'Advanced'
export type ProblemDifficulty = 'Easy' | 'Medium' | 'Hard'

export interface LessonSummary {
  id: string
  moduleId: string
  position: number
  title: string
  slug: string
  difficulty: Difficulty
  estimatedMinutes: number
  summary: string
  tags: string[]
}

export interface ModuleWithLessons {
  id: string
  position: number
  title: string
  slug: string
  summary: string
  icon: string | null
  lessonCount: number
  totalMinutes: number
  lessons: LessonSummary[]
}

export interface SyntaxForm {
  form: string
  notes: string
}

export interface Example {
  title: string
  sql: string
  /** Null means the default `learning` dataset. */
  schema: string | null
  walkthrough: string
  /** Never null in practice — the loader refuses to start without it. */
  result: QueryResult | null
}

export interface EdgeCase {
  title: string
  body: string
  sql: string
  schema: string | null
  result: QueryResult | null
}

export interface Pitfall {
  mistake: string
  why: string
  fix: string
}

export interface QuestionAnswer {
  question: string
  answer: string
}

export interface Exercise {
  prompt: string
  difficulty: ProblemDifficulty
  hint: string
  solutionSql: string
  result: QueryResult | null
}

export interface Dialect {
  standard: string
  postgres: string
  mysql: string
}

export interface Lesson extends LessonSummary {
  prerequisites: string[]
  objectives: string[]
  /** Markdown. */
  concept: string
  syntax: SyntaxForm[]
  sampleTables: string[]
  examples: Example[]
  edgeCases: EdgeCase[]
  pitfalls: Pitfall[]
  /** Markdown, or null where there is nothing honest to say. */
  performance: string | null
  interviewQuestions: QuestionAnswer[]
  exercises: Exercise[]
  checklist: string[]
  related: string[]
  dialect: Dialect
}

// ------------------------------------------------------------- problems ----

export interface ProblemSource {
  name: string
  url: string
}

export interface ProblemSummary {
  id: string
  position: number
  title: string
  slug: string
  difficulty: ProblemDifficulty
  topic: string
  tags: string[]
  orderSensitive: boolean
  source?: ProblemSource
}

/**
 * The problem as served before the answer is asked for.
 *
 * Note what is absent and stays absent: `solutionSql`, `hints`, `explanation`
 * and `expected`. They live in different tables and are served by different
 * endpoints.
 */
export interface Problem extends ProblemSummary {
  statement: string
  tables: string[]
  expectedColumns: string[]
  edgeCases: string[]
}

export interface SolutionLine {
  /** A fragment, not a runnable statement — hence `clause` rather than `sql`. */
  clause: string
  note: string
}

export interface Alternative {
  title: string
  sql: string
  when: string
  /** Recorded by executing both, not asserted by the author. */
  equivalent: boolean
  result: QueryResult | null
}

export interface WrongApproach {
  title: string
  sql: string
  whyWrong: string
  /** Attached by the loader, so the page can show what the wrong answer returns. */
  result?: QueryResult | null
}

export interface ExtraTest {
  name: string
  schema: string
  note: string
  sql: string
  result: QueryResult | null
}

export interface Explanation {
  lineByLine: SolutionLine[]
  walkthrough: string
  alternatives: Alternative[] | null
  performance: string
  wrongApproaches: WrongApproach[]
  extraTests: ExtraTest[]
  followUps: QuestionAnswer[]
  related: string[]
  /** The reference solution's recorded output. */
  expected: QueryResult | null
}

export interface Solution {
  sql: string
  explanation: Explanation
}

export interface Hint {
  ordinal: number
  total: number
  hint: string
}

// ----------------------------------------------------------- evaluation ----

export type Verdict =
  | 'CORRECT'
  | 'CORRECT_ON_SAMPLE_ONLY'
  | 'INCORRECT'
  | 'DID_NOT_RUN'
  | 'REFUSED'

export interface Comparison {
  matches: boolean
  code: string
  notes: string[]
  firstDifferingRow: number | null
  submittedRowCount: number
  referenceRowCount: number
}

export interface DatasetOutcome {
  schema: string
  matches: boolean
  comparison: Comparison
}

export interface Evaluation {
  problemId: string
  problemTitle: string
  correct: boolean
  verdict: Verdict
  datasets: DatasetOutcome[]
  result: QueryResult | null
  error: QueryError | null
  /** What the check does and does not establish. Shown, not buried. */
  limits: string
}

// ----------------------------------------------------------------- quiz ----

export interface QuizQuestion {
  id: string
  topic: string
  difficulty: ProblemDifficulty
  question: string
  options: string[]
}

export interface QuizResult {
  id: string
  known: boolean
  correct?: boolean
  chosenIndex?: number
  answerIndex?: number
  explanation?: string
}

export interface QuizMarking {
  total: number
  correct: number
  results: QuizResult[]
}

// -------------------------------------------------------------- dataset ----

export interface ColumnInfo {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  foreignKey: string | null
  defaultValue: string | null
}

export interface TableInfo {
  name: string
  columns: ColumnInfo[]
  rowCount: number
}

// ------------------------------------------------------------- progress ----

export type LessonStatus = 'not_started' | 'in_progress' | 'completed'

export interface LessonProgress {
  id: string
  moduleId: string
  title: string
  slug: string
  difficulty: Difficulty
  estimatedMinutes: number
  position: number
  status: LessonStatus
  confidence: number | null
  viewCount: number | null
  lastViewedAt: string | null
  completedAt: string | null
  revisionDueOn: string | null
  revisionIntervalDays: number | null
}

export interface ProblemProgress {
  id: string
  title: string
  slug: string
  difficulty: ProblemDifficulty
  topic: string
  position: number
  attempts: number
  /** Whether it has *ever* been solved, not whether the last attempt was right. */
  solved: boolean
  firstSolvedAt: string | null
  lastAttemptAt: string | null
  lastVerdict: string | null
  revisionDueOn: string | null
}

export interface Attempt {
  id: number
  sql: string
  verdict: Verdict
  correct: boolean
  hintsUsed: number
  attemptedAt: string
}

export interface ModuleProgress {
  id: string
  title: string
  position: number
  lessons: number
  completed: number
}

export interface ActivityDay {
  day: string
  problems: number
  quiz: number
  lessons: number
}

export interface ProgressOverview {
  learnerId: string
  totalLessons: number
  lessonsCompleted: number
  lessonsStarted: number
  minutesCompleted: number
  minutesTotal: number
  totalProblems: number
  problemsSolved: number
  problemAttempts: number
  quizAnswered: number
  quizCorrect: number
  revisionsDue: number
  modules: ModuleProgress[]
  recentActivity: ActivityDay[]
}

export interface QuizTopicAccuracy {
  topic: string
  answered: number
  correct: number
  distinctQuestions: number
  accuracyPct: number
}

export type RevisionKind = 'lesson' | 'problem' | 'quiz'

export interface RevisionItem {
  kind: RevisionKind
  id: string
  title: string
  slug: string | null
  dueOn: string
  intervalDays: number
  ease: number
  reviews: number
  lapses: number
  daysOverdue: number
  lastReviewedAt: string | null
}

export interface ReviewOutcome {
  kind: RevisionKind
  item: string
  quality: number
  nextIntervalDays: number
  ease: number
  reviews: number
  lapses: number
}

// --------------------------------------------------------------- search ----

export interface SearchHit {
  kind: 'lesson' | 'problem'
  id: string
  title: string
  slug: string
  blurb: string
  difficulty: string
  rank: number
}

export interface Statistics {
  modules: number
  lessons: number
  problems: number
  quizQuestions: number
  problemsByDifficulty: Record<string, number>
  problemsByTopic: Record<string, number>
  contentChecksum: string
  contentLoadedAt: string
}

export interface Topics {
  problemTopics: string[]
  quizTopics: string[]
}
