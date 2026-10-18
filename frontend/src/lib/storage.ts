/* Безопасная работа с JSON-хранилищем (try/catch — приватный режим и т.п.
   не должны ронять приложение) и однократный seed со старого ключа настроек. */

/** Синхронное строковое хранилище — контракт zustand createJSONStorage. */
export interface StringStorage {
  getItem(name: string): string | null;
  setItem(name: string, value: string): void;
  removeItem(name: string): void;
}

/** Читает и парсит JSON; хранилище недоступно/битый JSON — null. */
export function readJson(storage: StringStorage | undefined, key: string): unknown {
  if (!storage) return null;
  let raw: string | null;
  try {
    raw = storage.getItem(key);
  } catch {
    return null;
  }
  if (raw == null) return null;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

/** Пишет JSON; ошибки хранилища глушатся (не критично). */
export function writeJson(storage: StringStorage | undefined, key: string, value: unknown): boolean {
  if (!storage) return false;
  try {
    storage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function removeKey(storage: StringStorage | undefined, key: string): void {
  if (!storage) return;
  try {
    storage.removeItem(key);
  } catch {
    // хранилище недоступно — удалять нечего
  }
}

export interface SeededStorageOptions {
  /** Новый ключ настроек (shturman.settings.v1). */
  newKey: string;
  /** Старый ключ-источник (ai-assistant.settings.v1). */
  oldKey: string;
  /** Версия, записываемая в конверт персиста при посеве. */
  version: number;
  /** Отбор известных полей из старых данных; null — старых данных нет. */
  mapSeed: (old: Record<string, unknown>) => Record<string, unknown> | null;
  /** Базовое хранилище; по умолчанию — memory (для тестов подменяется). */
  base?: StringStorage;
}

/**
 * Хранилище с однократным посевом: пока новый ключ пуст, старый читается
 * один раз, известные поля переносятся и результат сразу записывается под
 * новый ключ — старый больше никогда не читается. Удалённые поля (режимы)
 * воскреснуть не могут: mapSeed отбирает только известные.
 */
export function createSeededStorage({
  newKey,
  oldKey,
  version,
  mapSeed,
  base,
}: SeededStorageOptions): StringStorage {
  return {
    getItem(name: string): string | null {
      if (name !== newKey) return base ? base.getItem(name) : null;
      const fresh = readJson(base, newKey);
      if (fresh != null) return JSON.stringify(fresh);

      const old = readJson(base, oldKey);
      if (old === null || typeof old !== 'object' || Array.isArray(old)) return null;
      const seeded = mapSeed(old as Record<string, unknown>);
      if (seeded === null) return null;

      const envelope = JSON.stringify({ state: seeded, version });
      writeJson(base, newKey, { state: seeded, version });
      return envelope;
    },
    setItem(name: string, value: string): void {
      if (!base) return;
      try {
        base.setItem(name, value);
      } catch {
        // хранилище может быть недоступно — не критично
      }
    },
    removeItem(name: string): void {
      if (!base) return;
      try {
        base.removeItem(name);
      } catch {
        // хранилище недоступно — удалять нечего
      }
    },
  };
}

/** In-memory хранилище для тестов и окружений без localStorage. */
export function createMemoryStorage(initial?: Record<string, string>): StringStorage {
  const map = new Map<string, string>(Object.entries(initial ?? {}));
  return {
    getItem: (name) => map.get(name) ?? null,
    setItem: (name, value) => {
      map.set(name, value);
    },
    removeItem: (name) => {
      map.delete(name);
    },
  };
}
