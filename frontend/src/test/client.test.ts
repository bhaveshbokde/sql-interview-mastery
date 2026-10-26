import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, getLearnerId, setLearnerId } from '../api/client'

/**
 * The client's job is to turn an HTTP response into either data or a typed
 * error. The interesting cases are all failures: a structured QueryError must
 * survive with its `code` intact, because the UI branches on it, and a
 * network failure must say something more useful than "Failed to fetch".
 */
describe('api client', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function respond(status: number, body: unknown) {
    fetchMock.mockResolvedValue(
      new Response(typeof body === 'string' ? body : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  }

  it('returns parsed JSON on success', async () => {
    respond(200, { modules: 9 })
    await expect(api.statistics()).resolves.toMatchObject({ modules: 9 })
  })

  it('sends the learner id on every request', async () => {
    setLearnerId('second-run')
    respond(200, [])
    await api.lessons()
    const headers = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Record<string, string>
    expect(headers['X-Learner-Id']).toBe('second-run')
  })

  it('defaults the learner id to local', () => {
    expect(getLearnerId()).toBe('local')
  })

  it('preserves the structured error detail so the UI can branch on the code', async () => {
    respond(400, {
      code: '42703',
      message: 'column "nope" does not exist',
      explanation: 'That column does not exist on any table in the FROM clause.',
      position: 8,
      hint: null,
    })

    await expect(api.run('select nope')).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError)
      const api_error = error as ApiError
      expect(api_error.status).toBe(400)
      expect(api_error.detail?.code).toBe('42703')
      expect(api_error.detail?.position).toBe(8)
      return true
    })
  })

  it('keeps the refusal reason for a rejected statement', async () => {
    respond(400, {
      code: 'NOT_A_SELECT',
      message: 'The playground runs read-only queries only.',
      explanation: null,
      position: null,
      hint: null,
    })
    const error = await api.run('delete from employees').catch((e: unknown) => e)
    expect((error as ApiError).detail?.code).toBe('NOT_A_SELECT')
  })

  it('explains a network failure in terms of the backend not running', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const error = await api.statistics().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(0)
    expect((error as ApiError).message).toMatch(/backend/i)
  })

  it('falls back to a status message when the body is not an error shape', async () => {
    respond(500, 'Internal Server Error')
    const error = await api.statistics().catch((e: unknown) => e)
    expect((error as ApiError).message).toMatch(/500/)
  })

  it('encodes a path segment so a slug cannot escape its position', async () => {
    respond(200, {})
    await api.lesson('a/b')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/curriculum/lessons/a%2Fb')
  })

  it('omits the schema from a query when it was not given', async () => {
    respond(200, {})
    await api.run('select 1')
    const body = JSON.parse((fetchMock.mock.calls[0]?.[1] as RequestInit).body as string)
    expect(body).toEqual({ sql: 'select 1' })
  })

  it('builds the problem filter query string from only the set filters', async () => {
    respond(200, [])
    await api.problems({ difficulty: 'Hard' })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/problems?difficulty=Hard')
  })
})
