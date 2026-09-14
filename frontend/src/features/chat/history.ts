/* ============================================================
   Восстановление журнала из серверной истории — порт script.js
   renderHistory L1902–1937 (общий помощник для восстановления
   после перезагрузки, переключения сессий из панели и веток).
   Маппинг истории в записи журнала — чистая функция (тесты
   волны 6); запись в стор — applyHistory.
   ============================================================ */

import type { ChatMessage } from '../../api/types';
import { nowStamp } from '../../lib/format';
import { useSessionStore } from '../../stores/session';
import type { JournalEntry } from './Entry';

/** Служебный трафик агента (role «tool») и пустые записи не восстанавливаем —
    он эфемерен (script.js L1908–1914). */
export function isRestorableMessage(message: unknown): message is ChatMessage & { content: string } {
  if (message === null || typeof message !== 'object') return false;
  const m = message as Partial<ChatMessage>;
  return (m.role === 'user' || m.role === 'assistant')
    && typeof m.content === 'string'
    && m.content.trim().length > 0;
}

/**
 * Записи журнала из истории: ответы — без приборных показаний (они остались
 * в прошлом полёте, L1904–1905), трасса агента — из msg.events (L1926–1932).
 * Штамп шапки — время восстановления (в ваниле nowStamp при отрисовке);
 * передаётся параметром, чтобы маппинг оставался чистым.
 */
export function historyToEntries(
  messages: ChatMessage[],
  time: string,
): Array<Omit<JournalEntry, 'id'>> {
  const entries: Array<Omit<JournalEntry, 'id'>> = [];
  for (const m of messages) {
    if (!isRestorableMessage(m)) continue;
    if (m.role === 'user') {
      entries.push({ kind: 'user', text: m.content, meta: { time } });
    } else {
      entries.push({
        kind: 'bot',
        text: m.content,
        meta: { time },
        events: Array.isArray(m.events) && m.events.length ? m.events : undefined,
      });
    }
  }
  return entries;
}

/**
 * Перезаполнение журнала историей (renderHistory): true — журнал был
 * перезаполнен; false — показывать нечего, журнал не тронут (решение про
 * приветствие остаётся за вызывающим — script.js L1891–1894, L2166–2169).
 */
export function applyHistory(messages: ChatMessage[], time = nowStamp()): boolean {
  const entries = historyToEntries(messages, time);
  if (!entries.length) return false;
  const store = useSessionStore.getState();
  store.clearEntries();
  for (const entry of entries) store.appendEntry(entry);
  return true;
}
