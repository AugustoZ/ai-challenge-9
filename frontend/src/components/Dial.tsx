/* ============================================================
   Круглая шкала параметра — прибор-крутилка.
   Порт 1:1 из static/: разметка index.html L95–164, логика
   script.js L612–827 (initDial, setControlValue, тултипы «?»),
   математика — dialMath.ts. Дизайн заморожен.

   Контракт a11y (static/AGENTS.md) сохранён:
   - скрытый настоящий <input type="range"> — источник клавиатуры
     и PageUp/PageDown (±10 позиций), aria-valuetext озвучивает
     показание (цифры .dial-value — aria-hidden);
   - GAUGE_START=225, GAUGE_SWEEP=270 — не менять;
   - hit-область «?» 25px (WCAG 2.5.8) и hoverable-тултип с мостом
     (WCAG 1.4.13) — не удалять.
   Стрелка/дуга/цифры рендерятся из props (никаких мутаций DOM).
   ============================================================ */

import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, PointerEvent as ReactPointerEvent } from 'react';
import styles from './Dial.module.css';
import {
  GAUGE_START,
  GAUGE_SWEEP,
  arcBetween,
  arcPath,
  clamp,
  polar,
  tToValue,
  valueToT,
  zoneLevel,
  type DialConfig,
  type DialZone,
  type ZoneLevel,
} from './dialMath';

export interface DialProps {
  /** Русская подпись прибора: она же aria-label скрытого range. */
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  /** Формат показания (у ванилы у каждой шкалы свой: 0.70 / 1024 / 1.02М). */
  fmt: (v: number) => string;
  /** Логарифмическая шкала (топливо). */
  log?: boolean;
  /** Зоны допуска на лимбе, границы включительны (как в ванильном DIALS). */
  zones?: readonly DialZone[];
  /** Текст подсказки «?»; без него кнопка и тултип не рендерятся. */
  tip?: string;
  onChange: (value: number) => void;
}

const ZONE_CLASS: Record<ZoneLevel, string> = {
  red: styles.zRed,
  amber: styles.zAmber,
  green: styles.zGreen,
};

export default function Dial({
  label,
  value,
  min,
  max,
  step,
  fmt,
  log = false,
  zones = [],
  tip,
  onChange,
}: DialProps) {
  const cfg: DialConfig = useMemo(
    () => ({ min, max, step, log, fmt, zones }),
    [min, max, step, log, fmt, zones],
  );

  const dialRef = useRef<HTMLDivElement | null>(null);
  const rangeRef = useRef<HTMLInputElement | null>(null);
  const infoBtnRef = useRef<HTMLButtonElement | null>(null);
  const draggingRef = useRef(false);

  /* «Шкала взята»: фокус скрытого range или перетаскивание (класс engaged) */
  const [engaged, setEngaged] = useState(false);
  /* Закреплённая подсказка «?» (клик — для тача) */
  const [tipOpen, setTipOpen] = useState(false);

  const tipId = useId();

  const t = valueToT(cfg, value);
  const angle = GAUGE_START + GAUGE_SWEEP * t;
  const level = zoneLevel(cfg, value);

  /* Тики шкалы: 5 крупных с промежуточными мелкими по всей дуге
     (порт script.js L698–712) */
  const ticks = useMemo(() => {
    const result: Array<{ x1: string; y1: string; x2: string; y2: string; major: boolean }> = [];
    for (let i = 0; i <= 20; i++) {
      const major = i % 5 === 0;
      const a = GAUGE_START + (GAUGE_SWEEP * i) / 20;
      const [x1, y1] = polar(a, major ? 34.5 : 37.5);
      const [x2, y2] = polar(a, 41.5);
      result.push({
        x1: x1.toFixed(2), y1: y1.toFixed(2),
        x2: x2.toFixed(2), y2: y2.toFixed(2),
        major,
      });
    }
    return result;
  }, []);

  /* Зоны допуска на лимбе: разметка сплошная (стык — начало следующей зоны)
     и приглушённая — цветом зоны говорит только активное показание
     (порт script.js L714–724) */
  const zoneArcs = useMemo(() => {
    const result: Array<{ d: string; level: ZoneLevel }> = [];
    cfg.zones.forEach(([from, , levelZ], i) => {
      const zTo = i < cfg.zones.length - 1 ? cfg.zones[i + 1][0] : cfg.max;
      const a0 = GAUGE_START + GAUGE_SWEEP * valueToT(cfg, from);
      const a1 = GAUGE_START + GAUGE_SWEEP * valueToT(cfg, zTo);
      result.push({ d: arcBetween(a0, a1, 31), level: levelZ });
    });
    return result;
  }, [cfg]);

  /* Позиция указателя -> значение: угол от центра, мёртвая зона в центре
     игнорируется (порт script.js L740–750) */
  const pointerToValue = (clientX: number, clientY: number): number | null => {
    const dial = dialRef.current;
    if (!dial) return null;
    const rect = dial.getBoundingClientRect();
    const dx = clientX - (rect.left + rect.width / 2);
    const dy = clientY - (rect.top + rect.height / 2);
    if (Math.hypot(dx, dy) < rect.width * 0.18) return null;
    const clockDeg = ((Math.atan2(dx, -dy) * 180) / Math.PI + 360) % 360;
    const raw = (clockDeg - GAUGE_START + 360) % 360;
    const tPtr = raw > GAUGE_SWEEP ? (raw > GAUGE_SWEEP + 45 ? 0 : 1) : raw / GAUGE_SWEEP;
    return tToValue(cfg, tPtr);
  };

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    draggingRef.current = true;
    rangeRef.current?.focus({ preventScroll: true });
    const v = pointerToValue(e.clientX, e.clientY);
    if (v != null) onChange(v);
  };

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return;
    const v = pointerToValue(e.clientX, e.clientY);
    if (v != null) onChange(v);
  };

  const endDrag = () => {
    draggingRef.current = false;
  };

  /* Колесо над шкалой — точная подстройка (2% дуги за щелчок). Слушатель
     навешивается напрямую: React делает wheel-слушатели пассивными, а ваниле
     нужен preventDefault (порт script.js L777–784) */
  useEffect(() => {
    const dial = dialRef.current;
    if (!dial) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const dir = e.deltaY < 0 ? 1 : -1;
      const tWheel = clamp(valueToT(cfg, value) + dir * 0.02, 0, 1);
      onChange(tToValue(cfg, tWheel));
    };
    dial.addEventListener('wheel', onWheel, { passive: false });
    return () => dial.removeEventListener('wheel', onWheel);
  }, [cfg, onChange, value]);

  /* Скрытый range: клавиатура и скринридер. Стрелки двигают позицию
     на 1% дуги (порт script.js L786–792) */
  const onRangeInput = (e: ChangeEvent<HTMLInputElement>) => {
    const tInput = Number(e.target.value) / 100;
    if (!Number.isFinite(tInput)) return;
    onChange(tToValue(cfg, tInput));
  };

  /* Клик мимо подсказки закрывает закреплённую; клик по собственной «?» —
     исключение: её обработчик уже переключил состояние (порт script.js
     L829–838; в ваниле исключение шире — любой .info-btn, но при
     покомпонентных слушателях тот же итог даёт исключение своей кнопки) */
  useEffect(() => {
    if (!tipOpen) return;
    const onDocClick = (e: MouseEvent) => {
      if (e.target instanceof Element && infoBtnRef.current?.contains(e.target)) return;
      setTipOpen(false);
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [tipOpen]);

  const zoneExtra = level === 'green' ? '' : ` ${ZONE_CLASS[level]}`;
  const dialClass = engaged ? `${styles.dial} ${styles.engaged}` : styles.dial;
  const gaugeClass = tipOpen ? `${styles.gauge} ${styles.tipOpen}` : styles.gauge;

  return (
    <div className={gaugeClass}>
      <div
        ref={dialRef}
        className={dialClass}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        <svg className={styles.dialSvg} viewBox="0 0 100 100" aria-hidden="true">
          <circle className={styles.dialBezel} cx={50} cy={50} r={47} />
          <circle className={styles.dialFace} cx={50} cy={50} r={42} />
          <g className={styles.dialTicks}>
            {ticks.map((tk, i) => (
              <line
                key={i}
                x1={tk.x1}
                y1={tk.y1}
                x2={tk.x2}
                y2={tk.y2}
                className={tk.major ? styles.major : undefined}
              />
            ))}
            {zoneArcs.map((z, i) => (
              <path key={i} d={z.d} className={`${styles.dialZone} ${ZONE_CLASS[z.level]}`} />
            ))}
          </g>
          <path className={`${styles.dialArc}${zoneExtra}`} d={arcPath(angle)} />
          <line
            className={`${styles.dialNeedle}${zoneExtra}`}
            x1={50}
            y1={50}
            x2={50}
            y2={15}
            transform={`rotate(${angle.toFixed(2)} 50 50)`}
          />
          <circle className={styles.dialHub} cx={50} cy={50} r={4} />
        </svg>
        <span className={`${styles.dialValue}${zoneExtra}`} aria-hidden="true">
          {fmt(value)}
        </span>
        <input
          ref={rangeRef}
          type="range"
          className={styles.dialKbd}
          min={0}
          max={100}
          step={1}
          value={Math.round(t * 100)}
          aria-label={label}
          aria-valuetext={fmt(value)}
          onChange={onRangeInput}
          onFocus={() => setEngaged(true)}
          onBlur={() => setEngaged(false)}
        />
      </div>
      <div className={styles.gaugeLabel}>
        <span className={styles.gaugeName}>{label}</span>
        {tip !== undefined && (
          <button
            type="button"
            ref={infoBtnRef}
            className={styles.infoBtn}
            aria-expanded={tipOpen}
            aria-controls={tipId}
            aria-describedby={tipId}
            aria-label={`Описание параметра «${label}»`}
            onClick={() => setTipOpen((open) => !open)}
            onKeyDown={(e) => {
              if (e.key !== 'Escape') return;
              setTipOpen(false);
              e.currentTarget.blur();
            }}
          >
            ?
          </button>
        )}
      </div>
      {tip !== undefined && (
        <span className={styles.tooltip} role="tooltip" id={tipId}>
          {tip}
        </span>
      )}
    </div>
  );
}
