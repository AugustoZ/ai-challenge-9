/* Тесты чистой математики топливомера (порт script.js L1407–1456). */

import { describe, expect, it } from 'vitest';
import { fmtMoney, niceScale, TOKEN_TARIFF } from './scale';

describe('niceScale', () => {
  it('нулевое и отрицательное значение — честный минимум', () => {
    expect(niceScale(0)).toEqual({ max: 1, lines: 3 });
    expect(niceScale(-5)).toEqual({ max: 1, lines: 3 });
    expect(niceScale(NaN)).toEqual({ max: 1, lines: 3 });
  });

  it('5542 → 6000 по 2000 (три линии)', () => {
    expect(niceScale(5542)).toEqual({ max: 6000, lines: 3 });
  });

  it('1200 → 2000 по 500 (четыре линии)', () => {
    expect(niceScale(1200)).toEqual({ max: 2000, lines: 4 });
  });

  it('значение в разряде укладывается в шаг 1', () => {
    expect(niceScale(1)).toEqual({ max: 1, lines: 4 });
  });

  it('шаг 3 даёт три линии', () => {
    expect(niceScale(3500)).toEqual({ max: 4000, lines: 4 });
    expect(niceScale(2.9)).toEqual({ max: 3, lines: 3 });
  });

  it('хвост 9 в разряде уходит в шаг 12', () => {
    expect(niceScale(900)).toEqual({ max: 1200, lines: 4 });
    expect(niceScale(850000)).toEqual({ max: 1200000, lines: 4 });
  });

  it('нормализованный хвост 1,2 — шаг 2', () => {
    expect(niceScale(120)).toEqual({ max: 200, lines: 4 });
    expect(niceScale(1.2)).toEqual({ max: 2, lines: 4 });
  });
});

describe('fmtMoney', () => {
  it('ноль и мусор — $0', () => {
    expect(fmtMoney(0)).toBe('$0');
    expect(fmtMoney(-3)).toBe('$0');
    expect(fmtMoney(NaN)).toBe('$0');
    expect(fmtMoney(Infinity)).toBe('$0');
  });

  it('доллары и копейки без хвостовых нулей', () => {
    expect(fmtMoney(2)).toBe('$2');
    expect(fmtMoney(1.5)).toBe('$1,5');
    expect(fmtMoney(1.234)).toBe('$1,23');
  });

  it('копеечные значения — три значащих цифры без экспоненты', () => {
    expect(fmtMoney(0.00212)).toBe('$0,00212');
    expect(fmtMoney(0.0001)).toBe('$0,0001');
  });

  it('sig = 2 — подписи оси цены', () => {
    expect(fmtMoney(0.00524, 2)).toBe('$0,0052');
  });

  it('тариф: копеечная цена за токен', () => {
    expect(TOKEN_TARIFF).toBe(0.00000036);
    expect(fmtMoney(730 * TOKEN_TARIFF)).toBe('$0,000263');
  });
});
