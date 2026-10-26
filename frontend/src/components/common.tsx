import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { ReactNode } from 'react'
import type { QueryError } from '../api/types'
import { ApiError } from '../api/client'

/** Lesson prose is authored in Markdown, with GFM tables. */
export function Markdown({ children }: { children: string }) {
  return (
    <div className="prose">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </div>
  )
}

export function Badge({ children, kind }: { children: ReactNode; kind?: string }) {
  const suffix = kind ? ` badge-${kind.toLowerCase()}` : ''
  return <span className={`badge${suffix}`}>{children}</span>
}

export function Loading({ lines = 3, label = 'Loading' }: { lines?: number; label?: string }) {
  return (
    <div className="stack" role="status" aria-live="polite" aria-label={label}>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: `${100 - i * 12}%` }} />
      ))}
    </div>
  )
}

/**
 * Renders a failed request.
 *
 * A network failure and a 404 need different words — "the backend is not
 * running" and "that page does not exist" are both actionable and neither is
 * served by a generic "something went wrong".
 */
export function ErrorBox({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const title =
    status === 0
      ? 'Cannot reach the server'
      : status === 404
        ? 'Not found'
        : 'Something went wrong'

  return (
    <div className="notice notice-bad" role="alert">
      <strong>{title}</strong>
      <p>{error.message}</p>
      {status === 0 && (
        <p className="small">
          Start it with <code>cd backend &amp;&amp; ./mvnw spring-boot:run</code>, or check that
          the database is running with <code>bash scripts/db.sh status</code>.
        </p>
      )}
      {onRetry && (
        <p>
          <button className="btn-small" onClick={onRetry}>
            Try again
          </button>
        </p>
      )}
    </div>
  )
}

/**
 * Renders a query that PostgreSQL or the validator rejected.
 *
 * The caret under the offending character is the part that earns its keep —
 * it is the difference between "syntax error at or near" and seeing where.
 */
export function QueryErrorBox({ error, sql }: { error: QueryError; sql?: string }) {
  const caret = error.position && sql ? caretLine(sql, error.position) : null

  return (
    <div className="notice notice-bad" role="alert">
      <strong>
        <span className="mono">{error.code}</span> — {error.message}
      </strong>
      {caret && (
        <pre className="error-detail error-caret">
          {caret.line}
          {'\n'}
          {caret.marker}
        </pre>
      )}
      {error.hint && (
        <p className="small">
          <strong style={{ display: 'inline' }}>Hint: </strong>
          {error.hint}
        </p>
      )}
      {error.explanation && <p className="small">{error.explanation}</p>}
    </div>
  )
}

/** Finds the line containing a 1-based character offset and points at it. */
function caretLine(sql: string, position: number): { line: string; marker: string } | null {
  const index = Math.min(Math.max(position - 1, 0), sql.length)
  const start = sql.lastIndexOf('\n', index - 1) + 1
  const endIndex = sql.indexOf('\n', index)
  const end = endIndex === -1 ? sql.length : endIndex
  const line = sql.slice(start, end)
  if (!line.trim()) return null
  return { line, marker: `${' '.repeat(Math.max(index - start, 0))}^` }
}

export function ProgressBar({ value, total }: { value: number; total: number }) {
  const pct = total > 0 ? Math.round((value / total) * 100) : 0
  return (
    <div
      className="progress-track"
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={`${value} of ${total}`}
    >
      <div className={`progress-fill${pct === 100 ? ' done' : ''}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

export function Stat({
  value,
  label,
  sub,
}: {
  value: ReactNode
  label: string
  sub?: ReactNode
}) {
  return (
    <div className="card stat">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  )
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="card">
      <p className="card-title">{title}</p>
      {children && <div className="small muted">{children}</div>}
    </div>
  )
}

export function Section({
  title,
  note,
  children,
}: {
  title: string
  note?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="section">
      <div className="section-head">
        <h2>{title}</h2>
        {note && <span className="section-note">{note}</span>}
      </div>
      {children}
    </section>
  )
}

/** A link to a lesson or problem by id, used by the `related` lists. */
export function ContentLink({ id, label }: { id: string; label?: string }) {
  // Problem ids start with a p-number prefix; lesson ids do not. Deriving the
  // route from the id avoids a lookup for what is only a link.
  const isProblem = /^p\d{2}-/.test(id)
  const to = isProblem ? `/problems/${id}` : `/lessons/${id}`
  return <Link to={to}>{label ?? id}</Link>
}

export function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}
