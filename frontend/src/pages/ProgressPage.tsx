import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getLearnerId, setLearnerId } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import {
  Badge,
  EmptyState,
  ErrorBox,
  Loading,
  ProgressBar,
  Section,
  formatDate,
} from '../components/common'

export function ProgressPage() {
  const overview = useApi(() => api.progress(), [])
  const lessons = useApi(() => api.lessonProgress(), [])
  const problems = useApi(() => api.problemProgress(), [])
  const quiz = useApi(() => api.quizAccuracy(), [])
  const reset = useMutation(() => api.resetProgress())

  const [learner, setLearner] = useState(getLearnerId())
  const [confirmingReset, setConfirmingReset] = useState(false)

  if (overview.error) return <ErrorBox error={overview.error} onRetry={overview.reload} />
  if (overview.loading || !overview.data) return <Loading lines={6} label="Loading progress" />

  const p = overview.data
  const started = (lessons.data ?? []).filter((l) => l.status === 'in_progress')
  const attempted = (problems.data ?? []).filter((x) => x.attempts > 0)
  const unsolved = attempted.filter((x) => !x.solved)

  return (
    <div className="container">
      <h1>My progress</h1>
      <p className="muted">
        Everything here is scoped to the learner id <code>{p.learnerId}</code>. There is no
        authentication — the id just lets you keep two separate runs through the course.
      </p>

      <Section title="Curriculum">
        <div className="card">
          <div className="spread">
            <span className="small">
              <strong>
                {p.lessonsCompleted} of {p.totalLessons}
              </strong>{' '}
              lessons complete
            </span>
            <span className="tiny faint">
              {p.minutesCompleted} of {p.minutesTotal} minutes
            </span>
          </div>
          <div style={{ marginTop: '0.4rem' }}>
            <ProgressBar value={p.lessonsCompleted} total={p.totalLessons} />
          </div>
        </div>

        {started.length > 0 && (
          <div className="card">
            <div className="card-title">Opened but not finished</div>
            <div className="list">
              {started.map((lesson) => (
                <div className="list-row" key={lesson.id}>
                  <span className="status-dot in_progress" />
                  <div className="list-row-main">
                    <div className="list-row-title">
                      <Link to={`/lessons/${lesson.slug}`}>{lesson.title}</Link>
                    </div>
                    <div className="list-row-sub">
                      {lesson.viewCount} view{lesson.viewCount === 1 ? '' : 's'} · last opened{' '}
                      {formatDate(lesson.lastViewedAt)}
                    </div>
                  </div>
                  <Badge kind={lesson.difficulty}>{lesson.difficulty}</Badge>
                </div>
              ))}
            </div>
          </div>
        )}
      </Section>

      <Section title="Problems">
        <div className="card">
          <div className="spread">
            <span className="small">
              <strong>
                {p.problemsSolved} of {p.totalProblems}
              </strong>{' '}
              solved
            </span>
            <span className="tiny faint">{p.problemAttempts} attempts in total</span>
          </div>
          <div style={{ marginTop: '0.4rem' }}>
            <ProgressBar value={p.problemsSolved} total={p.totalProblems} />
          </div>
        </div>

        {unsolved.length > 0 && (
          <div className="card">
            <div className="card-title">Attempted, not yet solved</div>
            <div className="list">
              {unsolved.map((problem) => (
                <div className="list-row" key={problem.id}>
                  <div className="list-row-main">
                    <div className="list-row-title">
                      <Link to={`/problems/${problem.slug}`}>{problem.title}</Link>
                    </div>
                    <div className="list-row-sub">
                      {problem.attempts} attempt{problem.attempts === 1 ? '' : 's'} · last{' '}
                      {problem.lastVerdict} · {formatDate(problem.lastAttemptAt)}
                    </div>
                  </div>
                  <Badge kind={problem.difficulty}>{problem.difficulty}</Badge>
                </div>
              ))}
            </div>
          </div>
        )}
      </Section>

      <Section title="Quiz accuracy by topic" note="weakest first — what to study next">
        {quiz.data && quiz.data.length > 0 ? (
          <div className="card">
            <div className="list">
              {quiz.data.map((row) => (
                <div className="list-row" key={row.topic}>
                  <div className="list-row-main">
                    <div className="list-row-title">{row.topic}</div>
                    <div style={{ marginTop: '0.25rem' }}>
                      <ProgressBar value={row.correct} total={row.answered} />
                    </div>
                  </div>
                  <span className="tiny faint nowrap">
                    {row.correct}/{row.answered} · {row.accuracyPct}%
                  </span>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <EmptyState title="No quiz answers yet">
            <Link to="/quiz">Answer a few questions</Link> and this becomes a list of what to
            revise.
          </EmptyState>
        )}
      </Section>

      <Section title="Settings">
        <div className="card">
          <div className="card-title">Learner profile</div>
          <p className="small muted">
            Changing this switches to a separate set of progress records. It is not a login and
            protects nothing.
          </p>
          <div className="row">
            <input
              type="text"
              value={learner}
              onChange={(e) => setLearner(e.target.value)}
              aria-label="Learner id"
            />
            <button
              onClick={() => {
                setLearnerId(learner || 'local')
                window.location.reload()
              }}
            >
              Switch
            </button>
          </div>
        </div>

        <div className="card">
          <div className="card-title">Reset progress</div>
          <p className="small muted">
            Deletes lesson status, attempt history, quiz answers and the revision schedule for{' '}
            <code>{p.learnerId}</code>. The curriculum and the dataset are untouched.
          </p>
          {confirmingReset ? (
            <div className="button-row">
              <button
                className="btn-primary"
                disabled={reset.running}
                onClick={async () => {
                  await reset.run()
                  setConfirmingReset(false)
                  overview.reload()
                  lessons.reload()
                  problems.reload()
                  quiz.reload()
                }}
              >
                {reset.running ? 'Resetting…' : 'Yes, delete it'}
              </button>
              <button className="btn-ghost" onClick={() => setConfirmingReset(false)}>
                Cancel
              </button>
            </div>
          ) : (
            <button onClick={() => setConfirmingReset(true)}>Reset my progress</button>
          )}
          {reset.result && (
            <p className="tiny faint">{reset.result.rowsDeleted} rows deleted.</p>
          )}
        </div>
      </Section>
    </div>
  )
}
