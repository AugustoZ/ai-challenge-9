/* ============================================================
   Реестр смонтированных колонок зеркала — провод от конвейера
   отправки (useChatStream) к TwinPane. Внутреннее состояние
   близнецов живёт ЛОКАЛЬНО в TwinPane (spec «Состояние»: стором
   не является); реестр ключом по twinId стороны адресует вызовы
   своей колонке — никакого общего «latest» (tasks.md, подводные
   камни). Page-lifetime, как mirrorState в ваниле.
   ============================================================ */

import type { ChatResponse } from '../../api/types';
import type { MirrorStrategy } from '../../stores/settings';
import type { TwinSide, TwinTotals } from './twin';

export interface TwinHandle {
  /** Начало обмена: при пустом стаже расчищает колонку, возвращает
      номер обмена для guard'а (script.js L2472–2476). */
  beginExchange(): number;
  /** twin-sessionId колонки (выдаёт сервер первым обменом, L2229). */
  getSessionId(): string | null;
  /** Ответ своей стороны; обмен устаревшего номера игнорируется. */
  applyResponse(seq: number, data: ChatResponse, strategy: MirrorStrategy): void;
  /** Отказ своей стороны — карточкой в свою колонку (settleTwin). */
  applyFault(seq: number, message: string): void;
  /** Накопленные показания стороны (для итоговой строки сравнения). */
  totals(): TwinTotals;
}

const registry = new Map<TwinSide, TwinHandle>();

export function registerTwin(side: TwinSide, handle: TwinHandle): void {
  registry.set(side, handle);
}

export function unregisterTwin(side: TwinSide): void {
  registry.delete(side);
}

export function getTwin(side: TwinSide): TwinHandle | undefined {
  return registry.get(side);
}

/**
 * Полная очистка реестра при выходе/смене пользователя (план Task 14):
 * хэндлы колонок ушедшего пользователя недействительны. При выходе
 * консоль демонтируется и cleanup сам убирает записи, очистка —
 * страховка resetState на случай любого другого порядка демонтирования.
 */
export function resetTwinRegistry(): void {
  registry.clear();
}
