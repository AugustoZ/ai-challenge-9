/* ============================================================
   Протокол агентного цикла — порт 1:1 из static/script.js
   L470–582 (buildAgentEventRow, buildAgentTrace, createLiveTrace,
   appendLiveAgentEvent). Презентационная: живой поток — родитель
   дописывает события в массив, трасса растёт по строке на событие.
   Аргументы инструментов и промежуточные тексты — ненадёжный ввод
   от LLM: всё рендерится текстом, без innerHTML.
   ============================================================ */

import type { AgentEvent } from '../../api/types';
import { eventsWord, truncateText } from '../../lib/format';
import DownloadChip from './DownloadChip';
import styles from './AgentTrace.module.css';

export interface AgentTraceProps {
  events: AgentEvent[];
  /** Живая трасса во время потока: раскрыта, на заголовке — маячок работы. */
  live?: boolean;
}

/* Строка протокола для одного события AgentLoop. События приходят только
   дописыванием — индекс стабилен и служит ключом (как порядок строк в ваниле). */
function AgentEventRow({ ev }: { ev: AgentEvent }) {
  const step = ev.step != null ? ev.step : '—';

  const rowCls = [styles.event];
  if (ev.type === 'final') rowCls.push(styles.final);
  if (ev.type === 'tool_result' && ev.ok === false) rowCls.push(styles.fault);

  return (
    <div className={rowCls.join(' ')}>
      <span className={styles.step}>{`Шаг ${step}`}</span>
      {ev.type === 'step_started' && <span>— обдумываю задачу</span>}
      {ev.type === 'tool_call' && (
        <>
          <span>— вызов инструмента: </span>
          <code className={styles.args}>{`${ev.tool ?? '?'}(${truncateText(ev.arguments ?? '', 200)})`}</code>
          {ev.tool === 'write_file' && <DownloadChip argumentsJson={ev.arguments} />}
        </>
      )}
      {ev.type === 'tool_result' && (
        <>
          <span>{`— результат ${ev.tool ?? '?'}: `}</span>
          <span className={ev.ok ? `${styles.flag} ${styles.ok}` : `${styles.flag} ${styles.faultFlag}`}>
            {ev.ok ? '✓' : '✗'}
          </span>
          <span>{truncateText(ev.content ?? '', 300)}</span>
        </>
      )}
      {ev.type === 'assistant_message' && (
        <span>{`— промежуточный ответ: ${truncateText(ev.text ?? '', 300)}`}</span>
      )}
      {ev.type === 'final' && (
        <span>{`— итог (${ev.reason ?? 'без причины'}): ${truncateText(ev.text ?? '', 300)}`}</span>
      )}
    </div>
  );
}

export default function AgentTrace({ events, live = false }: AgentTraceProps) {
  const traceCls = live ? `${styles.trace} ${styles.live}` : styles.trace;
  return (
    <details className={traceCls} open={live}>
      <summary>
        {live && !events.length
          ? 'Ход работы агента · идёт работа'
          : `Ход работы агента · ${events.length} ${eventsWord(events.length)}`}
      </summary>
      <div className={styles.body}>
        {events.map((ev, i) => <AgentEventRow key={i} ev={ev} />)}
      </div>
    </details>
  );
}
