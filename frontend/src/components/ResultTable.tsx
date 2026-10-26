import type { CellValue, QueryResult } from '../api/types'

/**
 * Renders a query result.
 *
 * The one requirement that is not obvious: a NULL must be distinguishable
 * from an empty string by looking at it. Telling those two apart is most of
 * what the three-valued-logic lesson is about, and a grid that renders both
 * as a blank cell quietly undermines the lesson it is illustrating. So NULL
 * is drawn as a dimmed italic `NULL` and an empty string as nothing at all.
 */
export function ResultTable({
  result,
  maxRows,
}: {
  result: QueryResult
  /** Render at most this many rows, with a note. For inline previews. */
  maxRows?: number
}) {
  const shown = maxRows ? result.rows.slice(0, maxRows) : result.rows
  const hiddenByPreview = result.rows.length - shown.length

  if (result.columns.length === 0) {
    return <p className="muted small">The query returned no columns.</p>
  }

  return (
    <div>
      <div className="table-scroll">
        <table className="result">
          <thead>
            <tr>
              {result.columns.map((column, i) => (
                <th key={`${column.name}-${i}`} scope="col">
                  {column.name}
                  {/*
                    The explicit space matters. JSX strips the newline between
                    these two children, so without it the accessible name of
                    the header is "nint8" rather than "n int8" — the gap is
                    drawn by a CSS margin and exists only visually. A screen
                    reader, and anything matching on the accessible name,
                    sees the run-together version.
                  */}{' '}
                  <span className="col-type">{column.typeName}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr>
                <td colSpan={result.columns.length} className="muted">
                  No rows. That is a result, not an error — an empty result and a NULL are
                  different things.
                </td>
              </tr>
            ) : (
              shown.map((row, r) => (
                <tr key={r}>
                  {result.columns.map((column, c) => (
                    <Cell key={c} value={row[c] ?? null} alignment={column.alignment} />
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="result-meta">
        <span>
          {result.rowCount} row{result.rowCount === 1 ? '' : 's'}
        </span>
        {result.durationMs > 0 && <span>{result.durationMs} ms</span>}
        <span className="faint">{result.schema}</span>
        {hiddenByPreview > 0 && <span className="faint">{hiddenByPreview} more not shown here</span>}
        {result.truncated && (
          <span className="badge badge-warn">
            truncated at {result.rowLimit} — there were more rows
          </span>
        )}
      </div>
    </div>
  )
}

function Cell({ value, alignment }: { value: CellValue; alignment: string }) {
  if (value === null) {
    return (
      <td className="cell-null" title="SQL NULL — not an empty string, not zero">
        NULL
      </td>
    )
  }
  if (typeof value === 'boolean') {
    return <td className="cell-bool">{value ? 'true' : 'false'}</td>
  }
  if (Array.isArray(value)) {
    return <td title={JSON.stringify(value)}>{`{${value.map(format).join(', ')}}`}</td>
  }
  const text = String(value)
  return (
    <td className={alignment === 'numeric' ? 'cell-numeric' : undefined} title={text}>
      {text}
    </td>
  )
}

function format(value: CellValue): string {
  if (value === null) return 'NULL'
  if (Array.isArray(value)) return `{${value.map(format).join(', ')}}`
  return String(value)
}
