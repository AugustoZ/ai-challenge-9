/* Тесты auth-стора: токен живёт в sessionStorage (ключ shturman.auth.v1),
   login/logout/restore/handle401 по плану (Task 5). Хранилище инъецируется
   (in-memory StringStorage) — vitest идёт в node-окружении. */

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DEFAULT_USER_PREFERENCES as DEFAULT_PREFS,
  type AuthTokenResponse,
} from '../api/types';
import { createMemoryStorage, type StringStorage } from '../lib/storage';
import { AUTH_STORAGE_KEY, createAuthStore } from './auth';

const TOKEN_RESPONSE: AuthTokenResponse = {
  token: 'tok-1',
  user: { id: 'u1', name: 'Шумахер' },
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubFetchSequence(responses: Array<Response | Error>): ReturnType<typeof vi.fn> {
  const queue = [...responses];
  return vi.fn(async () => {
    const next = queue.shift();
    if (next instanceof Error) throw next;
    return next ?? new Response('{}', { status: 500 });
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Разворачивает стор с инъекцией хранилища и возвращает его + хранилище. */
function setupStore(storage: StringStorage = createMemoryStorage()) {
  const store = createAuthStore(storage);
  return { store, storage };
}

describe('createAuthStore — стартовое состояние', () => {
  it('стартует в checking без токена и пользователя', () => {
    const { store } = setupStore();
    const state = store.getState();
    expect(state.status).toBe('checking');
    expect(state.token).toBeNull();
    expect(state.user).toBeNull();
    expect(state.onboardingRequired).toBe(false);
  });
});

describe('login', () => {
  it('POST-ответ сохраняет token+user в state и sessionStorage', async () => {
    const fetchMock = stubFetchSequence([jsonResponse(200, TOKEN_RESPONSE)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();

    const res = await store.getState().login('u1', '0407');

    expect(res.token).toBe('tok-1');
    const state = store.getState();
    expect(state.status).toBe('authed');
    expect(state.token).toBe('tok-1');
    expect(state.user).toEqual({ id: 'u1', name: 'Шумахер' });
    // Токен — в sessionStorage (ключ shturman.auth.v1), НЕ в localStorage
    const raw = storage.getItem(AUTH_STORAGE_KEY);
    expect(raw).not.toBeNull();
    expect(JSON.parse(raw ?? '{}')).toEqual({
      token: 'tok-1',
      user: { id: 'u1', name: 'Шумахер' },
    });
    // Тело запроса: POST /api/auth/login {userId, pin}
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ userId: 'u1', pin: '0407' });
  });

  it('неверный ПИН (401) → ApiError наружу, статус anon, хранилище пусто', async () => {
    const fetchMock = stubFetchSequence([
      jsonResponse(401, { error: 'Неверный ПИН или пользователь' }),
    ]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();

    await expect(store.getState().login('u1', '9999')).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
    });
    expect(store.getState().status).toBe('anon');
    expect(store.getState().token).toBeNull();
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it('повторный login заменяет токен', async () => {
    const fetchMock = stubFetchSequence([
      jsonResponse(200, TOKEN_RESPONSE),
      jsonResponse(200, { token: 'tok-2', user: { id: 'u2', name: 'Пилот' } }),
    ]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();

    await store.getState().login('u1', '0407');
    await store.getState().login('u2', '1234');

    expect(store.getState().token).toBe('tok-2');
    expect(store.getState().user).toEqual({ id: 'u2', name: 'Пилот' });
    expect(JSON.parse(storage.getItem(AUTH_STORAGE_KEY) ?? '{}').token).toBe('tok-2');
  });
});

describe('createUser — авто-вход после создания', () => {
  it('сохраняет токен и ставит onboardingRequired', async () => {
    const fetchMock = stubFetchSequence([jsonResponse(200, TOKEN_RESPONSE)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();

    await store.getState().createUser('Пилот Тест', '1111');

    expect(store.getState().status).toBe('authed');
    expect(store.getState().token).toBe('tok-1');
    expect(store.getState().onboardingRequired).toBe(true);
    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();

    store.getState().completeOnboarding();
    expect(store.getState().onboardingRequired).toBe(false);
  });
});

describe('logout', () => {
  it('чистит state и sessionStorage', async () => {
    const fetchMock = stubFetchSequence([jsonResponse(200, TOKEN_RESPONSE)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();
    await store.getState().login('u1', '0407');

    store.getState().logout();

    const state = store.getState();
    expect(state.status).toBe('anon');
    expect(state.token).toBeNull();
    expect(state.user).toBeNull();
    expect(state.onboardingRequired).toBe(false);
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });
});

describe('restore — при старте вкладки', () => {
  it('нет токена в хранилище → anon, сервер не вызывается', async () => {
    const fetchMock = stubFetchSequence([]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore();

    await store.getState().restore();

    expect(store.getState().status).toBe('anon');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('есть токен + GET /api/preferences отвечает → authed с токеном из хранилища', async () => {
    const storage = createMemoryStorage({
      [AUTH_STORAGE_KEY]: JSON.stringify({ token: 'tok-old', user: { id: 'u1', name: 'Шумахер' } }),
    });
    const fetchMock = stubFetchSequence([jsonResponse(200, DEFAULT_PREFS)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore(storage);

    await store.getState().restore();

    const state = store.getState();
    expect(state.status).toBe('authed');
    expect(state.token).toBe('tok-old');
    expect(state.user).toEqual({ id: 'u1', name: 'Шумахер' });
    // Проверочный запрос идёт в /api/preferences с сохранённым токеном
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/preferences');
    expect((init.headers as Record<string, string>)['X-Auth-Token']).toBe('tok-old');
  });

  it('сервер перезапущен (401) → logout: anon и хранилище пусто', async () => {
    const storage = createMemoryStorage({
      [AUTH_STORAGE_KEY]: JSON.stringify({ token: 'tok-old', user: { id: 'u1', name: 'Шумахер' } }),
    });
    const fetchMock = stubFetchSequence([jsonResponse(401, { error: 'Требуется вход' })]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore(storage);

    await store.getState().restore();

    expect(store.getState().status).toBe('anon');
    expect(store.getState().token).toBeNull();
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it('сервер недоступен (сеть) → тоже logout (план: restore = authed или logout)', async () => {
    const storage = createMemoryStorage({
      [AUTH_STORAGE_KEY]: JSON.stringify({ token: 'tok-old', user: { id: 'u1', name: 'Шумахер' } }),
    });
    const fetchMock = stubFetchSequence([new Error('network down')]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore(storage);

    await store.getState().restore();

    expect(store.getState().status).toBe('anon');
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it('битое содержимое хранилища → anon без падения', async () => {
    const storage = createMemoryStorage({ [AUTH_STORAGE_KEY]: '{битый json' });
    const fetchMock = stubFetchSequence([]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore(storage);

    await store.getState().restore();

    expect(store.getState().status).toBe('anon');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('handle401 — централизованный разлогин', () => {
  it('после login: status=anon, sessionStorage пуст', async () => {
    const fetchMock = stubFetchSequence([jsonResponse(200, TOKEN_RESPONSE)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store, storage } = setupStore();
    await store.getState().login('u1', '0407');

    store.getState().handle401();

    expect(store.getState().status).toBe('anon');
    expect(store.getState().token).toBeNull();
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });
});

describe('getToken', () => {
  it('после login возвращает токен, после logout — null', async () => {
    const fetchMock = stubFetchSequence([jsonResponse(200, TOKEN_RESPONSE)]);
    vi.stubGlobal('fetch', fetchMock);
    const { store } = setupStore();

    expect(store.getState().getToken()).toBeNull();
    await store.getState().login('u1', '0407');
    expect(store.getState().getToken()).toBe('tok-1');
    store.getState().logout();
    expect(store.getState().getToken()).toBeNull();
  });
});
