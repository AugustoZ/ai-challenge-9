/* Тесты стора настроек: инъекция in-memory хранилища, персист, подписка,
   коэрсия битых данных и однократный seed со старого ключа. */

import { describe, expect, it, vi } from 'vitest';

import { createMemoryStorage, type StringStorage } from '../lib/storage';
import {
  coerceSettings,
  createSettingsStore,
  DEFAULT_SETTINGS,
  LEGACY_SETTINGS_STORAGE_KEY,
  SETTINGS_STORAGE_KEY,
} from './settings';

/** Ключ удалённого в волне 1 поля режима рассуждений — строкой, без идентификатора. */
const REMOVED_MODE_FIELD = ['reasoning', 'Mode'].join('');
const persistedEnvelope = (state: unknown, version = 1): string =>
  JSON.stringify({ state, version });

describe('createSettingsStore', () => {
  it('стартует с дефолтов (DEFAULTS из script.js минус режим рассуждений)', () => {
    const store = createSettingsStore(createMemoryStorage());
    const state = store.getState();

    expect(state.model).toBe('');
    expect(state.temperature).toBe(0.7);
    expect(state.maxTokens).toBe(1024);
    expect(state.topP).toBe(1.0);
    expect(state.stop).toBe('');
    expect(state.thinking).toBe(true);
    expect(state.judge).toBe(false);
    expect(state.contextStrategy).toBe('off');
    expect(state.mirrorMode).toBe(false);
    expect(state.mirrorStrategyFull).toBe('off');
    expect(state.mirrorStrategyComp).toBe('summary');
    expect(state).not.toHaveProperty(REMOVED_MODE_FIELD);
  });

  it('сеттеры меняют значения, подписчики уведомляются', () => {
    const store = createSettingsStore(createMemoryStorage());
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    store.getState().setTemperature(0.42);
    store.getState().setJudge(true);
    store.getState().setContextStrategy('facts');

    const state = store.getState();
    expect(state.temperature).toBe(0.42);
    expect(state.judge).toBe(true);
    expect(state.contextStrategy).toBe('facts');
    expect(listener).toHaveBeenCalledTimes(3);
    unsubscribe();
  });

  it('setTemperature пишет точное значение', () => {
    const store = createSettingsStore(createMemoryStorage());
    store.getState().setTemperature(0.42);
    expect(store.getState().temperature).toBe(0.42);
  });

  it('персист пишет конверт {state, version} без действий', () => {
    const storage = createMemoryStorage();
    const store = createSettingsStore(storage);
    store.getState().setMaxTokens(2048);

    const raw = storage.getItem(SETTINGS_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const envelope = JSON.parse(raw ?? '{}') as { version: number; state: Record<string, unknown> };
    expect(envelope.version).toBe(1);
    expect(envelope.state.maxTokens).toBe(2048);
    expect(envelope.state).not.toHaveProperty('setModel');
  });

  it('новый стор реидратится из хранилища', () => {
    const storage = createMemoryStorage();
    const first = createSettingsStore(storage);
    first.getState().setStop('СТОП-1');
    first.getState().setMirrorMode(true);

    const second = createSettingsStore(storage);
    expect(second.getState().stop).toBe('СТОП-1');
    expect(second.getState().mirrorMode).toBe(true);
    expect(second.getState().temperature).toBe(DEFAULT_SETTINGS.temperature);
  });

  it('битые значения в хранилище отбрасываются коэрсией', () => {
    const storage = createMemoryStorage({
      [SETTINGS_STORAGE_KEY]: persistedEnvelope({
        temperature: 'горячо',
        maxTokens: Number.NaN,
        thinking: 'да',
        contextStrategy: 'телепатия',
        mirrorStrategyComp: 'branching',
        junk: true,
      }),
    });

    const store = createSettingsStore(storage);
    const state = store.getState();
    expect(state.temperature).toBe(DEFAULT_SETTINGS.temperature);
    expect(state.maxTokens).toBe(DEFAULT_SETTINGS.maxTokens);
    expect(state.thinking).toBe(DEFAULT_SETTINGS.thinking);
    expect(state.contextStrategy).toBe(DEFAULT_SETTINGS.contextStrategy);
    expect(state.mirrorStrategyComp).toBe(DEFAULT_SETTINGS.mirrorStrategyComp);
  });

  it('несовместимая версия персиста не ломает стор', () => {
    const storage = createMemoryStorage({
      [SETTINGS_STORAGE_KEY]: persistedEnvelope({ temperature: 0.1 }, 99),
    });
    const store = createSettingsStore(storage);
    expect(store.getState().temperature).toBe(DEFAULT_SETTINGS.temperature);
    store.getState().setJudge(true); // стор продолжает работать
    expect(store.getState().judge).toBe(true);
  });

  it('reset возвращает дефолты', () => {
    const store = createSettingsStore(createMemoryStorage());
    store.getState().setTemperature(1.5);
    store.getState().reset();
    expect(store.getState().temperature).toBe(DEFAULT_SETTINGS.temperature);
  });
});

describe('seed со старого ключа ai-assistant.settings.v1', () => {
  const legacy: Record<string, unknown> = {
    sessionId: 'srv-42', // сессии теперь серверные, не в настройках
    temperature: 0.9,
    maxTokens: 512,
    topP: 0.5,
    stop: ' СТОП ',
    thinking: false,
    judge: true,
    contextStrategy: 'branching',
    mirrorMode: true,
    mirrorStrategyFull: 'facts',
    mirrorStrategyComp: 'window',
    model: 'opencode/gpt-5',
    junk: true,
  };
  legacy[REMOVED_MODE_FIELD] = 'agent'; // удалённое — не переносится

  function seededStorage(): StringStorage {
    return createMemoryStorage({ [LEGACY_SETTINGS_STORAGE_KEY]: JSON.stringify(legacy) });
  }

  it('известные поля переносятся, удалённые — нет', () => {
    const store = createSettingsStore(seededStorage());
    const state = store.getState();
    expect(state.temperature).toBe(0.9);
    expect(state.maxTokens).toBe(512);
    expect(state.topP).toBe(0.5);
    expect(state.stop).toBe(' СТОП ');
    expect(state.thinking).toBe(false);
    expect(state.judge).toBe(true);
    expect(state.contextStrategy).toBe('branching');
    expect(state.mirrorMode).toBe(true);
    expect(state.mirrorStrategyFull).toBe('facts');
    expect(state.mirrorStrategyComp).toBe('window');
    expect(state.model).toBe('opencode/gpt-5');
    expect(state).not.toHaveProperty(REMOVED_MODE_FIELD);
    expect(state).not.toHaveProperty('sessionId');
  });

  it('seed выполняется однократно: результат сразу записан под новый ключ', () => {
    const storage = seededStorage();
    createSettingsStore(storage);

    const envelopeRaw = storage.getItem(SETTINGS_STORAGE_KEY);
    expect(envelopeRaw).not.toBeNull();
    const envelope = JSON.parse(envelopeRaw ?? '{}') as { state: Record<string, unknown> };
    expect(envelope.state.temperature).toBe(0.9);
    expect(envelope.state).not.toHaveProperty(REMOVED_MODE_FIELD);
  });

  it('новый ключ имеет приоритет над старым', () => {
    const storage = seededStorage();
    storage.setItem(SETTINGS_STORAGE_KEY, persistedEnvelope({ temperature: 0.2 }));
    const store = createSettingsStore(storage);
    expect(store.getState().temperature).toBe(0.2);
    expect(store.getState().judge).toBe(DEFAULT_SETTINGS.judge);
  });

  it('легаси-тумблер сжатия превращается в стратегию «Сводка»', () => {
    const storage = createMemoryStorage({
      [LEGACY_SETTINGS_STORAGE_KEY]: JSON.stringify({ compressionEnabled: true }),
    });
    const store = createSettingsStore(storage);
    expect(store.getState().contextStrategy).toBe('summary');
  });

  it('битый старый ключ — дефолты, без падения', () => {
    const storage = createMemoryStorage({ [LEGACY_SETTINGS_STORAGE_KEY]: '{битый' });
    const store = createSettingsStore(storage);
    expect(store.getState().temperature).toBe(DEFAULT_SETTINGS.temperature);
  });
});

describe('coerceSettings', () => {
  it('не-объектный вход даёт дефолты', () => {
    expect(coerceSettings(null)).toEqual(DEFAULT_SETTINGS);
    expect(coerceSettings('мусор')).toEqual(DEFAULT_SETTINGS);
    expect(coerceSettings([1])).toEqual(DEFAULT_SETTINGS);
  });

  it('maxTokens округляется до целого', () => {
    expect(coerceSettings({ maxTokens: 512.7 }).maxTokens).toBe(513);
  });
});
