/* Тесты восстановления журнала из серверной истории (волна 6) — порт
   script.js renderHistory L1902–1937: фильтр служебного трафика и
   пустых записей, маппинг в записи журнала, перезаполнение стора. */

import { beforeEach, describe, expect, it } from 'vitest';
import type { AgentEvent, ChatMessage } from '../../api/types';
import { useSessionStore } from '../../stores/session';
import { applyHistory, historyToEntries, isRestorableMessage } from './history';

const TIME = '12:00';

function msg(overrides: Partial<ChatMessage>): ChatMessage {
  return { role: 'user', content: 'вопрос', ...overrides };
}

beforeEach(() => {
  const store = useSessionStore.getState();
  store.clearEntries();
});

describe('isRestorableMessage — фильтр истории (script.js L1909–1914)', () => {
  it('только user/assistant с непустым строковым content', () => {
    expect(isRestorableMessage(msg({}))).toBe(true);
    expect(isRestorableMessage(msg({ role: 'assistant', content: 'ответ' }))).toBe(true);
    expect(isRestorableMessage(msg({ role: 'tool', content: 'данные' }))).toBe(false);
    expect(isRestorableMessage(msg({ content: '   ' }))).toBe(false);
    expect(isRestorableMessage(msg({ content: null }))).toBe(false);
    expect(isRestorableMessage(null)).toBe(false);
    expect(isRestorableMessage('мимо')).toBe(false);
  });
});

describe('historyToEntries — маппинг истории (script.js L1922–1934)', () => {
  it('запрос — сырой текст, ответ — с показаниями из msg.usage', () => {
    const usage = {
      elapsedMs: 42,
      promptTokens: 1,
      completionTokens: 2,
      totalTokens: 3,
      summaryApplied: true,
      summaryTokens: 7,
    };
    const entries = historyToEntries([
      msg({}),
      msg({ role: 'assistant', content: 'ответ', usage }),
    ], TIME);
    expect(entries).toEqual([
      { kind: 'user', text: 'вопрос', meta: { time: TIME } },
      { kind: 'bot', text: 'ответ', meta: { time: TIME, tokenData: usage }, events: undefined },
    ]);
  });

  it('ответ без usage — записи без приборных показаний', () => {
    const entries = historyToEntries([
      msg({ role: 'assistant', content: 'ответ' }),
    ], TIME);
    expect(entries[0]?.meta?.tokenData).toBeUndefined();
  });

  it('неполные показания дозаполняются нейтральными значениями', () => {
    const [entry] = historyToEntries([
      msg({ role: 'assistant', content: 'ответ', usage: { totalTokens: 5 } }),
    ], TIME);
    expect(entry?.meta?.tokenData).toEqual({
      elapsedMs: null,
      promptTokens: null,
      completionTokens: null,
      totalTokens: 5,
      summaryApplied: false,
      summaryTokens: null,
    });
  });

  it('служебный трафик и пустые записи пропускаются молча', () => {
    const entries = historyToEntries([
      msg({ role: 'tool', content: 'данные' }),
      msg({ content: '' }),
    ], TIME);
    expect(entries).toEqual([]);
  });

  it('трасса агента восстанавливается из msg.events', () => {
    const events: AgentEvent[] = [{ type: 'final', step: 1, text: 'готово', reason: 'готово' }];
    const [entry] = historyToEntries([msg({ role: 'assistant', content: 'ответ', events })], TIME);
    expect(entry?.events).toEqual(events);
  });
});

describe('applyHistory — перезаполнение журнала (script.js L1916–1936)', () => {
  it('журнал расчищается под восстановленные записи, id выдаёт стор', () => {
    const store = useSessionStore.getState();
    store.appendEntry({ kind: 'system', text: 'мусор', meta: { time: TIME } });
    const filled = applyHistory([msg({}), msg({ role: 'assistant', content: 'ответ' })], TIME);
    expect(filled).toBe(true);
    const entries = useSessionStore.getState().entries;
    expect(entries.map((e) => e.kind)).toEqual(['user', 'bot']);
    expect(entries[0]?.id).toBe('entry-2');
  });

  it('показывать нечего — журнал не тронут (решение за вызывающим)', () => {
    useSessionStore.getState().appendEntry({ kind: 'user', text: 'живой', meta: { time: TIME } });
    expect(applyHistory([msg({ content: '' })], TIME)).toBe(false);
    expect(useSessionStore.getState().entries).toHaveLength(1);
  });

  it('топливомер восстанавливается из показаний истории и сбрасывает чужой счётчик', () => {
    const store = useSessionStore.getState();
    // Чужие показания от предыдущей сессии
    store.applyUsage({ promptTokens: 100, completionTokens: 100, totalTokens: 999 });
    store.applyUsage({ totalTokens: 1 });

    const filled = applyHistory([
      msg({}),
      msg({ role: 'assistant', content: 'а', usage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 } }),
      msg({ role: 'assistant', content: 'б', usage: { totalTokens: 4 } }),
    ], TIME);
    expect(filled).toBe(true);
    expect(useSessionStore.getState().totalTokens).toBe(7);
    expect(useSessionStore.getState().tokenHistory).toEqual([
      { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
      { promptTokens: 0, completionTokens: 0, totalTokens: 4 },
    ]);
  });

  it('пустая история обнуляет топливомер (переключение на пустую сессию)', () => {
    const store = useSessionStore.getState();
    store.applyUsage({ totalTokens: 555 });

    expect(applyHistory([msg({ content: '' })], TIME)).toBe(false);
    expect(useSessionStore.getState().totalTokens).toBe(0);
    expect(useSessionStore.getState().tokenHistory).toEqual([]);
  });
});
