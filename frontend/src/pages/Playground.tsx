import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError, api } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import { SqlEditor, toCompletionSchema } from '../components/SqlEditor'
import { ResultTable } from '../components/ResultTable'
import { ErrorBox, Loading, QueryErrorBox } from '../components/common'
import type { QueryResult, TableInfo } from '../api/types'

const STARTER = `-- Every query here runs read-only, against a disposable dataset.
-- Cmd/Ctrl-Enter runs it.

select d.dept_name,
       count(*)                as headcount,
       round(avg(e.salary), 0) as avg_salary
from employees e
join departments d on d.dept_id = e.dept_id
where e.is_active
group by d.dept_name
order by avg_salary desc`

const HISTORY_KEY = 'sqlmastery.history'
const HISTORY_LIMIT = 20

export function Playground() {
  const [params, setParams] = useSearchParams()
  const [sql, setSql] = useState(() => params.get('sql') ?? STARTER)
  const [schema, setSchema] = useState(() => params.get('schema') ?? 'learning')
  const [history, setHistory] = useState<string[]>(loadHistory)
  const [result, setResult] = useState<QueryResult | null>(null)
  const [lastRun, setLastRun] = useState('')

  const limits = useApi(() => api.limits(), [])
  const tables = useApi(() => api.schema(schema), [schema])
  const run = useMutation((statement: string, target: string) => api.run(statement, target))
  const reset = useMutation(() => api.resetDataset())

  // A query arriving in the URL — from a lesson's "open in playground" — is
  // taken once and then cleared, so a later edit is not undone by a reload.
  useEffect(() => {
    if (params.has('sql')) {
      setParams({}, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const completion = useMemo(
    () => (tables.data ? toCompletionSchema(tables.data) : undefined),
    [tables.data],
  )

  async function execute() {
    const statement = sql.trim()
    if (!statement) return
    setLastRun(statement)
    const outcome = await run.run(statement, schema)
    if (outcome) {
      setResult(outcome)
      setHistory((previous) => {
        const next = [statement, ...previous.filter((h) => h !== statement)].slice(0, HISTORY_LIMIT)
        saveHistory(next)
        return next
      })
    } else {
      setResult(null)
    }
  }

  return (
    <div className="main wide" style={{ padding: 0 }}>
      <div className="spread" style={{ marginBottom: '0.75rem' }}>
        <div>
          <h1 style={{ marginBottom: '0.1rem' }}>SQL playground</h1>
          <p className="muted small" style={{ margin: 0 }}>
            Read-only. Anything that writes, locks rows or names a schema outside the learning
            data is refused before it reaches the database.
          </p>
        </div>
        <div className="button-row">
          <label className="tiny faint" htmlFor="schema-select">
            Dataset
          </label>
          <select
            id="schema-select"
            value={schema}
            onChange={(e) => setSchema(e.target.value)}
            aria-label="Dataset"
          >
            <option value="learning">learning</option>
            <option value="learning_alt">learning_alt (ties, NULLs, empty tables)</option>
          </select>
        </div>
      </div>

      <div className="playground">
        <aside className="card schema-browser">
          <div className="card-title">Tables</div>
          {tables.loading && <Loading lines={6} label="Loading schema" />}
          {tables.error && <ErrorBox error={tables.error} onRetry={tables.reload} />}
          {tables.data?.map((table) => <SchemaTable key={table.name} table={table} onPick={setSql} />)}

          <hr style={{ border: 'none', borderTop: '1px solid var(--border)', margin: '0.75rem 0' }} />

          <button
            className="btn-small"
            disabled={reset.running}
            onClick={async () => {
              await reset.run()
              tables.reload()
            }}
            title="Truncates and repopulates both learning schemas from the committed seed file"
          >
            {reset.running ? 'Resetting…' : 'Reset dataset'}
          </button>
          {reset.result && (
            <p className="tiny faint" style={{ marginTop: '0.4rem' }}>
              Restored in {reset.result.durationMs} ms.
            </p>
          )}
          {reset.error && (
            <p className="tiny" style={{ color: 'var(--bad)' }}>
              {reset.error.message}
            </p>
          )}
        </aside>

        <div>
          <SqlEditor
            value={sql}
            onChange={setSql}
            onRun={execute}
            schema={completion}
            minHeight="14rem"
          />

          <div className="button-row" style={{ marginTop: '0.6rem' }}>
            <button className="btn-primary" onClick={execute} disabled={run.running}>
              {run.running ? 'Running…' : 'Run'}
            </button>
            <span className="tiny faint">Cmd/Ctrl-Enter</span>
            <button className="btn-ghost btn-small" onClick={() => setSql('')}>
              Clear
            </button>
            {limits.data && (
              <span className="tiny faint" style={{ marginLeft: 'auto' }}>
                max {limits.data.maxRows} rows · {limits.data.statementTimeoutMs} ms timeout ·{' '}
                {limits.data.maxSqlLength} chars
              </span>
            )}
          </div>

          {run.error instanceof ApiError && run.error.detail && (
            <QueryErrorBox error={run.error.detail} sql={lastRun} />
          )}
          {run.error && !(run.error instanceof ApiError && run.error.detail) && (
            <div className="notice notice-bad" role="alert">
              {run.error.message}
            </div>
          )}

          {result && (
            <div style={{ marginTop: '0.75rem' }}>
              <ResultTable result={result} />
            </div>
          )}

          {history.length > 0 && (
            <details className="disclosure" style={{ marginTop: '1rem' }}>
              <summary>Recent queries ({history.length})</summary>
              <div className="list">
                {history.map((item, i) => (
                  <div className="list-row" key={i}>
                    <code
                      className="list-row-main tiny"
                      style={{
                        background: 'none',
                        border: 'none',
                        padding: 0,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {item.replace(/\s+/g, ' ').slice(0, 110)}
                    </code>
                    <button className="btn-small btn-ghost" onClick={() => setSql(item)}>
                      Load
                    </button>
                  </div>
                ))}
              </div>
              <button
                className="btn-small btn-ghost"
                style={{ marginTop: '0.5rem' }}
                onClick={() => {
                  setHistory([])
                  saveHistory([])
                }}
              >
                Clear history
              </button>
            </details>
          )}
        </div>
      </div>
    </div>
  )
}

function SchemaTable({ table, onPick }: { table: TableInfo; onPick: (sql: string) => void }) {
  return (
    <details className="schema-table">
      <summary>
        {table.name} <span className="faint">({table.rowCount})</span>
      </summary>
      <ul className="schema-cols">
        {table.columns.map((column) => (
          <li
            key={column.name}
            className={`${column.primaryKey ? 'pk' : ''} ${column.nullable ? '' : 'nn'}`.trim()}
            title={[
              column.type,
              column.primaryKey ? 'primary key' : null,
              column.nullable ? 'nullable' : 'not null',
              column.foreignKey ? `references ${column.foreignKey}` : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          >
            {column.name} <span className="faint">{column.type}</span>
          </li>
        ))}
      </ul>
      <button
        className="btn-small btn-ghost"
        onClick={() => onPick(`select *\nfrom ${table.name}\nlimit 20`)}
      >
        Select from it
      </button>
    </details>
  )
}

function loadHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

function saveHistory(history: string[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history))
  } catch {
    /* ignore — history is a convenience, not state the app depends on */
  }
}
