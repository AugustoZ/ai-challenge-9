/* ============================================================
   Топливомер: геометрия графиков — чистые модели данных для SVG.
   Порт 1:1 из static/script.js L1459–1473 (buildFuelAxis),
   L1477–1547 (buildTokenChart), L1550–1594 (buildPriceChart).
   Компонент FuelMeter рисует по этим моделям теже атрибуты 1:1.
   ============================================================ */

import { fmtFuel } from '../../components/dialMath';
import { niceScale } from './scale';

/** Одна запись расхода: сырьё топливомера (script.js L74–76, L309–313). */
export interface TokenRecord {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

/* Геометрия холстов: buildTokenChart L1478 и buildPriceChart L1551 */
export const TOKEN_CHART_BOX = { W: 560, H: 210, L: 44, R: 6, T: 10, B: 22 } as const;
export const PRICE_CHART_BOX = { W: 560, H: 210, L: 56, R: 6, T: 10, B: 22 } as const;

export interface AxisTick {
  /** y линии сетки и подписи (подпись ниже линии на 3). */
  y: number;
  value: number;
}

/* Сетка и подписи оси Y — общая часть обоих графиков */
export function axisTicks(bottom: number, plotH: number, max: number, lines: number): AxisTick[] {
  const ticks: AxisTick[] = [];
  for (let i = 1; i <= lines; i++) {
    ticks.push({ y: bottom - (plotH * i) / lines, value: (max * i) / lines });
  }
  return ticks;
}

export interface TokenBar {
  /** Центр паза и левый край столбца. */
  cx: number;
  x: number;
  barW: number;
  /** Высоты половин составного столбца (0 — половина не рисуется). */
  promptH: number;
  completionH: number;
  /** Подпись номера обмена (прореженная). */
  tick: boolean;
  label: number;
}

export interface TokenChartModel {
  max: number;
  lines: number;
  bottom: number;
  plotH: number;
  bars: TokenBar[];
}

/* График расхода: составной столбец на каждый обмен — запрос (зелёный)
   снизу и ответ (янтарный) сверху; при многих обменах столбцы жмутся
   и мелеют, но не вылезают за ось; подписи номеров прореживаются */
export function tokenChartModel(history: TokenRecord[]): TokenChartModel {
  const { W, H, L, R, T, B } = TOKEN_CHART_BOX;
  const plotW = W - L - R;
  const plotH = H - T - B;
  const bottom = T + plotH;

  const totals = history.map((rec) => rec.totalTokens || 0);
  const { max, lines } = niceScale(Math.max(...totals));

  const slot = plotW / history.length;
  const barW = Math.max(1.5, Math.min(34, slot * 0.62));
  const showEvery = slot >= 16 ? 1 : Math.ceil(16 / slot);

  const bars: TokenBar[] = history.map((rec, i) => {
    const prompt = rec.promptTokens || 0;
    const completion = rec.completionTokens || 0;
    const cx = L + slot * i + slot / 2;
    const x = cx - barW / 2;
    const promptH = prompt ? Math.max(1, (plotH * prompt) / max) : 0;
    const completionH = completion ? Math.max(1, (plotH * completion) / max) : 0;
    return { cx, x, barW, promptH, completionH, tick: i % showEvery === 0, label: i + 1 };
  });

  return { max, lines, bottom, plotH, bars };
}

export interface PriceChartModel {
  max: number;
  lines: number;
  bottom: number;
  plotH: number;
  points: { x: number; y: number; cost: number }[];
}

/* График цены: накопленная стоимость сессии по обменам. Один обмен —
   точка в центре, несколько — плечи по всей ширине оси */
export function priceChartModel(history: TokenRecord[], tariff: number): PriceChartModel {
  const { W, H, L, R, T, B } = PRICE_CHART_BOX;
  const plotW = W - L - R;
  const plotH = H - T - B;
  const bottom = T + plotH;

  let running = 0;
  const costs = history.map((rec) => {
    running += (rec.totalTokens || 0) * tariff;
    return running;
  });
  const { max, lines } = niceScale(costs[costs.length - 1]);

  const step = plotW / Math.max(1, history.length - 1);
  const points = costs.map((cost, i) => ({
    x: history.length === 1 ? L + plotW / 2 : L + step * i,
    y: bottom - (plotH * cost) / max,
    cost,
  }));

  return { max, lines, bottom, plotH, points };
}

/* Формат оси расхода — компактные токены (fmtFuel из шкал) */
export { fmtFuel as tokenAxisLabel };
