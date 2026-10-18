/* Подписи и чистые утилиты панели памяти: русские названия стратегий
   контекста и категорий памяти, разбор тегов из строки ввода. */

import type { ContextStrategy } from '../../stores/settings';
import type { MemoryKind } from '../../api/types';

/** Русские подписи стратегий контекста — как в Приборах (STRATEGY_OPTIONS). */
export const CONTEXT_STRATEGY_LABELS: Record<ContextStrategy, string> = {
  off: 'Выкл',
  window: 'Окно',
  summary: 'Сводка',
  facts: 'Факты',
  branching: 'Ветки',
};

export function strategyLabel(strategy: string): string {
  return strategy in CONTEXT_STRATEGY_LABELS
    ? CONTEXT_STRATEGY_LABELS[strategy as ContextStrategy]
    : strategy;
}

/** Фильтр-чипы долговременной памяти: undefined — «Все» (без фильтра). */
export interface KindFilter {
  kind: MemoryKind | undefined;
  label: string;
}

export const KIND_FILTERS: readonly KindFilter[] = [
  { kind: undefined, label: 'Все' },
  { kind: 'profile', label: 'Профиль' },
  { kind: 'decision', label: 'Решения' },
  { kind: 'knowledge', label: 'Знания' },
];

export function kindLabel(kind: MemoryKind): string {
  switch (kind) {
    case 'profile':
      return 'Профиль';
    case 'decision':
      return 'Решение';
    case 'knowledge':
      return 'Знание';
  }
}

/** Теги из ввода через запятую: обрезка, пустые и повторы отбрасываются. */
export function parseTags(input: string): string[] {
  const seen = new Set<string>();
  const tags: string[] = [];
  for (const chunk of input.split(',')) {
    const tag = chunk.trim();
    if (!tag || seen.has(tag)) continue;
    seen.add(tag);
    tags.push(tag);
  }
  return tags;
}

/** Момент записи «ДД.ММ.ГГГГ, ЧЧ:ММ:СС» по локали интерфейса. */
export function formatStamp(ts: number): string {
  return new Date(ts).toLocaleString('ru-RU');
}
