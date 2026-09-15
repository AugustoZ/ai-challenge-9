/* Зеркало серверной валидации: auth-домен (auth/UserAccount.kt — ПИН и имя)
   и предпочтения (prefs/PreferenceLimits.kt — лимиты и словари). НЕ дублирует
   проверку сервера — повторяет её правила в UX, чтобы PUT /api/preferences и
   POST /api/auth/users не возвращали 400 на живом экране. Все сообщения —
   на русском, с названием поля (как у сервера). */

import {
  ADDRESS_FORMS,
  ADDRESS_MAX,
  LANGUAGES,
  LENGTHS,
  TEXT_MAX,
  TONES,
} from '../api/types';

/** Лимиты свободных полей — реэкспорт из types (единый источник лимитов). */
export { ADDRESS_MAX, TEXT_MAX };

/* ---------- ПИН (UserAccount.kt: Regex("^\\d{4}$")) ---------- */

/** ПИН — ровно 4 цифры. */
export const PIN_LENGTH = 4;

const PIN_RE = /^\d{4}$/;

export function isValidPin(pin: string): boolean {
  return PIN_RE.test(pin);
}

/* ---------- Имя пользователя (UserAccount.kt) ---------- */

/** Имя: 1..25 символов после trim. */
export const NAME_MAX_LENGTH = 25;

/* Спецсимволы имени — дословное зеркало UserAccount.kt; буквы \p{L}
   покрывают кириллицу и латиницу, \p{N} — цифры. */
const NAME_ALLOWED_RE = /^[\p{L}\p{N} .,!?@#$%^&*()\-_+=/\\[\]{}'";:<>~]+$/u;
const CONTROL_RE = /\p{Cc}/u;

export function isValidName(name: string): boolean {
  const trimmed = name.trim();
  if (trimmed.length < 1 || trimmed.length > NAME_MAX_LENGTH) return false;
  if (CONTROL_RE.test(trimmed)) return false;
  return NAME_ALLOWED_RE.test(trimmed);
}

/* ---------- Предпочтения (PreferenceLimits.kt) ---------- */

const hasControlChars = (value: string): boolean => /[\p{Cc}]/u.test(value);

/**
 * Проверка предпочтений по серверным правилам: enum-членство, длины
 * (255/1000), отсутствие контрольных символов. Возвращает список
 * русскоязычных ошибок; пустой массив — валидно.
 */
export function validatePreferences(prefs: {
  addressForm: string;
  tone: string;
  language: string;
  answerStructure: string;
  length: string;
  avoidTopics: string;
  interests: string;
}): string[] {
  const errors: string[] = [];

  if (!(ADDRESS_FORMS as readonly string[]).includes(prefs.addressForm)) {
    errors.push('Форма обращения: недопустимое значение');
  }
  if (!(TONES as readonly string[]).includes(prefs.tone)) {
    errors.push('Тон: недопустимое значение');
  }
  if (!(LANGUAGES as readonly string[]).includes(prefs.language)) {
    errors.push('Язык: недопустимое значение');
  }
  if (!(LENGTHS as readonly string[]).includes(prefs.length)) {
    errors.push('Длина ответов: недопустимое значение');
  }

  if (prefs.answerStructure.length > ADDRESS_MAX) {
    errors.push(`Структура ответов: не длиннее ${ADDRESS_MAX} символов`);
  } else if (hasControlChars(prefs.answerStructure)) {
    errors.push('Структура ответов: не должен содержать управляющие символы');
  }

  if (prefs.avoidTopics.length > TEXT_MAX) {
    errors.push(`Избегаемые темы: не длиннее ${TEXT_MAX} символов`);
  } else if (hasControlChars(prefs.avoidTopics)) {
    errors.push('Избегаемые темы: не должен содержать управляющие символы');
  }

  if (prefs.interests.length > TEXT_MAX) {
    errors.push(`Интересы: не длиннее ${TEXT_MAX} символов`);
  } else if (hasControlChars(prefs.interests)) {
    errors.push('Интересы: не должен содержать управляющие символы');
  }

  return errors;
}
