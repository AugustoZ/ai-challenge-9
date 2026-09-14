/* ============================================================
   Восстановление журнала из серверной истории — порт script.js
   renderHistory L1902–1937 (общий помощник для восстановления
   после перезагрузки, переключения сессий из панели и веток).
   Маппинг истории в записи журнала — чистая функция (тесты
   волны 6); запись в стор — applyHistory.
   ============================================================ */

import type { ChatMessage, TurnUsage } from '../../api/types';
import { nowStamp } from '../../lib/format';
import { useSessionStore } from '../../stores/session';
import type { JournalEntry, EntryTokenData } from './Entry';

/** Служебный трафик агента (role «tool») и пустые записи не восстанавливаем —
    он эфемерен (script.js L1908–1914). */
export function isRestorableMessage(message: unknown): message is ChatMessage & { content: string } {
  if (message === null || typeof message !== 'object') return false;
  const m = message as Partial<ChatMessage>;
  return (m.role === 'user' || m.role === 'assistant')
    && typeof m.content === 'string'
    && m.content.trim().length > 0;
}

/** Показания хода с провода → приборные показания записи (та же форма полей). */
function usageToTokenData(usage?: TurnUsage | null): EntryTokenData | undefined {
  if (!usage) return undefined;
  return {
    elapsedMs: usage.elapsedMs ?? null,
    promptTokens: usage.promptTokens ?? null,
    completionTokens: usage.completionTokens ?? null,
    totalTokens: usage.totalTokens ?? null,
    summaryApplied: usage.summaryApplied ?? false,
    summaryTokens: usage.summaryTokens ?? null,
  };
}

/**
 * Записи журнала из истории: ответы — с восстановленными приборными
 * показаниями (msg.usage, шапка «Топливо: …»), трасса агента — из msg.events
 * (L1926–1932). Штамп шапки — время восстановления (в ваниле nowStamp при
 * отрисовке); передаётся параметром, чтобы маппинг оставался чистым.
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
        meta: { time, tokenData: usageToTokenData(m.usage) },
        events: Array.isArray(m.events) && m.events.length ? m.events : undefined,
      });
    }
  }
  return entries;
}

/** Показания истории для топливомера: один ответ с usage = одна запись. */
export function historyToUsages(messages: ChatMessage[]): Array<{
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}> {
  const usages: Array<{ promptTokens: number; completionTokens: number; totalTokens: number }> = [];
  for (const m of messages) {
    if (m.role === 'assistant' && m.usage?.totalTokens != null) {
      usages.push({
        promptTokens: m.usage.promptTokens ?? 0,
        completionTokens: m.usage.completionTokens ?? 0,
        totalTokens: m.usage.totalTokens,
      });
    }
  }
  return usages;
}

/**
 * Перезаполнение журнала историей (renderHistory): true — журнал был
 * перезаполнен; false — показывать нечего, журнал не тронут (решение про
 * приветствие остаётся за вызывающим — script.js L1891–1894, L2166–2169).
 * Топливомер (счётчик и график) восстанавливается из показаний истории
 * в любом случае — переключение на пустую сессию обнуляет чужой счётчик.
 */
export function applyHistory(messages: ChatMessage[], time = nowStamp()): boolean {
  const store = useSessionStore.getState();
  store.setSessionFuel(historyToUsages(messages));
  const entries = historyToEntries(messages, time);
  if (!entries.length) return false;
  store.clearEntries();
  for (const entry of entries) store.appendEntry(entry);
  return true;
}
