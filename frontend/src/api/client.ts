/* Типобезопасные обёртки над fetch для JSON-эндпоинтов /api.
   Не-2xx превращается в ApiError со статусом и телом ответа. */

export class ApiError extends Error {
  readonly status: number;
  readonly bodyText: string;

  constructor(status: number, bodyText: string) {
    super(`HTTP ${status}: ${bodyText}`);
    this.name = 'ApiError';
    this.status = status;
    this.bodyText = bodyText;
  }
}

async function parseBody<T>(res: Response): Promise<T> {
  const text = await res.text();
  if (!res.ok) throw new ApiError(res.status, text);
  return JSON.parse(text) as T;
}

/** GET /api/<path> — база '/api', path без префикса ('models', 'sessions'). */
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`/api/${path}`, { signal });
  return parseBody<T>(res);
}

/** POST /api/<path> с JSON-телом; body === undefined — запрос без тела. */
export async function postJson<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const res = await fetch(`/api/${path}`, {
    method: 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  return parseBody<T>(res);
}

/** DELETE /api/<path>; 204 обрабатывается как пустое тело. */
export async function deleteJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`/api/${path}`, { method: 'DELETE', signal });
  return parseBody<T>(res);
}
