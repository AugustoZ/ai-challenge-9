/* ============================================================
   Чип текущего пользователя (план Task 14): имя из auth-стора,
   меню «Сменить пользователя» / «Выйти». Оба пункта уводят в гейт
   входа (logout → AuthGate): отдельного экрана «выхода» в
   «Штурмане» нет — гейт и есть экран входа. Очистку бортового
   состояния выполняет каркас App при переходе authed → anon
   (features/auth/resetState.ts), здесь только logout auth-стора.
   Слои: Esc закрывает только верхний слой (overlayStack), клик мимо
   закрывает меню, фокус возвращается на чип.
   ============================================================ */

import { useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { isTopOverlay, popOverlay, pushOverlay } from '../../components/overlayStack';
import { useAuthStore } from '../../stores/auth';
import styles from './UserChip.module.css';

export default function UserChip() {
  const userName = useAuthStore((s) => s.user?.name ?? '');
  const logout = useAuthStore((s) => s.logout);
  const [menuOpen, setMenuOpen] = useState(false);
  const chipRef = useRef<HTMLButtonElement | null>(null);

  /* Esc: слой меню — верхний (чип живёт вне Drawer/Overlay, но стопка общая) */
  useEffect(() => {
    if (!menuOpen) return;
    const token = pushOverlay();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (!isTopOverlay(token)) return;
      e.stopPropagation();
      setMenuOpen(false);
      chipRef.current?.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      popOverlay(token);
    };
  }, [menuOpen]);

  /* Клик мимо меню закрывает его (порт закреплённой подсказки SettingInline) */
  useEffect(() => {
    if (!menuOpen) return;
    const onDocClick = (e: MouseEvent) => {
      if (e.target instanceof Element && chipRef.current?.parentElement?.contains(e.target)) return;
      setMenuOpen(false);
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [menuOpen]);

  /* Повторный клик по чипу закрывает меню: клик по своей клавише —
     исключение для обработчика «клик мимо» (тот же контракт, что у
     SettingInline: её обработчик уже переключил состояние) */
  const onChipClick = (e: ReactMouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    setMenuOpen((open) => !open);
  };

  /* Выход/смена: закрыть меню и разлогинить; сброс состояния и возврат
     гейта выполняет каркас (переход authed → anon) */
  const endSession = () => {
    setMenuOpen(false);
    logout();
  };

  return (
    <div className={styles.wrap}>
      <button
        type="button"
        ref={chipRef}
        className={styles.chip}
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        aria-label={`Пользователь ${userName}`}
        title={userName}
        onClick={onChipClick}
      >
        <span className={styles.dot} aria-hidden="true" />
        <span className={styles.name}>{userName}</span>
      </button>
      {menuOpen && (
        <div className={styles.menu} role="menu" aria-label="Меню пользователя">
          <button type="button" role="menuitem" className={styles.item} onClick={endSession}>
            Сменить пользователя
          </button>
          <div className={styles.separator} aria-hidden="true" />
          <button type="button" role="menuitem" className={styles.item} onClick={endSession}>
            Выйти
          </button>
        </div>
      )}
    </div>
  );
}
