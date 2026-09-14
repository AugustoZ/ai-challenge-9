/* ============================================================
   Запись бортового журнала — порт 1:1 из static/script.js
   L136–168 (buildEntryHead, buildSummaryBadge), L232–250
   (addMessage), L252–317 (addBotMessage), L334–352
   (addSystemMessage) и L586–600 (showTyping — запись-заглушка
   с лампами на время потока, волна 5). Презентационная: штампы
   и показания приходят готовыми строками/числами через props.
   Порядок содержимого записи — как в ваниле: шапка → бейдж
   сводки → пузырь → трасса агента → заключение судьи.
   ============================================================ */

import { Fragment, useEffect, useMemo, useRef } from 'react';
import type { AgentEvent, JudgeReport } from '../../api/types';
import { fmtElapsed, fmtRate } from '../../lib/format';
import { highlightCodeBlocks, renderMarkdown } from '../../lib/markdown';
import AgentTrace from '../trace/AgentTrace';
import JudgeReportComponent from '../judge/JudgeReport';
import styles from './Entry.module.css';

/** Приборные показания ответа (поля ChatResponse, script.js L259–274). */
export interface EntryTokenData {
  elapsedMs?: number | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  summaryApplied?: boolean;
  summaryTokens?: number | null;
}

export interface JournalEntry {
  /** Стабильный ключ React (список дописывается — id не переиспользуются). */
  id: string;
  kind: 'user' | 'bot' | 'system';
  /** Текст записи: у user/system — сырой, у bot — markdown. */
  text: string;
  /** Шапка записи (buildEntryHead/addBotMessage): метка режима, штамп
      «ЧЧ:ММ» — caller вычисляет nowStamp(), — и показания топлива. */
  meta?: {
    modeLabel?: string;
    time: string;
    tokenData?: EntryTokenData;
  };
  /** Протокол агентного цикла — трасса под ответом. */
  events?: AgentEvent[];
  /** Заключение судьи под ответом. */
  judge?: JudgeReport;
  /**
   * Индикатор обработки (script.js showTyping L586–600): запись-заглушка
   * на время потока — три мигающие лампы вместо пузыря, под ними строка
   * состояния потока (setStreamStatus L607–610) и живая трасса агента
   * (createLiveTrace L559–582). Пустой statusText не рисуется — как
   * `.typing-status:empty` в ваниле.
   */
  typing?: { statusText: string };
}

/** Теги шапки по видам записей (script.js L239, L275, L341). */
const ENTRY_TAGS: Record<JournalEntry['kind'], string> = {
  user: 'Запрос',
  bot: 'Ответ',
  system: 'Служебное',
};

interface HeadPart {
  text: string;
  /** Зелёные приборные цифры (fuel-digits). */
  fuel?: boolean;
}

/* Части шапки ответа — порт addBotMessage L259–275: метка режима, время,
   время+скорость генерации, расшифровка топлива со статьёй сводки. */
function headParts(entry: JournalEntry): HeadPart[] {
  const parts: HeadPart[] = [];
  const meta = entry.meta;
  if (meta?.modeLabel) parts.push({ text: meta.modeLabel });
  if (meta) parts.push({ text: meta.time });

  const td = meta?.tokenData;
  if (td && td.elapsedMs != null) {
    const bits = [fmtElapsed(td.elapsedMs)];
    const rate = fmtRate({ elapsedMs: td.elapsedMs, completionTokens: td.completionTokens });
    if (rate) bits.push(rate);
    parts.push({ fuel: true, text: bits.join(' · ') });
  }
  if (td && td.totalTokens != null) {
    let fuelText = `Топливо: запрос ${td.promptTokens ?? 0} + ответ ${td.completionTokens ?? 0} = ${td.totalTokens}`;
    if (td.summaryApplied && td.summaryTokens != null) {
      fuelText += ` (+сводка: ${td.summaryTokens})`;
    }
    parts.push({ fuel: true, text: fuelText });
  }
  return parts;
}

export default function Entry({ entry }: { entry: JournalEntry }) {
  const bubbleRef = useRef<HTMLDivElement | null>(null);

  /* У user/system пузырь — сырой текст (в ваниле textContent = экранирование
     браузером; JSX-подстановка даёт то же), у bot — markdown. */
  const isPlain = entry.kind !== 'bot';
  const html = useMemo(
    () => (isPlain ? '' : renderMarkdown(entry.text)),
    [isPlain, entry.text],
  );

  /* Подсветка кодовых колодцев после вставки разметки (script.js L283–284) */
  useEffect(() => {
    if (isPlain) return;
    if (bubbleRef.current) highlightCodeBlocks(bubbleRef.current);
  }, [isPlain, html]);

  /* Индикатор обработки (script.js showTyping L586–600): лампы вместо
     пузыря, под ними строка состояния потока и живая трасса агента.
     Живая трасса заводится лениво — по первому событию агента
     (ensureLiveTrace L2629–2635), поэтому без событий её нет. */
  if (entry.typing) {
    return (
      <div className={`${styles.message} ${styles.bot}`}>
        <div className={styles.entry}>
          <div className={styles.typing} role="status" aria-label="Идёт обработка">
            <span />
            <span />
            <span />
          </div>
          {entry.typing.statusText ? (
            <p className={styles.typingStatus}>{entry.typing.statusText}</p>
          ) : null}
          {entry.events && entry.events.length ? (
            <AgentTrace events={entry.events} live />
          ) : null}
        </div>
      </div>
    );
  }

  const kindClass = entry.kind === 'user' ? styles.user : styles.bot;
  const head = headParts(entry);

  return (
    <div className={`${styles.message} ${kindClass}`}>
      <div className={styles.entry}>
        <div className={styles.entryHead}>
          <span className={styles.entryTag}>{ENTRY_TAGS[entry.kind]}</span>
          {head.map((part, i) => (
            <Fragment key={i}>
              <span className={styles.entrySep}>·</span>
              <span className={part.fuel ? styles.fuelDigits : undefined}>{part.text}</span>
            </Fragment>
          ))}
        </div>

        {entry.meta?.tokenData?.summaryApplied ? (
          <span className={styles.summaryBadge}>Сводка применена</span>
        ) : null}

        {isPlain ? (
          <div className={entry.kind === 'system'
            ? `${styles.bubble} ${styles.system}`
            : styles.bubble}
          >
            {entry.text}
          </div>
        ) : (
          <div
            ref={bubbleRef}
            className={`${styles.bubble} ${styles.md}`}
            dangerouslySetInnerHTML={{ __html: html }}
          />
        )}

        {entry.events && entry.events.length ? (
          <AgentTrace events={entry.events} />
        ) : null}

        {entry.judge ? <JudgeReportComponent judge={entry.judge} /> : null}
      </div>
    </div>
  );
}
