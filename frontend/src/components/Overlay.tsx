/* ============================================================
   Модальное окно-оверлей (самописец / топливомер).
   Порт 1:1 из static/: разметка index.html L345–387, поведение
   script.js — открытие/закрытие (L1382–1393, L1689–1700:
   при открытии фокус на клавишу закрытия, при закрытии —
   открывшему элементу), клик по фону (L1403–1405), ESC в общей
   цепочке (L1216–1227, приоритет верхнего слоя — overlayStack.ts);
   CSS style.css L1998–2136, @keyframes entry-in (L861–870),
   мобильная вёрстка L2686–2708. Никаких фокус-ловушек не
   выдумывается — только ванильное поведение.
   ============================================================ */

import { useEffect, useId, useRef } from 'react';
import type { MouseEvent as ReactMouseEvent, ReactNode } from 'react';
import styles from './Overlay.module.css';
import { isTopOverlay, popOverlay, pushOverlay } from './overlayStack';

export interface OverlayProps {
  open: boolean;
  onClose: () => void;
  /** Заголовок (aria-labelledby диалога). */
  title: string;
  /** Подстрока рядом с заголовком («протокол обмена с моделью»). */
  subtitle?: string;
  /** Русская подпись клавиши закрытия («Закрыть самописец»). */
  closeLabel: string;
  /** id диалога — для aria-controls триггера потребителя (как у Drawer). */
  id?: string;
  /** Клавиша «Обновить» — есть только у самописца. */
  refreshLabel?: string;
  onRefresh?: () => void;
  children?: ReactNode;
}

export default function Overlay({
  open,
  onClose,
  title,
  subtitle,
  closeLabel,
  id,
  refreshLabel,
  onRefresh,
  children,
}: OverlayProps) {
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement | null>(null);

  /* ESC закрывает, только если этот слой на вершине стека
     (порт script.js L1216–1227) */
  useEffect(() => {
    if (!open) return;
    const token = pushOverlay();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!isTopOverlay(token)) return;
      onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      popOverlay(token);
    };
  }, [open, onClose]);

  /* Фокус: при открытии — клавиша закрытия, при закрытии — назад к
     открывшему элементу (порт openRecorder/closeRecorder,
     script.js L1382–1393 и openFuelModal/closeFuelModal L1689–1700) */
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    return () => {
      opener?.focus();
    };
  }, [open]);

  /* Клик по фону закрывает окно (порт script.js L1403–1405) */
  const onBackdropClick = (e: ReactMouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) onClose();
  };

  if (!open) return null;

  return (
    <div className={styles.overlay} onClick={onBackdropClick}>
      <div id={id} className={styles.modal} role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className={styles.head}>
          <div className={styles.heading}>
            <h2 className={styles.title} id={titleId}>{title}</h2>
            {subtitle ? <span className={styles.sub}>{subtitle}</span> : null}
          </div>
          <div className={styles.actions}>
            {onRefresh && refreshLabel ? (
              <button type="button" className={styles.refresh} onClick={onRefresh}>
                {refreshLabel}
              </button>
            ) : null}
            <button
              type="button"
              ref={closeRef}
              className={styles.close}
              aria-label={closeLabel}
              onClick={onClose}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   strokeWidth="2.5" strokeLinecap="round" aria-hidden="true" focusable="false">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </header>
        <div className={styles.body}>{children}</div>
      </div>
    </div>
  );
}
