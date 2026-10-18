/* Тесты SSE-конвейера: кадры реальной формы бэка (Sse.kt encodeFrame),
   толерантность парсера и поведение reader-loop streamChat. */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { extractFrames, makeRequestId, parseFrame, streamChat } from './sse';
import type { StreamEvent } from './types';

const frame = (payload: object | string): string =>
  `data: ${typeof payload === 'string' ? payload : JSON.stringify(payload)}\n\n`;

const agentFrame = frame({ type: 'agent', event: { type: 'step_started', step: 1 } });
const statusFrame = frame({ type: 'status', text: 'Судья проверяет ответ…' });
const finalFrame = frame({
  type: 'final',
  response: { reply: 'Готово', events: [], exchanges: [], facts: [], sessionId: 's-1' },
});
const cancelledFrame = frame({ type: 'cancelled', requestId: 'req-1' });
const errorFrame = frame({ type: 'error', message: 'Ошибка при обращении к API: boom' });

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

function stubFetch(res: Response | (() => Promise<Response>)): void {
  const mock = vi.fn(async () => (typeof res === 'function' ? res() : res));
  vi.stubGlobal('fetch', mock);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('extractFrames', () => {
  it('режет буфер на кадры, хвост без разделителя — в rest', () => {
    const buffer = `${agentFrame}${statusFrame}data: частичный`;
    expect(extractFrames(buffer)).toEqual({
      frames: [
        `data: ${JSON.stringify({ type: 'agent', event: { type: 'step_started', step: 1 } })}`,
        `data: ${JSON.stringify({ type: 'status', text: 'Судья проверяет ответ…' })}`,
      ],
      rest: 'data: частичный',
    });
  });

  it('без разделителя весь буфер остаётся в rest', () => {
    expect(extractFrames('data: {"type":"status"}')).toEqual({
      frames: [],
      rest: 'data: {"type":"status"}',
    });
  });

  it('пустой буфер — пустой результат', () => {
    expect(extractFrames('')).toEqual({ frames: [], rest: '' });
  });

  it('несколько разделителей подряд дают пустые кадры', () => {
    expect(extractFrames('\n\n\n\nx\n\n')).toEqual({ frames: ['', '', 'x'], rest: '' });
  });
});

describe('parseFrame', () => {
  it('разбирает кадр agent с вложенным AgentEvent', () => {
    const event = { type: 'tool_call', step: 2, tool: 'calculator', arguments: '{"a":1}' };
    expect(parseFrame(frame({ type: 'agent', event }))).toEqual({ type: 'agent', event });
  });

  it('разбирает кадр status', () => {
    expect(parseFrame(statusFrame)).toEqual({ type: 'status', text: 'Судья проверяет ответ…' });
  });

  it('разбирает терминальный кадр final с ChatResponse', () => {
    expect(parseFrame(finalFrame)).toMatchObject({ type: 'final', response: { reply: 'Готово' } });
  });

  it('разбирает кадр cancelled', () => {
    expect(parseFrame(cancelledFrame)).toEqual({ type: 'cancelled', requestId: 'req-1' });
  });

  it('разбирает кадр error', () => {
    expect(parseFrame(errorFrame)).toEqual({ type: 'error', message: 'Ошибка при обращении к API: boom' });
  });

  it('битый JSON — null (кадр пропускается)', () => {
    expect(parseFrame(frame('{"type":"status"'))).toBeNull();
  });

  it('неизвестный type — null', () => {
    expect(parseFrame(frame({ type: 'telemetry', text: '?' }))).toBeNull();
  });

  it('комментарии и строки без data: пропускаются', () => {
    expect(parseFrame(': ping\nevent: keep-alive\ndata: {"type":"status","text":"ok"}')).toEqual({
      type: 'status',
      text: 'ok',
    });
  });

  it('пустой payload после data: — null', () => {
    expect(parseFrame('data:\ndata:\n')).toBeNull();
  });

  it('CRLF-концы строк не мешают разбору', () => {
    expect(parseFrame('data: {"type":"status","text":"ok"}\r\n\r\n')).toEqual({
      type: 'status',
      text: 'ok',
    });
  });

  it('примитив вместо объекта — null', () => {
    expect(parseFrame(frame('42'))).toBeNull();
  });
});

describe('streamChat', () => {
  const request = { message: 'привет', requestId: 'req-1' };

  it('диспетчит события и завершается на терминальном кадре', async () => {
    stubFetch(sseResponse([agentFrame, statusFrame, finalFrame, agentFrame]));
    const events: StreamEvent[] = [];
    const result = await streamChat(request, { onEvent: (e) => events.push(e) });

    expect(result).toEqual({ ok: true, frames: 3 });
    expect(events.map((e) => e.type)).toEqual(['agent', 'status', 'final']);
  });

  it('кадр, разрезанный между чанками, собирается из буфера', async () => {
    const whole = frame({ type: 'status', text: 'Живой стрим' });
    stubFetch(sseResponse([whole.slice(0, 12), whole.slice(12)]));
    const events: StreamEvent[] = [];
    const result = await streamChat(request, { onEvent: (e) => events.push(e) });

    expect(result).toEqual({ ok: true, frames: 1 });
    expect(events).toEqual([{ type: 'status', text: 'Живой стрим' }]);
  });

  it('битые кадры считаются, но не диспетчатся', async () => {
    stubFetch(sseResponse([frame('не JSON'), frame({ type: 'status', text: 'ok' })]));
    const events: StreamEvent[] = [];
    const result = await streamChat(request, { onEvent: (e) => events.push(e) });

    expect(result).toEqual({ ok: true, frames: 2 });
    expect(events).toEqual([{ type: 'status', text: 'ok' }]);
  });

  it('не-2xx — ok: false, без событий', async () => {
    stubFetch(new Response('упс', { status: 500 }));
    const onEvent = vi.fn();
    const result = await streamChat(request, { onEvent });

    expect(result).toEqual({ ok: false, frames: 0 });
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('ответ без тела — ok: false', async () => {
    stubFetch(new Response(null, { status: 200 }));
    const result = await streamChat(request, { onEvent: vi.fn() });
    expect(result).toEqual({ ok: false, frames: 0 });
  });

  it('поток закрылся без кадров — ok: false', async () => {
    stubFetch(sseResponse([]));
    const result = await streamChat(request, { onEvent: vi.fn() });
    expect(result).toEqual({ ok: false, frames: 0 });
  });

  it('cancelled — терминальный кадр', async () => {
    stubFetch(sseResponse([statusFrame, cancelledFrame]));
    const events: StreamEvent[] = [];
    const result = await streamChat(request, { onEvent: (e) => events.push(e) });

    expect(result).toEqual({ ok: true, frames: 2 });
    expect(events[1]).toEqual({ type: 'cancelled', requestId: 'req-1' });
  });

  it('error — терминальный кадр', async () => {
    stubFetch(sseResponse([errorFrame]));
    const events: StreamEvent[] = [];
    const result = await streamChat(request, { onEvent: (e) => events.push(e) });

    expect(result).toEqual({ ok: true, frames: 1 });
    expect(events).toEqual([{ type: 'error', message: 'Ошибка при обращении к API: boom' }]);
  });

  it('AbortError пробрасывается вызывающему', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new DOMException('The operation was aborted.', 'AbortError'))),
    );

    await expect(
      streamChat({ message: 'привет', requestId: 'req-1' }, { onEvent: vi.fn() }),
    ).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('requestId из ChatRequest уходит в тело запроса', async () => {
    const fetchMock = vi.fn(async () => sseResponse([finalFrame]));
    vi.stubGlobal('fetch', fetchMock);
    await streamChat(request, { onEvent: vi.fn() });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual(request);
  });
});

describe('makeRequestId', () => {
  it('возвращает разные UUID-подобные идентификаторы', () => {
    const a = makeRequestId();
    const b = makeRequestId();
    expect(a).not.toEqual(b);
    expect(a).toMatch(/^[0-9a-f-]{32,36}$/);
  });
});
