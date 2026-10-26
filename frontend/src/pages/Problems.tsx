import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useApi } from '../api/useApi'
import { Badge, EmptyState, ErrorBox, Loading } from '../components/common'
import type { ProblemProgress } from '../api/types'

export function Problems() {
  const [topic, setTopic] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [query, setQuery] = useState('')

  const topics = useApi(() => api.topics(), [])
  const problems = useApi(
    () => api.problems({ topic, difficulty, q: query || undefined }),
    [topic, difficulty, query],
  )
  const progress = useApi(() => api.problemProgress(), [])

  const byId = new Map<string, ProblemProgress>()
  for (const row of progress.data ?? []) byId.set(row.id, row)

  const solved = (progress.data ?? []).filter((p) => p.solved).length

  return (
    <div className="container">
      <h1>Interview problems</h1>
      <p className="muted">
        Each one has a statement, the tables involved, the columns the answer must produce,
        progressive hints, and a verified solution with alternatives and the approaches that look
        right and are not.
        {progress.data && ` ${solved} of ${progress.data.length} solved.`}
      </p>

      <div className="card" style={{ marginTop: '1rem' }}>
        <div className="row">
          <label className="tiny faint" htmlFor="topic">
            Topic
          </label>
          <select id="topic" value={topic} onChange={(e) => setTopic(e.target.value)}>
            <option value="">All</option>
            {topics.data?.problemTopics.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>

          <label className="tiny faint" htmlFor="difficulty">
            Difficulty
          </label>
          <select
            id="difficulty"
            value={difficulty}
            onChange={(e) => setDifficulty(e.target.value)}
          >
            <option value="">All</option>
            <option value="Easy">Easy</option>
            <option value="Medium">Medium</option>
            <option value="Hard">Hard</option>
          </select>

          <input
            type="search"
            placeholder="Search problems…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search problems"
            style={{ marginLeft: 'auto', minWidth: '12rem' }}
          />
        </div>
      </div>

      {problems.error && <ErrorBox error={problems.error} onRetry={problems.reload} />}
      {problems.loading && <Loading lines={6} label="Loading problems" />}

      {problems.data && problems.data.length === 0 && (
        <EmptyState title="No problems match those filters">
          Clear the search box or widen the topic.
        </EmptyState>
      )}

      {problems.data && problems.data.length > 0 && (
        <div className="card" style={{ marginTop: '0.85rem' }}>
          <div className="list">
            {problems.data.map((problem) => {
              const mine = byId.get(problem.id)
              return (
                <div className="list-row" key={problem.id}>
                  <span
                    className={`status-dot ${mine?.solved ? 'completed' : mine?.attempts ? 'in_progress' : ''}`}
                    title={
                      mine?.solved
                        ? 'Solved'
                        : mine?.attempts
                          ? `${mine.attempts} attempt${mine.attempts === 1 ? '' : 's'}, not solved`
                          : 'Not attempted'
                    }
                  />
                  <div className="list-row-main">
                    <div className="list-row-title">
                      <Link to={`/problems/${problem.slug}`}>{problem.title}</Link>
                    </div>
                    <div className="list-row-sub">
                      {problem.topic}
                      {problem.orderSensitive && ' · row order matters'}
                      {problem.source && ` · ${problem.source.name}`}
                      {mine && mine.attempts > 0 && (
                        <>
                          {' · '}
                          {mine.attempts} attempt{mine.attempts === 1 ? '' : 's'}
                        </>
                      )}
                    </div>
                  </div>
                  <Badge kind={problem.difficulty}>{problem.difficulty}</Badge>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
