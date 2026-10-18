/* Тесты чистой логики AuthGate (план Task 9): маска ПИН («04a7» → «0407»),
   блокировка отправки при невалидных полях, русские тексты ошибок входа и
   создания пользователя. Vitest — node-окружение, только чистые функции. */

import { describe, expect, it } from 'vitest';

import { ApiError } from '../../api/client';
import {
  createErrorText,
  isPinComplete,
  loginErrorText,
  maskPin,
  pinHint,
  validateCreateForm,
} from './authGateLogic';

describe('maskPin — маска поля ПИН', () => {
  it('«04a7» → «047»: буква выпадает, остаются только цифры', () => {
    expect(maskPin('04a7')).toBe('047');
  });

  it('буквы между цифрами выпадают: «a1b2c3d4» → «1234»', () => {
    expect(maskPin('a1b2c3d4')).toBe('1234');
  });

  it('обрезает лишние цифры: «12345» → «1234»', () => {
    expect(maskPin('12345')).toBe('1234');
  });

  it('строка без цифр даёт пустую маску', () => {
    expect(maskPin('пин')).toBe('');
  });

  it('корректный ПИН проходит без изменений', () => {
    expect(maskPin('0407')).toBe('0407');
  });

  it('пустой ввод — пустая маска', () => {
    expect(maskPin('')).toBe('');
  });
});

describe('isPinComplete — блокировка отправки при невалидном ПИН', () => {
  it('4 цифры — отправка разрешена', () => {
    expect(isPinComplete('0407')).toBe(true);
    expect(isPinComplete('9999')).toBe(true);
  });

  it('меньше 4 цифр — отправка заблокирована', () => {
    expect(isPinComplete('')).toBe(false);
    expect(isPinComplete('04')).toBe(false);
  });

  it('буквы в ПИНе — отправка заблокирована', () => {
    expect(isPinComplete('04a7')).toBe(false);
  });
});

describe('pinHint — подсказка под полем ПИН', () => {
  it('валидный ПИН — подсказки нет', () => {
    expect(pinHint('1111')).toBeNull();
  });

  it('короткий ПИН — подсказка про 4 цифры', () => {
    expect(pinHint('12')).toBe('ПИН: ровно 4 цифры');
  });
});

describe('validateCreateForm — форма создания пользователя', () => {
  it('валидные имя и ПИН — ошибок нет', () => {
    expect(validateCreateForm('Пилот Тест', '1111')).toEqual({
      nameError: null,
      pinError: null,
    });
  });

  it('пустое имя — ошибка', () => {
    expect(validateCreateForm('', '1111').nameError).toBe('Введите имя пользователя');
  });

  it('имя из одних пробелов — ошибка (trim)', () => {
    expect(validateCreateForm('   ', '1111').nameError).toBe('Введите имя пользователя');
  });

  it('имя длиннее 25 символов — ошибка (QA: 30 символов)', () => {
    const longName = 'П'.repeat(30);
    const res = validateCreateForm(longName, '1111');
    expect(res.nameError).toBe('Имя: не длиннее 25 символов');
  });

  it('эмодзи в имени отклоняются (зеркало UserAccount.kt)', () => {
    expect(validateCreateForm('Пилот 🙂', '1111').nameError).toBe(
      'Имя: содержит недопустимые символы',
    );
  });

  it('неполный ПИН — ошибка', () => {
    expect(validateCreateForm('Пилот Тест', '12').pinError).toBe('ПИН: ровно 4 цифры');
  });
});

describe('loginErrorText — русские тексты ошибок входа', () => {
  it('401: текст сервера приоритетен («Неверный ПИН или пользователь»)', () => {
    const err = new ApiError(401, '{"error":"Неверный ПИН или пользователь"}');
    expect(loginErrorText(err)).toBe('Неверный ПИН или пользователь');
  });

  it('401 без тела: дефолт «Неверный ПИН или пользователь»', () => {
    expect(loginErrorText(new ApiError(401, ''))).toBe('Неверный ПИН или пользователь');
  });

  it('429: кулдаун-текст приходит с сервера', () => {
    const err = new ApiError(429, '{"error":"Повторите через 30 секунд"}');
    expect(loginErrorText(err)).toBe('Повторите через 30 секунд');
  });

  it('429 без тела: дефолтный кулдаун-текст', () => {
    expect(loginErrorText(new ApiError(429, ''))).toContain('Слишком много попыток');
  });

  it('5xx: общая ошибка входа', () => {
    expect(loginErrorText(new ApiError(500, ''))).toBe('Вход не удался. Попробуйте ещё раз');
  });

  it('сетевой сбой (не ApiError) — «нет связи с сервером»', () => {
    expect(loginErrorText(new Error('fetch failed'))).toContain('Нет связи с сервером');
  });
});

describe('createErrorText — ошибки создания пользователя', () => {
  it('400: серверный текст показывается как есть', () => {
    const err = new ApiError(400, '{"error":"Имя: содержит недопустимые символы"}');
    expect(createErrorText(err)).toBe('Имя: содержит недопустимые символы');
  });

  it('400 без тела: дефолт', () => {
    expect(createErrorText(new ApiError(400, ''))).toContain('Не удалось создать пользователя');
  });

  it('сетевой сбой — «нет связи с сервером»', () => {
    expect(createErrorText(new Error('fetch failed'))).toContain('Нет связи с сервером');
  });
});
