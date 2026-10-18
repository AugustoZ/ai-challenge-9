/* ============================================================
   Секция «Обслуживание» — порт static/index.html L244–255,
   script.js L1242–1248 (Режим ТО), L1721–1784 («Новый диалог»,
   «Очистить все» с подтверждением на борту) и style.css
   L1612–1624, L350–355 (.armed). Режим ТО не персистится —
   как в ваниле: тумблер класса body и директивы (App).
   ============================================================ */

import { useEffect, useRef, useState } from 'react';
import { deleteJson } from '../../api/client';
import type { SessionsCleared } from '../../api/types';
import annunciatorStyles from '../../components/AnnunciatorGroup.module.css';
import settingsStyles from '../settings/SettingsPanel.module.css';
import { useBranchesStore } from '../../stores/branches';
import { useSessionStore } from '../../stores/session';
import type { Directive } from '../send/request';
import { loadSessions } from '../sessions/loadSessions';
import styles from './Maintenance.module.css';

const CLEAR_ALL_LABEL = 'Очистить все';
const CLEAR_ALL_ARMED_LABEL = 'Точно стереть?';

export interface MaintenanceProps {
  maintOn: boolean;
  onMaintToggle: (on: boolean) => void;
  onOpenRecorder: () => void;
  onDirective: (directive: Directive) => void;
}

/** Локальный сброс как у «Новый диалог» (script.js L1722–1731): серверные
    сессии уже стёрты/диалог покинут — журнал, топливо и ветки обнуляются,
    прежняя сессия остаётся в перечне. */
function resetLocalSession(): void {
  const session = useSessionStore.getState();
  session.setSessionId(null);
  session.clearEntries();
  session.resetSessionFuel();
  useBranchesStore.getState().hideBranches();
  void loadSessions();
}

export default function Maintenance({ maintOn, onMaintToggle, onOpenRecorder, onDirective }: MaintenanceProps) {
  const [armed, setArmed] = useState(false);
  const timerRef = useRef<number | null>(null);

  /* Таймер автоматического взведения/снятия — один на клавишу */
  useEffect(() => () => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
  }, []);

  /* Возврат клавиши в исходное состояние: гаснет лампа, возвращается надпись */
  const disarm = () => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setArmed(false);
  };

  /* Клавиша требует двух нажатий — подтверждения на борту вместо диалога
     браузера; без нажатия через 4 секунды клавиша гаснет сама (L1752–1783) */
  const onClearAll = async () => {
    if (!armed) {
      setArmed(true);
      timerRef.current = window.setTimeout(disarm, 4000);
      return;
    }
    disarm();
    try {
      const data = await deleteJson<SessionsCleared>('sessions');
      const deleted = Number(data?.deleted) || 0;
      resetLocalSession();
      onDirective({ kind: 'ready', text: `Все сессии стёрты (${deleted})` });
    } catch {
      /* Состояние диалога не трогаем — только директива об отказе */
      onDirective({ kind: 'fault', text: 'Не удалось стереть сессии' });
    }
  };

  return (
    <section className={settingsStyles.panelSection}>
      <h2 className={settingsStyles.sectionCaption}>Обслуживание</h2>
      <div className={styles.grid}>
        <button
          type="button"
          className={annunciatorStyles.annunciator}
          aria-pressed={maintOn}
          onClick={() => onMaintToggle(!maintOn)}
        >
          Режим ТО
        </button>
        <button type="button" className={annunciatorStyles.annunciator} onClick={onOpenRecorder}>
          Самописец
        </button>
        <button
          type="button"
          className={`${annunciatorStyles.annunciator} ${styles.wide}`}
          onClick={() => {
            resetLocalSession();
            onDirective({ kind: 'ready', text: 'Готов к передаче' });
          }}
        >
          Новый диалог
        </button>
        <button
          type="button"
          className={armed
            ? `${annunciatorStyles.annunciator} ${styles.armed}`
            : annunciatorStyles.annunciator}
          aria-pressed={armed}
          onClick={() => void onClearAll()}
        >
          {armed ? CLEAR_ALL_ARMED_LABEL : CLEAR_ALL_LABEL}
        </button>
      </div>
      <p className={annunciatorStyles.hint}>
        Сырой JSON обмена с моделью — в бортовом журнале и в самописце; «Новый диалог»
        сбрасывает память разговора, «Очистить все» стирает все сессии на сервере
      </p>
    </section>
  );
}
