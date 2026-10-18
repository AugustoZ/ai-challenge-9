/* Тесты чистой логики зеркала (волна 6): стратегия стороны twin-запроса,
   решение о блокирующем фолбэке twin-потока, показания и итоговая строка
   сравнения — порт script.js L2200–2493. */

import { describe, expect, it } from 'vitest';
import {
  buildTwinRequest,
  mirrorTokensOrNd,
  mirrorVerdictText,
  sideStrategy,
  twinErrorDecision,
  twinStreamDecision,
  twinTotalsText,
  type TwinTotals,
} from './twin';
import { EMPTY_TWIN_TOTALS } from './twin';

const BASE_REQUEST = {
  message: 'проверка',
  sessionId: 'main-session',
  judgeEnabled: true,
  contextStrategy: 'window',
  branchId: 'b3',
};

describe('sideStrategy — стратегия стороны на обмен (script.js L2207–2209)', () => {
  it('значения селекта колонки проходят как есть', () => {
    expect(sideStrategy('off')).toBe('off');
    expect(sideStrategy('window')).toBe('window');
    expect(sideStrategy('summary')).toBe('summary');
    expect(sideStrategy('facts')).toBe('facts');
  });

  it('«Ветки» и прочие неизвестные значения трактуются как «Выкл»', () => {
    expect(sideStrategy('branching')).toBe('off');
    expect(sideStrategy('')).toBe('off');
    expect(sideStrategy('unknown')).toBe('off');
  });
});

describe('buildTwinRequest — twin-запрос из общего (script.js L2478–2484)', () => {
  it('несёт sessionId колонки и её стратегию, судья выключен', () => {
    const request = buildTwinRequest(BASE_REQUEST, 'twin-session', 'summary');
    expect(request.sessionId).toBe('twin-session');
    expect(request.contextStrategy).toBe('summary');
    expect(request.judgeEnabled).toBe(false);
    expect(request.message).toBe('проверка');
  });

  it('без twin-сессии sessionId null — сервер создаст новую', () => {
    expect(buildTwinRequest(BASE_REQUEST, null, 'off').sessionId).toBeNull();
  });

  it('branchId основного запроса не переходит — ветвление в зеркале подавлено', () => {
    expect(buildTwinRequest(BASE_REQUEST, 'twin', 'facts').branchId).toBeUndefined();
  });
});

describe('twinStreamDecision — решение после чтения twin-потока (L2453–2455)', () => {
  it('есть исход — он оседает в колонке', () => {
    expect(twinStreamDecision({ ok: true }, true, true)).toBe('settle');
    expect(twinStreamDecision({ ok: true }, true, false)).toBe('settle');
  });

  it('поток недоступен или ни одного события — блокирующий фолбэк', () => {
    expect(twinStreamDecision({ ok: false }, false, true)).toBe('blocking');
    expect(twinStreamDecision({ ok: true }, false, false)).toBe('blocking');
    expect(twinStreamDecision({ ok: false }, false, false)).toBe('blocking');
  });

  it('события были без итога — честный отказ колонки', () => {
    expect(twinStreamDecision({ ok: true }, false, true)).toBe('no-final');
  });
});

describe('twinErrorDecision — решение при ошибке чтения (L2456–2463)', () => {
  it('AbortError («Стоп») — карточка остановки', () => {
    expect(twinErrorDecision(true, true)).toBe('stop');
    expect(twinErrorDecision(true, false)).toBe('stop');
  });

  it('обрыв после событий — отказ, до событий — блокирующий фолбэк', () => {
    expect(twinErrorDecision(false, true)).toBe('fault');
    expect(twinErrorDecision(false, false)).toBe('blocking');
  });
});

describe('показания сторон (script.js L2238–2333)', () => {
  it('токен, которого сервер не прислал, — «н/д», а не ноль', () => {
    expect(mirrorTokensOrNd(null)).toBe('н/д');
    expect(mirrorTokensOrNd(undefined)).toBe('н/д');
    expect(mirrorTokensOrNd(0)).toBe('0');
    expect(mirrorTokensOrNd(42)).toBe('42');
  });

  it('итог колонки: без обменов «Итог: —», с обменами — сумма', () => {
    expect(twinTotalsText(EMPTY_TWIN_TOTALS)).toBe('Итог: —');
    expect(twinTotalsText({ total: 128, summaryTotal: 0, exchanges: 2 })).toBe('Итог: 128 ток');
    expect(twinTotalsText({ total: null, summaryTotal: 0, exchanges: 1 })).toBe('Итог: н/д ток');
  });

  it('без обменов обеих сторон итоговая строка обещает сравнение', () => {
    expect(mirrorVerdictText(EMPTY_TWIN_TOTALS, EMPTY_TWIN_TOTALS, 'Выкл', 'Сводка'))
      .toBe('Сравнение появится после первой передачи');
  });

  it('итоговая строка: обе стороны, статья сводки и экономия', () => {
    const full: TwinTotals = { total: 400, summaryTotal: 0, exchanges: 2 };
    const comp: TwinTotals = { total: 100, summaryTotal: 30, exchanges: 2 };
    expect(mirrorVerdictText(full, comp, 'Выкл', 'Сводка'))
      .toBe('Выкл: 400 ток · Сводка: 100 ток (из них сводка: 30) · экономия: 75%');
  });

  it('правая колонка молчит — статья сводки «н/д»; нулевой расход — экономия «н/д»', () => {
    const full: TwinTotals = { total: 400, summaryTotal: 0, exchanges: 2 };
    /* comp.total = 0 — не «н/д»: ваниль считает экономию от нуля (100%) */
    expect(mirrorVerdictText(full, EMPTY_TWIN_TOTALS, 'Выкл', 'Сводка'))
      .toBe('Выкл: 400 ток · Сводка: 0 ток (из них сводка: н/д) · экономия: 100%');
    expect(mirrorVerdictText({ total: 0, summaryTotal: 0, exchanges: 1 }, { total: 0, summaryTotal: 0, exchanges: 1 }, 'Выкл', 'Сводка'))
      .toBe('Выкл: 0 ток · Сводка: 0 ток (сводка ещё не применялась) · экономия: н/д');
  });

  it('сводка ещё не применялась — честная оговорка', () => {
    const full: TwinTotals = { total: 300, summaryTotal: 0, exchanges: 3 };
    const comp: TwinTotals = { total: 150, summaryTotal: 0, exchanges: 3 };
    expect(mirrorVerdictText(full, comp, 'Выкл', 'Сводка'))
      .toBe('Выкл: 300 ток · Сводка: 150 ток (сводка ещё не применялась) · экономия: 50%');
  });
});
