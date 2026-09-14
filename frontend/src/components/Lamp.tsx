/* ============================================================
   Лампа-индикатор — точка свечения на приборе.
   Порт 1:1 из static/style.css: .lamp (L1217–1223), перекраска
   по kind (L1225–1236) и @keyframes lamp (L1179–1186).
   Цвет и мигание задаются props; в директиве состояния и
   индикаторе обработки (волна 3b) используется этот же прибор.
   ============================================================ */

import styles from './Lamp.module.css';

export type LampColor = 'green' | 'amber' | 'red';

export interface LampProps {
  /** Цвет лампы по дисциплине палитры: зелёный — показания,
      янтарь — активность, красный — отказы. */
  color?: LampColor;
  /** Мигание обработки (busy, @keyframes lamp). */
  blinking?: boolean;
}

export default function Lamp({ color = 'green', blinking = false }: LampProps) {
  const classes = [styles.lamp, styles[color]];
  if (blinking) classes.push(styles.blinking);
  return <span className={classes.join(' ')} aria-hidden="true" />;
}
