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
 * Both are handled below, and both are the actual bugs this pattern has.
 */

export interface AsyncState<T> {
  data: T | null
  error: Error | null
  loading: boolean
  /** Re-runs the fetch. Useful after a mutation. */
  reload: () => void
}

export function useApi<T>(
  fetcher: () => Promise<T>,
  deps: readonly unknown[] = [],
): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)

  // Incremented per request; a response whose id is not the latest is
  // discarded. This is what stops a slow first request overwriting a fast
  // second one — the out-of-order bug that looks like random stale data.
  const latest = useRef(0)

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fetcher, deps)

  useEffect(() => {
    const id = ++latest.current
    let active = true
    setLoading(true)
    setError(null)

    run()
      .then((result) => {
        if (!active || id !== latest.current) return
        setData(result)
      })
      .catch((cause: unknown) => {
        if (!active || id !== latest.current) return
        setError(cause instanceof Error ? cause : new Error(String(cause)))
      })
      .finally(() => {
        if (!active || id !== latest.current) return
        setLoading(false)
      })

    return () => {
      active = false
    }
  }, [run, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { data, error, loading, reload }
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

  const run = useCallback(
    async (...args: TArgs): Promise<TResult | null> => {
      setRunning(true)
      setError(null)
      try {
        const value = await action(...args)
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
    },
    [action],
  )

  const reset = useCallback(() => {
    setResult(null)
    setError(null)
  }, [])

  return { run, result, error, running, reset }
}
