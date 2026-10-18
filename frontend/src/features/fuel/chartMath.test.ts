/* Тесты геометрии графиков топливомера (порт script.js L1459–1594). */

import { describe, expect, it } from 'vitest';
import {
  axisTicks,
  PRICE_CHART_BOX,
  priceChartModel,
  TOKEN_CHART_BOX,
  tokenChartModel,
} from './chartMath';
import { TOKEN_TARIFF } from './scale';
import type { TokenRecord } from './chartMath';

const HISTORY: TokenRecord[] = [
  { promptTokens: 612, completionTokens: 118, totalTokens: 730 },
  { promptTokens: 840, completionTokens: 204, totalTokens: 1044 },
  { promptTokens: 730, completionTokens: 96, totalTokens: 826 },
];

describe('axisTicks', () => {
  it('линии сетки снизу вверх, подписи круглые', () => {
    const bottom = 10 + 178;
    const ticks = axisTicks(bottom, 178, 6000, 3);
    expect(ticks.map((t) => t.value)).toEqual([2000, 4000, 6000]);
    expect(ticks.map((t) => t.y)).toEqual([
      bottom - 178 / 3,
      bottom - (178 * 2) / 3,
      bottom - 178,
    ]);
  });

  it('одна линия — только верхняя граница', () => {
    const ticks = axisTicks(188, 100, 500, 1);
    expect(ticks).toEqual([{ y: 88, value: 500 }]);
  });
});

describe('tokenChartModel', () => {
  it('размеры холста — как в buildTokenChart', () => {
    expect(TOKEN_CHART_BOX).toEqual({ W: 560, H: 210, L: 44, R: 6, T: 10, B: 22 });
  });

  it('потолок шкалы по максимуму суммы, столбцы в пазах', () => {
    const model = tokenChartModel(HISTORY);
    expect(model.max).toBe(2000);
    expect(model.lines).toBe(4);
    expect(model.bars).toHaveLength(3);

    const plotW = 560 - 44 - 6;
    const slot = plotW / 3;
    expect(model.bars[0].cx).toBeCloseTo(44 + slot / 2, 6);
    expect(model.bars[1].cx).toBeCloseTo(44 + slot * 1.5, 6);
    expect(model.bars[2].label).toBe(3);
  });

  it('ширина столбца зажата [1.5..34], подписи прореживаются при тесноте', () => {
    const wide = tokenChartModel(HISTORY);
    expect(wide.bars[0].barW).toBeLessThanOrEqual(34);
    expect(wide.bars.every((b) => b.tick)).toBe(true);

    const tight = tokenChartModel(Array.from({ length: 60 }, (_, i) => ({
      promptTokens: 100 + i, completionTokens: 50, totalTokens: 150 + i,
    })));
    const slotTight = (560 - 44 - 6) / 60;
    expect(slotTight).toBeLessThan(16);
    expect(tight.bars[0].tick).toBe(true);
    expect(tight.bars[1].tick).toBe(false);
  });

  it('нулевая половина не рисуется, ненулевая — минимум 1', () => {
    const model = tokenChartModel([
      { promptTokens: 0, completionTokens: 0, totalTokens: 0 },
      { promptTokens: 500, completionTokens: 0, totalTokens: 500 },
    ]);
    expect(model.bars[0].promptH).toBe(0);
    expect(model.bars[0].completionH).toBe(0);
    expect(model.bars[1].promptH).toBeGreaterThanOrEqual(1);
    expect(model.bars[1].completionH).toBe(0);
  });
});

describe('priceChartModel', () => {
  it('размеры холста — как в buildPriceChart (лево шире)', () => {
    expect(PRICE_CHART_BOX.L).toBe(56);
    expect(PRICE_CHART_BOX).toEqual({ W: 560, H: 210, L: 56, R: 6, T: 10, B: 22 });
  });

  it('накопленная стоимость по тарифу', () => {
    const model = priceChartModel(HISTORY, TOKEN_TARIFF);
    const t = TOKEN_TARIFF;
    expect(model.points.map((p) => p.cost)).toEqual([
      730 * t,
      (730 + 1044) * t,
      (730 + 1044 + 826) * t,
    ]);
  });

  it('один обмен — точка в центре', () => {
    const model = priceChartModel([{ promptTokens: 10, completionTokens: 5, totalTokens: 15 }], TOKEN_TARIFF);
    expect(model.points).toHaveLength(1);
    expect(model.points[0].x).toBeCloseTo(56 + (560 - 56 - 6) / 2, 6);
    /* 15 токенов = 5.4e-6$; потолок niceScale — 6e-6, точка на 90% высоты */
    expect(model.points[0].y).toBeCloseTo(model.bottom - model.plotH * 0.9, 6);
  });

  it('несколько обменов — плечи по всей ширине оси', () => {
    const model = priceChartModel(HISTORY, TOKEN_TARIFF);
    const plotW = 560 - 56 - 6;
    const step = plotW / 2;
    expect(model.points[0].x).toBeCloseTo(56, 6);
    expect(model.points[1].x).toBeCloseTo(56 + step, 6);
    expect(model.points[2].x).toBeCloseTo(56 + 2 * step, 6);
    expect(model.points[0].y).toBeCloseTo(model.bottom - (178 * model.points[0].cost) / model.max, 6);
  });
});
