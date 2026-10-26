import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import { useApi } from '../api/useApi'
import { Badge, EmptyState, ErrorBox, Loading } from '../components/common'

export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const [input, setInput] = useState(params.get('q') ?? '')
  const [query, setQuery] = useState(params.get('q') ?? '')

  // Debounced, so typing does not fire a request per keystroke against a
  // full-text index.
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input)
      setParams(input ? { q: input } : {}, { replace: true })
    }, 250)
    return () => clearTimeout(timer)
  }, [input, setParams])

  const results = useApi(() => (query ? api.search(query, 40) : Promise.resolve([])), [query])

  return (
    <div className="container">
      <h1>Search</h1>
      <p className="muted">
        Full-text across every lesson and problem, ranked. Backed by a PostgreSQL tsvector
        column with a GIN index — the same machinery the full-text lesson describes.
      </p>

      <div className="card" style={{ marginTop: '1rem' }}>
        <input
          type="search"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="window functions, null, deadlock, index…"
          aria-label="Search the curriculum"
          style={{ width: '100%' }}
          autoFocus
        />
        <p className="tiny faint" style={{ margin: '0.4rem 0 0' }}>
          Quoted phrases and <code>or</code> work the way a search box should —{' '}
          <code>websearch_to_tsquery</code> parses it, so a stray apostrophe is not an error.
        </p>
      </div>

      {results.error && <ErrorBox error={results.error} onRetry={results.reload} />}
      {query && results.loading && <Loading lines={4} label="Searching" />}

      {query && results.data && results.data.length === 0 && !results.loading && (
        <EmptyState title={`Nothing matches “${query}”`}>
          Search matches whole words with stemming, so a partial word will not match. Try a
          shorter term.
        </EmptyState>
      )}

      {results.data && results.data.length > 0 && (
        <div className="card" style={{ marginTop: '0.85rem' }}>
          <p className="tiny faint" style={{ marginTop: 0 }}>
            {results.data.length} result{results.data.length === 1 ? '' : 's'}
          </p>
          <div className="list">
            {results.data.map((hit) => (
              <div className="list-row" key={`${hit.kind}-${hit.id}`}>
                <Badge>{hit.kind}</Badge>
                <div className="list-row-main">
                  <div className="list-row-title">
                    <Link
                      to={hit.kind === 'problem' ? `/problems/${hit.slug}` : `/lessons/${hit.slug}`}
                    >
                      {hit.title}
                    </Link>
                  </div>
                  <div className="list-row-sub">{hit.blurb}</div>
                </div>
                <Badge kind={hit.difficulty}>{hit.difficulty}</Badge>
                <span className="tiny faint nowrap" title="ts_rank">
                  {hit.rank.toFixed(3)}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
