/* ============================================================
   Тесты сброса бортового состояния (план Task 14): после сброса
   sessionId=null, журнал и топливомер пусты, перечень сессий и
   факты очищены, ветки скрыты, память пуста, реестр зеркала пуст.
   Две плоскости: чистая логика на инъекции операций и обёртка на
   реальных сторах приложения.
   ============================================================ */

import { beforeEach, describe, expect, it } from 'vitest';

import { useBranchesStore } from '../../stores/branches';
import { useMemoryStore } from '../../stores/memory';
import { useSessionStore } from '../../stores/session';
import { getTwin, registerTwin, resetTwinRegistry, unregisterTwin } from '../mirror/twinRegistry';
import { clearUserState, resetUserState, type UserStateStores } from './resetState';
import type { SessionSummary } from '../../api/types';

/** Фикстура сеанса пользователя A: всё, что обязан вычистить сброс. */
function dirtState(): SessionSummary[] {
  return [{ id: 'sess-1', title: 'Диалог Шумахера', updatedAt: 42, messageCount: 3 }];
}

describe('clearUserState (чистая логика на инъекции)', () => {
  it('вызывает каждую очистку ровно один раз', () => {
    const calls: string[] = [];
    const stores: UserStateStores = {
      session: {
        setSessionId: (id) => calls.push(`sessionId=${id}`),
        clearEntries: () => calls.push('entries'),
        resetSessionFuel: () => calls.push('fuel'),
        setSessionsCache: (sessions) => calls.push(`cache:${sessions.length}`),
        setFacts: (facts) => calls.push(`facts:${facts.length}`),
      },
      branches: { hideBranches: () => calls.push('branches') },
      memory: { resetMemory: () => calls.push('memory') },
      twins: { clear: () => calls.push('twins') },
    };

    clearUserState(stores);

    expect(calls).toEqual([
      'sessionId=null',
      'entries',
      'fuel',
      'cache:0',
      'facts:0',
      'branches',
      'memory',
      'twins',
    ]);
  });
});

describe('resetUserState (реальные сторы приложения)', () => {
  beforeEach(() => {
    /* Каждый тест стартует с чистыми сторами — мусор вносит сам */
    resetUserState();
    resetTwinRegistry();
  });

  it('после сброса sessionId=null, журнал и топливомер пусты (acceptance плана)', () => {
    const session = useSessionStore.getState();
    session.setSessionId('sess-a1');
    session.appendEntry({ kind: 'user', text: 'Дай сводку', meta: { time: '12:00:00' } });
    session.appendEntry({ kind: 'bot', text: 'Готово', meta: { time: '12:00:05' } });
    session.applyUsage({ totalTokens: 321 });
    session.setSessionsCache(dirtState());
    session.setFacts([{ key: 'борт', value: 'Шумахер' }]);

    resetUserState();

    const after = useSessionStore.getState();
    expect(after.sessionId).toBeNull();
    expect(after.entries).toEqual([]);
    expect(after.totalTokens).toBe(0);
    expect(after.tokenHistory).toEqual([]);
    expect(after.sessionsCache).toEqual([]);
    expect(after.facts).toEqual([]);
  });

  it('ветки скрыты: список пуст, бар не виден, activeBranchId обнулён', () => {
    useBranchesStore.getState().setBranches(
      [{ branchId: 'b1', label: 'ветка', forkUserCount: 0, userCountSinceFork: 0, messageCount: 1, active: true }],
      'b1',
      true,
    );

    resetUserState();

    const after = useBranchesStore.getState();
    expect(after).toMatchObject({ activeBranchId: null, list: [], barVisible: false });
  });

  it('память пуста: working/long-term/счётчики/сводка, ошибки и флаги нагрузки сняты', () => {
    useMemoryStore.setState({
      working: [{ id: 'w1', title: 'заметка', content: 'проверка', createdAt: 1, updatedAt: 1 }],
      longterm: [],
      counts: { profile: 2, decision: 1, knowledge: 4 },
      overview: {
        shortTerm: { turns: 3, hasSummary: false },
        working: { entries: 1 },
        longTerm: { total: 7, profile: 2, decision: 1, knowledge: 4 },
      },
      loadingWorking: true,
      loadingLongterm: true,
      error: 'Запись не найдена.',
    });

    resetUserState();

    const after = useMemoryStore.getState();
    expect(after.working).toEqual([]);
    expect(after.longterm).toEqual([]);
    expect(after.counts).toEqual({ profile: 0, decision: 0, knowledge: 0 });
    expect(after.overview).toBeNull();
    expect(after.loadingWorking).toBe(false);
    expect(after.loadingLongterm).toBe(false);
    expect(after.error).toBeNull();
  });

  it('реестр зеркала пуст: хэндлы обеих колонок убраны', () => {
    const side = (name: string) => ({
      beginExchange: () => 1,
      getSessionId: () => `twin-${name}`,
      applyResponse: () => undefined,
      applyFault: () => undefined,
      totals: () => ({ total: 0, summaryTotal: 0, exchanges: 0 }),
    });
    registerTwin('full', side('full'));
    registerTwin('compressed', side('compressed'));

    resetUserState();

    expect(getTwin('full')).toBeUndefined();
    expect(getTwin('compressed')).toBeUndefined();
  });

  it('сброс идемпотентен: повторный вызов оставляет сторы чистыми', () => {
    useSessionStore.getState().setSessionId('sess-a2');
    resetUserState();
    resetUserState();
    expect(useSessionStore.getState().sessionId).toBeNull();
    expect(useSessionStore.getState().entries).toEqual([]);
    expect(useBranchesStore.getState().list).toEqual([]);
    expect(useMemoryStore.getState().working).toEqual([]);
  });

  it('счётчик id записей не откатывается: новый вход не переиспользует ключи', () => {
    const first = useSessionStore.getState().appendEntry({ kind: 'user', text: 'a', meta: { time: 't' } });
    resetUserState();
    const second = useSessionStore.getState().appendEntry({ kind: 'user', text: 'b', meta: { time: 't' } });
    expect(second).not.toBe(first);
  });

  it('unregisterTwin после сброса не падает (демонтирование колонки вне сброса)', () => {
    registerTwin('full', {
      beginExchange: () => 1,
      getSessionId: () => null,
      applyResponse: () => undefined,
      applyFault: () => undefined,
      totals: () => ({ total: 0, summaryTotal: 0, exchanges: 0 }),
    });
    resetUserState();
    expect(() => unregisterTwin('full')).not.toThrow();
  });
});
