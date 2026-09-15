/* Типобезопасные обёртки над fetch для JSON-эндпоинтов /api.
   Не-2xx превращается в ApiError со статусом и телом ответа. */

import type {
  MemoryEntry,
  MemoryKind,
  MemoryListResponse,
  MemoryOverview,
  WorkingListResponse,
} from './types';

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

/** PUT /api/<path> с JSON-телом — зеркально postJson. */
export async function putJson<T>(
  path: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const res = await fetch(`/api/${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  return parseBody<T>(res);
}

/* ---------- Память: working-заметки и long-term записи ---------- */

/** GET /api/session/{id}/working — рабочий слой памяти сессии. */
export async function fetchWorking(
  sessionId: string,
  signal?: AbortSignal,
): Promise<WorkingListResponse> {
  return getJson<WorkingListResponse>(`session/${encodeURIComponent(sessionId)}/working`, signal);
}

/** PUT /api/session/{id}/working — создать или обновить заметку. */
export async function saveWorkingEntry(
  sessionId: string,
  input: { id?: string; title: string; content: string },
  signal?: AbortSignal,
): Promise<WorkingListResponse> {
  return putJson<WorkingListResponse>(
    `session/${encodeURIComponent(sessionId)}/working`,
    input,
    signal,
  );
}

/** DELETE /api/session/{id}/working/{entryId} — убрать одну заметку. */
export async function deleteWorkingEntry(
  sessionId: string,
  entryId: string,
  signal?: AbortSignal,
): Promise<WorkingListResponse> {
  return deleteJson<WorkingListResponse>(
    `session/${encodeURIComponent(sessionId)}/working/${encodeURIComponent(entryId)}`,
    signal,
  );
}

/** DELETE /api/session/{id}/working — очистить рабочий слой. */
export async function clearWorking(
  sessionId: string,
  signal?: AbortSignal,
): Promise<WorkingListResponse> {
  return deleteJson<WorkingListResponse>(
    `session/${encodeURIComponent(sessionId)}/working`,
    signal,
  );
}

/** GET /api/memory?kind=…&q=… — long-term записи; пустые параметры не передаются. */
export async function fetchMemory(
  params?: { kind?: MemoryKind; q?: string },
  signal?: AbortSignal,
): Promise<MemoryListResponse> {
  const parts: string[] = [];
  if (params?.kind) parts.push(`kind=${encodeURIComponent(params.kind)}`);
  if (params?.q) parts.push(`q=${encodeURIComponent(params.q)}`);
  const query = parts.length > 0 ? `?${parts.join('&')}` : '';
  return getJson<MemoryListResponse>(`memory${query}`, signal);
}

/** POST /api/memory — добавить long-term запись. */
export async function addMemoryEntry(
  input: { kind: MemoryKind; title: string; content: string; tags?: string[] },
  signal?: AbortSignal,
): Promise<{ entry: MemoryEntry }> {
  return postJson<{ entry: MemoryEntry }>('memory', input, signal);
}

/** PUT /api/memory/{id} — правка long-term записи. */
export async function updateMemoryEntry(
  id: string,
  input: { kind?: MemoryKind; title?: string; content?: string; tags?: string[] },
  signal?: AbortSignal,
): Promise<{ entry: MemoryEntry }> {
  return putJson<{ entry: MemoryEntry }>(`memory/${encodeURIComponent(id)}`, input, signal);
}

/** DELETE /api/memory/{id} — удалить long-term запись. */
export async function deleteMemoryEntry(
  id: string,
  signal?: AbortSignal,
): Promise<{ deleted: boolean }> {
  return deleteJson<{ deleted: boolean }>(`memory/${encodeURIComponent(id)}`, signal);
}

/** GET /api/memory/overview?sessionId=… — сводка трёх слоёв памяти. */
export async function fetchMemoryOverview(
  sessionId: string,
  signal?: AbortSignal,
): Promise<MemoryOverview> {
  return getJson<MemoryOverview>(`memory/overview?sessionId=${encodeURIComponent(sessionId)}`, signal);
}
