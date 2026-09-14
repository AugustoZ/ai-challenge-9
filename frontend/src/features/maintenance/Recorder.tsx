/* ============================================================
   Самописец — порт static/index.html L344–365 (#recorderOverlay)
   и script.js L1268–1405 (buildRecorderEntry, loadRecorder,
   openRecorder/closeRecorder). Тело модалки — общий Overlay
   (клавиша «Обновить» — его API); свежие записи сверху; ответ
   после закрытия игнорируется (ванильный guard recorderOpen).
   ============================================================ */

import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { getJson } from '../../api/client';
import type { ExchangeInfo, ExchangeLogResponse } from '../../api/types';
import Overlay from '../../components/Overlay';
import ExpandToggle from '../chat/ExpandToggle';
import expandableStyles from '../chat/ExpandToggle.module.css';
import { exchangeTime, fmtSize, prettyJson } from '../../lib/format';
import { highlightCodeBlocks } from '../../lib/markdown';
import styles from './Recorder.module.css';

export interface RecorderProps {
  open: boolean;
  onClose: () => void;
}

type RecorderState =
  | { phase: 'loading' }
  | { phase: 'empty' }
  | { phase: 'ready'; entries: ExchangeInfo[] }
  | { phase: 'fault'; message: string };

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** Чтение протокола (loadRecorder L1363–1380): state пишется только из
    колбэков промиса; ответ после закрытия игнорируется (guard L1368). */
function readProtocol(setState: (next: RecorderState) => void, isOpen: () => boolean): Promise<void> {
  return getJson<ExchangeLogResponse>('log')
    .then((data) => {
      if (!isOpen()) return;
      const entries = Array.isArray(data?.entries) ? data.entries : [];
      setState(entries.length ? { phase: 'ready', entries } : { phase: 'empty' });
    })
    .catch((err: unknown) => {
      if (!isOpen()) return;
      setState({ phase: 'fault', message: 'Не удалось прочитать протокол: ' + errorMessage(err) });
    });
}

/** Одна запись протокола: details с шапкой и JSON-колодцем
    (buildRecorderEntry L1326–1354 + buildPayloadBlock L1276–1289). */
function RecorderEntry({ entry }: { entry: ExchangeInfo }) {
  const bodyRef = useRef<HTMLPreElement | null>(null);
  const bodyId = useId();

  /* Подсветка JSON-колодца после вставки (buildPayloadBlock L1285–1287) */
  useEffect(() => {
    if (bodyRef.current) highlightCodeBlocks(bodyRef.current);
  }, [entry.payload]);

  const fault = entry.ok === false;
  const meta = [fmtSize(entry.payload), entry.ms != null ? `${entry.ms} мс` : null]
    .filter((bit): bit is string => bit != null)
    .join(' · ');

  return (
    <details
      className={`${styles.recEntry} ${entry.dir === 'req' ? styles.req : styles.res}${fault ? ` ${styles.faultEntry}` : ''}`}
    >
      <summary className={styles.recHead}>
        <span className={styles.recTime}>{exchangeTime(entry.ts)}</span>
        <span className={styles.recDir}>{entry.dir === 'req' ? '→ запрос' : '← ответ'}</span>
        <span className={styles.recMeta}>{meta}</span>
      </summary>
      <div className={styles.recBody}>
        <ExpandToggle target={bodyRef} controlsId={bodyId} />
        <pre ref={bodyRef} id={bodyId} className={`${styles.jsonView} ${expandableStyles.expandable}`}>
          <code className="language-json">{prettyJson(entry.payload)}</code>
        </pre>
      </div>
    </details>
  );
}

export default function Recorder({ open, onClose }: RecorderProps) {
  const [state, setState] = useState<RecorderState>({ phase: 'loading' });
  const openRef = useRef(false);

  /* Открытие самописца (openRecorder L1382–1387): протокол перечитывается
     с чистого листа — подстройка состояния при смене prop (паттерн React) */
  const [prevOpen, setPrevOpen] = useState(open);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) setState({ phase: 'loading' });
  }

  /* Стартовое чтение при открытии (openRecorder → loadRecorder, L1386) */
  useEffect(() => {
    openRef.current = open;
    if (open) void readProtocol(setState, () => openRef.current);
  }, [open]);

  const refresh = useCallback(() => {
    setState({ phase: 'loading' }); // «Читаю протокол…» (L1364)
    void readProtocol(setState, () => openRef.current);
  }, []);

  return (
    <Overlay
      open={open}
      onClose={onClose}
      title="Самописец"
      subtitle="протокол обмена с моделью"
      closeLabel="Закрыть самописец"
      id="recorderOverlay"
      refreshLabel="Обновить"
      onRefresh={refresh}
    >
      {state.phase === 'loading' && <p className={styles.note}>Читаю протокол…</p>}
      {state.phase === 'empty' && (
        <p className={styles.note}>Протокол пуст — обмен с моделью ещё не выполнялся</p>
      )}
      {state.phase === 'fault' && <p className={`${styles.note} ${styles.faultNote}`}>{state.message}</p>}
      {state.phase === 'ready' && state.entries.slice().reverse().map((entry, i) => (
        <RecorderEntry key={`${entry.seq}-${i}`} entry={entry} />
      ))}
    </Overlay>
  );
}
