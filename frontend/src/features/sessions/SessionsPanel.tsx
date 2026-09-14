/* ============================================================
   Панель сессий — третья колонка каркаса. Порт static/index.html
   L338–341, script.js L1786–1898 (renderSessions, switchToSession)
   и style.css L1361–1493. Стартовый перечень — идемпотентный GET
   в mount-эффекте с abort в cleanup (spec «Правила эффектов»:
   восстановление сессии); переключение — из обработчика клика.
   ============================================================ */

import { useEffect, useRef } from 'react';
import { useSessionStore } from '../../stores/session';
import { loadSessions } from './loadSessions';
import { switchToSession } from './restoreSession';
import { sessionMeta, sessionTitle } from './sessionList';
import styles from './SessionsPanel.module.css';

export default function SessionsPanel() {
  const sessions = useSessionStore((s) => s.sessionsCache);
  const sessionId = useSessionStore((s) => s.sessionId);
  const abortRef = useRef<AbortController | null>(null);

  /* Стартовый перечень (ваниль loadSessions при загрузке, script.js L2497):
     StrictMode удваивает эффект — первый запрос абортируется в cleanup */
  useEffect(() => {
    const controller = new AbortController();
    abortRef.current = controller;
    void loadSessions(controller.signal);
    return () => controller.abort();
  }, []);

  const onSwitch = (id: string) => {
    /* Предыдущее чтение истории отменяется — активен последний клик */
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    void switchToSession(id, controller.signal);
  };

  return (
    <aside className={styles.panel} aria-label="Сессии">
      <h2 className={styles.caption}>Сессии</h2>
      <div className={styles.list} role="list">
        {sessions.length ? sessions.map((session) => (
          <button
            key={session.id}
            type="button"
            className={styles.item}
            aria-current={session.id === sessionId}
            onClick={() => onSwitch(session.id)}
          >
            <span className={styles.title}>{sessionTitle(session)}</span>
            <span className={styles.meta}>{sessionMeta(session)}</span>
          </button>
        )) : (
          <p className={styles.empty}>Пока нет сессий</p>
        )}
      </div>
    </aside>
  );
}
