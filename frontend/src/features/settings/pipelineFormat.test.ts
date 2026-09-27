/* Тесты чистых форматтеров статуса FAQ-пайплайна (план Task 11). */

import { describe, expect, it } from 'vitest';
import type { PipelineStatus } from '../../api/types';
import {
  collectPipelineErrors,
  formatClockTime,
  formatDuration,
  formatPipelineState,
  formatStepMark,
} from './pipelineFormat';

describe('formatPipelineState', () => {
  it('три состояния прогона — русские подписи', () => {
    expect(formatPipelineState('running')).toBe('Выполняется');
    expect(formatPipelineState('succeeded')).toBe('Завершён');
    expect(formatPipelineState('failed')).toBe('Ошибка');
  });
});

describe('formatStepMark', () => {
  it('готов/упал — галочка и крест', () => {
    expect(formatStepMark('done')).toBe('✓');
    expect(formatStepMark('failed')).toBe('✗');
  });

  it('идёт/ждёт/пропущен', () => {
    expect(formatStepMark('running')).toBe('…');
    expect(formatStepMark('pending')).toBe('·');
    expect(formatStepMark('skipped')).toBe('—');
  });
});

describe('formatDuration', () => {
  it('меньше секунды — миллисекунды', () => {
    expect(formatDuration(0)).toBe('0 мс');
    expect(formatDuration(450)).toBe('450 мс');
    expect(formatDuration(999)).toBe('999 мс');
  });

  it('секунды с приборной запятой', () => {
    expect(formatDuration(1000)).toBe('1,0 с');
    expect(formatDuration(1200)).toBe('1,2 с');
    expect(formatDuration(59_900)).toBe('59,9 с');
  });

  it('минута и больше — минуты с остатком секунд', () => {
    expect(formatDuration(60_000)).toBe('1 мин 0 с');
    expect(formatDuration(65_400)).toBe('1 мин 5 с');
    expect(formatDuration(125_000)).toBe('2 мин 5 с');
  });

  it('нет данных — прочерк', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(undefined)).toBe('—');
  });
});

describe('formatClockTime', () => {
  it('ISO-строка — локальные HH:MM с ведущим нулём', () => {
    const iso = new Date(2026, 8, 26, 9, 7).toISOString();
    expect(formatClockTime(iso)).toBe('09:07');
  });

  it('пустая и битая строка — прочерк', () => {
    expect(formatClockTime(null)).toBe('—');
    expect(formatClockTime(undefined)).toBe('—');
    expect(formatClockTime('не дата')).toBe('—');
  });
});

describe('collectPipelineErrors', () => {
  const base: PipelineStatus = {
    name: 'faq-24h',
    runId: 'r-1',
    state: 'failed',
    startedAt: '2026-09-26T10:00:00Z',
    steps: [
      { id: 'collect', tool: 'tg_read_recent', status: 'done', durationMs: 1200 },
      { id: 'publish', tool: 'wiki_create_faq_page', status: 'failed', error: 'API-ключ не задан' },
      { id: 'notify', tool: 'echo', status: 'skipped' },
    ],
  };

  it('ошибки упавших шагов с префиксом id', () => {
    expect(collectPipelineErrors(base)).toEqual(['publish: API-ключ не задан']);
  });

  it('упавший шаг без message — просто id', () => {
    const noMessage: PipelineStatus = {
      ...base,
      steps: [{ id: 'publish', tool: 'wiki', status: 'failed' }],
    };
    expect(collectPipelineErrors(noMessage)).toEqual(['publish']);
  });

  it('без упавших шагов — пустой список', () => {
    const ok: PipelineStatus = {
      ...base,
      state: 'succeeded',
      steps: base.steps.map((step) => ({ ...step, status: 'done', error: null })),
    };
    expect(collectPipelineErrors(ok)).toEqual([]);
  });
});
