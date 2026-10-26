import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import { Badge, EmptyState, ErrorBox, Loading } from '../components/common'
import type { RevisionItem } from '../api/types'

/**
 * The spaced-revision queue.
 *
 * Grading is the whole interaction, so the five buttons carry what each
 * grade means rather than a number: an honest grade is the only input the
 * schedule has, and "3" tells a learner nothing about whether to press it.
 */
export function Revision() {
  const due = useApi(() => api.dueRevisions(50), [])
  const review = useMutation((item: RevisionItem, quality: number) =>
    api.review(item.kind, item.id, quality),
  )

  if (due.error) return <ErrorBox error={due.error} onRetry={due.reload} />
  if (due.loading) return <Loading lines={5} label="Loading revision queue" />

  const items = due.data ?? []

  return (
    <div className="container">
      <h1>Revision</h1>
      <p className="muted">
        Items come back on a schedule that adapts to how well you remembered them. A grade below
        3 resets the interval to tomorrow and slows the item&apos;s future growth, so something
        you keep forgetting keeps coming back.
      </p>

      {items.length === 0 ? (
        <EmptyState title="Nothing due today">
          <p>
            Lessons enter the queue when you mark them complete, and problems when you first
            solve one. Come back tomorrow, or{' '}
            <Link to="/modules">carry on with the curriculum</Link>.
          </p>
        </EmptyState>
      ) : (
        <>
          <p className="small">
            <strong>{items.length}</strong> due
            {items.some((i) => i.daysOverdue > 0) &&
              ` · ${items.filter((i) => i.daysOverdue > 0).length} overdue`}
          </p>

          {items.map((item) => (
            <div className="card" key={`${item.kind}-${item.id}`}>
              <div className="spread">
                <div>
                  <div className="row">
                    <Badge>{item.kind}</Badge>
                    <strong>
                      {item.slug ? (
                        <Link
                          to={item.kind === 'problem' ? `/problems/${item.slug}` : `/lessons/${item.slug}`}
                        >
                          {item.title}
                        </Link>
                      ) : (
                        item.title
                      )}
                    </strong>
                  </div>
                  <div className="tiny faint" style={{ marginTop: '0.2rem' }}>
                    {item.daysOverdue > 0
                      ? `${item.daysOverdue} day${item.daysOverdue === 1 ? '' : 's'} overdue`
                      : 'due today'}{' '}
                    · interval {item.intervalDays}d · ease {item.ease.toFixed(2)} ·{' '}
                    {item.reviews} review{item.reviews === 1 ? '' : 's'}
                    {item.lapses > 0 && ` · ${item.lapses} lapse${item.lapses === 1 ? '' : 's'}`}
                  </div>
                </div>
              </div>

              <div className="button-row" style={{ marginTop: '0.6rem' }}>
                <span className="tiny faint">How well did you recall it?</span>
                {GRADES.map((grade) => (
                  <button
                    key={grade.value}
                    className={`btn-small${grade.value >= 4 ? ' btn-primary' : ''}`}
                    disabled={review.running}
                    title={grade.help}
                    onClick={async () => {
                      await review.run(item, grade.value)
                      due.reload()
                    }}
                  >
                    {grade.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </>
      )}

      {review.result && (
        <div className="notice notice-ok" role="status">
          Next review in {review.result.nextIntervalDays} day
          {review.result.nextIntervalDays === 1 ? '' : 's'} · ease{' '}
          {review.result.ease.toFixed(2)}
          {review.result.lapses > 0 && ` · ${review.result.lapses} lapse(s) so far`}
        </div>
      )}
      {review.error && <div className="notice notice-bad">{review.error.message}</div>}
    </div>
  )
}

/**
 * SM-2 grades 0 to 5. Only four are offered: 0 and 1 are the same decision
 * in practice ("I did not know it"), and offering six buttons makes the
 * choice slower without making the schedule better.
 */
const GRADES = [
  { value: 1, label: 'No idea', help: 'Resets the interval and lowers the ease sharply' },
  { value: 3, label: 'Scraped it', help: 'A pass — the interval grows, but slowly' },
  { value: 4, label: 'Knew it', help: 'A clean pass; the ease is unchanged' },
  { value: 5, label: 'Instant', help: 'Raises the ease so it comes back less often' },
] as const
