/* ============================================================
   Мобильное меню (drawer) — выдвижная панель приборов.
   Порт 1:1 из static/: поведение script.js L1185–1228
   (openSettings/closeSettings: backdrop, aria-expanded живёт у
   клавиши-триггера потребителя, при закрытии фокус возвращается
   открывшему, ESC — только если слой на вершине стека),
   CSS style.css L190–256 (панель), L1595–1610 (backdrop) и
   мобильная вёрстка L2740–2764.

   Отличие от ванилы (сознательное): grid-area: side не переносится —
   позиция панели в каркасе страницы принадлежит макету App (волна 5).
   ============================================================ */

import { useCallback, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import styles from './Drawer.module.css';
import { isTopOverlay, popOverlay, pushOverlay } from './overlayStack';

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  /** Русская подпись клавиши закрытия («Закрыть приборы»). */
  closeLabel: string;
  /** id панели — для aria-controls триггера потребителя. */
  id?: string;
  children: ReactNode;
}

export default function Drawer({ open, onClose, closeLabel, id, children }: DrawerProps) {
  const panelRef = useRef<HTMLElement | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  /* Закрытие с возвратом фокуса открывшему, если фокус остался внутри
     панели (порт closeSettings, script.js L1198–1206) */
  const closeWithFocusReturn = useCallback(() => {
    const panel = panelRef.current;
    const active = document.activeElement;
    if (panel && active instanceof Node && panel.contains(active)) {
      openerRef.current?.focus();
    }
    onClose();
  }, [onClose]);

  /* ESC закрывает, только если этот слой на вершине стека (в ваниле ESC
     сначала проверяет самописец и топливомер — script.js L1216–1227) */
  useEffect(() => {
    if (!open) return;
    const token = pushOverlay();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!isTopOverlay(token)) return;
      closeWithFocusReturn();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      popOverlay(token);
    };
  }, [open, closeWithFocusReturn]);

  /* Запоминаем открывшего — на случай возврата фокуса при закрытии.
     Само открытие фокус не двигает (в ваниле openSettings тоже). */
  useEffect(() => {
    if (!open) return;
    openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }, [open]);

  return (
    <>
      <div
        className={open ? `${styles.backdrop} ${styles.visible}` : styles.backdrop}
        onClick={closeWithFocusReturn}
        aria-hidden="true"
      />
      <aside
        ref={panelRef}
        id={id}
        className={open ? `${styles.panel} ${styles.open}` : styles.panel}
      >
        <div className={styles.head}>
          <button
            type="button"
            className={styles.close}
            aria-label={closeLabel}
            onClick={closeWithFocusReturn}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2.5" strokeLinecap="round" aria-hidden="true" focusable="false">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
        <div className={styles.body}>{children}</div>
      </aside>
    </>
  );
}
