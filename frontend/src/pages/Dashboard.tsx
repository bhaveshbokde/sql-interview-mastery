import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useApi } from '../api/useApi'
import {
  EmptyState,
  ErrorBox,
  Loading,
  ProgressBar,
  Section,
  Stat,
  formatMinutes,
} from '../components/common'
import type { ActivityDay } from '../api/types'

export function Dashboard() {
  const progress = useApi(() => api.progress(), [])
  const stats = useApi(() => api.statistics(), [])
  const due = useApi(() => api.dueRevisions(5), [])

  if (progress.error) return <ErrorBox error={progress.error} onRetry={progress.reload} />
  if (progress.loading || !progress.data) return <Loading lines={6} label="Loading dashboard" />

  const p = progress.data
  const lessonPct = p.totalLessons > 0 ? Math.round((p.lessonsCompleted / p.totalLessons) * 100) : 0
  const quizPct = p.quizAnswered > 0 ? Math.round((p.quizCorrect / p.quizAnswered) * 100) : null

  return (
    <div className="container">
      <h1>Dashboard</h1>
      <p className="muted">
        {p.lessonsCompleted === 0 && p.problemAttempts === 0
          ? 'Nothing recorded yet. Start with the foundations module, or open the playground and type a query.'
          : `${lessonPct}% of the curriculum complete, ${p.problemsSolved} of ${p.totalProblems} problems solved.`}
      </p>

      <div className="grid grid-4" style={{ marginTop: '1.25rem' }}>
        <Stat
          value={`${p.lessonsCompleted}/${p.totalLessons}`}
          label="Lessons complete"
          sub={`${formatMinutes(p.minutesCompleted)} of ${formatMinutes(p.minutesTotal)}`}
        />
        <Stat
          value={`${p.problemsSolved}/${p.totalProblems}`}
          label="Problems solved"
          sub={`${p.problemAttempts} attempt${p.problemAttempts === 1 ? '' : 's'}`}
        />
        <Stat
          value={quizPct === null ? '—' : `${quizPct}%`}
          label="Quiz accuracy"
          sub={p.quizAnswered > 0 ? `${p.quizCorrect} of ${p.quizAnswered}` : 'none answered'}
        />
        <Stat
          value={p.revisionsDue}
          label="Due for revision"
          sub={p.revisionsDue > 0 ? <Link to="/revision">Review now</Link> : 'nothing today'}
        />
      </div>

      <Section title="Progress by module">
        <div className="card">
          <div className="list">
            {p.modules.map((module) => (
              <div className="list-row" key={module.id}>
                <div className="list-row-main">
                  <div className="list-row-title">
                    {module.position}. {module.title}
                  </div>
                  <div style={{ marginTop: '0.3rem' }}>
                    <ProgressBar value={module.completed} total={module.lessons} />
                  </div>
                </div>
                <div className="tiny faint nowrap">
                  {module.completed}/{module.lessons}
                </div>
              </div>
            ))}
          </div>
          <p className="tiny faint" style={{ marginTop: '0.75rem', marginBottom: 0 }}>
            Modules are in dependency order, not difficulty order — window functions come before
            schema design because the interview problems lean on them constantly.
          </p>
        </div>
      </Section>

      <Section title="Last fourteen days" note="including the days with nothing on them">
        <div className="card">
          <Sparkline days={p.recentActivity} />
        </div>
      </Section>

      <Section title="Due for revision">
        {due.loading ? (
          <Loading lines={2} />
        ) : due.data && due.data.length > 0 ? (
          <div className="card">
            <div className="list">
              {due.data.map((item) => (
                <div className="list-row" key={`${item.kind}-${item.id}`}>
                  <span className="badge">{item.kind}</span>
                  <div className="list-row-main">
                    <div className="list-row-title">{item.title}</div>
                    <div className="list-row-sub">
                      {item.daysOverdue > 0
                        ? `${item.daysOverdue} day${item.daysOverdue === 1 ? '' : 's'} overdue`
                        : 'due today'}{' '}
                      · {item.reviews} review{item.reviews === 1 ? '' : 's'}
                      {item.lapses > 0 && ` · ${item.lapses} lapse${item.lapses === 1 ? '' : 's'}`}
                    </div>
                  </div>
                </div>
              ))}
            </div>
            <p style={{ marginBottom: 0, marginTop: '0.75rem' }}>
              <Link className="button btn-small btn-primary" to="/revision">
                Start reviewing
              </Link>
            </p>
          </div>
        ) : (
          <EmptyState title="Nothing due">
            Items enter the revision queue when you mark a lesson complete or solve a problem, and
            come back on a schedule that adapts to how well you remembered them.
          </EmptyState>
        )}
      </Section>

      {stats.data && (
        <Section title="What is in here">
          <div className="card">
            <dl className="kv">
              <dt>Modules</dt>
              <dd>{stats.data.modules}</dd>
              <dt>Lessons</dt>
              <dd>
                {stats.data.lessons} · {formatMinutes(p.minutesTotal)} of reading
              </dd>
              <dt>Problems</dt>
              <dd>
                {stats.data.problems} ·{' '}
                {Object.entries(stats.data.problemsByDifficulty)
                  .map(([k, v]) => `${v} ${k.toLowerCase()}`)
                  .join(', ')}
              </dd>
              <dt>Quiz questions</dt>
              <dd>{stats.data.quizQuestions}</dd>
              <dt>Content version</dt>
              <dd className="mono tiny" title={stats.data.contentChecksum}>
                {stats.data.contentChecksum.slice(0, 12)}
              </dd>
            </dl>
          </div>
        </Section>
      )}
    </div>
  )
}

/**
 * Fourteen bars, one per day.
 *
 * The empty days are bars too, drawn in the border colour. A sparkline that
 * omitted them would compress a quiet week into a busy-looking one, which is
 * the same mistake as a GROUP BY that cannot produce a row for a month with
 * no sales — and the backend generates those days with generate_series for
 * exactly this reason.
 */
function Sparkline({ days }: { days: ActivityDay[] }) {
  const totals = days.map((d) => d.problems + d.quiz + d.lessons)
  const max = Math.max(1, ...totals)

  return (
    <div>
      <div className="spark" role="img" aria-label="Activity over the last fourteen days">
        {days.map((day, i) => {
          const total = totals[i] ?? 0
          return (
            <div
              key={day.day}
              className={`spark-bar${total === 0 ? ' empty' : ''}`}
              style={{ height: `${total === 0 ? 2 : Math.max(8, (total / max) * 100)}%` }}
              title={`${day.day}: ${day.lessons} lesson${day.lessons === 1 ? '' : 's'}, ${
                day.problems
              } problem attempt${day.problems === 1 ? '' : 's'}, ${day.quiz} quiz answer${
                day.quiz === 1 ? '' : 's'
              }`}
            />
          )
        })}
      </div>
      <div className="spread tiny faint" style={{ marginTop: '0.3rem' }}>
        <span>{days[0]?.day}</span>
        <span>{totals.reduce((a, b) => a + b, 0)} activities</span>
        <span>today</span>
      </div>
    </div>
  )
}
