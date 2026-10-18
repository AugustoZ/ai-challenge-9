/* Состояние памяти: working-заметки сессии, long-term записи и сводка —
   серверное состояние, не персистится. Тексты ошибок сервера уже на
   русском — прокидываем сообщение как есть (spec «Память»). */

import { create } from 'zustand';
import {
  addMemoryEntry,
  clearWorking as clearWorkingApi,
  deleteMemoryEntry,
  deleteWorkingEntry,
  fetchMemory,
  fetchMemoryOverview,
  fetchWorking,
  saveWorkingEntry,
} from '../api/client';
import type {
  MemoryCounts,
  MemoryEntry,
  MemoryKind,
  MemoryOverview,
  WorkingEntry,
} from '../api/types';

export interface WorkingInput {
  id?: string;
  title: string;
  content: string;
}

export interface LongtermInput {
  kind: MemoryKind;
  title: string;
  content: string;
  tags?: string[];
}

export interface MemoryStateData {
  /** Рабочие заметки активной сессии (GET/PUT/DELETE …/working). */
  working: WorkingEntry[];
  /** Long-term записи с учётом активного фильтра (GET /api/memory). */
  longterm: MemoryEntry[];
  /** Счётчики по категориям из последнего ответа /api/memory. */
  counts: MemoryCounts;
  /** Сводка трёх слоёв (GET /api/memory/overview); null — не загружалась. */
  overview: MemoryOverview | null;
  loadingWorking: boolean;
  loadingLongterm: boolean;
  error: string | null;
  loadWorking: (sessionId: string) => Promise<void>;
  upsertWorking: (sessionId: string, input: WorkingInput) => Promise<void>;
  removeWorking: (sessionId: string, entryId: string) => Promise<void>;
  /** Очистить рабочий слой сессии (DELETE …/working). */
  clearWorking: (sessionId: string) => Promise<void>;
  loadLongterm: (kind?: MemoryKind, q?: string) => Promise<void>;
  upsertLongterm: (input: LongtermInput) => Promise<void>;
  removeLongterm: (id: string) => Promise<void>;
  loadOverview: (sessionId: string) => Promise<void>;
  /** Полный сброс слоя памяти — зеркало hideBranches (новая сессия). */
  resetMemory: () => void;
}

/** Текст ошибки для панели; серверные сообщения уже локализованы. */
function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

const EMPTY_COUNTS: MemoryCounts = { profile: 0, decision: 0, knowledge: 0 };

export const useMemoryStore = create<MemoryStateData>()((set, get) => ({
  working: [],
  longterm: [],
  counts: EMPTY_COUNTS,
  overview: null,
  loadingWorking: false,
  loadingLongterm: false,
  error: null,
  loadWorking: async (sessionId) => {
    set({ loadingWorking: true });
    try {
      const res = await fetchWorking(sessionId);
      set({ working: res.entries, loadingWorking: false, error: null });
    } catch (err) {
      set({ loadingWorking: false, error: errorText(err) });
    }
  },
  upsertWorking: async (sessionId, input) => {
    try {
      const res = await saveWorkingEntry(sessionId, input);
      set({ working: res.entries, error: null });
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  removeWorking: async (sessionId, entryId) => {
    try {
      const res = await deleteWorkingEntry(sessionId, entryId);
      set({ working: res.entries, error: null });
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  clearWorking: async (sessionId) => {
    try {
      await clearWorkingApi(sessionId);
      set({ working: [], error: null });
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  loadLongterm: async (kind, q) => {
    set({ loadingLongterm: true });
    try {
      const res = await fetchMemory({ kind, q });
      set({
        longterm: res.entries,
        counts: res.counts,
        loadingLongterm: false,
        error: null,
      });
    } catch (err) {
      set({ loadingLongterm: false, error: errorText(err) });
    }
  },
  upsertLongterm: async (input) => {
    try {
      await addMemoryEntry(input);
      await get().loadLongterm();
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  removeLongterm: async (id) => {
    try {
      await deleteMemoryEntry(id);
      await get().loadLongterm();
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  loadOverview: async (sessionId) => {
    try {
      const overview = await fetchMemoryOverview(sessionId);
      set({ overview, error: null });
    } catch (err) {
      set({ error: errorText(err) });
    }
  },
  resetMemory: () =>
    set({
      working: [],
      longterm: [],
      counts: EMPTY_COUNTS,
      overview: null,
      loadingWorking: false,
      loadingLongterm: false,
      error: null,
    }),
}));
