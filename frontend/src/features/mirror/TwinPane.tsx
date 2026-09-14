/* ============================================================
   Колонка сравнения — порт static/index.html L286–313 и script.js
   L2228–2465 (mirrorState стороны, buildMirrorCard, applyTwinResponse,
   settleTwin). Внутреннее состояние близнеца — ЛОКАЛЬНОЕ (useState/
   useRef в компоненте; spec «Состояние»: стором не является), ключ —
   twinId стороны. Никакого общего «latest»: записи адресуются своей
   колонке, устаревшие обмены гвардятся номером обмена (twinId+seq).
   ============================================================ */

import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ChatResponse } from '../../api/types';
import { fmtElapsed, fmtRate, nowStamp } from '../../lib/format';
import { highlightCodeBlocks, renderMarkdown } from '../../lib/markdown';
import { MIRROR_STRATEGIES, type MirrorStrategy } from '../../stores/settings';
import { useSettingsStore } from '../../stores/settings';
import { registerTwin, unregisterTwin } from './twinRegistry';
import {
  EMPTY_TWIN_TOTALS,
  MIRROR_STRATEGY_LABELS,
  mirrorTokensOrNd,
  sideStrategy,
  twinTotalsText,
  type TwinSide,
  type TwinTotals,
} from './twin';
import entryStyles from '../chat/Entry.module.css';
import styles from './TwinPane.module.css';

/** Часть шапки карточки — как в buildEntryHead (fuel — зелёные цифры). */
interface HeadPart {
  text: string;
  fuel?: boolean;
}

/** Карточка одной передачи: ответ или отказ, отказ не выходит за сторону. */
interface TwinCardData {
  id: number;
  fault: boolean;
  /** Отказ: текст и штамп сборки карточки (buildEntryHead). */
  message?: string;
  time?: string;
  /** Ответ: шапка с показаниями, бейдж сводки, разметка ответа. */
  headParts?: HeadPart[];
  summaryApplied?: boolean;
  replyHtml?: string;
}

/** Карточка ответа — порт buildMirrorCard L2276–2303: шапка «Ответ»
    со временем, скоростью и расшифровкой топлива со статьёй сводки. */
function buildResponseCard(id: number, data: ChatResponse, strategy: MirrorStrategy): TwinCardData {
  const parts: HeadPart[] = [{ text: nowStamp() }];
  if (data.elapsedMs != null) {
    const bits = [fmtElapsed(data.elapsedMs)];
    const rate = fmtRate({ elapsedMs: data.elapsedMs, completionTokens: data.completionTokens });
    if (rate) bits.push(rate);
    parts.push({ fuel: true, text: bits.join(' · ') });
  }
  let fuelText = `Топливо: запрос ${mirrorTokensOrNd(data.promptTokens)}`
    + ` + ответ ${mirrorTokensOrNd(data.completionTokens)}`
    + ` = ${mirrorTokensOrNd(data.totalTokens)}`;
  if (data.summaryApplied && data.summaryTokens != null) {
    fuelText += ` (+сводка: ${data.summaryTokens})`;
  } else if (strategy === 'summary' && !data.summaryApplied) {
    fuelText += ' · сводка включена, сводки пока не было';
  }
  parts.push({ fuel: true, text: fuelText });

  return {
    id,
    fault: false,
    headParts: parts,
    summaryApplied: data.summaryApplied === true,
    replyHtml: renderMarkdown(data.reply || ''),
  };
}

/** Карточка передачи (ответ или отказ) — переиспользует классы записи
    журнала (Entry.module.css); колодец ответа утоплен в колонку. */
function MirrorCard({ card }: { card: TwinCardData }) {
  const bubbleRef = useRef<HTMLDivElement | null>(null);

  /* Подсветка кодовых колодцев после вставки разметки (L2300–2301) */
  useEffect(() => {
    if (!card.fault && bubbleRef.current) highlightCodeBlocks(bubbleRef.current);
  }, [card]);

  if (card.fault) {
    return (
      <article className={`${styles.card} ${styles.fault}`}>
        <div className={entryStyles.entryHead}>
          <span className={`${entryStyles.entryTag} ${styles.tag}`}>Отказ</span>
          <span className={entryStyles.entrySep}>·</span>
          <span>{card.time}</span>
        </div>
        <p className={styles.error}>{card.message}</p>
      </article>
    );
  }

  return (
    <article className={styles.card}>
      <div className={`${entryStyles.entryHead} ${styles.cardHead}`}>
        <span className={entryStyles.entryTag}>Ответ</span>
        {card.headParts?.map((part, i) => (
          <Fragment key={i}>
            <span className={entryStyles.entrySep}>·</span>
            <span className={part.fuel ? entryStyles.fuelDigits : undefined}>{part.text}</span>
          </Fragment>
        ))}
      </div>
      {card.summaryApplied ? (
        <span className={`${entryStyles.summaryBadge} ${styles.badge}`}>Сводка применена</span>
      ) : null}
      <div
        ref={bubbleRef}
        className={`${entryStyles.bubble} ${entryStyles.md} ${styles.well}`}
        dangerouslySetInnerHTML={{ __html: card.replyHtml ?? '' }}
      />
    </article>
  );
}

export interface TwinPaneProps {
  side: TwinSide;
  /** Пересчёт итоговой строки сравнения после осевшего ответа. */
  onSettle?: () => void;
}

export default function TwinPane({ side, onSettle }: TwinPaneProps) {
  const [cards, setCards] = useState<TwinCardData[]>([]);
  const [totals, setTotals] = useState<TwinTotals>(EMPTY_TWIN_TOTALS);
  const [started, setStarted] = useState(false);
  const totalsRef = useRef<TwinTotals>(EMPTY_TWIN_TOTALS);
  const sessionIdRef = useRef<string | null>(null);
  const exchangeSeqRef = useRef(0);
  const cardSeqRef = useRef(0);
  const bodyRef = useRef<HTMLDivElement | null>(null);

  /* Дописанная карточка уводит прокрутку колонки вниз — ваниль
     (applyTwinResponse L2357–2358, settleTwin L2341–2342) */
  useLayoutEffect(() => {
    const body = bodyRef.current;
    if (body) body.scrollTop = body.scrollHeight;
  }, [cards]);

  /* Стратегия и селект своей колонки: источник истины — настройки
     (персист); читаются заново перед каждым обменом (L2198–2199) */
  const strategy = useSettingsStore((s) =>
    side === 'full' ? s.mirrorStrategyFull : s.mirrorStrategyComp);
  const setStrategy = useSettingsStore((s) =>
    side === 'full' ? s.setMirrorStrategyFull : s.setMirrorStrategyComp);

  /* Регистрация своей колонки в реестре: конвейер отправки адресует
     обмены по twinId; демонтированная колонка реестр покидает */
  useEffect(() => {
    registerTwin(side, {
      beginExchange: () => {
        const seq = ++exchangeSeqRef.current;
        setStarted(true);
        /* Первая передача расчищает колонку с пустым стажем (L2472–2476) */
        if (totalsRef.current.exchanges === 0) setCards([]);
        return seq;
      },
      getSessionId: () => sessionIdRef.current,
      applyResponse: (seq, data, cardStrategy) => {
        if (exchangeSeqRef.current !== seq) return; // guard twinId+обмена
        if (data.sessionId) sessionIdRef.current = data.sessionId;
        const card = buildResponseCard(++cardSeqRef.current, data, cardStrategy);
        setCards((prev) => [...prev, card]);
        /* Токен, которого сервер не прислал, обнуляет накопленное в «н/д»
           (applyTwinResponse L2347–2360); отказ обменом не считается */
        const next: TwinTotals = {
          total: data.totalTokens != null
            ? (totalsRef.current.total != null ? totalsRef.current.total + data.totalTokens : null)
            : null,
          summaryTotal: totalsRef.current.summaryTotal + (data.summaryTokens ?? 0),
          exchanges: totalsRef.current.exchanges + 1,
        };
        totalsRef.current = next;
        setTotals(next);
        onSettle?.();
      },
      applyFault: (seq, message) => {
        if (exchangeSeqRef.current !== seq) return;
        setCards((prev) => [...prev, {
          id: ++cardSeqRef.current,
          fault: true,
          message,
          time: nowStamp(),
        }]);
      },
      totals: () => totalsRef.current,
    });
    return () => unregisterTwin(side);
  }, [side, onSettle]);

  const isFull = side === 'full';
  const strategyLabel = MIRROR_STRATEGY_LABELS[strategy];

  return (
    <section
      className={isFull ? styles.col : `${styles.col} ${styles.colCompressed}`}
      data-side={side}
      aria-label={isFull ? 'Левая колонка сравнения' : 'Правая колонка сравнения'}
    >
      <header className={styles.head}>
        <h3 className={styles.title}>{strategyLabel}</h3>
        <select
          className={styles.select}
          autoComplete="off"
          aria-label={isFull ? 'Стратегия памяти левой колонки' : 'Стратегия памяти правой колонки'}
          value={strategy}
          onChange={(e) => setStrategy(sideStrategy(e.target.value))}
        >
          {MIRROR_STRATEGIES.map((value) => (
            <option key={value} value={value}>{MIRROR_STRATEGY_LABELS[value]}</option>
          ))}
        </select>
        <span className={styles.total}>{twinTotalsText(totals)}</span>
      </header>
      <div ref={bodyRef} className={styles.body}>
        {cards.length === 0 && !started ? (
          <p className={styles.emptyNote}>Передач ещё не было</p>
        ) : cards.map((card) => <MirrorCard key={card.id} card={card} />)}
      </div>
    </section>
  );
}
