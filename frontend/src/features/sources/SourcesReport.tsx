/* ============================================================
   «Источники» — блок RAG-цитат под ответом (план shturman-rag-mode,
   задача 7). Структурный близнец JudgeReport: нативная раскрывашка
   <details>/<summary> с янтарной плашкой-саммари, тело — колодец
   на --well. Отличие — маркер раскрытия «▸/▾» вместо «+/−» и счёт
   источников в саммари. Строки источников собирает чистый
   форматтер formatSourceRow; релевантность — показание, поэтому
   зелёная (дисциплина цвета из DESIGN.md).
   ============================================================ */

import type { RetrievedSource } from '../../api/types';
import { formatSourceMeta, formatSourceScore, formatSourceSnippet } from './format';
import styles from './SourcesReport.module.css';

export default function SourcesReport({ sources }: { sources: RetrievedSource[] }) {
  return (
    <details className={styles.report}>
      <summary>{`Источники · ${sources.length}`}</summary>
      <div className={styles.body}>
        {sources.map((s, i) => {
          const snippet = formatSourceSnippet(s);
          return (
            <div key={s.chunkId || i} className={styles.row}>
              <span className={styles.sourceLine}>
                {formatSourceMeta(i + 1, s)}
                {' · '}
                <span className={styles.score}>{formatSourceScore(s)}</span>
              </span>
              {snippet ? <p className={styles.snippet}>{snippet}</p> : null}
            </div>
          );
        })}
      </div>
    </details>
  );
}
