import { useState } from 'react'
import { api } from '../api/client'
import { useApi, useMutation } from '../api/useApi'
import { Badge, EmptyState, ErrorBox, Loading } from '../components/common'
import type { QuizMarking } from '../api/types'

export function Quiz() {
  const [topic, setTopic] = useState('')
  const [count, setCount] = useState(10)
  const [answers, setAnswers] = useState<Record<string, number>>({})
  const [marking, setMarking] = useState<QuizMarking | null>(null)
  const [round, setRound] = useState(0)

  const topics = useApi(() => api.topics(), [])
  const questions = useApi(
    () => api.quizQuestions({ topic: topic || undefined, count }),
    [topic, count, round],
  )
  const submit = useMutation(() => api.submitQuiz(answers))

  const answered = Object.keys(answers).length

  function startOver() {
    setAnswers({})
    setMarking(null)
    setRound((n) => n + 1)
  }

  if (questions.error) return <ErrorBox error={questions.error} onRetry={questions.reload} />

  return (
    <div className="container">
      <h1>Quiz</h1>
      <p className="muted">
        The theory questions that have a right answer and no query to run. Marked on the server —
        the answer key is never sent to the browser.
      </p>

      <div className="card" style={{ marginTop: '1rem' }}>
        <div className="row">
          <label className="tiny faint" htmlFor="quiz-topic">
            Topic
          </label>
          <select
            id="quiz-topic"
            value={topic}
            onChange={(e) => {
              setTopic(e.target.value)
              startOver()
            }}
          >
            <option value="">All topics</option>
            {topics.data?.quizTopics.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>

          <label className="tiny faint" htmlFor="quiz-count">
            Questions
          </label>
          <select
            id="quiz-count"
            value={count}
            onChange={(e) => {
              setCount(Number(e.target.value))
              startOver()
            }}
          >
            {[5, 10, 20].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>

          <button className="btn-small btn-ghost" onClick={startOver} style={{ marginLeft: 'auto' }}>
            Start over
          </button>
        </div>
        <p className="tiny faint" style={{ margin: '0.5rem 0 0' }}>
          The same request returns the same paper, so &ldquo;I got question 3 wrong&rdquo; still
          means something after a refresh.
        </p>
      </div>

      {questions.loading && <Loading lines={6} label="Loading questions" />}

      {questions.data && questions.data.length === 0 && (
        <EmptyState title="No questions for that topic" />
      )}

      {marking && (
        <div
          className={`notice notice-${marking.correct === marking.total ? 'ok' : marking.correct * 2 >= marking.total ? 'warn' : 'bad'}`}
          role="status"
          style={{ marginTop: '1rem' }}
        >
          <strong>
            {marking.correct} of {marking.total} correct
          </strong>
          <p>
            The explanations are below each question. Wrong answers are recorded against their
            topic, so the progress page can tell you what to study next.
          </p>
        </div>
      )}

      {questions.data?.map((question, index) => {
        const chosen = answers[question.id]
        const result = marking?.results.find((r) => r.id === question.id)

        return (
          <div className="card" key={question.id}>
            <div className="spread">
              <span className="tiny faint">
                {index + 1} · {question.topic}
              </span>
              <Badge kind={question.difficulty}>{question.difficulty}</Badge>
            </div>

            <p style={{ fontWeight: 550, margin: '0.4rem 0 0.6rem' }}>{question.question}</p>

            <div className="stack" style={{ gap: '0.3rem' }}>
              {question.options.map((option, i) => {
                const isChosen = chosen === i
                const isAnswer = result?.answerIndex === i
                const tone = !result
                  ? ''
                  : isAnswer
                    ? ' notice-ok'
                    : isChosen
                      ? ' notice-bad'
                      : ''
                return (
                  <label
                    key={i}
                    className={`notice${tone}`}
                    style={{
                      margin: 0,
                      cursor: marking ? 'default' : 'pointer',
                      display: 'flex',
                      gap: '0.5rem',
                      alignItems: 'flex-start',
                      background: tone ? undefined : isChosen ? 'var(--accent-soft)' : undefined,
                    }}
                  >
                    <input
                      type="radio"
                      name={question.id}
                      checked={isChosen ?? false}
                      disabled={Boolean(marking)}
                      onChange={() => setAnswers((a) => ({ ...a, [question.id]: i }))}
                    />
                    <span className="small">{option}</span>
                  </label>
                )
              })}
            </div>

            {result?.explanation && (
              <div className="notice notice-info" style={{ marginTop: '0.6rem' }}>
                <strong>{result.correct ? 'Correct' : 'Why the right answer is right'}</strong>
                <p>{result.explanation}</p>
              </div>
            )}
          </div>
        )
      })}

      {questions.data && questions.data.length > 0 && !marking && (
        <div className="card">
          <div className="spread">
            <span className="small muted">
              {answered} of {questions.data.length} answered
            </span>
            <button
              className="btn-primary"
              disabled={answered === 0 || submit.running}
              onClick={async () => {
                const outcome = await submit.run()
                if (outcome) setMarking(outcome)
              }}
            >
              {submit.running ? 'Marking…' : 'Submit'}
            </button>
          </div>
          {submit.error && <div className="notice notice-bad">{submit.error.message}</div>}
        </div>
      )}

      {marking && (
        <div className="card">
          <button className="btn-primary" onClick={startOver}>
            Another round
          </button>
        </div>
      )}
    </div>
  )
}
