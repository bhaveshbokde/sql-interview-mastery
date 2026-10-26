import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { Dashboard } from './pages/Dashboard'
import { Modules } from './pages/Modules'
import { LessonPage } from './pages/LessonPage'
import { Playground } from './pages/Playground'
import { Problems } from './pages/Problems'
import { ProblemPage } from './pages/ProblemPage'
import { Quiz } from './pages/Quiz'
import { Revision } from './pages/Revision'
import { SearchPage } from './pages/SearchPage'
import { ProgressPage } from './pages/ProgressPage'
import { NotFound } from './pages/NotFound'
import { useApi } from './api/useApi'
import { api } from './api/client'

export function App() {
  // One request for the badge counts in the sidebar. It fails quietly: a
  // missing count is not worth an error state on every page.
  const overview = useApi(() => api.progress(), [])
  const due = overview.data?.revisionsDue ?? 0

  return (
    <div className="app">
      <nav className="sidebar" aria-label="Main">
        <div className="brand">
          SQL Interview Mastery
          <small>fundamentals to advanced PostgreSQL</small>
        </div>

        <div>
          <div className="nav-group-label">Learn</div>
          <div className="nav">
            <Item to="/" label="Dashboard" end />
            <Item to="/modules" label="Curriculum" />
            <Item to="/problems" label="Interview problems" />
            <Item to="/quiz" label="Quiz" />
            <Item to="/revision" label="Revision" badge={due > 0 ? String(due) : undefined} />
          </div>
        </div>

        <div>
          <div className="nav-group-label">Practise</div>
          <div className="nav">
            <Item to="/playground" label="SQL playground" />
            <Item to="/search" label="Search" />
            <Item to="/progress" label="My progress" />
          </div>
        </div>

        {/* Desktop only — see .sidebar-note. On a phone the sidebar becomes a
            nav strip and this sentence would push the page content down. */}
        <div style={{ marginTop: 'auto' }} className="tiny faint sidebar-note">
          <p style={{ margin: '0 0.6rem' }}>
            Read-only playground. Every example&apos;s output was recorded by running it.
          </p>
        </div>
      </nav>

      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/modules" element={<Modules />} />
          <Route path="/lessons/:idOrSlug" element={<LessonPage />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/problems/:idOrSlug" element={<ProblemPage />} />
          <Route path="/playground" element={<Playground />} />
          <Route path="/quiz" element={<Quiz />} />
          <Route path="/revision" element={<Revision />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/progress" element={<ProgressPage />} />
          <Route path="/lessons" element={<Navigate to="/modules" replace />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </div>
  )
}

function Item({
  to,
  label,
  badge,
  end,
}: {
  to: string
  label: string
  badge?: string
  end?: boolean
}) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => (isActive ? 'active' : '')}>
      <span>{label}</span>
      {badge && <span className="badge badge-warn">{badge}</span>}
    </NavLink>
  )
}
