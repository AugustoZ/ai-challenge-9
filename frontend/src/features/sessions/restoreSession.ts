/* ============================================================
   Переключение на выбранную сессию — порт script.js
   switchToSession L1862–1898: идентификатор запоминается сразу,
   история отрисовывается общим помощником (applyHistory);
   неуспех/сервер недоступен — возврат к прежней активной записи.
   Вызывается только из обработчика клика (правила эффектов);
   чтение истории — идемпотентный GET с signal отмены.
   ============================================================ */

import { ApiError, getJson } from '../../api/client';
import type { SessionState } from '../../api/types';
import { useSessionStore } from '../../stores/session';
import { applyHistory } from '../chat/history';
import { loadBranches } from '../branches/branchesApi';
import { loadSessions } from './loadSessions';

export async function switchToSession(id: string, signal?: AbortSignal): Promise<void> {
  const session = useSessionStore.getState();
  if (!id || id === session.sessionId) return;
  const previous = session.sessionId;
  session.setSessionId(id);

  let data: SessionState;
  try {
    data = await getJson<SessionState>(`session/${encodeURIComponent(id)}`, signal);
  } catch (err) {
    if (signal?.aborted) return; // переключение отменено новым кликом — молча
    /* Сессия не дожила до переключения (не-2xx) или сервер недоступен:
       возвращаем прежнюю активную (L1875–1888); при неуспехе перечень
       перечитывается — исчезнувшая из него строка уйдёт */
    useSessionStore.getState().setSessionId(previous);
    if (err instanceof ApiError) void loadSessions();
    return;
  }

  /* Истории нет — журнал возвращается к исходному виду (L1891–1894);
     лампа активной записи переезжает сама (селектор sessionsCache) */
  if (!applyHistory(data.messages)) {
    useSessionStore.getState().clearEntries();
  }
  void loadBranches(id);
}
