import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import { RunnableSql } from '../components/RunnableSql'
import {
  Badge,
  ContentLink,
  ErrorBox,
  Loading,
  Markdown,
  Section,
} from '../components/common'
import type { Exercise } from '../api/types'

/**
 * One lesson, with all fourteen of its parts.
 *
 * The ordering is the one the content model fixes — objectives, concept,
 * syntax, examples, edge cases, pitfalls, performance, interview questions,
 * exercises, checklist, related. A lesson is complete or it does not load, so
 * there is no "if this section exists" branching here except for performance,
 * which is genuinely optional.
 */
export function LessonPage() {
  const { idOrSlug = '' } = useParams()
  const lesson = useApi(() => api.lesson(idOrSlug), [idOrSlug])
  const progress = useApi(() => api.lessonProgress(), [idOrSlug])
  const setStatus = useMutation((completed: boolean, confidence?: number) =>
    api.setLessonStatus(idOrSlug, completed, confidence),
  )

  // Recording the view is fire-and-forget. If it fails the lesson still
  // renders, which is the right priority — progress tracking is a
  // convenience and the lesson is the product.
  useEffect(() => {
    if (!idOrSlug) return
    void api.viewLesson(idOrSlug).catch(() => undefined)
  }, [idOrSlug])

  if (lesson.error) return <ErrorBox error={lesson.error} onRetry={lesson.reload} />
  if (lesson.loading || !lesson.data) return <Loading lines={10} label="Loading lesson" />

  const l = lesson.data
  const mine = progress.data?.find((p) => p.id === l.id)
  const completed = mine?.status === 'completed'

  return (
    <div className="container">
      <p className="tiny faint">
        <Link to="/modules">Curriculum</Link> / {l.moduleId}
      </p>

      <h1>{l.title}</h1>
      <div className="row" style={{ marginBottom: '0.5rem' }}>
        <Badge kind={l.difficulty}>{l.difficulty}</Badge>
        <span className="tiny faint">{l.estimatedMinutes} min</span>
        <div className="tags">
          {l.tags.map((tag) => (
            <span className="tag" key={tag}>
              {tag}
            </span>
          ))}
        </div>
      </div>
      <p className="muted">{l.summary}</p>

      {l.prerequisites.length > 0 && (
        <p className="small">
          <span className="faint">Read first: </span>
          {l.prerequisites.map((id, i) => (
            <span key={id}>
              {i > 0 && ', '}
              <ContentLink id={id} />
            </span>
          ))}
        </p>
      )}

      <Section title="What you will be able to do">
        <ul>
          {l.objectives.map((objective, i) => (
            <li key={i}>{objective}</li>
          ))}
        </ul>
      </Section>

      <Section title="The idea">
        <Markdown>{l.concept}</Markdown>
      </Section>

      <Section title="Syntax">
        <div className="card">
          {l.syntax.map((form, i) => (
            <div key={i} style={{ marginBottom: i === l.syntax.length - 1 ? 0 : '0.8rem' }}>
              <pre style={{ margin: '0 0 0.3rem' }}>
                <code>{form.form}</code>
              </pre>
              <p className="small muted" style={{ margin: 0 }}>
                {form.notes}
              </p>
            </div>
          ))}
        </div>
      </Section>

      <Section
        title="Examples"
        note={`runs against ${l.sampleTables.join(', ')}`}
      >
        {l.examples.map((example, i) => (
          <div className="card" key={i}>
            <h3 style={{ marginTop: 0 }}>{example.title}</h3>
            <RunnableSql sql={example.sql} schema={example.schema} recorded={example.result} />
            <div style={{ marginTop: '0.75rem' }}>
              <Markdown>{example.walkthrough}</Markdown>
            </div>
          </div>
        ))}
      </Section>

      <Section title="Edge cases" note="the boundaries, shown rather than described">
        {l.edgeCases.map((edge, i) => (
          <div className="card" key={i}>
            <h3 style={{ marginTop: 0 }}>{edge.title}</h3>
            <p className="small">{edge.body}</p>
            <RunnableSql sql={edge.sql} schema={edge.schema} recorded={edge.result} />
          </div>
        ))}
      </Section>

      <Section title="Common mistakes">
        {l.pitfalls.map((pitfall, i) => (
          <div className="pitfall" key={i}>
            <div className="pitfall-mistake">{pitfall.mistake}</div>
            <dl>
              <dt>Why it happens</dt>
              <dd className="small">{pitfall.why}</dd>
              <dt>What to do instead</dt>
              <dd className="small">{pitfall.fix}</dd>
            </dl>
          </div>
        ))}
      </Section>

      {l.performance && (
        <Section title="Performance">
          <Markdown>{l.performance}</Markdown>
        </Section>
      )}

      <Section title="Interview questions" note="with model answers">
        {l.interviewQuestions.map((qa, i) => (
          <details className="disclosure" key={i}>
            <summary>{qa.question}</summary>
            <div className="small">{qa.answer}</div>
          </details>
        ))}
      </Section>

      <Section title="Practice" note="try it before revealing the answer">
        {l.exercises.map((exercise, i) => (
          <ExerciseCard key={i} exercise={exercise} index={i + 1} />
        ))}
      </Section>

      <Section title="Revision checklist">
        <div className="card">
          <ul className="checklist">
            {l.checklist.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        </div>
      </Section>

      <Section title="Portability">
        <div className="card">
          <dl className="kv">
            <dt>Standard SQL</dt>
            <dd className="small">{l.dialect.standard}</dd>
            <dt>PostgreSQL</dt>
            <dd className="small">{l.dialect.postgres}</dd>
            <dt>MySQL</dt>
            <dd className="small">{l.dialect.mysql}</dd>
          </dl>
        </div>
      </Section>

      <Section title="Next">
        <p className="small">
          {l.related.map((id, i) => (
            <span key={id}>
              {i > 0 && ' · '}
              <ContentLink id={id} />
            </span>
          ))}
        </p>
      </Section>

      <div className="card" style={{ marginTop: '2rem' }}>
        <div className="spread">
          <div>
            <div className="card-title" style={{ marginBottom: 0 }}>
              {completed ? 'Marked complete' : 'Finished this lesson?'}
            </div>
            <div className="tiny faint">
              {completed
                ? `Completed${mine?.revisionDueOn ? ` · next review ${mine.revisionDueOn}` : ''}`
                : 'Completing it adds the lesson to your revision queue.'}
            </div>
          </div>
          {completed ? (
            <button
              className="btn-small"
              disabled={setStatus.running}
              onClick={async () => {
                await setStatus.run(false)
                progress.reload()
              }}
            >
              Reopen
            </button>
          ) : (
            <ConfidenceButtons
              disabled={setStatus.running}
              onPick={async (confidence) => {
                await setStatus.run(true, confidence)
                progress.reload()
              }}
            />
          )}
        </div>
        {setStatus.error && <div className="notice notice-bad">{setStatus.error.message}</div>}
      </div>
    </div>
  )
}

/**
 * Confidence is not decoration: it lowers the item's starting ease, so a
 * lesson you were shaky on comes back through the schedule faster than one
 * you were sure of.
 */
function ConfidenceButtons({
  onPick,
  disabled,
}: {
  onPick: (confidence: number) => void
  disabled: boolean
}) {
  const labels: Record<number, string> = {
    1: 'Barely followed it',
    2: 'Shaky',
    3: 'Mostly got it',
    4: 'Comfortable',
    5: 'Could teach it',
  }
  return (
    <div className="button-row">
      <span className="tiny faint">How well did it land?</span>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          className="btn-small"
          disabled={disabled}
          title={labels[n]}
          onClick={() => onPick(n)}
        >
          {n}
        </button>
      ))}
    </div>
  )
}

function ExerciseCard({ exercise, index }: { exercise: Exercise; index: number }) {
  const [showHint, setShowHint] = useState(false)

  return (
    <div className="card">
      <div className="spread">
        <h3 style={{ margin: 0 }}>Exercise {index}</h3>
        <Badge kind={exercise.difficulty}>{exercise.difficulty}</Badge>
      </div>
      <p style={{ marginBottom: '0.5rem' }}>{exercise.prompt}</p>

      <div className="button-row">
        <Link
          className="button btn-small btn-primary"
          to={`/playground?sql=${encodeURIComponent('-- ' + exercise.prompt.replace(/\s+/g, ' ') + '\n\n')}`}
        >
          Try it in the playground
        </Link>
        {!showHint && (
          <button className="btn-small btn-ghost" onClick={() => setShowHint(true)}>
            Show hint
          </button>
        )}
      </div>

      {showHint && (
        <div className="notice notice-info">
          <strong>Hint</strong>
          <p>{exercise.hint}</p>
        </div>
      )}

      <details className="disclosure">
        <summary>Reveal the answer</summary>
        <RunnableSql
          sql={exercise.solutionSql}
          recorded={exercise.result}
          openInPlayground={false}
        />
      </details>
    </div>
  )
}
