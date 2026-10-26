/**
 * Display helpers.
 *
 * Kept out of `common.tsx` so that file exports only components. A module
 * that mixes components with plain functions breaks Fast Refresh — editing
 * the helper forces a full reload instead of a hot update, which is a small
 * irritation that compounds over a day of UI work.
 */

export function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/**
 * Turns the dataset description into the shape CodeMirror's completion
 * expects: table name to column names.
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

/**
 * Whether an id names a problem rather than a lesson.
 *
 * Problem ids carry a `pNN-` prefix and lesson ids do not, so a `related`
 * list can be turned into links without a lookup for what is only a link.
 */
export function isProblemId(id: string): boolean {
  return /^p\d{2}-/.test(id)
}
