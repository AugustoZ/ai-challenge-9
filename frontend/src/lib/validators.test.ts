/* Тесты валидаторов auth-домена и предпочтений: ПИН ровно 4 цифры,
   имя 1..25 после trim (кириллица/латиница/цифры/спецсимволы, без
   контрольных), зеркальные правила предпочтений (лимиты 255/1000).
   Кейсы — из Acceptance Criteria плана (Task 5) и негативов Task 2/4. */

import { describe, expect, it } from 'vitest';

import {
  ADDRESS_FORMS,
  DEFAULT_USER_PREFERENCES,
  LANGUAGES,
  LENGTHS,
  TONES,
  type UserPreferences,
} from '../api/types';
import {
  ADDRESS_MAX,
  isValidName,
  isValidPin,
  TEXT_MAX,
  validatePreferences,
} from './validators';

describe('isValidPin — ровно 4 цифры', () => {
  it('валидный ПИН «0407» → true', () => {
    expect(isValidPin('0407')).toBe(true);
  });

  it('«12a5» / «123» / «12345» / пустой → false', () => {
    expect(isValidPin('12a5')).toBe(false);
    expect(isValidPin('123')).toBe(false);
    expect(isValidPin('12345')).toBe(false);
    expect(isValidPin('')).toBe(false);
  });

  it('цифры с пробелами и не-цифрами — false', () => {
    expect(isValidPin(' 1234')).toBe(false);
    expect(isValidPin('12 4')).toBe(false);
    expect(isValidPin('12.4')).toBe(false);
    expect(isValidPin('١٢٣٤')).toBe(false); // арабо-индийские цифры — не \d
  });
});

describe('isValidName — 1..25 после trim, без контрольных', () => {
  it('«Пилот-1!» → true', () => {
    expect(isValidName('Пилот-1!')).toBe(true);
  });

  it('имя из 26 символов → false, из 25 → true', () => {
    expect(isValidName('А'.repeat(26))).toBe(false);
    expect(isValidName('А'.repeat(25))).toBe(true);
  });

  it('перенос строки и другие контрольные символы → false', () => {
    expect(isValidName('Имя\nс переносом')).toBe(false);
    expect(isValidName('Имя\tтабуляция')).toBe(false);
    expect(isValidName('Имя\u0000с нулём')).toBe(false);
  });

  it('пустое имя и имя из одних пробелов → false', () => {
    expect(isValidName('')).toBe(false);
    expect(isValidName('   ')).toBe(false);
  });

  it('пробелы по краям обрезаются перед проверкой длины', () => {
    // 25 символов + обрамляющие пробелы → после trim валидно
    expect(isValidName(`  ${'А'.repeat(25)}  `)).toBe(true);
  });

  it('кириллица, латиница, цифры и допустимые спецсимволы → true', () => {
    expect(isValidName('Шумахер')).toBe(true);
    expect(isValidName('Pilot_01')).toBe(true);
    expect(isValidName("O'Connor #2 (тест)")).toBe(true);
    // 19 спецсимволов — весь допустимый набор, но в пределах 25
    expect(isValidName('~<>=+/\\[]{};:,.!?@#%')).toBe(true);
  });

  it('недопустимые символы (эмодзи, <script>) → false', () => {
    expect(isValidName('Пилот 🚀')).toBe(false);
    expect(isValidName('a<b? нет — скриптовые скобки можно, а эмодзи нет')).toBe(
      false,
    );
  });
});

describe('validatePreferences — зеркальные серверные правила', () => {
  it('дефолты валидны', () => {
    expect(validatePreferences(DEFAULT_USER_PREFERENCES)).toEqual([]);
  });

  it('enum-поля принимают только члены словарей', () => {
    const base: UserPreferences = { ...DEFAULT_USER_PREFERENCES };
    for (const addressForm of ADDRESS_FORMS) {
      expect(validatePreferences({ ...base, addressForm })).toEqual([]);
    }
    for (const tone of TONES) {
      expect(validatePreferences({ ...base, tone })).toEqual([]);
    }
    for (const language of LANGUAGES) {
      expect(validatePreferences({ ...base, language })).toEqual([]);
    }
    for (const length of LENGTHS) {
      expect(validatePreferences({ ...base, length })).toEqual([]);
    }
  });

  it('мусор в enum-полях даёт русские ошибки с названием поля', () => {
    const errors = validatePreferences({
      ...DEFAULT_USER_PREFERENCES,
      addressForm: 'эскимо' as UserPreferences['addressForm'],
      tone: 'агрессивный' as UserPreferences['tone'],
      length: 'бесконечно' as UserPreferences['length'],
      language: 'Клингонский' as UserPreferences['language'],
    });
    expect(errors).toHaveLength(4);
    expect(errors[0]).toContain('Форма обращения');
    expect(errors[1]).toContain('Тон');
    expect(errors[2]).toContain('Язык');
    expect(errors[3]).toContain('Длина ответов');
  });

  it('answerStructure: словарь, свободный текст ≤255 и пустая строка — ок', () => {
    const base = { ...DEFAULT_USER_PREFERENCES };
    expect(validatePreferences({ ...base, answerStructure: 'таблицы' })).toEqual([]);
    expect(validatePreferences({ ...base, answerStructure: 'списком по пунктам' })).toEqual([]);
    expect(validatePreferences({ ...base, answerStructure: '' })).toEqual([]);
    expect(validatePreferences({ ...base, answerStructure: 'х'.repeat(ADDRESS_MAX) })).toEqual([]);
    expect(
      validatePreferences({ ...base, answerStructure: 'х'.repeat(ADDRESS_MAX + 1) }),
    ).toEqual(['Структура ответов: не длиннее 255 символов']);
  });

  it('avoidTopics/interests: лимит 1000 и контрольные символы', () => {
    const base = { ...DEFAULT_USER_PREFERENCES };
    expect(validatePreferences({ ...base, avoidTopics: 'т'.repeat(TEXT_MAX) })).toEqual([]);
    expect(validatePreferences({ ...base, interests: 'и'.repeat(TEXT_MAX) })).toEqual([]);
    expect(
      validatePreferences({ ...base, avoidTopics: 'т'.repeat(TEXT_MAX + 1) }),
    ).toEqual(['Избегаемые темы: не длиннее 1000 символов']);
    expect(
      validatePreferences({ ...base, interests: 'и'.repeat(TEXT_MAX + 1) }),
    ).toEqual(['Интересы: не длиннее 1000 символов']);
    expect(
      validatePreferences({ ...base, interests: 'полёты\u0007над океаном' }),
    ).toEqual(['Интересы: не должен содержать управляющие символы']);
    expect(
      validatePreferences({ ...base, answerStructure: 'таблицы\u0000' }),
    ).toEqual(['Структура ответов: не должен содержать управляющие символы']);
  });
});
