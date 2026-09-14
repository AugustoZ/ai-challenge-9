/* ============================================================
   Чистая логика зеркала — порт точек принятия решений из
   static/script.js (стороны и стратегии L2198–2209, показания
   L2228–2345, фолбэк twin-потока L2384–2465, пара обменов
   L2467–2493). Состояния не держит — только считает; покрыто
   Vitest (волна 6: стратегия стороны, решение о фолбэке, итоги).
   ============================================================ */

import type { ChatRequest } from '../../api/types';
import type { MirrorStrategy } from '../../stores/settings';

/** Сторона зеркала = twinId: у каждой колонки своё состояние в TwinPane. */
export type TwinSide = 'full' | 'compressed';

export const MIRROR_SIDES: readonly TwinSide[] = ['full', 'compressed'];

/** Подписи стратегий колонок зеркала — script.js L54–59 («Ветки» не участвует). */
export const MIRROR_STRATEGY_LABELS: Record<MirrorStrategy, string> = {
  off: 'Выкл',
  window: 'Окно',
  summary: 'Сводка',
  facts: 'Факты',
};

/** Стратегия стороны на текущий обмен; неизвестное значение селекта — «Выкл»
    (script.js L2207–2209: sideStrategy). */
export function sideStrategy(value: string): MirrorStrategy {
  return value in MIRROR_STRATEGY_LABELS ? (value as MirrorStrategy) : 'off';
}

/** Токен, которого сервер не прислал, — не ноль: честное «н/д» (script.js L2238–2240). */
export function mirrorTokensOrNd(value: number | null | undefined): string {
  return value == null ? 'н/д' : String(value);
}

/** Накопленные показания одной стороны (script.js L2232–2235: mirrorState[side]). */
export interface TwinTotals {
  /** Сумма токенов; null после обмена без токенов — «н/д», а не ноль. */
  total: number | null;
  /** Суммарная статья «сводка» за все передачи. */
  summaryTotal: number;
  /** Число осевших ответов (отказ обменом не считается — L2336–2337). */
  exchanges: number;
}

export const EMPTY_TWIN_TOTALS: TwinTotals = { total: 0, summaryTotal: 0, exchanges: 0 };

/** Строка «Итог: …» в шапке колонки (renderMirrorTotals, script.js L2310–2312). */
export function twinTotalsText(totals: TwinTotals): string {
  return totals.exchanges
    ? `Итог: ${mirrorTokensOrNd(totals.total)} ток`
    : 'Итог: —';
}

/** Итоговая строка сравнения под сеткой (renderMirrorTotals, script.js L2315–2333). */
export function mirrorVerdictText(
  full: TwinTotals,
  comp: TwinTotals,
  fullLabel: string,
  compLabel: string,
): string {
  if (!full.exchanges && !comp.exchanges) {
    return 'Сравнение появится после первой передачи';
  }
  const saving = full.total != null && comp.total != null && full.total > 0
    ? `экономия: ${Math.round(((full.total - comp.total) / full.total) * 100)}%`
    : 'экономия: н/д';
  const summaryPart = !comp.exchanges
    ? '(из них сводка: н/д)'
    : comp.summaryTotal > 0
      ? `(из них сводка: ${comp.summaryTotal})`
      : '(сводка ещё не применялась)';
  return `${fullLabel}: ${mirrorTokensOrNd(full.total)} ток`
    + ` · ${compLabel}: ${mirrorTokensOrNd(comp.total)} ток`
    + ` ${summaryPart}`
    + ` · ${saving}`;
}

/**
 * Twin-запрос из общего: свой sessionId и стратегия колонки, судья выключен
 * (script.js L2478–2484). branchId из основного запроса не переходит — у
 * зеркала ветвление подавлено (в ваниле commonRequest его просто не имеет).
 */
export function buildTwinRequest(
  base: ChatRequest,
  sessionId: string | null,
  strategy: MirrorStrategy,
): ChatRequest {
  const request: ChatRequest = {
    ...base,
    sessionId,
    judgeEnabled: false,
    contextStrategy: strategy,
  };
  delete request.branchId;
  return request;
}

/** Результат одной стороны обмена: fault-семантика ванильного settleTwin
    (script.js L2336–2345) — отказ оседает карточкой и наружу не выходит. */
export interface TwinOutcome {
  fault: boolean;
  message?: string;
}

/**
 * Решение после завершения чтения twin-потока (sendTwinStream,
 * script.js L2453–2455): есть исход — он оседает; ни одного события
 * (поток недоступен или пуст) — блокирующий фолбэк; события были без
 * итога — честный отказ колонки.
 */
export function twinStreamDecision(
  result: { ok: boolean },
  hasOutcome: boolean,
  gotEvents: boolean,
): 'settle' | 'blocking' | 'no-final' {
  if (hasOutcome) return 'settle';
  if (!result.ok || !gotEvents) return 'blocking';
  return 'no-final';
}

/**
 * Решение при ошибке чтения twin-потока (catch, script.js L2456–2463):
 * AbortError — карточка остановки; обрыв после событий — отказ;
 * обрыв до событий — блокирующий фолбэк.
 */
export function twinErrorDecision(isAbort: boolean, gotEvents: boolean): 'stop' | 'fault' | 'blocking' {
  if (isAbort) return 'stop';
  return gotEvents ? 'fault' : 'blocking';
}
