/* Тесты обёрток auth/preferences (план Task 5, QA-сценарии):
   «Обёртки ходят на правильные пути с заголовком» и
   «Негатив — 401 разлогинивает». fetch замокан глобально;
   auth-стор используется синглтоном, как в приложении. */

import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_USER_PREFERENCES } from './types';
import {
  ApiError,
  createUser,
  deleteUser,
  fetchPreferences,
  listUsers,
  login,
  savePreferences,
} from './client';
import { streamChat } from './sse';
import { useAuthStore } from '../stores/auth';

const TOKEN_RESPONSE = { token: 'tok-9', user: { id: 'u1', name: 'Шумахер' } };
const DEFAULT_PREFS_WIRE = DEFAULT_USER_PREFERENCES;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sseResponse(): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(
        encoder.encode(
          `data: ${JSON.stringify({
            type: 'final',
            response: { reply: 'Готово', events: [], exchanges: [], facts: [] },
          })}\n\n`,
        ),
      );
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
  useAuthStore.getState().logout();
});

describe('QA: обёртки ходят на правильные пути с заголовком', () => {
  it('login("u1","0407") → POST /api/auth/login с телом {userId, pin}', async () => {
    const mock = vi.fn(async () => jsonResponse(200, TOKEN_RESPONSE));
    vi.stubGlobal('fetch', mock);

    const res = await login('u1', '0407');

    expect(res).toEqual(TOKEN_RESPONSE);
    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ userId: 'u1', pin: '0407' });
  });

  it('listUsers → GET /api/auth/users; createUser → POST /api/auth/users {name, pin}', async () => {
    let call = 0;
    const mock = vi.fn(async () => {
      call += 1;
      return call === 1
        ? new Response('[]', { status: 200 })
        : jsonResponse(200, { token: 't', user: { id: 'u2', name: 'Новый' } });
    });
    vi.stubGlobal('fetch', mock);

    await expect(listUsers()).resolves.toEqual([]);
    expect((mock.mock.calls[0] as unknown as [string])[0]).toBe('/api/auth/users');

    await createUser('Пилот Тест', '1111');
    const [url, init] = mock.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe('/api/auth/users');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ name: 'Пилот Тест', pin: '1111' });
  });

  it('deleteUser → DELETE /api/auth/users/{id} (пустое тело допустимо)', async () => {
    const mock = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', mock);

    await expect(deleteUser('u1')).resolves.toBeUndefined();
    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/auth/users/u1');
    expect(init.method).toBe('DELETE');
  });

  it('после login GET/PUT /api/preferences и streamChat несут X-Auth-Token', async () => {
    const mock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/auth/login')) return jsonResponse(200, TOKEN_RESPONSE);
      if (url.endsWith('/api/preferences')) return jsonResponse(200, DEFAULT_PREFS_WIRE);
      return sseResponse();
    });
    vi.stubGlobal('fetch', mock);

    // Вход через стор: токен появляется в сторе и уходит во все запросы
    await useAuthStore.getState().login('u1', '0407');
    expect(useAuthStore.getState().getToken()).toBe('tok-9');

    await fetchPreferences();
    let [, init] = mock.mock.calls[1] as unknown as [string, RequestInit];
    expect((init?.headers as Record<string, string>)['X-Auth-Token']).toBe('tok-9');

    await savePreferences({ ...DEFAULT_PREFS_WIRE });
    [, init] = mock.mock.calls[2] as unknown as [string, RequestInit];
    expect((init?.headers as Record<string, string>)['X-Auth-Token']).toBe('tok-9');

    const result = await streamChat({ message: 'привет' }, { onEvent: vi.fn() });
    expect(result.ok).toBe(true);
    const [streamUrl, streamInit] = mock.mock.calls[3] as unknown as [string, RequestInit];
    expect(streamUrl).toBe('/api/chat/stream');
    expect((streamInit.headers as Record<string, string>)['X-Auth-Token']).toBe('tok-9');
  });
});

describe('QA: негатив — 401 разлогинивает централизованно', () => {
  it('401 на GET /api/preferences → ApiError(401), стор уходит в anon', async () => {
    const mock = vi.fn(async () => new Response('{"error":"Требуется вход"}', { status: 401 }));
    vi.stubGlobal('fetch', mock);

    // Стартуем «авторизованным»: токен в сторе
    useAuthStore.setState({ token: 'tok-9', user: { id: 'u1', name: 'Шумахер' }, status: 'authed' });

    const err = await fetchPreferences().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).bodyText).toContain('Требуется вход');

    // Централизованный хук: ЛЮБОЙ 401 разлогинил стор
    expect(useAuthStore.getState().status).toBe('anon');
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('401 в стриме: streamChat возвращает ok:false (негативный путь SSE)', async () => {
    const mock = vi.fn(async () => new Response('{"error":"Требуется вход"}', { status: 401 }));
    vi.stubGlobal('fetch', mock);

    const result = await streamChat({ message: 'привет' }, { onEvent: vi.fn() });
    expect(result).toEqual({ ok: false, frames: 0 });
  });
});
