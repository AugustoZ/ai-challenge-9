/* ============================================================
   Строка передачи — порт 1:1 из static/: разметка index.html
   L323–335 (form#chatForm.tx-row), поведение script.js
   L2735–2746 + L2785–2791 (submit: trim, пустое — мимо, очистка
   поля, блокировка на время передачи, возврат фокуса), CSS
   style.css L1250–1357 (.tx-row/.tx-input/.tx-btn/.tx-stop) и
   ≤600px L2826–2828. Поле — однострочный input, как в ваниле:
   Enter отдаёт форму нативно (Shift+Enter в однострочном поле
   не отличается), авто-grow нет. Клавиша «Стоп» видна, только
   пока запрос в полёте (script.js L2510–2519, hidden-атрибут —
   здесь условный рендер).
   ============================================================ */

import { useEffect, useRef } from 'react';
import type { FormEvent } from 'react';
import type { TaskState } from '../../api/types';
import styles from './SendBar.module.css';

export interface SendBarProps {
  inFlight: boolean;
  /** Клавиша «Стоп» видна только при активном запросе (в зеркале его нет —
      script.js L2510–2519: активный activeRequest). */
  stoppable?: boolean;
  /** Состояние задачи FSM (ChatResponse.taskState): null — клавиша
      паузы погашена; PAUSED — на клавише «Продолжить». */
  taskState?: TaskState | null;
  /** Клик по клавише паузы: POST /api/task/pause|resume (useTaskControl). */
  onPauseToggle?: () => void;
  onSend: (text: string) => void;
  onStop: () => void;
}

export default function SendBar({
  inFlight,
  stoppable = true,
  taskState,
  onPauseToggle,
  onSend,
  onStop,
}: SendBarProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const wasInFlightRef = useRef(false);

  /* Возврат фокуса в поле после передачи (script.js L2790):
     только при переходе полёта → покой, не при монтировании */
  useEffect(() => {
    if (inFlight) {
      wasInFlightRef.current = true;
      return;
    }
    if (wasInFlightRef.current) {
      wasInFlightRef.current = false;
      inputRef.current?.focus();
    }
  }, [inFlight]);

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const message = inputRef.current?.value.trim() ?? '';
    if (!message) return; // ваниль L2739 (плюс нативный required на поле)
    onSend(message);
    if (inputRef.current) inputRef.current.value = ''; // L2744
  };

  return (
    <form className={styles.txRow} onSubmit={handleSubmit}>
      <input
        ref={inputRef}
        type="text"
        className={styles.txInput}
        placeholder="Введите запрос…"
        autoComplete="off"
        spellCheck={false}
        required
        disabled={inFlight}
      />
      {inFlight && stoppable ? (
        <button type="button" className={`${styles.txBtn} ${styles.txStop}`} onClick={onStop}>
          Стоп
        </button>
      ) : null}
      <button
        type="button"
        className={styles.txBtn}
        disabled={taskState == null}
        aria-pressed={taskState?.stage === 'PAUSED'}
        onClick={onPauseToggle}
      >
        {taskState?.stage === 'PAUSED' ? 'Продолжить' : 'Пауза'}
      </button>
      <button type="submit" className={styles.txBtn} disabled={inFlight}>
        Передать
      </button>
    </form>
  );
}
