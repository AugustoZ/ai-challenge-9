/* Перечень сессий для панели: идемпотентный GET /api/sessions →
   sessionsCache (script.js loadSessions L1849–1860). Ошибка/неуспех —
   кэш остаётся как был; вызывающие запрос не awaited — панель
   обновится сама. */

import { getJson } from '../../api/client';
import type { SessionsResponse } from '../../api/types';
import { useSessionStore } from '../../stores/session';
import { validSessionSummaries } from './sessionList';

export async function loadSessions(signal?: AbortSignal): Promise<void> {
  try {
    const data = await getJson<SessionsResponse>('sessions', signal);
    useSessionStore.getState().setSessionsCache(validSessionSummaries(data?.sessions));
  } catch {
    // перечень недоступен — панель остаётся с прежними записями (L1856–1858)
  }
}
