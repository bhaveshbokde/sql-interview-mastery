import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useMutation } from '../api/useApi'
import type { QueryResult } from '../api/types'
import { ResultTable } from './ResultTable'
import { QueryErrorBox } from './common'
import { ApiError } from '../api/client'

/**
 * A SQL snippet with its recorded output, and a button to run it for real.
 *
 * The recorded result is shown by default. That is the output the query
 * produced when the content was last verified against the database, which is
 * what makes it trustworthy — nobody typed it.
 *
 * Running it live then does something quietly valuable: if the two disagree,
 * the dataset has drifted from what the lesson was written against, and the
 * learner can see that rather than being misled by it. The comparison is
 * shown rather than hidden.
 */
export function RunnableSql({
  sql,
  schema,
  recorded,
  openInPlayground = true,
}: {
  sql: string
  schema?: string | null
  recorded?: QueryResult | null
  openInPlayground?: boolean
}) {
  const [live, setLive] = useState<QueryResult | null>(null)
  const run = useMutation(() => api.run(sql, schema ?? undefined))

  const shown = live ?? recorded ?? null
  const drifted =
    live && recorded ? JSON.stringify(live.rows) !== JSON.stringify(recorded.rows) : false

  const playgroundHref = `/playground?sql=${encodeURIComponent(sql)}${
    schema ? `&schema=${encodeURIComponent(schema)}` : ''
  }`

  return (
    <div className="sql-block">
      <pre>
        <code>{sql.trim()}</code>
      </pre>

      <div className="button-row">
        <button
          className="btn-small btn-primary"
          disabled={run.running}
          onClick={async () => {
            const result = await run.run()
            if (result) setLive(result)
          }}
        >
          {run.running ? 'Running…' : 'Run it'}
        </button>

        {openInPlayground && (
          <Link className="button btn-small" to={playgroundHref}>
            Open in playground
          </Link>
        )}

        {schema && schema !== 'learning' && (
          <span className="badge" title="This example runs against the alternate dataset">
            {schema}
          </span>
        )}

        {live && (
          <button className="btn-small btn-ghost" onClick={() => setLive(null)}>
            Show recorded output
          </button>
        )}
      </div>

      {run.error instanceof ApiError && run.error.detail && (
        <QueryErrorBox error={run.error.detail} sql={sql} />
      )}
      {run.error && !(run.error instanceof ApiError && run.error.detail) && (
        <div className="notice notice-bad">{run.error.message}</div>
      )}

      {drifted && (
        <div className="notice notice-warn">
          <strong>This differs from the recorded output</strong>
          <p>
            The result below is what the query returns right now; the recorded one is what it
            returned when the lesson was last verified. The dataset has been changed — use{' '}
            <strong>Reset dataset</strong> on the playground page to restore it.
          </p>
        </div>
      )}

      {shown && (
        <>
          {!live && recorded && (
            <p className="tiny faint" style={{ margin: '0.3rem 0 0.2rem' }}>
              Recorded output — what this query actually returned when the content was verified.
            </p>
          )}
          <ResultTable result={shown} maxRows={live ? undefined : 12} />
        </>
      )}
    </div>
  )
}
