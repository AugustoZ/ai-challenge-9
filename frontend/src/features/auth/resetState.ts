/* ============================================================
   Сброс бортового состояния при выходе/смене пользователя (план
   Task 14): пользователь B не должен увидеть в той же вкладке ни
   одного следа пользователя A. Чистая логика clearUserState
   принимает операции инъекцией (тесты без сторов), обёртка
   resetUserState работает на реальных сторах приложения.

   Reset-лист плана (обязательные очистки):
   - sessionStore: sessionId=null, журнал (entries), топливомер
     (totalTokens/tokenHistory), перечень сессий, факты;
   - branchesStore: чипы веток и activeBranchId (hideBranches);
   - memoryStore: working/long-term/счётчики/сводка (resetMemory);
   - twinRegistry: хэндлы колонок зеркала (их sessionIdRef умирает
     вместе с TwinPane);
   - черновик SendBar: поле неконтролируемое и умирает вместе с
     консолью (App рендерит AuthGate вместо контента) — отдельной
     очистки не существует по устройству; защиту от «переехавшего»
     черновика даёт сам условный рендер.

   SettingsStore НЕ входит в reset-лист (решение по плану): настройки
   приборов персистятся на уровне браузера (shturman.settings.v1),
   это конфигурация прибора, а не данные пользователя; персональные
   данные живут на сервере per-user.

   Вызывается при переходе auth-стора authed → anon (клавиши чипа,
   logout, любой 401 в client/sse). Перечень сессий и память нового
   пользователя перечитываются после повторного входа: SessionsPanel
   запрашивает /api/sessions в mount-эффекте, MemoryPanel — при
   открытии (перезагрузка списков, план Task 14).
   ============================================================ */

import type { Fact, SessionSummary } from '../../api/types';
import { useBranchesStore } from '../../stores/branches';
import { useMemoryStore } from '../../stores/memory';
import { useSessionStore } from '../../stores/session';
import { resetTwinRegistry } from '../mirror/twinRegistry';

/** Часть session-стора, участвующая в сбросе (структурно = его actions). */
export interface SessionResetOps {
  setSessionId: (sessionId: string | null) => void;
  clearEntries: () => void;
  resetSessionFuel: () => void;
  setSessionsCache: (sessions: SessionSummary[]) => void;
  setFacts: (facts: Fact[]) => void;
}

export interface BranchResetOps {
  hideBranches: () => void;
}

export interface MemoryResetOps {
  resetMemory: () => void;
}

/** Реестр зеркала адресуется одной операцией очистки. */
export interface TwinsResetOps {
  clear: () => void;
}

export interface UserStateStores {
  session: SessionResetOps;
  branches: BranchResetOps;
  memory: MemoryResetOps;
  twins: TwinsResetOps;
}

/** Сброс без сети и без обращений к auth-стору: чистые записи в сторы. */
export function clearUserState(stores: UserStateStores): void {
  stores.session.setSessionId(null);
  stores.session.clearEntries();
  stores.session.resetSessionFuel();
  stores.session.setSessionsCache([]);
  stores.session.setFacts([]);
  stores.branches.hideBranches();
  stores.memory.resetMemory();
  stores.twins.clear();
}

/** Сброс на реальных сторах приложения — точка входа для App (logout/401). */
export function resetUserState(): void {
  clearUserState({
    session: useSessionStore.getState(),
    branches: useBranchesStore.getState(),
    memory: useMemoryStore.getState(),
    twins: { clear: resetTwinRegistry },
  });
}
