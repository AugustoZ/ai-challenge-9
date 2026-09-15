/* Тесты чистых утилит панели памяти: подписи категорий и стратегий,
   разбор тегов из строки, состав фильтра-чипов. */

import { describe, expect, it } from 'vitest';
import {
  CONTEXT_STRATEGY_LABELS,
  KIND_FILTERS,
  kindLabel,
  parseTags,
  strategyLabel,
} from './memoryText';

describe('parseTags', () => {
  it('разбирает строку через запятую, обрезая пробелы', () => {
    expect(parseTags(' пилот , приборы,топливо ')).toEqual(['пилот', 'приборы', 'топливо']);
  });

  it('пустые и повторные теги отбрасываются', () => {
    expect(parseTags('a, ,b,,a')).toEqual(['a', 'b']);
  });

  it('пустой ввод — пустой массив', () => {
    expect(parseTags('')).toEqual([]);
    expect(parseTags('  ,  ')).toEqual([]);
  });
});

describe('kindLabel и фильтры', () => {
  it('категории памяти подписываются по-русски', () => {
    expect(kindLabel('profile')).toBe('Профиль');
    expect(kindLabel('decision')).toBe('Решение');
    expect(kindLabel('knowledge')).toBe('Знание');
  });

  it('первый чип — «Все» без фильтра, остальные — по категориям', () => {
    expect(KIND_FILTERS[0]).toEqual({ kind: undefined, label: 'Все' });
    expect(KIND_FILTERS.map((f) => f.kind)).toEqual([
      undefined,
      'profile',
      'decision',
      'knowledge',
    ]);
  });
});

describe('strategyLabel', () => {
  it('все стратегии контекста подписаны по-русски', () => {
    expect(CONTEXT_STRATEGY_LABELS.off).toBe('Выкл');
    expect(CONTEXT_STRATEGY_LABELS.window).toBe('Окно');
    expect(CONTEXT_STRATEGY_LABELS.summary).toBe('Сводка');
    expect(CONTEXT_STRATEGY_LABELS.facts).toBe('Факты');
    expect(CONTEXT_STRATEGY_LABELS.branching).toBe('Ветки');
  });

  it('неизвестное значение уходит как есть', () => {
    expect(strategyLabel('rolling')).toBe('rolling');
  });
});
