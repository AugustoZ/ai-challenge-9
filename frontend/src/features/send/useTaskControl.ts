/* ============================================================
   Управление паузой FSM (план задач 6): POST /api/task/pause
   или /api/task/resume с текущим sessionId; ответ {taskState}
   оседает в стор чата — тем же сеттером, что применяет
   applyChatResponse. Кнопка SendBar дергает togglePause.
   ============================================================ */

import { useCallback } from 'react';
import { postJson } from '../../api/client';
import type { TaskState } from '../../api/types';
import { useSessionStore } from '../../stores/session';

/** Ответ POST /api/task/pause|resume (Kotlin: TaskStateEnvelope). */
interface TaskStateEnvelope {
  taskState: TaskState | null;
}

export function useTaskControl(onError?: (message: string) => void) {
  const togglePause = useCallback(() => {
    const { taskState, sessionId, setTaskState } = useSessionStore.getState();
    if (!taskState) return;
    const path = taskState.stage === 'PAUSED' ? 'task/resume' : 'task/pause';
    postJson<TaskStateEnvelope>(path, { sessionId })
      .then((res) => setTaskState(res.taskState ?? null))
      .catch((err: unknown) => {
        onError?.(`Не удалось переключить паузу: ${err instanceof Error ? err.message : String(err)}`);
      });
  }, [onError]);

  return { togglePause };
}
