/* Тесты серверных сторов (session, branches) — мутации повторяют точки
   записи vanilla (script.js applyChatResponse / addBotMessage / showTyping /
   loadSessions). Записи журнала — волна 5: дописывание, правки по id,
   живая трасса агента. */

import { beforeEach, describe, expect, it } from 'vitest';
import type { AgentEvent } from '../api/types';
import { useBranchesStore } from './branches';
import { useSessionStore } from './session';

beforeEach(() => {
  useSessionStore.getState().resetSessionFuel();
  useSessionStore.getState().setSessionId(null);
  useSessionStore.getState().setSessionsCache([]);
  useSessionStore.getState().setFacts([]);
  useSessionStore.getState().clearEntries();
  useBranchesStore.getState().hideBranches();
});

describe('session store', () => {
  it('setSessionId хранит идентификатор серверной сессии', () => {
    const store = useSessionStore.getState();
    store.setSessionId('srv-1');
    expect(useSessionStore.getState().sessionId).toBe('srv-1');
    store.setSessionId(null);
    expect(useSessionStore.getState().sessionId).toBeNull();
  });

  it('applyUsage копит сумму и пишет запись истории', () => {
    const { applyUsage } = useSessionStore.getState();
    applyUsage({ promptTokens: 10, completionTokens: 20, totalTokens: 30 });
    applyUsage({ promptTokens: 5, completionTokens: 7, totalTokens: 12 });

    const state = useSessionStore.getState();
    expect(state.totalTokens).toBe(42);
    expect(state.tokenHistory).toEqual([
      { promptTokens: 10, completionTokens: 20, totalTokens: 30 },
      { promptTokens: 5, completionTokens: 7, totalTokens: 12 },
    ]);
  });

  it('applyUsage добивает отсутствующие токены нулями', () => {
    useSessionStore.getState().applyUsage({ totalTokens: 5 });
    const history = useSessionStore.getState().tokenHistory;
    expect(history[history.length - 1]).toEqual({
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 5,
    });
  });

  it('ответ без totalTokens ничего не меняет', () => {
    const before = useSessionStore.getState();
    before.applyUsage({ promptTokens: 3 });
    const after = useSessionStore.getState();
    expect(after.totalTokens).toBe(before.totalTokens);
    expect(after.tokenHistory).toBe(before.tokenHistory);
  });

  it('resetSessionFuel чистит табло под новую сессию', () => {
    useSessionStore.getState().applyUsage({ totalTokens: 9 });
    useSessionStore.getState().resetSessionFuel();
    const state = useSessionStore.getState();
    expect(state.totalTokens).toBe(0);
    expect(state.tokenHistory).toEqual([]);
  });

  it('setSessionFuel выставляет историю показаний и сумму счётчика', () => {
    const history = [
      { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
      { promptTokens: 4, completionTokens: 5, totalTokens: 9 },
    ];
    useSessionStore.getState().applyUsage({ totalTokens: 100 });
    useSessionStore.getState().setSessionFuel(history);
    const state = useSessionStore.getState();
    expect(state.tokenHistory).toEqual(history);
    expect(state.totalTokens).toBe(12);

    useSessionStore.getState().setSessionFuel([]);
    expect(useSessionStore.getState().totalTokens).toBe(0);
    expect(useSessionStore.getState().tokenHistory).toEqual([]);
  });

  it('setSessionsCache кэширует список сессий', () => {
    const sessions = [{ id: 's1', title: 'Диалог', updatedAt: 1, messageCount: 2 }];
    useSessionStore.getState().setSessionsCache(sessions);
    expect(useSessionStore.getState().sessionsCache).toEqual(sessions);
  });
});

describe('branches store', () => {
  it('activeBranchId по умолчанию null и переключается', () => {
    expect(useBranchesStore.getState().activeBranchId).toBeNull();
    useBranchesStore.getState().setActiveBranchId('b1');
    expect(useBranchesStore.getState().activeBranchId).toBe('b1');
    useBranchesStore.getState().setActiveBranchId(null);
    expect(useBranchesStore.getState().activeBranchId).toBeNull();
  });
});

describe('session store: записи журнала (волна 5)', () => {
  it('appendEntry дописывает запись и возвращает стабильный уникальный id', () => {
    const store = useSessionStore.getState();
    const first = store.appendEntry({ kind: 'user', text: 'первый', meta: { time: '10:00' } });
    const second = store.appendEntry({ kind: 'bot', text: 'второй', meta: { time: '10:01' } });

    const entries = useSessionStore.getState().entries;
    expect(entries).toHaveLength(2);
    expect(entries[0].id).toBe(first);
    expect(entries[1].id).toBe(second);
    expect(first).not.toBe(second);
    expect(entries[0]).toEqual({ id: first, kind: 'user', text: 'первый', meta: { time: '10:00' } });
  });

  it('clearEntries чистит журнал, счётчик id не переиспользуется', () => {
    const store = useSessionStore.getState();
    const before = store.appendEntry({ kind: 'user', text: 'до сброса' });
    useSessionStore.getState().clearEntries();
    expect(useSessionStore.getState().entries).toEqual([]);

    const after = store.appendEntry({ kind: 'user', text: 'после сброса' });
    expect(after).not.toBe(before);
  });

  it('patchEntry правит только свою запись, соседние не пересоздаются', () => {
    const store = useSessionStore.getState();
    const user = store.appendEntry({ kind: 'user', text: 'вопрос' });
    const bot = store.appendEntry({ kind: 'bot', text: '', typing: { statusText: '' } });

    const entriesBefore = useSessionStore.getState().entries;
    useSessionStore.getState().patchEntry(bot, { text: 'ответ', typing: undefined });
    const entriesAfter = useSessionStore.getState().entries;

    expect(entriesAfter.find((e) => e.id === bot)).toMatchObject({ kind: 'bot', text: 'ответ' });
    expect(entriesAfter.find((e) => e.id === user)).toBe(entriesBefore.find((e) => e.id === user));
    expect(entriesAfter.find((e) => e.id === bot)?.typing).toBeUndefined();
  });

  it('appendTraceEvent растит живую трассу записи по одному событию', () => {
    const store = useSessionStore.getState();
    const placeholder = store.appendEntry({
      kind: 'bot',
      text: '',
      typing: { statusText: '' },
      events: [],
    });

    const step: AgentEvent = { type: 'step_started', step: 1 };
    const call: AgentEvent = { type: 'tool_call', step: 1, tool: 'write_file', arguments: '{}' };
    useSessionStore.getState().appendTraceEvent(placeholder, step);
    useSessionStore.getState().appendTraceEvent(placeholder, call);

    const entry = useSessionStore.getState().entries.find((e) => e.id === placeholder);
    expect(entry?.events).toEqual([step, call]);
  });

  it('appendTraceEvent на записи без трассы заводит список событий', () => {
    const store = useSessionStore.getState();
    const id = store.appendEntry({ kind: 'bot', text: '' });
    useSessionStore.getState().appendTraceEvent(id, { type: 'step_started', step: 1 });
    expect(useSessionStore.getState().entries.find((e) => e.id === id)?.events).toHaveLength(1);
  });

  it('removeEntry убирает заглушку по id — hideTyping (script.js L602–604)', () => {
    const store = useSessionStore.getState();
    const user = store.appendEntry({ kind: 'user', text: 'вопрос' });
    const placeholder = store.appendEntry({ kind: 'bot', text: '', typing: { statusText: '' } });

    useSessionStore.getState().removeEntry(placeholder);
    const entries = useSessionStore.getState().entries;
    expect(entries).toHaveLength(1);
    expect(entries[0].id).toBe(user);

    /* Идемпотентно: повторный hideTyping ничего не ломает */
    useSessionStore.getState().removeEntry(placeholder);
    expect(useSessionStore.getState().entries).toHaveLength(1);
  });
});
