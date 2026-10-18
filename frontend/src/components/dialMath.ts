/* ============================================================
   Приборы: математика круглых шкал.
   Порт 1:1 из static/script.js L612–686 (clamp, GAUGE_START/SWEEP,
   fmtFuel, DIALS, zoneLevel, valueToT, tToValue, polar, arcBetween,
   arcPath). Чистые функции — без DOM, покрыты dialMath.test.ts.
   ============================================================ */

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/* Дуга шкалы: 225° — нижний левый упор (7:30), 270° по часовой до 4:30.
   Отсчёт углов — «часовой»: 0° = 12:00, по часовой стрелке.
   Контракт a11y (static/AGENTS.md): GAUGE_START/GAUGE_SWEEP не менять. */
export const GAUGE_START = 225;
export const GAUGE_SWEEP = 270;

export type ZoneLevel = 'red' | 'amber' | 'green';

/** Зона допуска: [от, до, цвет]; границы включительны. */
export type DialZone = readonly [from: number, to: number, level: ZoneLevel];

export interface DialConfig {
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly log: boolean;
  readonly fmt: (v: number) => string;
  readonly zones: readonly DialZone[];
}

/** Топливо: компактный формат больших чисел («1024», «15К», «1.02М»). */
export function fmtFuel(v: number): string {
  if (v >= 1e6) return (v / 1e6).toFixed(2).replace(/\.?0+$/, '') + 'М';
  if (v >= 1e4) return (v / 1e3).toFixed(1).replace(/\.0$/, '') + 'К';
  return String(Math.round(v));
}

/* Топливо — логарифмическая шкала: диапазон 200..1 024 000 токенов
   линеен в лог-координате, иначе вся полезная зона сжалась бы в упор. */
export const DIALS = {
  temperature: {
    min: 0, max: 2, step: 0.01, log: false, fmt: (v: number) => v.toFixed(2),
    zones: [
      [0, 0.25, 'red'], [0.26, 0.69, 'amber'], [0.7, 1.3, 'green'],
      [1.31, 1.65, 'amber'], [1.66, 2, 'red'],
    ],
  },
  maxTokens: {
    min: 200, max: 1024000, step: 1, log: true, fmt: fmtFuel,
    zones: [
      [200, 500, 'red'], [501, 800, 'amber'], [801, 99999, 'green'],
      [100000, 499999, 'amber'], [500000, 1024000, 'red'],
    ],
  },
  topP: {
    min: 0.01, max: 1, step: 0.01, log: false, fmt: (v: number) => v.toFixed(2),
    zones: [[0.01, 0.25, 'red'], [0.26, 0.5, 'amber'], [0.51, 1, 'green']],
  },
} satisfies Record<'temperature' | 'maxTokens' | 'topP', DialConfig>;

/* Зона значения — по ТЗ: границы включительны, соседняя зона начинается
   через шаг, поэтому попадание проверяется точным диапазоном.
   Значение вне всех зон (щель в одну десятую шага) — «green» по умолчанию. */
export function zoneLevel(cfg: DialConfig, value: number): ZoneLevel {
  const v = clamp(value, cfg.min, cfg.max);
  for (const [from, to, level] of cfg.zones) {
    if (v >= from && v <= to) return level;
  }
  return 'green';
}

export function valueToT(cfg: DialConfig, value: number): number {
  const v = clamp(value, cfg.min, cfg.max);
  return cfg.log
    ? Math.log(v / cfg.min) / Math.log(cfg.max / cfg.min)
    : (v - cfg.min) / (cfg.max - cfg.min);
}

export function tToValue(cfg: DialConfig, t: number): number {
  const tt = clamp(t, 0, 1);
  const raw = cfg.log
    ? cfg.min * Math.pow(cfg.max / cfg.min, tt)
    : cfg.min + tt * (cfg.max - cfg.min);
  const snapped = cfg.log
    ? Math.max(cfg.min, Math.round(raw / cfg.step) * cfg.step)
    : Number((cfg.min + Math.round((raw - cfg.min) / cfg.step) * cfg.step).toFixed(4));
  return clamp(snapped, cfg.min, cfg.max);
}

export function polar(angleDeg: number, r: number): [number, number] {
  const a = (angleDeg * Math.PI) / 180;
  return [50 + r * Math.sin(a), 50 - r * Math.cos(a)];
}

export function arcBetween(a0: number, a1: number, r: number): string {
  const [x1, y1] = polar(a0, r);
  const [x2, y2] = polar(a1, r);
  const large = ((a1 - a0 + 360) % 360) > 180 ? 1 : 0;
  return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

export function arcPath(angleDeg: number): string {
  return arcBetween(GAUGE_START, angleDeg, 41);
}
