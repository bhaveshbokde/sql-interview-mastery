import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * A small data-fetching hook.
 *
 * Deliberately not TanStack Query. This application makes a handful of
 * requests per page against a local server, and the things a query library
 * buys — background refetching, cache invalidation across components,
 * optimistic updates — are not needed here. What is needed is that a request
 * which resolves after its component unmounts does not set state, and that a
 * stale response from a superseded request does not overwrite a newer one.
 * Both are the actual bugs this pattern has, and both are handled below.
 *
 * `loading` is **derived**, not stored. The obvious implementation calls
 * `setLoading(true)` at the top of the effect, which costs an extra render on
 * every fetch and is what React's `set-state-in-effect` lint rule objects to.
 * Instead the resolved state carries the key it was fetched for, and anything
 * whose key does not match the current one is loading by definition. One
 * state update per fetch instead of three, and no synchronous setState in an
 * effect body.
 */

export interface AsyncState<T> {
  data: T | null
  error: Error | null
  loading: boolean
  /** Re-runs the fetch. Useful after a mutation. */
  reload: () => void
}

/** What a settled request produced, and what it was a request for. */
interface Settled<T> {
  key: string
  data: T | null
  error: Error | null
}

/**
 * A stable string for a dependency list.
 *
 * Computed during render rather than in a `useMemo`, because a hook with a
 * variable-length dependency array is exactly what the hooks lint rules
 * cannot check — and this is cheap enough that memoising it would be the
 * more expensive of the two.
 */
function dependencyKey(deps: readonly unknown[]): string {
  // Joined with an escaped NUL, written as the escape rather than as a raw
  // character. A literal control byte in a source file is invisible in every
  // editor and in every diff, which makes it the kind of thing that costs an
  // afternoon when it eventually matters.
  return deps.map(keyPart).join('\u0000')
}

/**
 * One dependency, as a string.
 *
 * Objects are serialised rather than stringified. `String(someObject)` is
 * '[object Object]' for almost everything, so two different objects would
 * produce the same key and the hook would not refetch when it should — a bug
 * that looks like a page showing another page's data.
 */
function keyPart(dep: unknown): string {
  if (dep === null || dep === undefined) return String(dep)
  switch (typeof dep) {
    case 'string':
      return dep
    case 'number':
    case 'boolean':
    case 'bigint':
    case 'symbol':
      return String(dep)
    default:
      return JSON.stringify(dep) ?? ''
  }
}

export function useApi<T>(
  fetcher: () => Promise<T>,
  deps: readonly unknown[] = [],
): AsyncState<T> {
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const [nonce, setNonce] = useState(0)

  // The key identifies "which request should be in flight right now".
  // Including the nonce means reload() changes it, which re-runs the effect
  // without any other mechanism.
  const key = `${dependencyKey(deps)}${nonce}`

  // The fetcher is a fresh closure on every render, so depending on it
  // directly would refetch forever. It is kept in a ref, synchronised by an
  // effect declared *before* the fetching one — effects run in declaration
  // order, so the ref holds the current closure by the time the fetch runs.
  const fetcherRef = useRef(fetcher)
  useEffect(() => {
    fetcherRef.current = fetcher
  })

  useEffect(() => {
    let active = true

    fetcherRef
      .current()
      .then((data) => {
        // The cleanup below clears `active` when the key changes or the
        // component unmounts, so a superseded or late response is dropped
        // rather than overwriting a newer one.
        if (active) setSettled({ key, data, error: null })
      })
      .catch((cause: unknown) => {
        if (active) {
          setSettled({
            key,
            data: null,
            error: cause instanceof Error ? cause : new Error(String(cause)),
          })
        }
      })

    return () => {
      active = false
    }
  }, [key])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  const current = settled?.key === key ? settled : null

  return {
    data: current?.data ?? null,
    error: current?.error ?? null,
    loading: current === null,
    reload,
  }
}

/**
 * The mutation counterpart: a callable that tracks its own in-flight and
 * error state, so a button can disable itself and show what went wrong
 * without every page reimplementing it.
 */
export interface Mutation<TArgs extends unknown[], TResult> {
  run: (...args: TArgs) => Promise<TResult | null>
  result: TResult | null
  error: Error | null
  running: boolean
  reset: () => void
}

export function useMutation<TArgs extends unknown[], TResult>(
  action: (...args: TArgs) => Promise<TResult>,
): Mutation<TArgs, TResult> {
  const [result, setResult] = useState<TResult | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [running, setRunning] = useState(false)

  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  // Same reasoning as above: the action is a new closure each render, so it
  // is read through a ref rather than captured, which keeps `run` stable and
  // therefore safe to pass to a child or list in a dependency array.
  const actionRef = useRef(action)
  useEffect(() => {
    actionRef.current = action
  })

  const run = useCallback(async (...args: TArgs): Promise<TResult | null> => {
    setRunning(true)
    setError(null)
    try {
      const value = await actionRef.current(...args)
      if (mounted.current) setResult(value)
      return value
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof Error ? cause : new Error(String(cause)))
        setResult(null)
      }
      return null
    } finally {
      if (mounted.current) setRunning(false)
    }
  }, [])

  const reset = useCallback(() => {
    setResult(null)
    setError(null)
  }, [])

  return { run, result, error, running, reset }
}
