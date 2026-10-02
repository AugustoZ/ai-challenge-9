/* Тесты чистых форматтеров «Источников» (RAG) — без DOM, только функции. */

import { describe, expect, it } from 'vitest';
import type { RetrievedSource } from '../../api/types';
import {
  formatSourceMeta,
  formatSourceRow,
  formatSourceScore,
  formatSourceSnippet,
} from './format';

const source: RetrievedSource = {
  chunkId: 'c-1',
  source: 'handbook.pdf',
  title: 'Руководство пилота',
  section: '3.2 Запуск двигателя',
  score: 0.9126,
  snippet: 'Перед запуском проверьте давление в магистрали.',
};

describe('formatSourceRow', () => {
  it('строка «[N] источник · раздел · релевантность»', () => {
    expect(formatSourceRow(1, source)).toBe('[1] handbook.pdf · 3.2 Запуск двигателя · 0.913');
  });

  it('индекс проходит как передан', () => {
    expect(formatSourceRow(12, source).startsWith('[12] ')).toBe(true);
  });
});

describe('formatSourceScore', () => {
  it('три десятичных знака с округлением', () => {
    expect(formatSourceScore(source)).toBe('0.913');
    expect(formatSourceScore({ ...source, score: 0.9124 })).toBe('0.912');
    expect(formatSourceScore({ ...source, score: 0.5 })).toBe('0.500');
  });

  it('нулевая и единичная релевантность', () => {
    expect(formatSourceScore({ ...source, score: 0 })).toBe('0.000');
    expect(formatSourceScore({ ...source, score: 1 })).toBe('1.000');
  });
});

describe('formatSourceMeta', () => {
  it('голова строки без релевантности', () => {
    expect(formatSourceMeta(3, source)).toBe('[3] handbook.pdf · 3.2 Запуск двигателя');
  });
});

describe('formatSourceSnippet', () => {
  it('название документа и фрагмент через приборный разделитель', () => {
    expect(formatSourceSnippet(source)).toBe(
      'Руководство пилота · Перед запуском проверьте давление в магистрали.',
    );
  });

  it('без названия — только фрагмент', () => {
    expect(formatSourceSnippet({ ...source, title: '' })).toBe(
      'Перед запуском проверьте давление в магистрали.',
    );
  });

  it('пустой или пробельный фрагмент — null (строка не рисуется)', () => {
    expect(formatSourceSnippet({ ...source, snippet: '' })).toBeNull();
    expect(formatSourceSnippet({ ...source, snippet: '   ' })).toBeNull();
  });
});
