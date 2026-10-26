import CodeMirror from '@uiw/react-codemirror'
import { PostgreSQL, sql } from '@codemirror/lang-sql'
import { EditorView, keymap } from '@codemirror/view'
import { Prec } from '@codemirror/state'
import { useMemo } from 'react'

/**
 * A real SQL editor, not a textarea.
 *
 * CodeMirror with the PostgreSQL dialect gives keyword and string
 * highlighting, bracket matching and a schema-aware completion list. That
 * last one matters more than it sounds: a learner who does not yet know the
 * column names spends their time guessing rather than learning SQL, and
 * completion turns the dataset into something explorable.
 *
 * Cmd/Ctrl-Enter runs the query. It is wrapped in `Prec.highest` so it beats
 * CodeMirror's own default binding for the same chord.
 */
export function SqlEditor({
  value,
  onChange,
  onRun,
  schema,
  minHeight = '10rem',
  readOnly = false,
  ariaLabel = 'SQL editor',
}: {
  value: string
  onChange: (value: string) => void
  onRun?: () => void
  /** Table name to column names, for completion. */
  schema?: Record<string, string[]>
  minHeight?: string
  readOnly?: boolean
  ariaLabel?: string
}) {
  const extensions = useMemo(() => {
    const language = sql({
      dialect: PostgreSQL,
      schema,
      upperCaseKeywords: false,
    })

    const runKeymap = Prec.highest(
      keymap.of([
        {
          key: 'Mod-Enter',
          preventDefault: true,
          run: () => {
            onRun?.()
            // Returning true stops CodeMirror inserting a newline as well,
            // which is what happens if the handler only has a side effect.
            return true
          },
        },
      ]),
    )

    return [language, runKeymap, EditorView.lineWrapping]
  }, [schema, onRun])

  return (
    <div className="editor">
      <CodeMirror
        value={value}
        onChange={onChange}
        extensions={extensions}
        editable={!readOnly}
        minHeight={minHeight}
        basicSetup={{
          lineNumbers: true,
          foldGutter: false,
          highlightActiveLine: !readOnly,
          highlightActiveLineGutter: !readOnly,
          autocompletion: !readOnly,
          // The editor holds one statement, so a search panel and a history
          // of undo across statements are clutter rather than features.
          searchKeymap: false,
        }}
        aria-label={ariaLabel}
      />
    </div>
  )
}

/**
 * Turns the dataset description into the shape CodeMirror's completion
 * expects.
 */
export function toCompletionSchema(
  tables: { name: string; columns: { name: string }[] }[],
): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const table of tables) {
    out[table.name] = table.columns.map((c) => c.name)
  }
  return out
}
