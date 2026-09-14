/* ============================================================
   Клавиша-плашка «Развернуть/Свернуть» — порт 1:1 из
   static/script.js L194–230 (syncExpandToggle, buildExpandToggle).
   Настоящая кнопка, состояние — в aria-expanded; ResizeObserver
   пересчитывает потребность в развороте по фактическому переполнению.
   Класс is-expanded ставится целевому колодцу (как в ваниле).
   ============================================================ */

import { useEffect, useState } from 'react';
import type { RefObject } from 'react';
import { isOverflowing } from './expand';
import styles from './ExpandToggle.module.css';

export interface ExpandToggleProps {
  /** Колодец, чей потолок поднимается (в ваниле — box из buildExpandToggle). */
  target: RefObject<HTMLElement | null>;
  /** id колодца для aria-controls (в ваниле — expandable-<seq>). */
  controlsId: string;
}

export default function ExpandToggle({ target, controlsId }: ExpandToggleProps) {
  const [hidden, setHidden] = useState(true);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    const box = target.current;
    if (!box) return;

    const sync = () => {
      if (!box.isConnected || box.clientHeight === 0) return;
      const wasExpanded = box.classList.contains(styles.expanded);
      if (wasExpanded) box.classList.remove(styles.expanded);
      const overflowing = isOverflowing(box.clientHeight, box.scrollHeight);
      if (wasExpanded) box.classList.add(styles.expanded);
      if (!overflowing) {
        box.classList.remove(styles.expanded);
        setExpanded(false);
        setHidden(true);
        return;
      }
      setHidden(false);
    };

    const observer = new ResizeObserver(sync);
    observer.observe(box);
    return () => {
      observer.disconnect();
      box.classList.remove(styles.expanded);
    };
  }, [target]);

  const onToggle = () => {
    const box = target.current;
    if (!box) return;
    setExpanded(box.classList.toggle(styles.expanded));
  };

  return (
    <button
      type="button"
      className={styles.toggle}
      hidden={hidden}
      aria-controls={controlsId}
      aria-expanded={expanded}
      onClick={onToggle}
    >
      {expanded ? 'Свернуть' : 'Развернуть'}
    </button>
  );
}
