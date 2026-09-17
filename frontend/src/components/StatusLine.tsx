/* ============================================================
   Директива состояния — строка под журналом с лампой.
   Порт 1:1 из static/: разметка index.html L318–321, логика
   setDirective (script.js L93–97: kind на data-kind, у отказа —
   title с текстом), CSS style.css L1200–1246.
   Живое обновление придёт с фичевыми волнами — сейчас kind/text
   приходят через props.
   ============================================================ */

import type { TaskStage } from '../api/types';
import { useSessionStore } from '../stores/session';
import Lamp from './Lamp';
import styles from './StatusLine.module.css';

export type StatusKind = 'ready' | 'busy' | 'fault';

export interface StatusLineProps {
  kind: StatusKind;
  text: string;
}

/* Метки этапов FSM (ChatResponse.taskState, план задач 6). */
const STAGE_LABELS: Record<TaskStage, string> = {
  PLANNING: 'Планирование',
  EXECUTION: 'Исполнение',
  VALIDATION: 'Проверка',
  DONE: 'Готово',
  PAUSED: 'Пауза',
};

export default function StatusLine({ kind, text }: StatusLineProps) {
  /* taskState — из стора напрямую, по образцу Facts (s.facts):
     null — зона задачи пуста, как и до задачи 6. */
  const taskState = useSessionStore((s) => s.taskState);
  const stepLine = taskState
    ? `Шаг: ${taskState.step} · Далее: ${taskState.expectedAction}`
    : '';
  return (
    <div className={styles.directive} data-kind={kind} aria-live="polite">
      <Lamp
        color={kind === 'fault' ? 'red' : kind === 'busy' ? 'amber' : 'green'}
        blinking={kind === 'busy'}
      />
      <span className={styles.text} title={kind === 'fault' ? text : ''}>{text}</span>
      {taskState ? (
        <span className={styles.task}>
          <span className={styles.badge} data-stage={taskState.stage}>
            {STAGE_LABELS[taskState.stage]}
          </span>
          <span className={styles.step} title={stepLine}>{stepLine}</span>
        </span>
      ) : null}
    </div>
  );
}
