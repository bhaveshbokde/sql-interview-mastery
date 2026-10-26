import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ResultTable } from '../components/ResultTable'
import type { QueryResult } from '../api/types'

function result(partial: Partial<QueryResult>): QueryResult {
  return {
    columns: [],
    rows: [],
    rowCount: 0,
    truncated: false,
    rowLimit: 500,
    durationMs: 0,
    schema: 'learning',
    ...partial,
  }
}

describe('ResultTable', () => {
  it('renders columns with their PostgreSQL type', () => {
    render(
      <ResultTable
        result={result({
          columns: [
            { name: 'emp_id', typeName: 'int4', alignment: 'numeric' },
            { name: 'name', typeName: 'text', alignment: 'text' },
          ],
          rows: [[1, 'Asha']],
          rowCount: 1,
        })}
      />,
    )
    expect(screen.getByRole('columnheader', { name: /emp_id/ })).toHaveTextContent('int4')
    expect(screen.getByRole('columnheader', { name: /name/ })).toHaveTextContent('text')
  })

  it('shows a NULL differently from an empty string', () => {
    // This is the assertion the three-valued-logic lesson depends on. A grid
    // that draws both as a blank cell quietly contradicts the lesson it is
    // illustrating.
    render(
      <ResultTable
        result={result({
          columns: [
            { name: 'a', typeName: 'text', alignment: 'text' },
            { name: 'b', typeName: 'text', alignment: 'text' },
          ],
          rows: [[null, '']],
          rowCount: 1,
        })}
      />,
    )
    const cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('NULL')
    expect(cells[0]).toHaveClass('cell-null')
    expect(cells[1]).toHaveTextContent('')
    expect(cells[1]).not.toHaveClass('cell-null')
  })

  it('right-aligns numeric columns', () => {
    render(
      <ResultTable
        result={result({
          columns: [
            { name: 'n', typeName: 'numeric', alignment: 'numeric' },
            { name: 's', typeName: 'text', alignment: 'text' },
          ],
          rows: [[42, 'x']],
          rowCount: 1,
        })}
      />,
    )
    const cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveClass('cell-numeric')
    expect(cells[1]).not.toHaveClass('cell-numeric')
  })

  it('renders booleans as true/false rather than as strings', () => {
    render(
      <ResultTable
        result={result({
          columns: [{ name: 'ok', typeName: 'bool', alignment: 'text' }],
          rows: [[true], [false]],
          rowCount: 2,
        })}
      />,
    )
    const cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('true')
    expect(cells[1]).toHaveTextContent('false')
  })

  it('renders an array in PostgreSQL brace notation', () => {
    render(
      <ResultTable
        result={result({
          columns: [{ name: 'tags', typeName: '_text', alignment: 'text' }],
          rows: [[['a', 'b', null]]],
          rowCount: 1,
        })}
      />,
    )
    expect(screen.getByRole('cell')).toHaveTextContent('{a, b, NULL}')
  })

  it('says that no rows is a result rather than an error', () => {
    render(
      <ResultTable
        result={result({ columns: [{ name: 'a', typeName: 'int4', alignment: 'numeric' }] })}
      />,
    )
    expect(screen.getByRole('cell')).toHaveTextContent(/No rows/)
  })

  it('reports truncation rather than hiding it', () => {
    // A silently truncated result is how someone concludes their aggregate
    // is wrong, so the warning has to be visible.
    render(
      <ResultTable
        result={result({
          columns: [{ name: 'a', typeName: 'int4', alignment: 'numeric' }],
          rows: [[1], [2]],
          rowCount: 2,
          truncated: true,
          rowLimit: 2,
        })}
      />,
    )
    expect(screen.getByText(/truncated at 2/)).toBeInTheDocument()
  })

  it('limits rows for a preview and says how many are hidden', () => {
    render(
      <ResultTable
        result={result({
          columns: [{ name: 'a', typeName: 'int4', alignment: 'numeric' }],
          rows: [[1], [2], [3], [4], [5]],
          rowCount: 5,
        })}
        maxRows={2}
      />,
    )
    const body = screen.getAllByRole('rowgroup')[1]
    expect(within(body!).getAllByRole('row')).toHaveLength(2)
    expect(screen.getByText(/3 more not shown/)).toBeInTheDocument()
  })

  it('shows the duration and the dataset', () => {
    render(
      <ResultTable
        result={result({
          columns: [{ name: 'a', typeName: 'int4', alignment: 'numeric' }],
          rows: [[1]],
          rowCount: 1,
          durationMs: 7,
          schema: 'learning_alt',
        })}
      />,
    )
    expect(screen.getByText('7 ms')).toBeInTheDocument()
    expect(screen.getByText('learning_alt')).toBeInTheDocument()
  })
})
