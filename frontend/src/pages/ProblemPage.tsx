import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import { SqlEditor } from '../components/SqlEditor'
import { toCompletionSchema } from '../components/format'
import { ResultTable } from '../components/ResultTable'
import { RunnableSql } from '../components/RunnableSql'
import {
  Badge,
  ContentLink,
  ErrorBox,
  Loading,
  Markdown,
  QueryErrorBox,
  Section,
} from '../components/common'
import { formatDate } from '../components/format'
import type { Evaluation, Hint, Solution } from '../api/types'

export function ProblemPage() {
  const { idOrSlug = '' } = useParams()

  const problem = useApi(() => api.problem(idOrSlug), [idOrSlug])
  const hintCount = useApi(() => api.hintCount(idOrSlug), [idOrSlug])
  const tables = useApi(() => api.schema('learning'), [])
  const attempts = useApi(() => api.attempts(idOrSlug), [idOrSlug])

  const [sql, setSql] = useState('')
  const [hints, setHints] = useState<Hint[]>([])
  const [solution, setSolution] = useState<Solution | null>(null)
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null)

  const evaluate = useMutation((statement: string, used: number) =>
    api.evaluate(idOrSlug, statement, used),
  )
  const revealHint = useMutation((ordinal: number) => api.hint(idOrSlug, ordinal))
  const revealSolution = useMutation(() => api.solution(idOrSlug))

  const completion = useMemo(
    () => (tables.data ? toCompletionSchema(tables.data) : undefined),
    [tables.data],
  )

  if (problem.error) return <ErrorBox error={problem.error} onRetry={problem.reload} />
  if (problem.loading || !problem.data) return <Loading lines={8} label="Loading problem" />

  const p = problem.data
  const totalHints = hintCount.data?.total ?? 0

  async function submit() {
    if (!sql.trim()) return
    const outcome = await evaluate.run(sql, hints.length)
    setEvaluation(outcome)
    attempts.reload()
  }

  return (
    <div className="container">
      <p className="tiny faint">
        <Link to="/problems">Interview problems</Link> / {p.topic}
      </p>

      <h1>{p.title}</h1>
      <div className="row" style={{ marginBottom: '0.75rem' }}>
        <Badge kind={p.difficulty}>{p.difficulty}</Badge>
        <span className="tag">{p.topic}</span>
        {p.orderSensitive && <Badge kind="warn">row order is part of the answer</Badge>}
        {p.source && (
          <a className="tiny" href={p.source.url} target="_blank" rel="noreferrer noopener">
            {p.source.name}
          </a>
        )}
      </div>

      <div className="card">
        <Markdown>{p.statement}</Markdown>
        <dl className="kv" style={{ marginTop: '0.75rem' }}>
          <dt>Tables</dt>
          <dd className="mono tiny">{p.tables.join(', ')}</dd>
          <dt>Columns required</dt>
          <dd className="mono tiny">{p.expectedColumns.join(', ')}</dd>
        </dl>
      </div>

      <Section title="Things to watch for" note="before you write anything">
        <ul className="small">
          {p.edgeCases.map((edge, i) => (
            <li key={i}>{edge}</li>
          ))}
        </ul>
      </Section>

      <Section title="Your answer">
        <SqlEditor
          value={sql}
          onChange={setSql}
          onRun={submit}
          schema={completion}
          minHeight="12rem"
          ariaLabel="Your SQL answer"
        />

        <div className="button-row" style={{ marginTop: '0.6rem' }}>
          <button className="btn-primary" onClick={submit} disabled={evaluate.running}>
            {evaluate.running ? 'Checking…' : 'Check my answer'}
          </button>
          <Link
            className="button btn-small"
            to={`/playground?sql=${encodeURIComponent(sql || `-- ${p.title}\n\n`)}`}
          >
            Open in playground
          </Link>
          {hints.length < totalHints && (
            <button
              className="btn-small btn-ghost"
              disabled={revealHint.running}
              onClick={async () => {
                const hint = await revealHint.run(hints.length + 1)
                if (hint) setHints((previous) => [...previous, hint])
              }}
            >
              {hints.length === 0 ? `Show a hint (${totalHints})` : `Next hint (${hints.length + 1}/${totalHints})`}
            </button>
          )}
        </div>

        {hints.map((hint) => (
          <div className="notice notice-info" key={hint.ordinal}>
            <strong>
              Hint {hint.ordinal} of {hint.total}
            </strong>
            <p>{hint.hint}</p>
          </div>
        ))}

        {evaluate.error && <div className="notice notice-bad">{evaluate.error.message}</div>}
        {evaluation && <Verdict evaluation={evaluation} sql={sql} />}
      </Section>

      {attempts.data && attempts.data.length > 0 && (
        <Section title="Your attempts" note={`${attempts.data.length} recorded`}>
          <div className="card">
            <div className="list">
              {attempts.data.map((attempt) => (
                <div className="list-row" key={attempt.id}>
                  <Badge kind={attempt.correct ? 'ok' : 'bad'}>{attempt.verdict}</Badge>
                  <div className="list-row-main">
                    <code
                      className="tiny"
                      style={{
                        background: 'none',
                        border: 'none',
                        padding: 0,
                        display: 'block',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {attempt.sql.replace(/\s+/g, ' ').slice(0, 110)}
                    </code>
                    <div className="list-row-sub">
                      {formatDate(attempt.attemptedAt)}
                      {attempt.hintsUsed > 0 &&
                        ` · ${attempt.hintsUsed} hint${attempt.hintsUsed === 1 ? '' : 's'}`}
                    </div>
                  </div>
                  <button className="btn-small btn-ghost" onClick={() => setSql(attempt.sql)}>
                    Load
                  </button>
                </div>
              ))}
            </div>
          </div>
        </Section>
      )}

      <Section title="Solution">
        {!solution ? (
          <div className="card">
            <p className="small muted">
              The reference solution, a line-by-line reading, a walkthrough over the real rows,
              alternative approaches with whether each returns an identical result, the answers
              that look right and are not, and the follow-up questions an interviewer asks next.
            </p>
            <button
              disabled={revealSolution.running}
              onClick={async () => {
                const revealed = await revealSolution.run()
                if (revealed) setSolution(revealed)
              }}
            >
              {revealSolution.running ? 'Loading…' : 'Reveal the solution'}
            </button>
          </div>
        ) : (
          <SolutionView solution={solution} />
        )}
      </Section>
    </div>
  )
}

function Verdict({ evaluation, sql }: { evaluation: Evaluation; sql: string }) {
  const tone =
    evaluation.verdict === 'CORRECT'
      ? 'ok'
      : evaluation.verdict === 'CORRECT_ON_SAMPLE_ONLY'
        ? 'warn'
        : 'bad'

  const headline: Record<string, string> = {
    CORRECT: 'Correct on both datasets',
    CORRECT_ON_SAMPLE_ONLY: 'Right on the sample data, wrong on the alternate dataset',
    INCORRECT: 'Not correct yet',
    DID_NOT_RUN: 'The query did not run',
    REFUSED: 'The playground refused this statement',
  }

  return (
    <div style={{ marginTop: '0.75rem' }}>
      <div className={`notice notice-${tone}`} role="status">
        <strong>{headline[evaluation.verdict] ?? evaluation.verdict}</strong>
        {evaluation.verdict === 'CORRECT_ON_SAMPLE_ONLY' && (
          <p>
            This is the most useful verdict in the whole checker. Your query matches on{' '}
            <code>learning</code> and not on <code>learning_alt</code>, which holds the ties,
            NULLs and empty tables chosen to break answers that happen to fit the sample.
          </p>
        )}
        {evaluation.datasets.map((dataset) => (
          <p key={dataset.schema} className="small">
            <code>{dataset.schema}</code>: {dataset.matches ? 'matches' : dataset.comparison.code}
            {dataset.comparison.notes.length > 0 && (
              <ul style={{ margin: '0.2rem 0 0' }}>
                {dataset.comparison.notes.map((note, i) => (
                  <li key={i}>{note}</li>
                ))}
              </ul>
            )}
          </p>
        ))}
      </div>

      {evaluation.error && <QueryErrorBox error={evaluation.error} sql={sql} />}

      {evaluation.result && (
        <>
          <p className="tiny faint" style={{ marginBottom: '0.2rem' }}>
            What your query returned:
          </p>
          <ResultTable result={evaluation.result} maxRows={15} />
        </>
      )}

      <p className="tiny faint" style={{ marginTop: '0.6rem' }}>
        {evaluation.limits}
      </p>
    </div>
  )
}

function SolutionView({ solution }: { solution: Solution }) {
  const e = solution.explanation
  return (
    <>
      <div className="card">
        <RunnableSql sql={solution.sql} recorded={e.expected} />
      </div>

      <h3>Line by line</h3>
      <div className="card">
        <dl className="kv">
          {e.lineByLine.map((line, i) => (
            <div key={i} style={{ display: 'contents' }}>
              <dt className="mono tiny" style={{ whiteSpace: 'pre-wrap' }}>
                {line.clause}
              </dt>
              <dd className="small">{line.note}</dd>
            </div>
          ))}
        </dl>
      </div>

      <h3>Walkthrough</h3>
      <Markdown>{e.walkthrough}</Markdown>

      {e.alternatives && e.alternatives.length > 0 && (
        <>
          <h3>Other ways to solve it</h3>
          {e.alternatives.map((alternative, i) => (
            <div className="card" key={i}>
              <div className="spread">
                <strong>{alternative.title}</strong>
                <Badge kind={alternative.equivalent ? 'ok' : 'warn'}>
                  {alternative.equivalent ? 'same result' : 'different result'}
                </Badge>
              </div>
              <p className="small muted">{alternative.when}</p>
              <RunnableSql
                sql={alternative.sql}
                recorded={alternative.result}
                openInPlayground={false}
              />
            </div>
          ))}
        </>
      )}

      <h3>Performance</h3>
      <Markdown>{e.performance}</Markdown>

      <h3>Answers that look right and are not</h3>
      {e.wrongApproaches.map((wrong, i) => (
        <div className="card" key={i}>
          <strong style={{ color: 'var(--bad)' }}>{wrong.title}</strong>
          <pre>
            <code>{wrong.sql.trim()}</code>
          </pre>
          <p className="small">{wrong.whyWrong}</p>
          {wrong.result && (
            <details className="disclosure">
              <summary>What it actually returns</summary>
              <ResultTable result={wrong.result} maxRows={10} />
            </details>
          )}
        </div>
      ))}

      <h3>Extra checks</h3>
      {e.extraTests.map((test, i) => (
        <div className="card" key={i}>
          <div className="spread">
            <strong>{test.name}</strong>
            <span className="badge">{test.schema}</span>
          </div>
          <p className="small muted">{test.note}</p>
          <RunnableSql
            sql={test.sql}
            schema={test.schema}
            recorded={test.result}
            openInPlayground={false}
          />
        </div>
      ))}

      <h3>What the interviewer asks next</h3>
      {e.followUps.map((qa, i) => (
        <details className="disclosure" key={i}>
          <summary>{qa.question}</summary>
          <div className="small">{qa.answer}</div>
        </details>
      ))}

      <h3>Related</h3>
      <p className="small">
        {e.related.map((id, i) => (
          <span key={id}>
            {i > 0 && ' · '}
            <ContentLink id={id} />
          </span>
        ))}
      </p>
    </>
  )
}
