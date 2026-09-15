/* ============================================================
   Панель «Память» — оверлей на общем Overlay с тремя вкладками:
   «Краткосрочная» (сводка слоёв, только чтение), «Рабочая»
   (заметки сессии: добавление/правка/удаление/очистка) и
   «Долговременная» (категории Профиль/Решения/Знания, поиск,
   теги). Данные — из stores/memory, сессия — из stores/session,
   стратегия контекста — из stores/settings.
   ============================================================ */

import { useEffect, useId, useState } from 'react';
import type { FormEvent } from 'react';
import Overlay from '../../components/Overlay';
import type { MemoryKind, MemoryEntry, WorkingEntry } from '../../api/types';
import { useMemoryStore } from '../../stores/memory';
import { useSessionStore } from '../../stores/session';
import { useSettingsStore } from '../../stores/settings';
import {
  KIND_FILTERS,
  formatStamp,
  kindLabel,
  parseTags,
  strategyLabel,
} from './memoryText';
import styles from './MemoryPanel.module.css';

type MemoryTab = 'shortterm' | 'working' | 'longterm';

const TABS: ReadonlyArray<{ id: MemoryTab; label: string }> = [
  { id: 'shortterm', label: 'Краткосрочная' },
  { id: 'working', label: 'Рабочая' },
  { id: 'longterm', label: 'Долговременная' },
];

/** Короткая строка-подпись с показанием (моно). */
function MetaLine({ label, value }: { label: string; value: string }) {
  return (
    <p className={styles.metaLine}>
      {label} <span className={styles.metaValue}>{value}</span>
    </p>
  );
}

function ErrorLine() {
  const error = useMemoryStore((s) => s.error);
  if (!error) return null;
  return (
    <p className={styles.error} role="alert">
      {error}
    </p>
  );
}

/* ---------- Вкладка «Краткосрочная» ---------- */

function ShorttermTab() {
  const sessionId = useSessionStore((s) => s.sessionId);
  const overview = useMemoryStore((s) => s.overview);
  const contextStrategy = useSettingsStore((s) => s.contextStrategy);

  if (!sessionId) {
    return <p className={styles.empty}>Сессия ещё не начата — отправьте сообщение, чтобы появилась краткосрочная память</p>;
  }
  if (!overview) {
    return <p className={styles.empty}>Загрузка…</p>;
  }
  return (
    <>
      <MetaLine label="Ходов в диалоге:" value={String(overview.shortTerm.turns)} />
      <MetaLine label="Сводка:" value={overview.shortTerm.hasSummary ? 'есть' : 'нет'} />
      <MetaLine label="Стратегия контекста:" value={strategyLabel(contextStrategy)} />
      <p className={styles.note}>
        Краткосрочный слой — это текущий диалог: сообщения хода и скатывающаяся
        сводка старой истории. Управляет этим слоем выбранная в «Приборах»
        стратегия контекста: она решает, что уходит модели — полная история,
        окно последних сообщений, сводка или факты.
      </p>
    </>
  );
}

/* ---------- Вкладка «Рабочая» ---------- */

function WorkingTab() {
  const sessionId = useSessionStore((s) => s.sessionId);
  const working = useMemoryStore((s) => s.working);
  const loadingWorking = useMemoryStore((s) => s.loadingWorking);
  const addTitleId = useId();
  const addContentId = useId();

  const [addTitle, setAddTitle] = useState('');
  const [addContent, setAddContent] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');

  const beginEdit = (entry: WorkingEntry) => {
    setEditingId(entry.id);
    setEditTitle(entry.title);
    setEditContent(entry.content);
  };
  const cancelEdit = () => setEditingId(null);

  const onAdd = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!sessionId || !addTitle.trim() || !addContent.trim()) return;
    void useMemoryStore
      .getState()
      .upsertWorking(sessionId, { title: addTitle.trim(), content: addContent.trim() })
      .then(() => {
        setAddTitle('');
        setAddContent('');
      });
  };

  const onSaveEdit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!sessionId || !editingId || !editTitle.trim() || !editContent.trim()) return;
    void useMemoryStore
      .getState()
      .upsertWorking(sessionId, { id: editingId, title: editTitle.trim(), content: editContent.trim() })
      .then(() => setEditingId(null));
  };

  const onRemove = (entry: WorkingEntry) => {
    if (!sessionId) return;
    void useMemoryStore.getState().removeWorking(sessionId, entry.id);
  };

  const onClear = () => {
    if (!sessionId || !window.confirm('Очистить рабочую память этой сессии?')) return;
    void useMemoryStore.getState().clearWorking(sessionId);
  };

  if (!sessionId) {
    return <p className={styles.empty}>Сессия ещё не начата — рабочая память появится после первого сообщения</p>;
  }
  return (
    <>
      <form className={styles.form} onSubmit={onAdd}>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addTitleId}>Заголовок</label>
          <input
            id={addTitleId}
            type="text"
            className={styles.input}
            value={addTitle}
            placeholder="Например: цель рейса"
            autoComplete="off"
            onChange={(e) => setAddTitle(e.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addContentId}>Содержание</label>
          <input
            id={addContentId}
            type="text"
            className={styles.input}
            value={addContent}
            placeholder="Текст заметки"
            autoComplete="off"
            onChange={(e) => setAddContent(e.target.value)}
          />
        </div>
        <button type="submit" className={styles.btnPrimary} disabled={!addTitle.trim() || !addContent.trim()}>
          Добавить
        </button>
      </form>

      <div className={styles.listHead}>
        <span className={styles.listCaption}>Заметки сессии</span>
        {working.length > 0 && (
          <button type="button" className={styles.btnDanger} title="Очистить рабочую память сессии" onClick={onClear}>
            Очистить
          </button>
        )}
      </div>

      {loadingWorking && <p className={styles.metaLine}>Загрузка…</p>}
      <ErrorLine />

      {!loadingWorking && working.length === 0 ? (
        <p className={styles.empty}>Рабочая память пуста — агент и вы можете добавлять заметки</p>
      ) : (
        <ul className={styles.list}>
          {working.map((entry) => (
            <li key={entry.id} className={styles.item}>
              {editingId === entry.id ? (
                <form className={styles.editForm} onSubmit={onSaveEdit}>
                  <input
                    type="text"
                    className={styles.input}
                    aria-label="Заголовок заметки"
                    value={editTitle}
                    autoComplete="off"
                    onChange={(e) => setEditTitle(e.target.value)}
                  />
                  <input
                    type="text"
                    className={styles.input}
                    aria-label="Содержание заметки"
                    value={editContent}
                    autoComplete="off"
                    onChange={(e) => setEditContent(e.target.value)}
                  />
                  <div className={styles.itemActions}>
                    <button type="submit" className={styles.btnPrimary} disabled={!editTitle.trim() || !editContent.trim()}>
                      Сохранить
                    </button>
                    <button type="button" className={styles.btn} onClick={cancelEdit}>Отмена</button>
                  </div>
                </form>
              ) : (
                <>
                  <div className={styles.itemHead}>
                    <span className={styles.itemTitle}>{entry.title}</span>
                    <span className={styles.stamp}>{formatStamp(entry.updatedAt)}</span>
                  </div>
                  <p className={styles.content}>{entry.content}</p>
                  <div className={styles.itemActions}>
                    <button type="button" className={styles.btn} title={`Править «${entry.title}»`} onClick={() => beginEdit(entry)}>
                      Править
                    </button>
                    <button type="button" className={styles.btnDanger} title={`Удалить «${entry.title}»`} onClick={() => onRemove(entry)}>
                      Удалить
                    </button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/* ---------- Вкладка «Долговременная» ---------- */

function LongtermTab() {
  const longterm = useMemoryStore((s) => s.longterm);
  const counts = useMemoryStore((s) => s.counts);
  const loadingLongterm = useMemoryStore((s) => s.loadingLongterm);
  const addTitleId = useId();
  const addContentId = useId();
  const addTagsId = useId();
  const addKindId = useId();
  const searchId = useId();

  const [kindFilter, setKindFilter] = useState<MemoryKind | undefined>(undefined);
  const [query, setQuery] = useState('');
  const [addKind, setAddKind] = useState<MemoryKind>('knowledge');
  const [addTitle, setAddTitle] = useState('');
  const [addContent, setAddContent] = useState('');
  const [addTags, setAddTags] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editKind, setEditKind] = useState<MemoryKind>('knowledge');
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editTags, setEditTags] = useState('');

  const reload = (kind: MemoryKind | undefined, q: string) => {
    void useMemoryStore.getState().loadLongterm(kind, q || undefined);
  };

  const onSearch = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    reload(kindFilter, query.trim());
  };

  const onKindChip = (kind: MemoryKind | undefined) => {
    setKindFilter(kind);
    reload(kind, query.trim());
  };

  const beginEdit = (entry: MemoryEntry) => {
    setEditingId(entry.id);
    setEditKind(entry.kind);
    setEditTitle(entry.title);
    setEditContent(entry.content);
    setEditTags(entry.tags.join(', '));
  };
  const cancelEdit = () => setEditingId(null);

  const onAdd = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!addTitle.trim() || !addContent.trim()) return;
    void useMemoryStore
      .getState()
      .upsertLongterm({ kind: addKind, title: addTitle.trim(), content: addContent.trim(), tags: parseTags(addTags) })
      .then(() => {
        setAddTitle('');
        setAddContent('');
        setAddTags('');
      });
  };

  const onSaveEdit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!editingId || !editTitle.trim() || !editContent.trim()) return;
    void useMemoryStore
      .getState()
      .upsertLongterm({ kind: editKind, title: editTitle.trim(), content: editContent.trim(), tags: parseTags(editTags) })
      .then(() => setEditingId(null));
  };

  return (
    <>
      <p className={styles.countsLine}>
        {`Профилей: ${counts.profile} · Решений: ${counts.decision} · Знаний: ${counts.knowledge}`}
      </p>

      <div className={styles.filterRow}>
        <div className={styles.chips} role="group" aria-label="Фильтр по категории памяти">
          {KIND_FILTERS.map((filter) => (
            <button
              key={filter.label}
              type="button"
              className={kindFilter === filter.kind ? styles.chipActive : styles.chip}
              aria-pressed={kindFilter === filter.kind}
              onClick={() => onKindChip(filter.kind)}
            >
              {filter.label}
            </button>
          ))}
        </div>
        <form className={styles.searchForm} onSubmit={onSearch}>
          <label className={styles.srOnly} htmlFor={searchId}>Поиск по долговременной памяти</label>
          <input
            id={searchId}
            type="search"
            className={styles.input}
            placeholder="Поиск — Enter"
            autoComplete="off"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </form>
      </div>

      <form className={styles.form} onSubmit={onAdd}>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addKindId}>Категория</label>
          <select
            id={addKindId}
            className={styles.select}
            value={addKind}
            onChange={(e) => setAddKind(e.target.value as MemoryKind)}
          >
            <option value="profile">Профиль</option>
            <option value="decision">Решение</option>
            <option value="knowledge">Знание</option>
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addTitleId}>Заголовок</label>
          <input
            id={addTitleId}
            type="text"
            className={styles.input}
            value={addTitle}
            placeholder="Например: предпочитает краткие ответы"
            autoComplete="off"
            onChange={(e) => setAddTitle(e.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addContentId}>Содержание</label>
          <input
            id={addContentId}
            type="text"
            className={styles.input}
            value={addContent}
            placeholder="Текст записи"
            autoComplete="off"
            onChange={(e) => setAddContent(e.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label className={styles.fieldLabel} htmlFor={addTagsId}>Теги</label>
          <input
            id={addTagsId}
            type="text"
            className={styles.input}
            value={addTags}
            placeholder="через запятую"
            autoComplete="off"
            onChange={(e) => setAddTags(e.target.value)}
          />
        </div>
        <button type="submit" className={styles.btnPrimary} disabled={!addTitle.trim() || !addContent.trim()}>
          Добавить
        </button>
      </form>

      {loadingLongterm && <p className={styles.metaLine}>Загрузка…</p>}
      <ErrorLine />

      {!loadingLongterm && longterm.length === 0 ? (
        <p className={styles.empty}>В долговременной памяти пока ничего нет</p>
      ) : (
        <ul className={styles.list}>
          {longterm.map((entry) => (
            <li key={entry.id} className={styles.item}>
              {editingId === entry.id ? (
                <form className={styles.editForm} onSubmit={onSaveEdit}>
                  <select
                    className={styles.select}
                    aria-label="Категория записи"
                    value={editKind}
                    onChange={(e) => setEditKind(e.target.value as MemoryKind)}
                  >
                    <option value="profile">Профиль</option>
                    <option value="decision">Решение</option>
                    <option value="knowledge">Знание</option>
                  </select>
                  <input
                    type="text"
                    className={styles.input}
                    aria-label="Заголовок записи"
                    value={editTitle}
                    autoComplete="off"
                    onChange={(e) => setEditTitle(e.target.value)}
                  />
                  <input
                    type="text"
                    className={styles.input}
                    aria-label="Содержание записи"
                    value={editContent}
                    autoComplete="off"
                    onChange={(e) => setEditContent(e.target.value)}
                  />
                  <input
                    type="text"
                    className={styles.input}
                    aria-label="Теги записи"
                    value={editTags}
                    placeholder="через запятую"
                    autoComplete="off"
                    onChange={(e) => setEditTags(e.target.value)}
                  />
                  <div className={styles.itemActions}>
                    <button type="submit" className={styles.btnPrimary} disabled={!editTitle.trim() || !editContent.trim()}>
                      Сохранить
                    </button>
                    <button type="button" className={styles.btn} onClick={cancelEdit}>Отмена</button>
                  </div>
                </form>
              ) : (
                <>
                  <div className={styles.itemHead}>
                    <span className={styles.itemTitle}>{entry.title}</span>
                    <span
                      className={entry.source === 'user' ? styles.badgeUser : styles.badgeAgent}
                      title={entry.source === 'user' ? 'Запись добавлена пользователем' : 'Запись добавлена агентом'}
                    >
                      {entry.source === 'user' ? 'пользователь' : 'агент'}
                    </span>
                    <span className={styles.kindTag}>{kindLabel(entry.kind)}</span>
                    <span className={styles.stamp}>{formatStamp(entry.updatedAt)}</span>
                  </div>
                  <p className={styles.content}>{entry.content}</p>
                  {entry.tags.length > 0 && (
                    <p className={styles.tags}>{entry.tags.map((tag) => `#${tag}`).join(' ')}</p>
                  )}
                  <div className={styles.itemActions}>
                    <button type="button" className={styles.btn} title={`Править «${entry.title}»`} onClick={() => beginEdit(entry)}>
                      Править
                    </button>
                    <button type="button" className={styles.btnDanger} title={`Удалить «${entry.title}»`} onClick={() => void useMemoryStore.getState().removeLongterm(entry.id)}>
                      Удалить
                    </button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/* ---------- Оверлей памяти ---------- */

export default function MemoryPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [tab, setTab] = useState<MemoryTab>('shortterm');
  const sessionId = useSessionStore((s) => s.sessionId);

  /* Открытие — идемпотентные GET трёх слоёв; повтор в StrictMode безвреден */
  useEffect(() => {
    if (!open) return;
    if (sessionId) {
      void useMemoryStore.getState().loadOverview(sessionId);
      void useMemoryStore.getState().loadWorking(sessionId);
    }
    void useMemoryStore.getState().loadLongterm();
  }, [open, sessionId]);

  const tabPanelId = `memory-tabpanel-${tab}`;

  return (
    <Overlay
      open={open}
      onClose={onClose}
      title="Память"
      subtitle="краткосрочный, рабочий и долговременный слои"
      closeLabel="Закрыть панель памяти"
      id="memoryOverlay"
    >
      <div className={styles.tabs} role="tablist" aria-label="Слои памяти">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            id={`memory-tab-${item.id}`}
            aria-selected={tab === item.id}
            aria-controls={tabPanelId}
            className={tab === item.id ? styles.tabActive : styles.tab}
            onClick={() => setTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className={styles.tabPanel} role="tabpanel" id={tabPanelId} aria-labelledby={`memory-tab-${tab}`}>
        {tab === 'shortterm' && <ShorttermTab />}
        {tab === 'working' && <WorkingTab />}
        {tab === 'longterm' && <LongtermTab />}
      </div>
    </Overlay>
  );
}
