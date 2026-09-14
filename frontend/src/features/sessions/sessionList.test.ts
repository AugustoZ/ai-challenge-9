/* Тесты маппинга перечня сессий в строки панели (волна 6) — порт
   script.js L1795–1831. */

import { describe, expect, it } from 'vitest';
import type { SessionSummary } from '../../api/types';
import {
  sessionMeta,
  sessionTimeLabel,
  sessionTitle,
  validSessionSummaries,
} from './sessionList';

const DAY_MS = 24 * 60 * 60 * 1000;

function localMidnight(): number {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
}

describe('sessionTimeLabel — время обновления (script.js L1795–1805)', () => {
  it('сегодня — «ЧЧ:ММ»', () => {
    const noon = localMidnight() + 12 * 60 * 60 * 1000;
    expect(sessionTimeLabel(noon)).toMatch(/^\d{2}:\d{2}$/);
  });

  it('вчера — слово «вчера», старше — дата «ДД.ММ»', () => {
    expect(sessionTimeLabel(localMidnight() - DAY_MS + 12 * 60 * 60 * 1000)).toBe('вчера');
    expect(sessionTimeLabel(localMidnight() - 10 * DAY_MS + 12 * 60 * 60 * 1000))
      .toMatch(/^\d{2}\.\d{2}$/);
  });

  it('не-число — пустая мета', () => {
    expect(sessionTimeLabel(Number.NaN)).toBe('');
  });
});

describe('sessionTitle/sessionMeta — запись панели (script.js L1813–1831)', () => {
  it('пустой заголовок — честное «Без сообщений»', () => {
    expect(sessionTitle({ id: 's1', title: '', updatedAt: 0, messageCount: 0 })).toBe('Без сообщений');
    expect(sessionTitle({ id: 's1', title: '   ', updatedAt: 0, messageCount: 0 })).toBe('Без сообщений');
    expect(sessionTitle({ id: 's1', title: 'Привет', updatedAt: 0, messageCount: 0 })).toBe('Привет');
  });

  it('мета: время и число записей с русской формой, пустое — пусто', () => {
    expect(sessionMeta({ id: 's', title: 't', updatedAt: Number.NaN, messageCount: 0 })).toBe('');
    expect(sessionMeta({ id: 's', title: 't', updatedAt: Number.NaN, messageCount: 1 })).toBe('1 запись');
    expect(sessionMeta({ id: 's', title: 't', updatedAt: Number.NaN, messageCount: 3 })).toBe('3 записи');
    expect(sessionMeta({ id: 's', title: 't', updatedAt: Number.NaN, messageCount: 12 })).toBe('12 записей');
  });
});

describe('validSessionSummaries — валидация перечня (script.js L1811)', () => {
  it('без строкового непустого id — мимо, мусор отбрасывается', () => {
    const raw: unknown[] = [
      { id: 's1', title: 'Один', updatedAt: 1, messageCount: 2 },
      { id: '', title: 'x', updatedAt: 1, messageCount: 0 },
      { id: 7, title: 'x', updatedAt: 1, messageCount: 0 },
      null,
      'мусор',
    ];
    const result = validSessionSummaries(raw);
    expect(result).toHaveLength(1);
    expect((result[0] as SessionSummary).id).toBe('s1');
  });

  it('не-массив — пустой перечень', () => {
    expect(validSessionSummaries(null)).toEqual([]);
    expect(validSessionSummaries(undefined)).toEqual([]);
    expect(validSessionSummaries('нет')).toEqual([]);
  });
});
