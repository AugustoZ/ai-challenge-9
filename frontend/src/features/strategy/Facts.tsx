/* ============================================================
   Панель фактов — порт static/index.html L228–231 (#factsPanel)
   и script.js L1012–1045 (renderFacts, updateFactsPanel),
   style.css L713–767. Видна только под стратегией «Факты»
   (с присланными фактами или честным «пока нет»), остальные
   стратегии скрывают панель; данные приходят из ChatResponse
   (session store, пишет useChatStream).
   ============================================================ */

import type { Fact } from '../../api/types';
import { useSessionStore } from '../../stores/session';
import { useSettingsStore } from '../../stores/settings';
import styles from './Facts.module.css';

export default function Facts() {
  const facts = useSessionStore((s) => s.facts);
  const active = useSettingsStore((s) => s.contextStrategy);

  /* Видимость панели следует за активной стратегией (updateFactsPanel);
     без ключа — мимо (renderFacts L1014–1016) */
  if (active !== 'facts') return null;

  const list = (Array.isArray(facts) ? facts : []).filter(
    (f): f is Fact => f !== null && typeof f === 'object' && typeof f.key === 'string' && f.key !== '',
  );
  return (
    <div className={styles.panel}>
      <h3 className={styles.caption}>Факты диалога</h3>
      <ul className={styles.list} aria-live="polite">
        {list.length ? list.map((fact, i) => (
          /* Порядок фактов значим — ключ по позиции, список заменяется целиком */
          <li key={i}>
            <span className={styles.key}>{fact.key}</span>
            {`: ${fact.value == null ? '' : String(fact.value)}`}
          </li>
        )) : (
          <li className={styles.empty}>Фактов пока нет</li>
        )}
      </ul>
    </div>
  );
}
