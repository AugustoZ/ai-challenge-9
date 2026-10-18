/* Тесты чистой логики выбора модели — порт script.js L925–980:
   разбор составного значения, сборка групп/дефолта, выбор при загрузке. */

import { describe, expect, it } from 'vitest';

import type { ProviderModels } from '../../api/types';
import {
  buildModelSelect,
  modelSelectValues,
  providerToGroup,
  resolveModelValue,
  splitModelValue,
} from './models';

const PROVIDERS: ProviderModels[] = [
  { id: 'openai', name: 'OpenAI', models: ['gpt-4o', 'gpt-4o-mini'] },
  { id: 'vllm', name: '', models: ['qwen2.5', ''] },
];

describe('splitModelValue (script.js L975–980)', () => {
  it('разбирает составное значение «providerId/modelId»', () => {
    expect(splitModelValue('openai/gpt-4o')).toEqual({ provider: 'openai', model: 'gpt-4o' });
  });

  it('режет по первому «/» — внутри id модели слэши не страшны', () => {
    expect(splitModelValue('vllm/deepseek-ai/DeepSeek-R1')).toEqual({
      provider: 'vllm',
      model: 'deepseek-ai/DeepSeek-R1',
    });
  });

  it('без разделителя модель уходит как есть, провайдер — null', () => {
    expect(splitModelValue('gpt-4o')).toEqual({ provider: null, model: 'gpt-4o' });
  });

  it('пустая строка — model null; ведущий «/» — модель целиком (ветка sep<=0)', () => {
    expect(splitModelValue('')).toEqual({ provider: null, model: null });
    expect(splitModelValue('/gpt-4o')).toEqual({ provider: null, model: '/gpt-4o' });
  });
});

describe('providerToGroup (script.js L949–958)', () => {
  it('заголовок группы — name, опции — составные значения', () => {
    expect(providerToGroup(PROVIDERS[0])).toEqual({
      label: 'OpenAI',
      options: [
        { label: 'gpt-4o', value: 'openai/gpt-4o' },
        { label: 'gpt-4o-mini', value: 'openai/gpt-4o-mini' },
      ],
    });
  });

  it('без name заголовком становится id, пустые модели пропускаются', () => {
    const group = providerToGroup(PROVIDERS[1]);
    expect(group.label).toBe('vllm');
    expect(group.options).toEqual([{ label: 'qwen2.5', value: 'vllm/qwen2.5' }]);
  });

  it('битый провайдер без списка моделей — пустая группа', () => {
    expect(providerToGroup(undefined)).toEqual({ label: '', options: [] });
    expect(providerToGroup({ id: 'x', name: 'X', models: [] })).toEqual({ label: 'X', options: [] });
  });
});

describe('buildModelSelect (script.js L946–963)', () => {
  it('пустые группы не попадают в селект', () => {
    const built = buildModelSelect(
      [PROVIDERS[0], { id: 'empty', name: 'Пустой', models: [] }],
      'openai',
      'gpt-4o-mini',
    );
    expect(built.groups).toHaveLength(1);
    expect(built.fallbackValue).toBe('openai/gpt-4o-mini');
    expect(built.extraOption).toBeNull();
  });

  it('если группы не загрузились — единственный вариант по умолчанию', () => {
    const built = buildModelSelect([], 'openai', 'gpt-4o-mini');
    expect(built.groups).toEqual([]);
    expect(built.extraOption).toEqual({ label: 'gpt-4o-mini', value: 'openai/gpt-4o-mini' });
  });

  it('без дефолта и без групп выбирать нечего', () => {
    const built = buildModelSelect([], '', '');
    expect(built.fallbackValue).toBe('');
    expect(built.extraOption).toBeNull();
    expect(modelSelectValues(built)).toEqual([]);
  });

  it('modelSelectValues собирает значения групп и дефолта', () => {
    const built = buildModelSelect(PROVIDERS, 'openai', 'gpt-4o');
    expect(modelSelectValues(built)).toEqual([
      'openai/gpt-4o',
      'openai/gpt-4o-mini',
      'vllm/qwen2.5',
    ]);
  });
});

describe('resolveModelValue (script.js L966–967)', () => {
  const values = ['openai/gpt-4o', 'openai/gpt-4o-mini'];

  it('сохранённая модель остаётся, если ещё в списке', () => {
    expect(resolveModelValue(values, 'openai/gpt-4o-mini', 'openai/gpt-4o')).toBe(
      'openai/gpt-4o-mini',
    );
  });

  it('устаревшая сохранённая модель заменяется дефолтом', () => {
    expect(resolveModelValue(values, 'gone/model', 'openai/gpt-4o')).toBe('openai/gpt-4o');
  });

  it('пустой сохранённый выбор — дефолт; нечего выбрать — пустая строка', () => {
    expect(resolveModelValue(values, '', 'openai/gpt-4o')).toBe('openai/gpt-4o');
    expect(resolveModelValue([], '', '')).toBe('');
  });
});
