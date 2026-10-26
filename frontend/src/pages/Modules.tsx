import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useApi } from '../api/useApi'
import { Badge, ErrorBox, Loading, ProgressBar } from '../components/common'
import { formatMinutes } from '../components/format'
import type { LessonProgress, LessonStatus } from '../api/types'

export function Modules() {
  const modules = useApi(() => api.modules(), [])
  const progress = useApi(() => api.lessonProgress(), [])

  if (modules.error) return <ErrorBox error={modules.error} onRetry={modules.reload} />
  if (modules.loading || !modules.data) return <Loading lines={8} label="Loading curriculum" />

  const byLesson = new Map<string, LessonProgress>()
  for (const row of progress.data ?? []) byLesson.set(row.id, row)

  const totalLessons = modules.data.reduce((n, m) => n + m.lessonCount, 0)
  const totalMinutes = modules.data.reduce((n, m) => n + m.totalMinutes, 0)
  const completed = (progress.data ?? []).filter((l) => l.status === 'completed').length

  return (
    <div className="container">
      <h1>Curriculum</h1>
      <p className="muted">
        {modules.data.length} modules, {totalLessons} lessons, {formatMinutes(totalMinutes)} of
        reading. The order is a dependency order rather than a difficulty one.
      </p>

      {progress.data && (
        <div className="card" style={{ marginTop: '1rem' }}>
          <div className="spread">
            <span className="small">
              <strong>
                {completed} of {totalLessons}
              </strong>{' '}
              lessons complete
            </span>
            <span className="tiny faint">
              {totalLessons > 0 ? Math.round((completed / totalLessons) * 100) : 0}%
            </span>
          </div>
          <div style={{ marginTop: '0.4rem' }}>
            <ProgressBar value={completed} total={totalLessons} />
          </div>
        </div>
      )}

      {modules.data.map((module) => {
        const done = module.lessons.filter(
          (l) => byLesson.get(l.id)?.status === 'completed',
        ).length

        return (
          <section className="section" key={module.id}>
            <div className="section-head">
              <h2>
                {module.position}. {module.title}
              </h2>
              <span className="section-note">
                {module.lessonCount} lessons · {formatMinutes(module.totalMinutes)}
                {done > 0 && ` · ${done} complete`}
              </span>
            </div>

            <p className="muted small" style={{ marginTop: '-0.4rem' }}>
              {module.summary}
            </p>

            <div className="card">
              <div className="list">
                {module.lessons.map((lesson) => {
                  const status: LessonStatus = byLesson.get(lesson.id)?.status ?? 'not_started'
                  return (
                    <div className="list-row" key={lesson.id}>
                      <span
                        className={`status-dot ${status}`}
                        title={status.replace('_', ' ')}
                        aria-label={status.replace('_', ' ')}
                      />
                      <div className="list-row-main">
                        <div className="list-row-title">
                          <Link to={`/lessons/${lesson.slug}`}>{lesson.title}</Link>
                        </div>
                        <div className="list-row-sub">{lesson.summary}</div>
                      </div>
                      <Badge kind={lesson.difficulty}>{lesson.difficulty}</Badge>
                      <span className="tiny faint nowrap">{lesson.estimatedMinutes} min</span>
                    </div>
                  )
                })}
              </div>
            </div>
          </section>
        )
      })}
    </div>
  )
}
