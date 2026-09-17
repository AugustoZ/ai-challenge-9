/* Серверное состояние сессии — не персистится. Мутации повторяют точки
   записи vanilla (applyChatResponse / loadSessions / новая сессия).
   Записи журнала (волна 5) обновляются по id со structural sharing:
   маппинг заменяет только затронутую запись (spec «Состояние»). */

import { create } from 'zustand';
import type { AgentEvent, Fact, SessionSummary, TaskState } from '../api/types';
import type { JournalEntry } from '../features/chat/Entry';

/** Запись истории топливомера: один обмен = один столбец графика. */
export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

/** Форма применения ответа: токены приходят опциональными с провода. */
export interface UsageInput {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
}

export interface SessionStateData {
  /** Идентификатор сессии диалога; выдаёт сервер (ChatResponse.sessionId). */
  sessionId: string | null;
  /** Общий расход топлива за сессию (счётчик на статус-полосе). */
  totalTokens: number;
  tokenHistory: TokenUsage[];
  /** Кэш панели сессий (GET /api/sessions). */
  sessionsCache: SessionSummary[];
  /** Актуальный список фактов сессии (стратегия «Факты», ChatResponse.facts). */
  facts: Fact[];
  /** Состояние задачи FSM (ChatResponse.taskState); null — задачи нет. */
  taskState: TaskState | null;
  /** Бортовой журнал: записи дописываются, обновляются по id. */
  entries: JournalEntry[];
  setSessionId: (sessionId: string | null) => void;
  /** Учёт токенов ответа: totalTokens != null — плюс к счётчику и в историю. */
  applyUsage: (usage: UsageInput) => void;
  /** Полный сброс топливомера: новая сессия начинается с чистого табло. */
  resetSessionFuel: () => void;
  /**
   * Восстановить топливомер из показаний истории (usage на assistant-сообщениях):
   * счётчик = сумма показаний. Заменяет и чужой счётчик, оставшийся от
   * предыдущей сессии, — переключение всегда начинается с чистого табло.
   */
  setSessionFuel: (history: TokenUsage[]) => void;
  setSessionsCache: (sessions: SessionSummary[]) => void;
  /** Факты последнего ответа (updateFactsPanel — панель скрыта вне «Фактов»). */
  setFacts: (facts: Fact[]) => void;
  /** Состояние задачи последнего ответа (ChatResponse.taskState, безусловно каждый ход). */
  setTaskState: (taskState: TaskState | null) => void;
  /**
   * Дописать запись и вернуть её id. Id генерирует стор — стабильный
   * монотонный счётчик, «Новый диалог» (clearEntries) его не сбрасывает,
   * поэтому React-ключи не переиспользуются.
   */
  appendEntry: (entry: Omit<JournalEntry, 'id'>) => string;
  /** Точечная правка записи по id (structural sharing — остальные не трогаются). */
  patchEntry: (id: string, patch: Partial<Omit<JournalEntry, 'id'>>) => void;
  /** Событие хода агента в живую трассу записи (script.js appendLiveAgentEvent). */
  appendTraceEvent: (entryId: string, event: AgentEvent) => void;
  /** Убрать запись по id — hideTyping у заглушки (script.js L602–604). */
  removeEntry: (id: string) => void;
  /** Полная очистка журнала («Новый диалог», волна 6). */
  clearEntries: () => void;
}

/** Монотонный счётчик id записей; живёт вне стора — не сбрасывается. */
let entrySeq = 0;

function nextEntryId(): string {
  entrySeq += 1;
  return `entry-${entrySeq}`;
}

export const useSessionStore = create<SessionStateData>()((set) => ({
  sessionId: null,
  totalTokens: 0,
  tokenHistory: [],
  sessionsCache: [],
  facts: [],
  taskState: null,
  entries: [],
  setSessionId: (sessionId) => set({ sessionId }),
  applyUsage: (usage) =>
    set((state) => {
      if (usage.totalTokens == null) return state;
      return {
        totalTokens: state.totalTokens + usage.totalTokens,
        tokenHistory: [
          ...state.tokenHistory,
          {
            promptTokens: usage.promptTokens ?? 0,
            completionTokens: usage.completionTokens ?? 0,
            totalTokens: usage.totalTokens,
          },
        ],
      };
    }),
  resetSessionFuel: () => set({ totalTokens: 0, tokenHistory: [] }),
  setSessionFuel: (history) =>
    set({
      tokenHistory: history,
      totalTokens: history.reduce((sum, usage) => sum + usage.totalTokens, 0),
    }),
  setSessionsCache: (sessionsCache) => set({ sessionsCache }),
  setFacts: (facts) => set({ facts }),
  setTaskState: (taskState) => set({ taskState }),
  appendEntry: (entry) => {
    const id = nextEntryId();
    set((state) => ({ entries: [...state.entries, { ...entry, id }] }));
    return id;
  },
  patchEntry: (id, patch) =>
    set((state) => ({
      entries: state.entries.map((entry) =>
        entry.id === id ? { ...entry, ...patch } : entry,
      ),
    })),
  appendTraceEvent: (entryId, event) =>
    set((state) => ({
      entries: state.entries.map((entry) =>
        entry.id === entryId
          ? { ...entry, events: [...(entry.events ?? []), event] }
          : entry,
      ),
    })),
  removeEntry: (id) =>
    set((state) => ({ entries: state.entries.filter((entry) => entry.id !== id) })),
  clearEntries: () => set({ entries: [] }),
}));
