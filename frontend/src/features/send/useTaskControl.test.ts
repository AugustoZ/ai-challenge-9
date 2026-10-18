/* Тесты togglePause (решение 2026-09-20): «Пауза» в полёте обрывает
   прогон — cancelActive (stop из useChatStream) вызывается ровно один
   раз при переходе В паузу; при resume (PAUSED → продолжить) — нет;
   без задачи — ранний return. Resume с checkpoint (ответ
   resumeRun: true) — ровно один вызов onResumeRun; без поля или
   false — нет. Хук вызывается без рендера: react замокан
   стаб-хуками, postJson — vi.fn (стиль useChatStream.test.ts). */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TaskState } from '../../api/types';
import { postJson } from '../../api/client';
import { useSessionStore } from '../../stores/session';
import { useTaskControl } from './useTaskControl';

vi.mock('react', () => ({
  useCallback: (fn: unknown) => fn,
}));

vi.mock('../../api/client', () => ({
  ApiError: class MockApiError extends Error {
    status = 0;
    bodyText = '';
  },
  postJson: vi.fn(async () => ({ taskState: null })),
}));

const executionState: TaskState = {
  stage: 'EXECUTION',
  step: 'шаг 2',
  expectedAction: 'вызвать инструмент',
};

const pausedState: TaskState = {
  stage: 'PAUSED',
  step: 'ожидание',
  expectedAction: 'ждать возобновления',
  prevStage: 'EXECUTION',
};

beforeEach(() => {
  vi.mocked(postJson).mockClear();
  useSessionStore.setState({
    sessionId: 'sess-1',
    taskState: null,
  });
});

function mountCancelActive(onResumeRun?: () => void) {
  const cancelActive = vi.fn();
  // eslint-disable-next-line react-hooks/rules-of-hooks -- вызов вне React-рендера: react замокан стаб-хуками, рендер не нужен
  const { togglePause } = useTaskControl(vi.fn(), cancelActive, onResumeRun);
  return { togglePause, cancelActive };
}

describe('useTaskControl: togglePause и обрыв прогона', () => {
  it('пауза в полёте: cancelActive ровно один раз + POST task/pause', () => {
    useSessionStore.setState({ taskState: executionState });
    const { togglePause, cancelActive } = mountCancelActive();

    togglePause();

    // Пауза: отмена несёт saveContext: true (сервер сохранит контекст прогона)
    expect(cancelActive).toHaveBeenCalledTimes(1);
    expect(cancelActive).toHaveBeenCalledWith(true);
    expect(postJson).toHaveBeenCalledTimes(1);
    expect(postJson).toHaveBeenCalledWith('task/pause', { sessionId: 'sess-1' });
  });

  it('resume (PAUSED → продолжить): cancelActive не вызывается, POST task/resume', () => {
    useSessionStore.setState({ taskState: pausedState });
    const { togglePause, cancelActive } = mountCancelActive();

    togglePause();

    expect(cancelActive).not.toHaveBeenCalled();
    expect(postJson).toHaveBeenCalledTimes(1);
    expect(postJson).toHaveBeenCalledWith('task/resume', { sessionId: 'sess-1' });
  });

  it('без taskState — ранний return: ни cancelActive, ни запрос', () => {
    const { togglePause, cancelActive } = mountCancelActive();

    togglePause();

    expect(cancelActive).not.toHaveBeenCalled();
    expect(postJson).not.toHaveBeenCalled();
  });

  it('resume с checkpoint (resumeRun: true) → onResumeRun ровно один раз', async () => {
    useSessionStore.setState({ taskState: pausedState });
    const onResumeRun = vi.fn();
    vi.mocked(postJson).mockResolvedValueOnce({ taskState: pausedState, resumeRun: true });
    const { togglePause } = mountCancelActive(onResumeRun);

    togglePause();

    await vi.waitFor(() => expect(onResumeRun).toHaveBeenCalledTimes(1));
    expect(postJson).toHaveBeenCalledWith('task/resume', { sessionId: 'sess-1' });
  });

  it('resume без checkpoint (resumeRun: false / нет поля) → onResumeRun не вызывается', async () => {
    useSessionStore.setState({ taskState: pausedState });
    const onResumeRun = vi.fn();
    vi.mocked(postJson).mockResolvedValueOnce({ taskState: pausedState, resumeRun: false });
    const { togglePause } = mountCancelActive(onResumeRun);

    togglePause();
    await new Promise((r) => setTimeout(r, 0));
    expect(onResumeRun).not.toHaveBeenCalled();

    useSessionStore.setState({ taskState: pausedState });
    vi.mocked(postJson).mockResolvedValueOnce({ taskState: pausedState });
    togglePause();
    await new Promise((r) => setTimeout(r, 0));
    expect(onResumeRun).not.toHaveBeenCalled();
  });
});
