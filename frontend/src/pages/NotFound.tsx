import { Link } from 'react-router-dom'

export function NotFound() {
  return (
    <div className="container">
      <h1>Not here</h1>
      <p className="muted">That page does not exist.</p>
      <p>
        <Link to="/">Dashboard</Link> · <Link to="/modules">Curriculum</Link> ·{' '}
        <Link to="/problems">Problems</Link> · <Link to="/playground">Playground</Link>
      </p>
    </div>
  )
}
