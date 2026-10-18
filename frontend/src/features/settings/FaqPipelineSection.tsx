/* ============================================================
   Секция «Обслуживание: FAQ» (план Task 11): клавиша запуска
   сценария faq-24h и зона статуса прогона. После 202 клиент
   опрашивает GET /api/pipeline/status каждые 3 с, пока прогон
   running; терминальное состояние (succeeded/failed) и размон-
   тирование гасят таймер. Сетевые сбои — читаемым сообщением
   в зоне статуса, UI не падает; 409 запуска подключает к уже
   идущему прогону.
   ============================================================ */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getPipelineStatus, runPipeline } from '../../api/client';
import type { PipelineStatus } from '../../api/types';
import annunciatorStyles from '../../components/AnnunciatorGroup.module.css';
import settingsStyles from './SettingsPanel.module.css';
import {
  collectPipelineErrors,
  formatClockTime,
  formatDuration,
  formatPipelineState,
  formatStepMark,
} from './pipelineFormat';
import styles from './FaqPipeline.module.css';

const PIPELINE_NAME = 'faq-24h';
const RUN_LABEL = 'Запустить FAQ (24ч)';
const RUN_TIP =
  'Сценарий собирает вопросы за 24ч из TG-чата и публикует в скрытую Wiki-страницу (нужны подключённые tg/wiki-серверы)';
const IDLE_TEXT = 'Сценарий ещё не запускался';
const POLL_INTERVAL_MS = 3000;

/** Читаемое сообщение любой ошибки (ApiError со статусом, сеть, прочее). */
function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'Сценарий «faq-24h» на сервере не найден';
    return `Сервер ответил ошибкой ${err.status}`;
  }
  return err instanceof Error ? err.message : String(err);
}

export default function FaqPipelineSection() {
  const [status, setStatus] = useState<PipelineStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const timerRef = useRef<number | null>(null);
  const pollInFlightRef = useRef(false);

  const stopPolling = useCallback(() => {
    if (timerRef.current != null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  /* Размонтирование панели — таймер поллинга снимается */
  useEffect(() => stopPolling, [stopPolling]);

  const poll = useCallback(async () => {
    if (pollInFlightRef.current) return;
    pollInFlightRef.current = true;
    try {
      const next = await getPipelineStatus(PIPELINE_NAME);
      setStatus(next);
      setError(null);
      if (next.state !== 'running') stopPolling();
    } catch (err) {
      /* Сеть/HTTP не роняют UI; 404 (прогонов не было) — опрос бессмыслен */
      setError(`Статус недоступен: ${describeError(err)}`);
      if (err instanceof ApiError && err.status === 404) stopPolling();
    } finally {
      pollInFlightRef.current = false;
    }
  }, [stopPolling]);

  const startPolling = useCallback(() => {
    stopPolling();
    void poll();
    timerRef.current = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
  }, [poll, stopPolling]);

  const onRun = useCallback(async () => {
    setError(null);
    setStarting(true);
    try {
      await runPipeline(PIPELINE_NAME);
      startPolling();
    } catch (err) {
      /* 409 — прогон уже идёт: не ошибка для пилота, подключаемся к статусу */
      if (err instanceof ApiError && err.status === 409) {
        startPolling();
      } else {
        setError(describeError(err));
      }
    } finally {
      setStarting(false);
    }
  }, [startPolling]);

  const running = status?.state === 'running';
  const failed = status?.state === 'failed';
  const stepErrors = status ? collectPipelineErrors(status) : [];

  const stateClass = running
    ? styles.stateRunning
    : status?.state === 'succeeded'
      ? styles.stateSucceeded
      : styles.stateFailed;

  return (
    <section className={settingsStyles.panelSection}>
      <h2 className={settingsStyles.sectionCaption}>Обслуживание: FAQ</h2>
      <button
        type="button"
        className={annunciatorStyles.annunciator}
        disabled={starting || running}
        title={RUN_TIP}
        onClick={() => void onRun()}
      >
        {RUN_LABEL}
      </button>
      <div className={styles.statusZone} role="status" aria-live="polite">
        {error && <p className={styles.errorText}>{error}</p>}
        {!error && !status && <p className={styles.idleText}>{IDLE_TEXT}</p>}
        {status && (
          <>
            <p className={stateClass}>
              {formatPipelineState(status.state)}
              {` · старт ${formatClockTime(status.startedAt)}`}
              {status.finishedAt ? ` · финиш ${formatClockTime(status.finishedAt)}` : ''}
            </p>
            <ul className={styles.steps}>
              {status.steps.map((step) => {
                const rowClass =
                  step.status === 'done'
                    ? styles.stepDone
                    : step.status === 'failed'
                      ? styles.stepFailed
                      : step.status === 'running'
                        ? styles.stepRunning
                        : styles.stepIdle;
                return (
                  <li key={step.id}>
                    <span className={`${styles.stepRow} ${rowClass}`}>
                      <span className={styles.stepMark}>{formatStepMark(step.status)}</span>
                      <span>{step.id}</span>
                      <span>{formatDuration(step.durationMs)}</span>
                    </span>
                    {step.error && <span className={styles.stepError}>{step.error}</span>}
                  </li>
                );
              })}
            </ul>
            {failed && stepErrors.length === 0 && (
              <p className={styles.stepError}>Прогон завершился ошибкой — подробностей нет</p>
            )}
          </>
        )}
      </div>
    </section>
  );
}
