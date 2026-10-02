/* Настройки «Штурмана» — единая точка записи параметров (в vanilla было 18
   мест записи). Персист: ключ shturman.settings.v1, version 1. Старый ключ
   ai-assistant.settings.v1 — однократный seed: переносятся только известные
   поля новой схемы, режимы рассуждений (удалены в волне 1) не воскресают.
   Хранилище инъецируется для тестов; по умолчанию — localStorage. */

import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { createMemoryStorage, createSeededStorage, type StringStorage } from '../lib/storage';

/** Стратегии контекста (радиогруппа; значения = wire-значения ChatRequest). */
export const CONTEXT_STRATEGIES = ['off', 'window', 'summary', 'facts', 'branching'] as const;
export type ContextStrategy = (typeof CONTEXT_STRATEGIES)[number];

/** Стратегии, доступные колонкам зеркала: «Ветки» там не участвует. */
export const MIRROR_STRATEGIES = ['off', 'window', 'summary', 'facts'] as const;
export type MirrorStrategy = (typeof MIRROR_STRATEGIES)[number];

/** Значения настроек без действий — ровно то, что персистится. */
export interface SettingsValues {
  /** Выбор селекта моделей «providerId/modelId»; '' — модель по умолчанию. */
  model: string;
  temperature: number;
  /** «Топливо» — maxTokens. */
  maxTokens: number;
  topP: number;
  /** Стоп-фразы одной строкой. */
  stop: string;
  thinking: boolean;
  judge: boolean;
  contextStrategy: ContextStrategy;
  mirrorMode: boolean;
  mirrorStrategyFull: MirrorStrategy;
  mirrorStrategyComp: MirrorStrategy;
  /** Инструменты памяти агента: чтение/сохранение памяти на сервере. */
  memoryTools: boolean;
  /** RAG: поиск релевантных документов в корпусе перед ответом. */
  ragEnabled: boolean;
}

/** Дефолты = DEFAULTS из script.js минус поле режима рассуждений (режимы удалены). */
export const DEFAULT_SETTINGS: SettingsValues = {
  model: '',
  temperature: 0.7,
  maxTokens: 1024,
  topP: 1.0,
  stop: '',
  thinking: true,
  judge: false,
  contextStrategy: 'off',
  mirrorMode: false,
  mirrorStrategyFull: 'off',
  mirrorStrategyComp: 'summary',
  memoryTools: true,
  ragEnabled: false,
};

export const SETTINGS_STORAGE_KEY = 'shturman.settings.v1';
export const LEGACY_SETTINGS_STORAGE_KEY = 'ai-assistant.settings.v1';
const SETTINGS_VERSION = 1;

function isStrategy(value: unknown, allowed: readonly string[]): value is string {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value);
}

/**
 * Коэрсия сохранённых значений по образцу loadSettings из script.js:
 * числа — только конечные, булевы — только настоящие boolean, строки
 * стратегий — только из известных наборов. Неизвестные поля отбрасываются.
 */
export function coerceSettings(raw: unknown): SettingsValues {
  const stored = raw !== null && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};

  const number = (key: keyof SettingsValues, fallback: number, round = false): number => {
    // null из JSON (в т.ч. NaN, сериализовавшийся в null) — не ноль, а отсутствие
    const raw = stored[key];
    const value = raw == null || raw === '' ? Number.NaN : Number(raw);
    if (!Number.isFinite(value)) return fallback;
    return round ? Math.round(value) : value;
  };
  const bool = (key: keyof SettingsValues, fallback: boolean): boolean =>
    typeof stored[key] === 'boolean' ? (stored[key] as boolean) : fallback;

  /* Однократная миграция со старого тумблера сжатия (как в vanilla):
     включённое «Сжатие истории» трактуется как стратегия «Сводка». */
  const legacyCompression = stored.compressionEnabled === true && !stored.contextStrategy;
  const strategy = isStrategy(stored.contextStrategy, CONTEXT_STRATEGIES)
    ? (stored.contextStrategy as ContextStrategy)
    : legacyCompression
      ? 'summary'
      : DEFAULT_SETTINGS.contextStrategy;

  return {
    model: typeof stored.model === 'string' ? stored.model : DEFAULT_SETTINGS.model,
    temperature: number('temperature', DEFAULT_SETTINGS.temperature),
    maxTokens: number('maxTokens', DEFAULT_SETTINGS.maxTokens, true),
    topP: number('topP', DEFAULT_SETTINGS.topP),
    stop: typeof stored.stop === 'string' ? stored.stop : DEFAULT_SETTINGS.stop,
    thinking: bool('thinking', DEFAULT_SETTINGS.thinking),
    judge: bool('judge', DEFAULT_SETTINGS.judge),
    contextStrategy: strategy,
    mirrorMode: bool('mirrorMode', DEFAULT_SETTINGS.mirrorMode),
    mirrorStrategyFull: isStrategy(stored.mirrorStrategyFull, MIRROR_STRATEGIES)
      ? (stored.mirrorStrategyFull as MirrorStrategy)
      : DEFAULT_SETTINGS.mirrorStrategyFull,
    mirrorStrategyComp: isStrategy(stored.mirrorStrategyComp, MIRROR_STRATEGIES)
      ? (stored.mirrorStrategyComp as MirrorStrategy)
      : DEFAULT_SETTINGS.mirrorStrategyComp,
    memoryTools: bool('memoryTools', DEFAULT_SETTINGS.memoryTools),
    ragEnabled: bool('ragEnabled', DEFAULT_SETTINGS.ragEnabled),
  };
}

/** Seed из старого ключа: только известные поля новой схемы. */
function mapLegacySettings(old: Record<string, unknown>): Record<string, unknown> {
  return coerceSettings(old) as unknown as Record<string, unknown>;
}

export interface SettingsState extends SettingsValues {
  setModel: (model: string) => void;
  setTemperature: (temperature: number) => void;
  setMaxTokens: (maxTokens: number) => void;
  setTopP: (topP: number) => void;
  setStop: (stop: string) => void;
  setThinking: (thinking: boolean) => void;
  setJudge: (judge: boolean) => void;
  setContextStrategy: (strategy: ContextStrategy) => void;
  setMirrorMode: (mirrorMode: boolean) => void;
  setMirrorStrategyFull: (strategy: MirrorStrategy) => void;
  setMirrorStrategyComp: (strategy: MirrorStrategy) => void;
  setMemoryTools: (memoryTools: boolean) => void;
  setRagEnabled: (ragEnabled: boolean) => void;
  /** «Новый диалог»: настройки возвращаются к дефолтам. */
  reset: () => void;
}

/** Хранилище по умолчанию: localStorage в браузере, memory — в node/тестах. */
function defaultStorage(): StringStorage {
  return typeof localStorage !== 'undefined' ? localStorage : createMemoryStorage();
}

/**
 * Фабрика стора: тесты передают in-memory хранилище, приложение пользуется
 * дефолтным localStorage. Персист пишет только SettingsValues (без действий).
 */
export function createSettingsStore(baseStorage?: StringStorage) {
  const storage = createSeededStorage({
    newKey: SETTINGS_STORAGE_KEY,
    oldKey: LEGACY_SETTINGS_STORAGE_KEY,
    version: SETTINGS_VERSION,
    mapSeed: mapLegacySettings,
    base: baseStorage ?? defaultStorage(),
  });

  return create<SettingsState>()(
    persist(
      (set) => ({
        ...DEFAULT_SETTINGS,
        setModel: (model) => set({ model }),
        setTemperature: (temperature) => set({ temperature }),
        setMaxTokens: (maxTokens) => set({ maxTokens }),
        setTopP: (topP) => set({ topP }),
        setStop: (stop) => set({ stop }),
        setThinking: (thinking) => set({ thinking }),
        setJudge: (judge) => set({ judge }),
        setContextStrategy: (strategy) => set({ contextStrategy: strategy }),
        setMirrorMode: (mirrorMode) => set({ mirrorMode }),
        setMirrorStrategyFull: (strategy) => set({ mirrorStrategyFull: strategy }),
        setMirrorStrategyComp: (strategy) => set({ mirrorStrategyComp: strategy }),
        setMemoryTools: (memoryTools) => set({ memoryTools }),
        setRagEnabled: (ragEnabled) => set({ ragEnabled }),
        reset: () => set({ ...DEFAULT_SETTINGS }),
      }),
      {
        name: SETTINGS_STORAGE_KEY,
        version: SETTINGS_VERSION,
        storage: createJSONStorage(() => storage),
        partialize: (state): SettingsValues => ({
          model: state.model,
          temperature: state.temperature,
          maxTokens: state.maxTokens,
          topP: state.topP,
          stop: state.stop,
          thinking: state.thinking,
          judge: state.judge,
          contextStrategy: state.contextStrategy,
          mirrorMode: state.mirrorMode,
          mirrorStrategyFull: state.mirrorStrategyFull,
          mirrorStrategyComp: state.mirrorStrategyComp,
          memoryTools: state.memoryTools,
          ragEnabled: state.ragEnabled,
        }),
        // Битые/чужие данные в хранилище не пролезают: та же коэрсия, что у seed
        merge: (persisted, current) => ({ ...current, ...coerceSettings(persisted) }),
      },
    ),
  );
}

/** Стор настроек приложения. */
export const useSettingsStore = createSettingsStore();
