/* ============================================================
   Секция «Инварианты» панели приборов (план Task 10): два скоупа
   непреложных правил — «Глобальные» (файл-стор) и «Сессионные»
   (текущая сессия). Данные — из session store (invariants), куда
   пишут и GET /api/invariants, и эхо ChatResponse каждый ход;
   записи стора не дублируются локальным состоянием. Чтение —
   при монтаже секции и на смену sessionId (панель живёт в DOM
   постоянно, триггер — смена сессии). Нумерация сессионных
   1..N совпадает с командами чата «убери инвариант N». Ошибки
   hook-а — инлайн-строкой секции, без всплывающих окон.
   ============================================================ */

import { useEffect, useRef, useState } from 'react';
import type { Invariant } from '../../api/types';
import annunciatorStyles from '../../components/AnnunciatorGroup.module.css';
import { useSessionStore } from '../../stores/session';
import settingsStyles from '../settings/SettingsPanel.module.css';
import { useInvariants } from './useInvariants';
import styles from './InvariantsPanel.module.css';

const TEXT_MAX = 300;
const HINT = 'До 300 символов, максимум 20';

/* Один скоуп: строки правил + пустое состояние + форма добавления.
    Нумерация (showNumber) — только для сессии, порядок 1..N. */
function Scope(props: {
  caption: string;
  items: Invariant[];
  emptyText: string;
  placeholder: string;
  text: string;
  onTextChange: (text: string) => void;
  onAdd: () => void;
  onRemove: (id: string) => void;
  busy: boolean;
  showNumber: boolean;
}) {
  return (
    <>
      <h3 className={styles.scopeCaption}>{props.caption}</h3>
      {props.items.length ? (
        <ul className={styles.list}>
          {props.items.map((item, index) => (
            <li key={item.id} className={styles.item}>
              {props.showNumber && <span className={styles.num}>{index + 1}</span>}
              <span className={styles.itemText} title={item.text}>{item.text}</span>
              <button
                type="button"
                className={styles.deleteBtn}
                aria-label={`Удалить инвариант: ${item.text}`}
                disabled={props.busy}
                onClick={() => props.onRemove(item.id)}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className={styles.note}>{props.emptyText}</p>
      )}
      <div className={styles.addRow}>
        <input
          type="text"
          className={settingsStyles.textInput}
          value={props.text}
          placeholder={props.placeholder}
          maxLength={TEXT_MAX}
          autoComplete="off"
          spellCheck={false}
          aria-label={props.placeholder}
          onChange={(e) => props.onTextChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && props.text.trim()) props.onAdd();
          }}
        />
        <button
          type="button"
          className={`${annunciatorStyles.annunciator} ${styles.addBtn}`}
          disabled={props.busy || !props.text.trim()}
          onClick={props.onAdd}
        >
          Добавить
        </button>
      </div>
      <p className={styles.note}>{HINT}</p>
    </>
  );
}

export default function InvariantsPanel() {
  const { invariants, error, busy, load, addGlobal, removeGlobal, addSession, removeSession } =
    useInvariants();
  const sessionId = useSessionStore((s) => s.sessionId);

  /* Отдельные состояния ввода на скоуп; ввод не живёт в сторе */
  const [globalText, setGlobalText] = useState('');
  const [sessionText, setSessionText] = useState('');

  /* Чтение при монтаже и на смену sessionId: без сессии читать
     нечего (GET требует sessionId, 400 без него) */
  useEffect(() => {
    if (!sessionId) return;
    load(sessionId);
  }, [sessionId, load]);

  /* Успешное добавление очищает своё поле, отказ — оставляет текст
     на правку: намерение ставится на клике, исполнение — после
     завершения запроса (busy=false без новой ошибки) */
  const pendingClear = useRef<'global' | 'session' | null>(null);
  useEffect(() => {
    if (busy || !pendingClear.current) return;
    if (!error) {
      if (pendingClear.current === 'global') setGlobalText('');
      else setSessionText('');
    }
    pendingClear.current = null;
  }, [busy, error]);

  const global = invariants?.global ?? [];
  const session = invariants?.session ?? [];

  return (
    <section className={settingsStyles.panelSection} aria-label="Инварианты">
      <h2 className={settingsStyles.sectionCaption}>Инварианты</h2>

      {error && (
        <p className={styles.error}>
          {error}{' '}
          {sessionId && (
            <button
              type="button"
              className={settingsStyles.retry}
              onClick={() => load(sessionId)}
            >
              Повторить
            </button>
          )}
        </p>
      )}

      <Scope
        caption="Глобальные"
        items={global}
        emptyText="Пока нет инвариантов"
        placeholder="Непреложное правило для всех сессий"
        text={globalText}
        onTextChange={setGlobalText}
        onAdd={() => {
          const text = globalText.trim();
          if (!text) return;
          pendingClear.current = 'global';
          addGlobal(text);
        }}
        onRemove={removeGlobal}
        busy={busy}
        showNumber={false}
      />

      <Scope
        caption="Сессионные"
        items={session}
        emptyText="В этой сессии инвариантов нет"
        placeholder="Правило для текущей сессии"
        text={sessionText}
        onTextChange={setSessionText}
        onAdd={() => {
          const text = sessionText.trim();
          if (!text) return;
          pendingClear.current = 'session';
          addSession(text);
        }}
        onRemove={removeSession}
        busy={busy}
        showNumber
      />

      <p className={styles.note}>
        Инварианты прикладываются к каждому запросу модели как непреложные правила;
        сессионные действуют до конца текущей сессии
      </p>
    </section>
  );
}
