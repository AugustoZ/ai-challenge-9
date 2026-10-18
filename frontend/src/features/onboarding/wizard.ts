/* ============================================================
   Чистая логика онбординг-визарда предпочтений (план Task 13):
   7 шагов-вопросов, валидация шагов и финального payload,
   сборка тела PUT /api/preferences, разбор ошибок сохранения
   в русские тексты. Файл без React — vitest идёт в node.

   Правила валидации НЕ дублируются: зеркалятся через
   lib/validators (validatePreferences — зеркало серверной
   PreferenceLimits.kt). Прерывание визарда НИЧЕГО не сохраняет:
   «skip» здесь означает просто «закрыть без PUT», серверные
   дефолты (PreferencesStore.DEFAULTS) применяются сами.
   ============================================================ */

import { ApiError } from '../../api/client';
import { DEFAULT_USER_PREFERENCES } from '../../api/types';
import type { UserPreferences } from '../../api/types';
import { validatePreferences } from '../../lib/validators';

/** Число шагов-вопросов (7 полей UserPreferences). */
export const WIZARD_STEP_COUNT = 7;

/** Русские тексты вопросов — дословно по плану Task 13. */
export const STEP_TITLES: readonly string[] = [
  'Выберите форму обращения',
  'Тон общения',
  'Язык общения',
  'Структура ответов',
  'Длина ответов',
  'Избегаемые темы',
  'Расскажите о своих интересах',
];

/** Словарные варианты структуры ответов (совпадают со значениями сервера). */
export const STRUCTURE_FIXED = ['таблицы', 'текст', 'стихи'] as const;
export type FixedStructure = (typeof STRUCTURE_FIXED)[number];

/** Клавиши шага «Структура ответов»: словарные + «иное» (свой вариант). */
export const STRUCTURE_CHOICES = [...STRUCTURE_FIXED, 'иное'] as const;
export type StructureChoice = FixedStructure | 'иное';

/** Ответы визарда: enum-поля (radio) + выбор структуры + свободные тексты. */
export interface WizardAnswers {
  addressForm: UserPreferences['addressForm'];
  tone: UserPreferences['tone'];
  language: UserPreferences['language'];
  structureChoice: StructureChoice;
  /** Свободный текст структуры; активен только при «иное», ≤255. */
  structureCustom: string;
  length: UserPreferences['length'];
  avoidTopics: string;
  interests: string;
}

/** Стартовые ответы — серверные дефолты (Task 4). */
export function createInitialAnswers(): WizardAnswers {
  return answersFromPreferences(DEFAULT_USER_PREFERENCES);
}

/**
 * Предзаполнение из сохранённых предпочтений: словарная структура
 * выбирается клавишей, всё прочее («иное», в т.ч. пустая строка
 * дефолта никогда сюда не попадает — дефолт «текст») — кастомный текст.
 */
export function answersFromPreferences(prefs: UserPreferences): WizardAnswers {
  const isFixed = (STRUCTURE_FIXED as readonly string[]).includes(prefs.answerStructure);
  return {
    addressForm: prefs.addressForm,
    tone: prefs.tone,
    language: prefs.language,
    structureChoice: isFixed ? (prefs.answerStructure as FixedStructure) : 'иное',
    structureCustom: isFixed ? '' : prefs.answerStructure,
    length: prefs.length,
    avoidTopics: prefs.avoidTopics,
    interests: prefs.interests,
  };
}

/* ---------- Маски свободных полей (зеркало очистки сервера) ---------- */

/**
 * Управляющие символы → пробел (зеркало Character.isISOControl на
 * сервере). Ввод НЕ триммится: срез конечного пробела на каждом
 * нажатии ломает набор текста, финальный trim делает buildPayload
 * (и сервер при сохранении).
 */
export function sanitizeFreeText(raw: string): string {
  return raw.replace(/[\p{Cc}]/gu, ' ');
}

/** Жёсткий кап длины (дубль атрибута maxLength на случай вставки). */
export function capFreeText(raw: string, max: number): string {
  return raw.length > max ? raw.slice(0, max) : raw;
}

/** Счётчик символов «8/1000» — формат из QA-сценария плана. */
export function counterText(value: string, max: number): string {
  return `${value.length}/${max}`;
}

/* ---------- Навигация по шагам ---------- */

export function nextStep(step: number): number {
  return Math.min(step + 1, WIZARD_STEP_COUNT - 1);
}

export function prevStep(step: number): number {
  return Math.max(step - 1, 0);
}

export function isLastStep(step: number): boolean {
  return step === WIZARD_STEP_COUNT - 1;
}

/* ---------- Сборка payload ---------- */

/**
 * Ответы → тело PUT /api/preferences: фиксированные клавиши структуры
 * уходят как есть, «иное» — своим текстом (trim, как на сервере).
 */
export function buildPayload(answers: WizardAnswers): UserPreferences {
  return {
    addressForm: answers.addressForm,
    tone: answers.tone,
    language: answers.language,
    answerStructure:
      answers.structureChoice === 'иное'
        ? answers.structureCustom.trim()
        : answers.structureChoice,
    length: answers.length,
    avoidTopics: answers.avoidTopics.trim(),
    interests: answers.interests.trim(),
  };
}

/* ---------- Валидация (зеркало сервера через lib/validators) ---------- */

const STRUCTURE_ERROR_PREFIX = 'Структура ответов:';
const TOPICS_ERROR_PREFIX = 'Избегаемые темы:';
const INTERESTS_ERROR_PREFIX = 'Интересы:';

/**
 * Ошибка текущего шага (русский текст из validatePreferences) или null.
 * Шаги-словари (radio) валидны всегда — клавиша не может выйти за словарь;
 * свободные поля проверяются лимитами 255/1000 и управляющими символами.
 */
export function stepError(step: number, answers: WizardAnswers): string | null {
  if (step < 0 || step >= WIZARD_STEP_COUNT) return null;
  let prefix: string | null = null;
  if (step === 3) prefix = STRUCTURE_ERROR_PREFIX;
  else if (step === 5) prefix = TOPICS_ERROR_PREFIX;
  else if (step === 6) prefix = INTERESTS_ERROR_PREFIX;
  if (prefix === null) return null;
  const errors = validatePreferences(buildPayload(answers));
  return errors.find((e) => e.startsWith(prefix)) ?? null;
}

/** «Далее» доступен: у текущего шага нет ошибок. */
export function canAdvance(step: number, answers: WizardAnswers): boolean {
  return stepError(step, answers) === null;
}

/** Ошибки всего опросника — финальная задержка «Сохранить». */
export function payloadErrors(answers: WizardAnswers): string[] {
  return validatePreferences(buildPayload(answers));
}

/* ---------- Разбор ошибок сохранения ---------- */

const SAVE_ERROR_DEFAULT = 'Не удалось сохранить предпочтения. Попробуйте ещё раз';
export const WIZARD_NETWORK_ERROR_TEXT =
  'Нет связи с сервером. Проверьте, что бэкенд запущен';

/** Текст ошибки из тела ответа: {error}/{message} или сырой текст. */
function bodyErrorText(bodyText: string): string | null {
  if (!bodyText) return null;
  try {
    const parsed: unknown = JSON.parse(bodyText);
    if (parsed !== null && typeof parsed === 'object') {
      const candidate = parsed as { error?: unknown; message?: unknown };
      if (typeof candidate.error === 'string' && candidate.error.trim().length > 0) {
        return candidate.error.trim();
      }
      if (typeof candidate.message === 'string' && candidate.message.trim().length > 0) {
        return candidate.message.trim();
      }
    }
  } catch {
    /* тело — не JSON: показываем сырой текст */
  }
  return bodyText.trim().length > 0 ? bodyText.trim().slice(0, 200) : null;
}

/** Сообщение при неудачном PUT: серверный текст приоритетен (400 с полями). */
export function saveErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    return bodyErrorText(err.bodyText) ?? SAVE_ERROR_DEFAULT;
  }
  return WIZARD_NETWORK_ERROR_TEXT;
}
