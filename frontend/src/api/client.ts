/* Типобезопасные обёртки над fetch для JSON-эндпоинтов /api.
   Не-2xx превращается в ApiError со статусом и телом ответа.
   Токен X-Auth-Token подставляется здесь (и в api/sse.ts) — по
   call-site'ам не рассыпается; любой ответ 401 дёргает хук
   auth-стора (см. stores/auth.ts), чтобы разлогинить вкладку. */

import type {
  AuthTokenResponse,
  AuthUserView,
  Invariant,
  InvariantsPayload,
  MemoryEntry,
  MemoryKind,
  MemoryListResponse,
  MemoryOverview,
  UpdatePreferencesRequest,
  UserCreatedResponse,
  UserPreferences,
  WorkingListResponse,
} from './types';

/* ---------- Токен и централизованный 401 (провайдера регистрирует stores/auth.ts) ---------- */

let authToken: () => string | null = () => null;
let onUnauthorized: (() => void) | null = null;

/** Регистрирует поставщика токена (stores/auth.ts). */
export function setAuthTokenProvider(provider: () => string | null): void {
  authToken = provider;
}

/** Регистрирует обработчик 401 — вызывается при ЛЮБОМ ответе 401. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

function authHeaders(): Record<string, string> {
  const token = authToken();
  return token ? { 'X-Auth-Token': token } : {};
}

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
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new ApiError(res.status, text);
  return JSON.parse(text) as T;
}

/** Как parseBody, но тело может быть пустым (DELETE без ответа). */
async function parseEmptyBody(res: Response): Promise<void> {
  await res.text();
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new ApiError(res.status, '');
}

/** GET /api/<path> — база '/api', path без префикса ('models', 'sessions'). */
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`/api/${path}`, { headers: authHeaders(), signal });
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
    headers: { ...authHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  return parseBody<T>(res);
}

/** DELETE /api/<path>; 204 обрабатывается как пустое тело. */
export async function deleteJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`/api/${path}`, { method: 'DELETE', headers: authHeaders(), signal });
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
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
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

/* ---------- Auth: вход, список, создание, удаление пользователей ---------- */

/** POST /api/auth/login — вход по ПИН; 401 «Неверный ПИН или пользователь», 429 кулдаун. */
export async function login(userId: string, pin: string, signal?: AbortSignal): Promise<AuthTokenResponse> {
  return postJson<AuthTokenResponse>('auth/login', { userId, pin }, signal);
}

/** GET /api/auth/users — список пользователей экрана входа (open-роут, только id+name). */
export async function listUsers(signal?: AbortSignal): Promise<AuthUserView[]> {
  return getJson<AuthUserView[]>('auth/users', signal);
}

/** POST /api/auth/users — создать пользователя; ответ сразу несёт токен (авто-вход). */
export async function createUser(name: string, pin: string, signal?: AbortSignal): Promise<UserCreatedResponse> {
  return postJson<UserCreatedResponse>('auth/users', { name, pin }, signal);
}

/** DELETE /api/auth/users/{id} — удалить пользователя с каскадом его данных. */
export async function deleteUser(id: string, signal?: AbortSignal): Promise<void> {
  const res = await fetch(`/api/auth/users/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
    signal,
  });
  return parseEmptyBody(res);
}

/* ---------- Предпочтения: GET/PUT /api/preferences ---------- */

/** GET /api/preferences — предпочтения текущего пользователя (401 → хук auth-стора). */
export async function fetchPreferences(signal?: AbortSignal): Promise<UserPreferences> {
  return getJson<UserPreferences>('preferences', signal);
}

/** PUT /api/preferences — сохранить предпочтения (UpdatePreferencesRequest). */
export async function savePreferences(
  prefs: UpdatePreferencesRequest,
  signal?: AbortSignal,
): Promise<UserPreferences> {
  return putJson<UserPreferences>('preferences', prefs, signal);
}

/* ---------- Инварианты: глобальный и сессионный скоупы (Kotlin: InvariantsPayload) ---------- */

/** GET /api/invariants?sessionId=… — оба скоупа одним пейлоадом (400 нет id, 404 неизвестная). */
export async function fetchInvariants(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InvariantsPayload> {
  return getJson<InvariantsPayload>(`invariants?sessionId=${encodeURIComponent(sessionId)}`, signal);
}

/** POST /api/invariants/global {text} — добавить глобальный инвариант (400 валидация/дубль/кап). */
export async function addGlobalInvariant(
  text: string,
  signal?: AbortSignal,
): Promise<{ global: Invariant[] }> {
  return postJson<{ global: Invariant[] }>('invariants/global', { text }, signal);
}

/** DELETE /api/invariants/global/{id} — убрать глобальный инвариант (404 — такого id нет). */
export async function deleteGlobalInvariant(
  id: string,
  signal?: AbortSignal,
): Promise<{ global: Invariant[] }> {
  return deleteJson<{ global: Invariant[] }>(
    `invariants/global/${encodeURIComponent(id)}`,
    signal,
  );
}

/** POST /api/invariants/session {sessionId, text} — добавить инвариант сессии (400/404). */
export async function addSessionInvariant(
  sessionId: string,
  text: string,
  signal?: AbortSignal,
): Promise<{ session: Invariant[] }> {
  return postJson<{ session: Invariant[] }>(
    'invariants/session',
    { sessionId, text },
    signal,
  );
}

/** DELETE /api/invariants/session/{sessionId}/{id} — убрать инвариант сессии (404). */
export async function deleteSessionInvariant(
  sessionId: string,
  id: string,
  signal?: AbortSignal,
): Promise<{ session: Invariant[] }> {
  return deleteJson<{ session: Invariant[] }>(
    `invariants/session/${encodeURIComponent(sessionId)}/${encodeURIComponent(id)}`,
    signal,
  );
}
