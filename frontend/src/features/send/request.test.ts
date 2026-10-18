/* Тесты чистой логики конвейера отправки (волна 5): сборка ChatRequest
   по настройкам (составная модель «provider/model»), вид директивы
   busy, отображение kind на StatusLine, решение о блокирующем фолбэке. */

import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS } from '../../stores/settings';
import type { SendSettings } from './request';
import {
  buildChatRequest,
  busyDirective,
  needsFallback,
  statusKindFor,
  streamAbortDecision,
} from './request';

const NO_SESSION = { sessionId: null, activeBranchId: null };

function settings(overrides: Partial<SendSettings> = {}): SendSettings {
  return {
    model: DEFAULT_SETTINGS.model,
    temperature: DEFAULT_SETTINGS.temperature,
    maxTokens: DEFAULT_SETTINGS.maxTokens,
    topP: DEFAULT_SETTINGS.topP,
    stop: DEFAULT_SETTINGS.stop,
    thinking: DEFAULT_SETTINGS.thinking,
    judge: DEFAULT_SETTINGS.judge,
    contextStrategy: DEFAULT_SETTINGS.contextStrategy,
    memoryTools: DEFAULT_SETTINGS.memoryTools,
    ...overrides,
  };
}

describe('buildChatRequest', () => {
  it('дефолтные настройки: модель по умолчанию — provider/model null, stop null', () => {
    expect(buildChatRequest(settings(), NO_SESSION, 'доклади состояние')).toEqual({
      message: 'доклади состояние',
      provider: null,
      model: null,
      thinkingEnabled: true,
      maxTokens: 1024,
      temperature: 0.7,
      topP: 1,
      stop: null,
      sessionId: null,
      judgeEnabled: false,
      contextStrategy: 'off',
      memoryTools: true,
    });
  });

  it('memoryTools уходит явным boolean всегда — и ВКЛ, и ВЫКЛ', () => {
    expect(buildChatRequest(settings({ memoryTools: true }), NO_SESSION, 'q').memoryTools).toBe(true);
    expect(buildChatRequest(settings({ memoryTools: false }), NO_SESSION, 'q').memoryTools).toBe(false);
  });

  it('составная модель «providerId/modelId» разбирается на пару провайдер/модель', () => {
    const request = buildChatRequest(settings({ model: 'openai/gpt-4o' }), NO_SESSION, 'q');
    expect(request.provider).toBe('openai');
    expect(request.model).toBe('gpt-4o');
  });

  it('модель без слэша уходит целиком в model, провайдер остаётся null', () => {
    const request = buildChatRequest(settings({ model: 'gpt-4o' }), NO_SESSION, 'q');
    expect(request.provider).toBeNull();
    expect(request.model).toBe('gpt-4o');
  });

  it('пустая стоп-фраза уходит null, непустая — строкой', () => {
    expect(buildChatRequest(settings({ stop: '   ' }), NO_SESSION, 'q').stop).toBeNull();
    expect(buildChatRequest(settings({ stop: 'КОНЕЦ' }), NO_SESSION, 'q').stop).toBe('КОНЕЦ');
  });

  it('сессия и параметры приёмки передаются как есть', () => {
    const request = buildChatRequest(
      settings({ judge: true, thinking: false, temperature: 0.2, maxTokens: 512, topP: 0.5 }),
      { sessionId: 'srv-1', activeBranchId: null },
      'q',
    );
    expect(request.sessionId).toBe('srv-1');
    expect(request.judgeEnabled).toBe(true);
    expect(request.thinkingEnabled).toBe(false);
    expect(request.temperature).toBe(0.2);
    expect(request.maxTokens).toBe(512);
    expect(request.topP).toBe(0.5);
  });

  it('branchId уходит только при «Ветках» с сессией и активной веткой', () => {
    const branching = { sessionId: 'srv-1', activeBranchId: 'b2' };
    const withBranch = buildChatRequest(settings({ contextStrategy: 'branching' }), branching, 'q');
    expect(withBranch.branchId).toBe('b2');

    /* Без сессии или без активной ветки поле не шлётся (script.js L2777–2781) */
    expect(
      'branchId' in buildChatRequest(settings({ contextStrategy: 'branching' }), { sessionId: 'srv-1', activeBranchId: null }, 'q'),
    ).toBe(false);
    expect(
      'branchId' in buildChatRequest(settings({ contextStrategy: 'branching' }), { sessionId: null, activeBranchId: 'b2' }, 'q'),
    ).toBe(false);

    /* Другие стратегии ветку не упоминают вовсе */
    expect(
      'branchId' in buildChatRequest(settings({ contextStrategy: 'off' }), branching, 'q'),
    ).toBe(false);
  });
});

describe('busyDirective', () => {
  it('без судьи — «Идёт обработка»', () => {
    expect(busyDirective(false)).toEqual({ kind: 'busy', text: 'Идёт обработка' });
  });

  it('с судьёй — «Идёт обработка · проверка судьёй»', () => {
    expect(busyDirective(true)).toEqual({ kind: 'busy', text: 'Идёт обработка · проверка судьёй' });
  });
});

describe('statusKindFor', () => {
  it('поток в полёте — busy поверх локального состояния', () => {
    expect(statusKindFor(true, { kind: 'fault', text: 'Передача остановлена' })).toBe('busy');
    expect(statusKindFor(true, { kind: 'ready', text: 'Готов к передаче' })).toBe('busy');
  });

  it('в покое — локальный kind: отказ держится до следующей передачи', () => {
    expect(statusKindFor(false, { kind: 'fault', text: '…' })).toBe('fault');
    expect(statusKindFor(false, { kind: 'ready', text: '…' })).toBe('ready');
  });
});

describe('needsFallback', () => {
  it('поток не состоялся ({ok:false, frames:0}) — блокирующий запрос', () => {
    expect(needsFallback({ ok: false, frames: 0 })).toBe(true);
  });

  it('стрим состоялся — фолбэк не нужен', () => {
    expect(needsFallback({ ok: true, frames: 1 })).toBe(false);
    expect(needsFallback({ ok: true, frames: 12 })).toBe(false);
  });
});

describe('streamAbortDecision', () => {
  it('события уже шли — честный отказ «связь прервалась во время передачи»', () => {
    expect(streamAbortDecision(true)).toBe('fault');
  });

  it('ни одного события — тот же запрос уходит блокирующим путём', () => {
    expect(streamAbortDecision(false)).toBe('fallback');
  });
});
