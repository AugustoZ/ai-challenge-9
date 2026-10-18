/* Тесты чистых форматтеров (порт из script.js). */

import { describe, expect, it } from 'vitest';
import {
  entriesWord,
  eventsWord,
  exchangeTime,
  fmtElapsed,
  fmtRate,
  fmtSize,
  nowStamp,
  prettyJson,
  truncateText,
} from './format';

describe('fmtElapsed', () => {
  it('секунды с приборной запятой', () => {
    expect(fmtElapsed(1234)).toBe('1,23 с');
    expect(fmtElapsed(0)).toBe('0,00 с');
  });
});

describe('fmtRate', () => {
  it('скорость генерации в токенах в секунду', () => {
    expect(fmtRate({ elapsedMs: 2000, completionTokens: 100 })).toBe('50 т/с');
  });

  it('нет длительности или токенов — null', () => {
    expect(fmtRate({ elapsedMs: 0, completionTokens: 100 })).toBeNull();
    expect(fmtRate({ elapsedMs: 2000, completionTokens: null })).toBeNull();
    expect(fmtRate({ elapsedMs: 2000, completionTokens: undefined })).toBeNull();
  });
});

describe('truncateText', () => {
  it('хвост заменяется многоточием', () => {
    expect(truncateText('abcdef', 3)).toBe('abc…');
    expect(truncateText('abc', 3)).toBe('abc');
  });

  it('null/undefined превращаются в пустую строку', () => {
    expect(truncateText(null, 5)).toBe('');
    expect(truncateText(undefined, 5)).toBe('');
    expect(truncateText(42, 5)).toBe('42');
  });
});

describe('русские множественные формы', () => {
  it('eventsWord', () => {
    expect(eventsWord(1)).toBe('событие');
    expect(eventsWord(2)).toBe('события');
    expect(eventsWord(5)).toBe('событий');
    expect(eventsWord(11)).toBe('событий');
    expect(eventsWord(21)).toBe('событие');
    expect(eventsWord(22)).toBe('события');
    expect(eventsWord(111)).toBe('событий');
    expect(eventsWord(112)).toBe('событий');
  });

  it('entriesWord', () => {
    expect(entriesWord(1)).toBe('запись');
    expect(entriesWord(3)).toBe('записи');
    expect(entriesWord(12)).toBe('записей');
    expect(entriesWord(14)).toBe('записей');
  });
});

describe('fmtSize', () => {
  it('байты ниже килобайта', () => {
    expect(fmtSize('abc')).toBe('3 Б');
  });

  it('килобайты с одной десятичной', () => {
    expect(fmtSize('x'.repeat(2048))).toBe('2.0 КБ');
  });
});

describe('prettyJson', () => {
  it('компактный JSON разворачивается в многострочный', () => {
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}');
  });

  it('не-JSON возвращается как есть', () => {
    expect(prettyJson('не JSON')).toBe('не JSON');
  });
});

describe('временные штампы', () => {
  it('nowStamp — «ЧЧ:ММ»', () => {
    expect(nowStamp()).toMatch(/^\d{2}:\d{2}$/);
  });

  it('exchangeTime — «ЧЧ:ММ:СС» по epoch millis', () => {
    expect(exchangeTime(0)).toMatch(/^\d{2}:\d{2}:\d{2}$/);
  });
});
