/* Тесты математики шкал (порт script.js L612–686). Чистая математика —
   jsdom не нужен. Границы зон включительны — сверяются с DIALS из script.js. */

import { describe, expect, it } from 'vitest';
import {
  DIALS,
  GAUGE_START,
  GAUGE_SWEEP,
  arcBetween,
  arcPath,
  clamp,
  fmtFuel,
  polar,
  tToValue,
  valueToT,
  zoneLevel,
} from './dialMath';

describe('константы геометрии шкал', () => {
  it('GAUGE_START=225, GAUGE_SWEEP=270 — контракт a11y, не менять', () => {
    expect(GAUGE_START).toBe(225);
    expect(GAUGE_SWEEP).toBe(270);
  });
});

describe('clamp', () => {
  it('значение внутри диапазона не меняется, снаружи — упирается в границу', () => {
    expect(clamp(1, 0, 2)).toBe(1);
    expect(clamp(-1, 0, 2)).toBe(0);
    expect(clamp(5, 0, 2)).toBe(2);
  });
});

describe('valueToT', () => {
  it('линейная шкала: мин → 0, макс → 1, середина по формуле', () => {
    const cfg = DIALS.temperature;
    expect(valueToT(cfg, 0)).toBe(0);
    expect(valueToT(cfg, 2)).toBe(1);
    expect(valueToT(cfg, 0.7)).toBeCloseTo(0.35, 12);
    expect(valueToT(DIALS.topP, 0.01)).toBe(0);
    expect(valueToT(DIALS.topP, 1)).toBe(1);
  });

  it('значение за границами зажимается', () => {
    expect(valueToT(DIALS.temperature, -5)).toBe(0);
    expect(valueToT(DIALS.temperature, 5)).toBe(1);
  });

  it('логарифмическая шкала топлива: 200 → 0, 1 024 000 → 1', () => {
    const cfg = DIALS.maxTokens;
    expect(valueToT(cfg, 200)).toBe(0);
    expect(valueToT(cfg, 1024000)).toBeCloseTo(1, 12);
  });
});

describe('tToValue', () => {
  it('линейная шкала: 0 → мин, 1 → макс, промежуточное — со привязкой к шагу', () => {
    const cfg = DIALS.temperature;
    expect(tToValue(cfg, 0)).toBe(0);
    expect(tToValue(cfg, 1)).toBe(2);
    expect(tToValue(cfg, 0.35)).toBeCloseTo(0.7, 10);
    expect(tToValue(cfg, 0.12345)).toBe(0.25); // 0.2469 → шаг 0.01
  });

  it('t за границами зажимается в [0, 1]', () => {
    expect(tToValue(DIALS.temperature, -1)).toBe(0);
    expect(tToValue(DIALS.temperature, 2)).toBe(2);
  });

  it('логарифмическая шкала: t=0.5 → корень из 5120 × 200, с привязкой к шагу 1', () => {
    const cfg = DIALS.maxTokens;
    const raw = 200 * Math.sqrt(5120); // ≈ 14310.85
    expect(tToValue(cfg, 0.5)).toBe(Math.round(raw));
  });
});

describe('туда-обратно valueToT → tToValue', () => {
  it('линейные шкалы: значения на сетке шага восстанавливаются точно', () => {
    for (const v of [0, 0.05, 0.5, 1, 1.95, 2]) {
      expect(tToValue(DIALS.temperature, valueToT(DIALS.temperature, v))).toBeCloseTo(v, 4);
    }
    for (const v of [0.01, 0.25, 0.5, 0.75, 1]) {
      expect(tToValue(DIALS.topP, valueToT(DIALS.topP, v))).toBeCloseTo(v, 4);
    }
  });

  it('логарифмическое топливо: характерные значения восстанавливаются', () => {
    const cfg = DIALS.maxTokens;
    for (const v of [200, 500, 1000, 10240, 100000, 1024000]) {
      expect(tToValue(cfg, valueToT(cfg, v))).toBeCloseTo(v, 2);
    }
  });
});

describe('zoneLevel — границы зон включительны', () => {
  it('температура: красный/янтарь/зелёный/янтарь/красный', () => {
    const cfg = DIALS.temperature;
    expect(zoneLevel(cfg, 0)).toBe('red');
    expect(zoneLevel(cfg, 0.25)).toBe('red');
    expect(zoneLevel(cfg, 0.26)).toBe('amber');
    expect(zoneLevel(cfg, 0.69)).toBe('amber');
    expect(zoneLevel(cfg, 0.7)).toBe('green');
    expect(zoneLevel(cfg, 1.3)).toBe('green');
    expect(zoneLevel(cfg, 1.31)).toBe('amber');
    expect(zoneLevel(cfg, 1.65)).toBe('amber');
    expect(zoneLevel(cfg, 1.66)).toBe('red');
    expect(zoneLevel(cfg, 2)).toBe('red');
  });

  it('щель между зонами — «green» по умолчанию (точные диапазоны из ТЗ)', () => {
    expect(zoneLevel(DIALS.temperature, 0.255)).toBe('green');
  });

  it('топливо: логарифмические зоны', () => {
    const cfg = DIALS.maxTokens;
    expect(zoneLevel(cfg, 200)).toBe('red');
    expect(zoneLevel(cfg, 500)).toBe('red');
    expect(zoneLevel(cfg, 501)).toBe('amber');
    expect(zoneLevel(cfg, 800)).toBe('amber');
    expect(zoneLevel(cfg, 801)).toBe('green');
    expect(zoneLevel(cfg, 99999)).toBe('green');
    expect(zoneLevel(cfg, 100000)).toBe('amber');
    expect(zoneLevel(cfg, 499999)).toBe('amber');
    expect(zoneLevel(cfg, 500000)).toBe('red');
    expect(zoneLevel(cfg, 1024000)).toBe('red');
  });

  it('Top P', () => {
    const cfg = DIALS.topP;
    expect(zoneLevel(cfg, 0.01)).toBe('red');
    expect(zoneLevel(cfg, 0.25)).toBe('red');
    expect(zoneLevel(cfg, 0.26)).toBe('amber');
    expect(zoneLevel(cfg, 0.5)).toBe('amber');
    expect(zoneLevel(cfg, 0.51)).toBe('green');
    expect(zoneLevel(cfg, 1)).toBe('green');
  });
});

describe('polar — «часовые» углы, 0° = 12:00', () => {
  it('кардиональные точки', () => {
    expect(polar(0, 10)).toEqual([50, 40]);
    expect(polar(90, 10)).toEqual([60, 50]);
    expect(polar(180, 10)).toEqual([50, 60]);
    expect(polar(270, 10)).toEqual([40, 50]);
  });
});

describe('arcBetween / arcPath', () => {
  it('короткая дуга — флаг large-arc 0', () => {
    const d = arcBetween(225, 315, 41);
    expect(d).toMatch(/^M -?\d+\.\d{2} -?\d+\.\d{2} A 41 41 0 0 1 /);
  });

  it('полный обхват 270° — флаг large-arc 1', () => {
    const d = arcBetween(GAUGE_START, GAUGE_START + GAUGE_SWEEP, 41);
    expect(d).toMatch(/^M -?\d+\.\d{2} -?\d+\.\d{2} A 41 41 0 1 1 /);
  });

  it('arcPath: радиус 41, начало — упор 225° (7:30)', () => {
    // polar(225, 41) = [21.01, 78.99]
    expect(arcPath(225)).toBe('M 21.01 78.99 A 41 41 0 0 1 21.01 78.99');
  });

  it('arcPath на 495° (=135°) — конец в правом нижнем упоре', () => {
    // polar(495, 41) = polar(135, 41) = [78.99, 78.99], дуга 270° → large-arc 1
    expect(arcPath(495)).toBe('M 21.01 78.99 A 41 41 0 1 1 78.99 78.99');
  });
});

describe('fmtFuel', () => {
  it('малые числа — целые', () => {
    expect(fmtFuel(0)).toBe('0');
    expect(fmtFuel(1024)).toBe('1024');
  });

  it('тысячи — с одной десятичной, хвост «.0» срезается', () => {
    expect(fmtFuel(15000)).toBe('15К');
    expect(fmtFuel(12345)).toBe('12.3К');
    expect(fmtFuel(999999)).toBe('1000К');
  });

  it('миллионы — с двумя десятичными', () => {
    expect(fmtFuel(1024000)).toBe('1.02М');
  });
});
