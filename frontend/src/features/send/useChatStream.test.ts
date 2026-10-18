/* Тесты статус-ветки конвейера useChatStream: ранний кадр «задача заведена»
   (StreamEvents.kt) кладёт taskState в стор прямо во время генерации —
   кнопка «Пауза» активна до final; штатные статусы без taskState стор
   не трогают. Хук вызывается без рендера: react замокан стаб-хуками,
   streamChat — эмиттером кадров (vitest здесь node, без jsdom/RTL). */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { postJson } from '../../api/client';
import type { StreamEvent, TaskState } from '../../api/types';
import { useSessionStore } from '../../stores/session';
import { useChatStream } from './useChatStream';

/* Кадры текущего теста и снимок стора после каждого кадра; hold — задержать
   поток в полёте (тесты stop); vi.hoisted — фабрики vi.mock поднимаются
   выше объявлений. */
const stream = vi.hoisted(() => ({
  frames: [] as unknown[],
  snapshot: null as null | (() => void),
  hold: false,
  holdRelease: null as null | (() => void),
}));

vi.mock('react', () => ({
  useCallback: (fn: unknown) => fn,
  useEffect: () => undefined,
  useRef: (initial: unknown) => ({ current: initial }),
  useState: (initial: unknown) => [initial, vi.fn()],
}));

vi.mock('../../api/client', () => ({
  ApiError: class MockApiError extends Error {
    status = 0;
    bodyText = '';
  },
  postJson: vi.fn(async () => ({})),
}));

vi.mock('../../api/sse', () => ({
  makeRequestId: () => 'req-test',
  streamChat: async (_request: unknown, opts: { onEvent: (event: unknown) => void }) => {
    for (const frame of stream.frames) {
      opts.onEvent(frame);
      stream.snapshot?.();
    }
    if (stream.hold) {
      /* задержка потока в полёте: тест обрывает прогон stop(), пока стрим открыт */
      await new Promise<void>((resolve) => {
        stream.holdRelease = resolve;
      });
    }
    return { ok: true, frames: stream.frames.length };
  },
}));

vi.mock('../branches/branchesApi', () => ({ loadBranches: vi.fn(async () => {}) }));
vi.mock('../sessions/loadSessions', () => ({ loadSessions: vi.fn(async () => {}) }));

const planningState: TaskState = {
  stage: 'PLANNING',
  step: 'составление плана',
  expectedAction: 'разбить задачу на шаги',
};

const executionState: TaskState = {
  stage: 'EXECUTION',
  step: 'шаг 2',
  expectedAction: 'вызвать инструмент',
};

const finalFrame = (taskState: TaskState | null): StreamEvent => ({
  type: 'final',
  response: { reply: 'Готово', facts: [], exchanges: [], events: [], taskState },
});

beforeEach(() => {
  stream.frames = [];
  stream.snapshot = null;
  stream.hold = false;
  stream.holdRelease = null;
  vi.mocked(postJson).mockClear();
  useSessionStore.setState({
    sessionId: null,
    taskState: null,
    entries: [],
    facts: [],
    invariants: null,
  });
});

async function runStream(frames: StreamEvent[]): Promise<(TaskState | null)[]> {
  const snapshots: (TaskState | null)[] = [];
  stream.frames = frames;
  stream.snapshot = () => snapshots.push(useSessionStore.getState().taskState);
  // eslint-disable-next-line react-hooks/rules-of-hooks -- вызов вне React-рендера: react замокан стаб-хуками, рендер не нужен
  const { send } = useChatStream();
  await send('привет');
  return snapshots;
}

describe('useChatStream: статус-кадры и taskState', () => {
  it('ранний кадр «задача заведена» кладёт taskState в стор во время генерации, до final', async () => {
    const snapshots = await runStream([
      { type: 'status', text: 'Задача заведена: планирование', taskState: planningState },
      finalFrame(executionState),
    ]);

    expect(snapshots).toEqual([planningState, executionState]);
  });

  it('штатный статус без taskState стор не трогает — final остаётся источником', async () => {
    const pausedState: TaskState = {
      stage: 'PAUSED',
      step: 'ожидание',
      expectedAction: 'ждать возобновления',
      prevStage: 'PLANNING',
    };
    useSessionStore.setState({ taskState: pausedState });

    const snapshots = await runStream([
      { type: 'status', text: 'Судья проверяет ответ…' },
      finalFrame(executionState),
    ]);

    expect(snapshots).toEqual([pausedState, executionState]);
  });

  it('явный taskState: null в статус-кадре затирает стор (контракт TaskState | null)', async () => {
    useSessionStore.setState({ taskState: planningState });

    const snapshots = await runStream([
      { type: 'status', text: 'Задача заведена: планирование', taskState: null },
      finalFrame(null),
    ]);

    expect(snapshots).toEqual([null, null]);
  });

  it('ранний кадр «задача заведена» кладёт sessionId в стор на первом ходе (до final)', async () => {
    await runStream([
      { type: 'status', text: 'Задача заведена: планирование', taskState: planningState, sessionId: 'srv-1' },
      finalFrame(executionState),
    ]);

    // final-ответ без sessionId не затирает: finalFrame не несёт id
    expect(useSessionStore.getState().sessionId).toBe('srv-1');
  });
});

describe('useChatStream: stop и /api/cancel', () => {
  async function stopInFlight(saveContext?: boolean) {
    stream.hold = true;
    // eslint-disable-next-line react-hooks/rules-of-hooks -- вызов вне React-рендера: react замокан стаб-хуками, рендер не нужен
    const { send, stop } = useChatStream();
    const done = send('привет');
    // activeRef стоит синхронно до await streamChat — прогон в полёте
    stop(saveContext);
    stream.holdRelease?.();
    stream.hold = false;
    await done;
  }

  it('stop() («Стоп»): тело /api/cancel несёт saveContext: false', async () => {
    await stopInFlight();

    expect(postJson).toHaveBeenCalledWith('cancel', { requestId: 'req-test', saveContext: false });
  });

  it('stop(true) (пауза): тело /api/cancel несёт saveContext: true', async () => {
    await stopInFlight(true);

    expect(postJson).toHaveBeenCalledWith('cancel', { requestId: 'req-test', saveContext: true });
  });
});
