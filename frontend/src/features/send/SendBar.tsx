/* ============================================================
   Строка передачи — порт 1:1 из static/: разметка index.html
   L323–335 (form#chatForm.tx-row), поведение script.js
   L2735–2746 + L2785–2791 (submit: trim, пустое — мимо, очистка
   поля, блокировка на время передачи, возврат фокуса), CSS
   style.css L1250–1357 (.tx-row/.tx-input/.tx-btn/.tx-stop) и
   ≤600px L2826–2828. Поле — многострочная textarea (порт
   колодцев ввода из OnboardingWizard): Enter без модификаторов
   отдаёт форму (preventDefault + requestSubmit), Ctrl/Cmd+Enter
   вставляет перенос строки вручную (браузер на модифицированном
   Enter перенос в textarea не делает).
   Авто-grow до max-height из CSS, сброс при очистке поля и на
   время полёта. Клавиша «Стоп» видна, только
   пока запрос в полёте (script.js L2510–2519, hidden-атрибут —
   здесь условный рендер).
   ============================================================ */

import { useEffect, useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
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
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const wasInFlightRef = useRef(false);
  const [text, setText] = useState('');

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

  /* Авто-grow: поле растёт под содержимое (ввод или вставка
      многострочного текста) до max-height из CSS, дальше — прокрутка.
      box-sizing: border-box глобально (base.css), поэтому
      height = scrollHeight даёт точную высоту. Сброс базовой высоты —
      при очистке поля (после передачи) и на время полёта. */
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    if (inFlight || text === '') {
      el.style.height = '';
      return;
    }
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, [text, inFlight]);

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter') return;
    // Ctrl/Cmd+Enter — вручную вставляем перенос строки: браузер на модифицированный
    // Enter в textarea перенос НЕ делает (обычный Enter — делает), поэтому без
    // явной вставки пользователь не получает новую строку.
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      const el = e.currentTarget;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const next = el.value.slice(0, start) + '\n' + el.value.slice(end);
      setText(next);
      requestAnimationFrame(() => {
        el.selectionStart = el.selectionEnd = start + 1;
      });
      return;
    }
    // Обычный Enter без модификаторов — передача (preventDefault гасит перенос строки,
    // как нативный submit однострочного поля). Enter подтверждения IME не отправляет.
    if (e.nativeEvent.isComposing) return;
    e.preventDefault();
    e.currentTarget.form?.requestSubmit();
  };

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const message = text.trim();
    if (!message) return; // ваниль L2739 (плюс нативный required на поле)
    onSend(message);
    setText(''); // L2744 — очистка поля, авто-grow сбросится эффектом
  };

  return (
    <form className={styles.txRow} onSubmit={handleSubmit}>
      <textarea
        ref={inputRef}
        className={styles.txTextarea}
        rows={1}
        wrap="soft"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
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
