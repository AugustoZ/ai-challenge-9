/* ============================================================
   Управление паузой FSM (план задач 6): POST /api/task/pause
   или /api/task/resume с текущим sessionId; ответ {taskState}
   оседает в стор чата — тем же сеттером, что применяет
   applyChatResponse. Кнопка SendBar дергает togglePause.
   Пауза в полёте обрывает прогон (как «Стоп») + ставит FSM на
   паузу — решение пользователя 2026-09-20; частичный ход в
   историю не пишется (семантика «Стоп»). Пауза в простое —
   только FSM (cancelActive в простое no-op).
   ============================================================ */

import { useCallback } from 'react';
import { postJson } from '../../api/client';
import type { TaskStateEnvelope } from '../../api/types';
import { useSessionStore } from '../../stores/session';

/**
 * @param onError — сбой POST pause/resume уходит сюда (директива «Авария» в App).
 * @param cancelActive — обрыв активного прогона (stop из useChatStream);
 *   вызывается только при переходе В паузу с saveContext: true — сервер
 *   сохранит контекст прогона для продолжения после паузы. В простое no-op:
 *   внутри stop() guard `if (!active) return`.
 * @param onResumeRun — авто-продолжение: вызывается на resume, когда ответ
 *   несёт resumeRun: true (checkpoint прерванного паузой прогона есть).
 *   В App это send(CONTINUATION_MESSAGE); send сам no-op при inFlight.
 */
export function useTaskControl(
  onError?: (message: string) => void,
  cancelActive?: (saveContext: boolean) => void,
  onResumeRun?: () => void,
) {
  const togglePause = useCallback(() => {
    const { taskState, sessionId, setTaskState } = useSessionStore.getState();
    if (!taskState) return;
    const toPause = taskState.stage !== 'PAUSED';
    /* Решение 2026-09-20: «Пауза» в полёте обрывает прогон, как «Стоп» —
       agent не дорабатывает; в простое это no-op (guard внутри stop()).
       saveContext: true — отмена от паузы: сервер сохранит контекст прогона
       для продолжения после возобновления (в отличие от «Стопа»). */
    if (toPause) {
      cancelActive?.(true);
    }
    const path = toPause ? 'task/pause' : 'task/resume';
    postJson<TaskStateEnvelope>(path, { sessionId })
      .then((res) => {
        setTaskState(res.taskState ?? null);
        /* continuation только в resume-ветке: resumeRun: true — сервер
           подтвердил, что checkpoint прерванного паузой прогона есть. */
        if (!toPause && res.resumeRun === true) {
          onResumeRun?.();
        }
      })
      .catch((err: unknown) => {
        onError?.(`Не удалось переключить паузу: ${err instanceof Error ? err.message : String(err)}`);
      });
  }, [onError, cancelActive, onResumeRun]);

  return { togglePause };
}
