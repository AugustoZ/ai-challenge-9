/* ============================================================
   Часы полосы состояния.
   Порт 1:1 из static/script.js L82–90: тик раз в секунду,
   время — toLocaleTimeString('ru-RU', { hour12: false });
   CSS — style.css L122–128. Строгий режим React безопасен:
   интервал заводится в эффекте и снимается в cleanup.
   ============================================================ */

import { useEffect, useState } from 'react';
import styles from './Clock.module.css';

function readClock(): string {
  return new Date().toLocaleTimeString('ru-RU', { hour12: false });
}

export interface ClockProps {
  className?: string;
}

export default function Clock({ className }: ClockProps) {
  const [time, setTime] = useState(readClock);

  useEffect(() => {
    const id = setInterval(() => setTime(readClock()), 1000);
    return () => clearInterval(id);
  }, []);

  const classes = className ? `${styles.clock} ${className}` : styles.clock;
  return <span className={classes}>{time}</span>;
}
