/* Тесты стора памяти: клиент ../api/client замокан целиком (vi.mock) —
   сеть не дёргается, проверяются мутации состояния и проброс ошибок. */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addMemoryEntry,
  clearWorking,
  deleteMemoryEntry,
  fetchMemory,
  fetchMemoryOverview,
  fetchWorking,
  saveWorkingEntry,
} from '../api/client';
import type { MemoryEntry, MemoryOverview, WorkingEntry } from '../api/types';
import { useMemoryStore } from './memory';

vi.mock('../api/client', () => ({
  fetchWorking: vi.fn(),
  saveWorkingEntry: vi.fn(),
  deleteWorkingEntry: vi.fn(),
  clearWorking: vi.fn(),
  fetchMemory: vi.fn(),
  addMemoryEntry: vi.fn(),
  updateMemoryEntry: vi.fn(),
  deleteMemoryEntry: vi.fn(),
  fetchMemoryOverview: vi.fn(),
}));

const workingEntry: WorkingEntry = {
  id: 'w1',
  title: 'Заметка',
  content: 'Текст',
  createdAt: 1,
  updatedAt: 2,
};

const memoryEntry: MemoryEntry = {
  id: 'm1',
  kind: 'decision',
  title: 'Выбор стека',
  content: 'Оставляем Vite',
  tags: ['стек'],
  source: 'user',
  createdAt: 3,
  updatedAt: 4,
};

const counts = { profile: 1, decision: 2, knowledge: 3 };

beforeEach(() => {
  vi.clearAllMocks();
  useMemoryStore.getState().resetMemory();
});

describe('memory store: working', () => {
  it('loadWorking заполняет working, снимает флаг и чистит ошибку', async () => {
    vi.mocked(fetchWorking).mockResolvedValueOnce({ entries: [workingEntry] });

    await useMemoryStore.getState().loadWorking('sess-1');

    const state = useMemoryStore.getState();
    expect(state.working).toEqual([workingEntry]);
    expect(state.loadingWorking).toBe(false);
    expect(state.error).toBeNull();
    expect(fetchWorking).toHaveBeenCalledWith('sess-1');
  });

  it('upsertWorking сохраняет заметку и берёт список из ответа сервера', async () => {
    const updated = { ...workingEntry, id: 'w9', title: 'План' };
    vi.mocked(saveWorkingEntry).mockResolvedValueOnce({ entries: [updated] });

    await useMemoryStore.getState().upsertWorking('sess-7', { title: 'План', content: 'Текст' });

    expect(saveWorkingEntry).toHaveBeenCalledWith(
      'sess-7',
      { title: 'План', content: 'Текст' },
    );
    expect(useMemoryStore.getState().working).toEqual([updated]);
  });

  it('clearWorking чистит список после ответа сервера', async () => {
    useMemoryStore.setState({ working: [workingEntry] });
    vi.mocked(clearWorking).mockResolvedValueOnce({ entries: [] });

    await useMemoryStore.getState().clearWorking('sess-1');

    expect(clearWorking).toHaveBeenCalledWith('sess-1');
    expect(useMemoryStore.getState().working).toEqual([]);
  });
});

describe('memory store: long-term', () => {
  it('loadLongterm с фильтром kind+q заполняет список и счётчики', async () => {
    vi.mocked(fetchMemory).mockResolvedValueOnce({
      entries: [memoryEntry],
      counts,
    });

    await useMemoryStore.getState().loadLongterm('decision', 'дедлайн');

    expect(fetchMemory).toHaveBeenCalledWith({ kind: 'decision', q: 'дедлайн' });
    const state = useMemoryStore.getState();
    expect(state.longterm).toEqual([memoryEntry]);
    expect(state.counts).toEqual(counts);
    expect(state.loadingLongterm).toBe(false);
  });

  it('removeLongterm удаляет запись и перезагружает список со счётчиками', async () => {
    vi.mocked(deleteMemoryEntry).mockResolvedValueOnce({ deleted: true });
    vi.mocked(fetchMemory).mockResolvedValueOnce({ entries: [], counts });

    await useMemoryStore.getState().removeLongterm('m1');

    expect(deleteMemoryEntry).toHaveBeenCalledWith('m1');
    expect(fetchMemory).toHaveBeenCalledWith({ kind: undefined, q: undefined });
    const state = useMemoryStore.getState();
    expect(state.longterm).toEqual([]);
    expect(state.counts).toEqual(counts);
  });

  it('upsertLongterm добавляет запись и обновляет список', async () => {
    vi.mocked(addMemoryEntry).mockResolvedValueOnce({ entry: memoryEntry });
    vi.mocked(fetchMemory).mockResolvedValueOnce({
      entries: [memoryEntry],
      counts,
    });

    await useMemoryStore
      .getState()
      .upsertLongterm({ kind: 'decision', title: 'Выбор стека', content: 'Оставляем Vite' });

    expect(addMemoryEntry).toHaveBeenCalledWith({
      kind: 'decision',
      title: 'Выбор стека',
      content: 'Оставляем Vite',
    });
    const state = useMemoryStore.getState();
    expect(state.longterm).toEqual([memoryEntry]);
    expect(state.counts).toEqual(counts);
  });
});

describe('memory store: overview и ошибки', () => {
  it('loadOverview заполняет сводку трёх слоёв', async () => {
    const overview: MemoryOverview = {
      shortTerm: { turns: 4, hasSummary: true },
      working: { entries: 2 },
      longTerm: { total: 6, profile: 1, decision: 2, knowledge: 3 },
    };
    vi.mocked(fetchMemoryOverview).mockResolvedValueOnce(overview);

    await useMemoryStore.getState().loadOverview('sess-1');

    expect(fetchMemoryOverview).toHaveBeenCalledWith('sess-1');
    expect(useMemoryStore.getState().overview).toEqual(overview);
  });

  it('ошибка загрузки пишет сообщение и снимает флаг загрузки', async () => {
    useMemoryStore.setState({ error: 'старая ошибка' });
    vi.mocked(fetchWorking).mockRejectedValueOnce(new Error('Сервер недоступен'));

    await useMemoryStore.getState().loadWorking('sess-1');

    const state = useMemoryStore.getState();
    expect(state.error).toContain('Сервер недоступен');
    expect(state.loadingWorking).toBe(false);
  });

  it('ошибка загрузки long-term тоже попадает в error', async () => {
    vi.mocked(fetchMemory).mockRejectedValueOnce(new Error('HTTP 500: упало'));

    await useMemoryStore.getState().loadLongterm('profile');

    const state = useMemoryStore.getState();
    expect(state.error).toContain('HTTP 500: упало');
    expect(state.loadingLongterm).toBe(false);
    expect(state.longterm).toEqual([]);
  });

  it('resetMemory возвращает стор к исходному состоянию', async () => {
    vi.mocked(fetchWorking).mockRejectedValueOnce(new Error('бой'));
    await useMemoryStore.getState().loadWorking('sess-1');

    useMemoryStore.getState().resetMemory();

    const state = useMemoryStore.getState();
    expect(state.working).toEqual([]);
    expect(state.longterm).toEqual([]);
    expect(state.counts).toEqual({ profile: 0, decision: 0, knowledge: 0 });
    expect(state.overview).toBeNull();
    expect(state.error).toBeNull();
    expect(state.loadingWorking).toBe(false);
    expect(state.loadingLongterm).toBe(false);
  });
});
