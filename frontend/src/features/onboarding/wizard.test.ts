/* Тесты чистой логики онбординг-визарда (план Task 13): маппинг
   7 ответов → payload, лимиты 255/1000, блокировка «Сохранить» при
   невалидном «иное» (>255), маски свободных полей, счётчик и
   навигация по шагам. Vitest — node-окружение, только чистые функции. */

import { describe, expect, it } from 'vitest';

import { ApiError } from '../../api/client';
import { ADDRESS_MAX, DEFAULT_USER_PREFERENCES, TEXT_MAX } from '../../api/types';
import {
  buildPayload,
  canAdvance,
  capFreeText,
  counterText,
  createInitialAnswers,
  answersFromPreferences,
  isLastStep,
  nextStep,
  payloadErrors,
  prevStep,
  sanitizeFreeText,
  saveErrorText,
  STEP_TITLES,
  stepError,
  WIZARD_STEP_COUNT,
  WIZARD_NETWORK_ERROR_TEXT,
} from './wizard';
import type { WizardAnswers } from './wizard';

/** Полный набор ответов из QA-сценария плана (пилот/панибратский/…). */
function qaAnswers(): WizardAnswers {
  return {
    addressForm: 'пилот',
    tone: 'панибратский',
    language: 'Русский',
    structureChoice: 'таблицы',
    structureCustom: '',
    length: 'развернуто',
    avoidTopics: 'политика',
    interests: 'космос, гонки',
  };
}

describe('STEP_TITLES — 7 вопросов из плана', () => {
  it('ровно 7 шагов и 7 заголовков', () => {
    expect(WIZARD_STEP_COUNT).toBe(7);
    expect(STEP_TITLES).toHaveLength(7);
  });

  it('тексты вопросов дословно по плану Task 13', () => {
    expect(STEP_TITLES[0]).toBe('Выберите форму обращения');
    expect(STEP_TITLES[1]).toBe('Тон общения');
    expect(STEP_TITLES[2]).toBe('Язык общения');
    expect(STEP_TITLES[3]).toBe('Структура ответов');
    expect(STEP_TITLES[4]).toBe('Длина ответов');
    expect(STEP_TITLES[5]).toBe('Избегаемые темы');
    expect(STEP_TITLES[6]).toBe('Расскажите о своих интересах');
  });
});

describe('createInitialAnswers — старт на серверных дефолтах', () => {
  it('дефолты Task 4: ты/дружелюбный/Русский/текст/средне/пусто/пусто', () => {
    expect(createInitialAnswers()).toEqual({
      addressForm: 'ты',
      tone: 'дружелюбный',
      language: 'Русский',
      structureChoice: 'текст',
      structureCustom: '',
      length: 'средне',
      avoidTopics: '',
      interests: '',
    });
  });
});

describe('answersFromPreferences — предзаполнение сохранённым', () => {
  it('словарная структура выбирается клавишей, кастом пуст', () => {
    const a = answersFromPreferences({ ...DEFAULT_USER_PREFERENCES, answerStructure: 'стихи' });
    expect(a.structureChoice).toBe('стихи');
    expect(a.structureCustom).toBe('');
  });

  it('произвольная структура сервера → «иное» с сохранением текста', () => {
    const a = answersFromPreferences({ ...DEFAULT_USER_PREFERENCES, answerStructure: 'схемы и диаграммы' });
    expect(a.structureChoice).toBe('иное');
    expect(a.structureCustom).toBe('схемы и диаграммы');
  });
});

describe('buildPayload — маппинг 7 ответов → payload', () => {
  it('QA-проход визарда даёт все 7 полей байт-в-байт', () => {
    expect(buildPayload(qaAnswers())).toEqual({
      addressForm: 'пилот',
      tone: 'панибратский',
      language: 'Русский',
      answerStructure: 'таблицы',
      length: 'развернуто',
      avoidTopics: 'политика',
      interests: 'космос, гонки',
    });
  });

  it('фиксированная клавиша структуры уходит как есть', () => {
    const p = buildPayload({ ...qaAnswers(), structureChoice: 'текст', structureCustom: 'мусор' });
    expect(p.answerStructure).toBe('текст');
  });

  it('«иное» уходит своим текстом с trim', () => {
    const p = buildPayload({ ...qaAnswers(), structureChoice: 'иное', structureCustom: '  схемы  ' });
    expect(p.answerStructure).toBe('схемы');
  });

  it('дефолтные ответы → дефолтный payload (серверные DEFAULTS)', () => {
    expect(buildPayload(createInitialAnswers())).toEqual(DEFAULT_USER_PREFERENCES);
  });

  it('свободные поля триммятся в payload', () => {
    const p = buildPayload({ ...qaAnswers(), avoidTopics: '  политика  ', interests: ' гонки ' });
    expect(p.avoidTopics).toBe('политика');
    expect(p.interests).toBe('гонки');
  });
});

describe('stepError / payloadErrors — лимиты 255/1000 и блокировка «Сохранить»', () => {
  it('шаги-словари (0..2, 4) всегда валидны', () => {
    for (const step of [0, 1, 2, 4]) {
      expect(stepError(step, createInitialAnswers())).toBeNull();
    }
  });

  it('«иное» длиннее 255 — блокирует «Далее» и «Сохранить»', () => {
    const long = 'x'.repeat(ADDRESS_MAX + 1);
    const answers = { ...qaAnswers(), structureChoice: 'иное' as const, structureCustom: long };
    expect(stepError(3, answers)).toBe('Структура ответов: не длиннее 255 символов');
    expect(canAdvance(3, answers)).toBe(false);
    expect(payloadErrors(answers).length).toBeGreaterThan(0);
  });

  it('«иное» ровно 255 символов — валидно', () => {
    const answers = { ...qaAnswers(), structureChoice: 'иное' as const, structureCustom: 'а'.repeat(ADDRESS_MAX) };
    expect(stepError(3, answers)).toBeNull();
    expect(canAdvance(3, answers)).toBe(true);
    expect(payloadErrors(answers)).toEqual([]);
  });

  it('«иное» с управляющим символом — блокировка с серверным текстом', () => {
    const answers = { ...qaAnswers(), structureChoice: 'иное' as const, structureCustom: 'схемы\u0007' };
    expect(stepError(3, answers)).toBe('Структура ответов: не должен содержать управляющие символы');
  });

  it('темы/интересы длиннее 1000 — блокируют свои шаги', () => {
    const long = 'т'.repeat(TEXT_MAX + 1);
    const topics = { ...qaAnswers(), avoidTopics: long };
    const interests = { ...qaAnswers(), interests: long };
    expect(stepError(5, topics)).toBe('Избегаемые темы: не длиннее 1000 символов');
    expect(stepError(6, interests)).toBe('Интересы: не длиннее 1000 символов');
    expect(canAdvance(5, topics)).toBe(false);
    expect(canAdvance(6, interests)).toBe(false);
  });

  it('ровно 1000 символов в свободных полях — валидно', () => {
    const ok = 'т'.repeat(TEXT_MAX);
    expect(stepError(5, { ...qaAnswers(), avoidTopics: ok })).toBeNull();
    expect(stepError(6, { ...qaAnswers(), interests: ok })).toBeNull();
  });

  it('пустые свободные поля — валидно (не обязательные)', () => {
    const a = { ...qaAnswers(), avoidTopics: '', interests: '' };
    expect(stepError(5, a)).toBeNull();
    expect(stepError(6, a)).toBeNull();
    expect(payloadErrors(a)).toEqual([]);
  });
});

describe('sanitizeFreeText — маска свободных полей (зеркало сервера)', () => {
  it('переводы строк и табуляции становятся пробелами', () => {
    expect(sanitizeFreeText('политика\nвойна\tвыборы')).toBe('политика война выборы');
  });

  it('обычный текст не меняется', () => {
    expect(sanitizeFreeText('космос, гонки')).toBe('космос, гонки');
  });

  it('нулевые байты вычищаются', () => {
    expect(sanitizeFreeText('ты\u0000вы')).toBe('ты вы');
  });
});

describe('capFreeText — жёсткий кап длины', () => {
  it('1005 символов срезаются до 1000', () => {
    expect(capFreeText('x'.repeat(1005), TEXT_MAX)).toHaveLength(1000);
  });

  it('короткий текст не меняется', () => {
    expect(capFreeText('политика', TEXT_MAX)).toBe('политика');
  });
});

describe('counterText — формат счётчика из QA', () => {
  it('«политика» на шаге тем: 8/1000', () => {
    expect(counterText('политика', TEXT_MAX)).toBe('8/1000');
  });

  it('кастомная структура: 4/255', () => {
    expect(counterText('схем', ADDRESS_MAX)).toBe('4/255');
  });
});

describe('навигация по шагам', () => {
  it('Далее: 0→1→…→6, дальше последнего не уходит', () => {
    expect(nextStep(0)).toBe(1);
    expect(nextStep(5)).toBe(6);
    expect(nextStep(6)).toBe(6);
  });

  it('Назад: 6→5→…→0, ниже первого не уходит', () => {
    expect(prevStep(6)).toBe(5);
    expect(prevStep(1)).toBe(0);
    expect(prevStep(0)).toBe(0);
  });

  it('последний шаг распознаётся', () => {
    expect(isLastStep(6)).toBe(true);
    expect(isLastStep(5)).toBe(false);
  });
});

describe('saveErrorText — русские тексты ошибок PUT', () => {
  it('400 с серверным текстом поля показывается как есть', () => {
    const err = new ApiError(400, '{"error":"Интересы: не длиннее 1000 символов"}');
    expect(saveErrorText(err)).toBe('Интересы: не длиннее 1000 символов');
  });

  it('ошибка без читаемого тела — фолбэк', () => {
    expect(saveErrorText(new ApiError(500, ''))).toBe('Не удалось сохранить предпочтения. Попробуйте ещё раз');
  });

  it('сетевой отказ — текст про связь с сервером', () => {
    expect(saveErrorText(new TypeError('fetch failed'))).toBe(WIZARD_NETWORK_ERROR_TEXT);
  });
});
