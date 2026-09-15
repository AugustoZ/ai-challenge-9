/* ============================================================
   Чистая логика AuthGate (экран входа): маска ПИН, валидация
   формы создания пользователя и разбор ошибок API в русские
   сообщения. Правила — из lib/validators.ts (зеркало
   auth/UserAccount.kt): ПИН ровно 4 цифры, имя 1..25 после trim.
   Тексты 401/429 с сервера показываются как есть (кулдаун-текст
   приходит в теле ответа), остальное — дружелюбные фолбэки.
   Файл без React — vitest идёт в node-окружении.
   ============================================================ */

import { ApiError } from '../../api/client';
import { isValidName, isValidPin, NAME_MAX_LENGTH, PIN_LENGTH } from '../../lib/validators';

/** Маска поля ПИН: только цифры, максимум 4 («04a7» → «0407»). */
export function maskPin(raw: string): string {
  return raw.replace(/\D/g, '').slice(0, PIN_LENGTH);
}

/** Вход разблокирован, когда ПИН полный: ровно 4 цифры. */
export function isPinComplete(pin: string): boolean {
  return isValidPin(pin);
}

/** Подсказка под полем ПИН; null — поле валидно. */
export function pinHint(pin: string): string | null {
  return isValidPin(pin) ? null : `ПИН: ровно ${PIN_LENGTH} цифры`;
}

export interface CreateFormValidation {
  nameError: string | null;
  pinError: string | null;
}

/** Валидация формы создания пользователя: имя + ПИН, ошибки на русском. */
export function validateCreateForm(name: string, pin: string): CreateFormValidation {
  const trimmed = name.trim();
  let nameError: string | null = null;
  if (trimmed.length === 0) {
    nameError = 'Введите имя пользователя';
  } else if (trimmed.length > NAME_MAX_LENGTH) {
    nameError = `Имя: не длиннее ${NAME_MAX_LENGTH} символов`;
  } else if (!isValidName(trimmed)) {
    nameError = 'Имя: содержит недопустимые символы';
  }
  return { nameError, pinError: pinHint(pin) };
}

/* ---------- Разбор ошибок API в русские сообщения ---------- */

const LOGIN_ERROR_DEFAULT = 'Неверный ПИН или пользователь';
const COOLDOWN_ERROR_DEFAULT =
  'Слишком много попыток входа. Подождите немного и попробуйте снова';
const LOGIN_FAILURE_DEFAULT = 'Вход не удался. Попробуйте ещё раз';
const CREATE_ERROR_DEFAULT = 'Не удалось создать пользователя. Проверьте имя и ПИН';
export const NETWORK_ERROR_TEXT = 'Нет связи с сервером. Проверьте, что бэкенд запущен';

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

/** Сообщение при неудачном входе: 401 — неверный ПИН, 429 — кулдаун сервера. */
export function loginErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return bodyErrorText(err.bodyText) ?? LOGIN_ERROR_DEFAULT;
    if (err.status === 429) return bodyErrorText(err.bodyText) ?? COOLDOWN_ERROR_DEFAULT;
    return bodyErrorText(err.bodyText) ?? LOGIN_FAILURE_DEFAULT;
  }
  return NETWORK_ERROR_TEXT;
}

/** Сообщение при неудачном создании пользователя (серверный текст приоритетен). */
export function createErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    return bodyErrorText(err.bodyText) ?? CREATE_ERROR_DEFAULT;
  }
  return NETWORK_ERROR_TEXT;
}
