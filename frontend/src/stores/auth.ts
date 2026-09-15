/* Auth-стор «Штурмана»: токен сессии живёт ТОЛЬКО в sessionStorage
   (ключ shturman.auth.v1) — вкладка закрывается, вход сбрасывается;
   перезапуск сервера инвалидирует токен (restore/401 → logout).
   Персист-мидлвир zustand НЕ используется — чтение/запись хранилища
   идут через маленькие хелперы. Хранилище инъецируется для тестов;
   по умолчанию — sessionStorage (в node/тестах — memory). */

import { create } from 'zustand';

import {
  setAuthTokenProvider,
  setUnauthorizedHandler,
  fetchPreferences,
  login as loginRequest,
  createUser as createUserRequest,
} from '../api/client';
import type { AuthTokenResponse, AuthUserView, UserCreatedResponse } from '../api/types';
import { createMemoryStorage, type StringStorage } from '../lib/storage';

export const AUTH_STORAGE_KEY = 'shturman.auth.v1';

/** checking — идёт restore() при старте; anon — гейт входа; authed — работа. */
export type AuthStatus = 'checking' | 'anon' | 'authed';

export interface AuthState {
  /** Токен для заголовка X-Auth-Token; null — гость. */
  token: string | null;
  /** Текущий пользователь (AuthUserView: только id и name). */
  user: AuthUserView | null;
  status: AuthStatus;
  /** True сразу после createUser — сигнал онбординг-визарду (Task 13). */
  onboardingRequired: boolean;
  login: (userId: string, pin: string) => Promise<AuthTokenResponse>;
  /** POST /api/auth/users + мгновенный вход; поднимает onboardingRequired. */
  createUser: (name: string, pin: string) => Promise<UserCreatedResponse>;
  /** Визард предпочтений пройден/закрыт — сигнал погашен (Task 13). */
  completeOnboarding: () => void;
  /** Чистит хранилище и состояние. */
  logout: () => void;
  /**
   * При старте вкладки: есть токен → проверочный GET /api/preferences
   * → authed; иначе logout. Рестарт сервера (401) = повторный вход.
   */
  restore: () => Promise<void>;
  /** Централизованный разлогин: любой 401 в client/sse приводит сюда. */
  handle401: () => void;
  /** Токен для точечного чтения вне React (sse.ts). */
  getToken: () => string | null;
}

interface StoredAuth {
  token: string;
  user: AuthUserView;
}

function isStoredAuth(value: unknown): value is StoredAuth {
  if (value === null || typeof value !== 'object') return false;
  const candidate = value as { token?: unknown; user?: unknown };
  if (typeof candidate.token !== 'string' || candidate.token.length === 0) return false;
  const user = candidate.user as { id?: unknown; name?: unknown } | null | undefined;
  return (
    user !== null &&
    typeof user === 'object' &&
    typeof user.id === 'string' &&
    typeof user.name === 'string'
  );
}

function readAuth(storage: StringStorage): StoredAuth | null {
  let raw: string | null;
  try {
    raw = storage.getItem(AUTH_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw == null) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return isStoredAuth(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function writeAuth(storage: StringStorage, auth: StoredAuth): void {
  try {
    storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(auth));
  } catch {
    /* хранилище недоступно (приватный режим) — сессия будет без токена на диске */
  }
}

function clearAuth(storage: StringStorage): void {
  try {
    storage.removeItem(AUTH_STORAGE_KEY);
  } catch {
    /* удалять нечего */
  }
}

function defaultStorage(): StringStorage {
  return typeof sessionStorage !== 'undefined' ? sessionStorage : createMemoryStorage();
}

/**
 * Фабрика стора: тесты передают in-memory хранилище, приложение —
 * sessionStorage вкладки. Регистрирует стор поставщиком токена и
 * обработчиком 401 в api/client — после этого ЛЮБОЙ ответ 401
 * разлогинивает последний созданный стор (в приложении он один).
 */
export function createAuthStore(storage: StringStorage = defaultStorage()) {

  const store = create<AuthState>()((set, get) => ({
    token: null,
    user: null,
    status: 'checking',
    onboardingRequired: false,

    getToken: () => get().token,

    login: async (userId, pin) => {
      const res = await loginRequest(userId, pin);
      const auth: StoredAuth = { token: res.token, user: res.user };
      writeAuth(storage, auth);
      set({ token: auth.token, user: auth.user, status: 'authed', onboardingRequired: false });
      return res;
    },

    createUser: async (name, pin) => {
      const res = await createUserRequest(name, pin);
      const auth: StoredAuth = { token: res.token, user: res.user };
      writeAuth(storage, auth);
      set({ token: auth.token, user: auth.user, status: 'authed', onboardingRequired: true });
      return res;
    },

    completeOnboarding: () => set({ onboardingRequired: false }),

    logout: () => {
      clearAuth(storage);
      set({ token: null, user: null, status: 'anon', onboardingRequired: false });
    },

    restore: async () => {
      const saved = readAuth(storage);
      if (!saved) {
        set({ token: null, user: null, status: 'anon', onboardingRequired: false });
        return;
      }
      // Токен из хранилища сразу в состояние, затем проверочный запрос:
      // сервер перезапущен (401) или недоступен → logout (план: authed или logout)
      set({ token: saved.token, user: saved.user, status: 'checking' });
      try {
        await fetchPreferences();
        set({ status: 'authed' });
      } catch {
        clearAuth(storage);
        set({ token: null, user: null, status: 'anon', onboardingRequired: false });
      }
    },

    handle401: () => {
      clearAuth(storage);
      set({ token: null, user: null, status: 'anon', onboardingRequired: false });
    },
  }));

  setAuthTokenProvider(() => store.getState().token);
  setUnauthorizedHandler(() => store.getState().handle401());

  return store;
}

/** Стор аутентификации приложения (один на вкладку). */
export const useAuthStore = createAuthStore();
