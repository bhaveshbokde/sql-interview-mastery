import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useApi, useMutation } from '../api/useApi'

/**
 * The hook's job is small and its two failure modes are not: a response that
 * arrives after its request was superseded must not overwrite a newer one,
 * and a response that arrives after unmount must not set state at all.
 *
 * `loading` being derived rather than stored is also pinned here, because
 * that is what the implementation trades an extra render for and a future
 * "simplification" back to `setLoading(true)` would silently undo it.
 */
describe('useApi', () => {
  it('starts loading, then resolves with data', async () => {
    const { result } = renderHook(() => useApi(() => Promise.resolve(42), []))

    expect(result.current.loading).toBe(true)
    expect(result.current.data).toBeNull()

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBe(42)
    expect(result.current.error).toBeNull()
  })

  it('captures a rejection as an Error', async () => {
    const { result } = renderHook(() =>
      useApi(() => Promise.reject(new Error('boom')), []),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBeInstanceOf(Error)
    expect(result.current.error?.message).toBe('boom')
    expect(result.current.data).toBeNull()
  })

  it('wraps a non-Error rejection rather than exposing it raw', async () => {
    const { result } = renderHook(() =>
      // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
      useApi(() => Promise.reject('just a string'), []),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBeInstanceOf(Error)
    expect(result.current.error?.message).toBe('just a string')
  })

  it('refetches when a dependency changes, and reports loading in between', async () => {
    const fetcher = vi.fn((id: number) => Promise.resolve(`value-${id}`))
    const { result, rerender } = renderHook(({ id }) => useApi(() => fetcher(id), [id]), {
      initialProps: { id: 1 },
    })

    await waitFor(() => expect(result.current.data).toBe('value-1'))

    rerender({ id: 2 })
    // Derived from the key mismatch, with no synchronous setState in the
    // effect — the property the implementation is built around.
    expect(result.current.loading).toBe(true)
    expect(result.current.data).toBeNull()

    await waitFor(() => expect(result.current.data).toBe('value-2'))
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not refetch when the dependencies are unchanged', async () => {
    const fetcher = vi.fn(() => Promise.resolve('x'))
    const { result, rerender } = renderHook(() => useApi(fetcher, ['stable']))
    await waitFor(() => expect(result.current.data).toBe('x'))

    rerender()
    rerender()
    // The fetcher is a new closure on every render; depending on it directly
    // would loop forever. This is the assertion that catches that.
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('discards a superseded response that resolves late', async () => {
    const resolvers: ((value: string) => void)[] = []
    const fetcher = (id: number) =>
      new Promise<string>((resolve) => {
        resolvers[id] = resolve
      })

    const { result, rerender } = renderHook(({ id }) => useApi(() => fetcher(id), [id]), {
      initialProps: { id: 0 },
    })

    rerender({ id: 1 })

    // The second request finishes first, then the first one lands.
    await act(async () => {
      resolvers[1]?.('second')
    })
    await waitFor(() => expect(result.current.data).toBe('second'))

    await act(async () => {
      resolvers[0]?.('first')
    })

    // The stale answer must not win. Without the cleanup flag this would
    // read 'first' — the out-of-order bug that looks like random stale data.
    expect(result.current.data).toBe('second')
  })

  it('reload() re-runs the fetch', async () => {
    const fetcher = vi.fn(() => Promise.resolve('v'))
    const { result } = renderHook(() => useApi(fetcher, []))
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => result.current.reload())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not warn about setting state after unmount', async () => {
    const warn = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    let resolve: (v: string) => void = () => undefined
    const { unmount } = renderHook(() =>
      useApi(() => new Promise<string>((r) => (resolve = r)), []),
    )

    unmount()
    await act(async () => {
      resolve('late')
    })

    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })
})

describe('useMutation', () => {
  it('tracks running, then result', async () => {
    const { result } = renderHook(() => useMutation((n: number) => Promise.resolve(n * 2)))

    expect(result.current.running).toBe(false)
    let returned: number | null = null
    await act(async () => {
      returned = await result.current.run(21)
    })

    expect(returned).toBe(42)
    expect(result.current.result).toBe(42)
    expect(result.current.running).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('captures an error and returns null rather than throwing', async () => {
    const { result } = renderHook(() =>
      useMutation(() => Promise.reject(new Error('nope'))),
    )

    let returned: unknown = 'unset'
    await act(async () => {
      returned = await result.current.run()
    })

    // Callers branch on the return value; a throw would mean every call site
    // needed its own try/catch.
    expect(returned).toBeNull()
    expect(result.current.error?.message).toBe('nope')
    expect(result.current.result).toBeNull()
  })

  it('keeps `run` stable across renders', async () => {
    const { result, rerender } = renderHook(() => useMutation(() => Promise.resolve(1)))
    const first = result.current.run
    rerender()
    // Stable, so it is safe to pass to a child or list as a dependency.
    expect(result.current.run).toBe(first)
  })

  it('reset() clears the result and the error', async () => {
    const { result } = renderHook(() => useMutation(() => Promise.resolve('v')))
    await act(async () => {
      await result.current.run()
    })
    expect(result.current.result).toBe('v')

    act(() => result.current.reset())
    expect(result.current.result).toBeNull()
    expect(result.current.error).toBeNull()
  })
})
