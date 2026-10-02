/* ============================================================
   Чистые форматтеры строк «Источников» (RAG, план shturman-rag-mode,
   задача 7). Без DOM и React — покрыты Vitest. Разделитель «·» —
   приборный, как в шапках записей журнала.
   ============================================================ */

import type { RetrievedSource } from '../../api/types';

/** Показ релевантности: приборные три десятичных знака, «0.913». */
export function formatSourceScore(s: RetrievedSource): string {
  return s.score.toFixed(3);
}

/**
 * Голова строки источника без показа релевантности:
 * `[N] <source> · <section>` — индексирует вызывающий (с единицы,
 * как ссылки на литературу).
 */
export function formatSourceMeta(index: number, s: RetrievedSource): string {
  return `[${index}] ${s.source} · ${s.section}`;
}

/** Полная строка источника: `[N] <source> · <section> · <score>`. */
export function formatSourceRow(index: number, s: RetrievedSource): string {
  return `${formatSourceMeta(index, s)} · ${formatSourceScore(s)}`;
}

/**
 * Строка фрагмента под источником: название документа (если есть)
 * и текст фрагмента. Пустой фрагмент — null (строка не рисуется).
 */
export function formatSourceSnippet(s: RetrievedSource): string | null {
  const snippet = s.snippet.trim();
  if (!snippet) return null;
  const title = s.title.trim();
  return title ? `${title} · ${snippet}` : snippet;
}
