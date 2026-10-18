/* ============================================================
   Заключение судьи — порт 1:1 из static/script.js L356–428
   (judgeScoreClass, buildJudgeReport). Презентационная.
   Сырой текст неразобранного заключения — колодец с клавишей
   разворота (buildExpandToggle).
   ============================================================ */

import { useId, useRef } from 'react';
import type { JudgeReport as JudgeReportData } from '../../api/types';
import ExpandToggle from '../chat/ExpandToggle';
import expandableStyles from '../chat/ExpandToggle.module.css';
import styles from './JudgeReport.module.css';

/* Оценка судьи: 7..10 — показание в допуске (зелёный), 4..6 — на грани
   (янтарь), 1..3 — брак (красный). Роли цветов — по DESIGN.md. */
function judgeScoreClass(score: number | null | undefined): string {
  if (score == null || !Number.isFinite(score)) return '';
  if (score >= 7) return 'ok';
  if (score >= 4) return 'mid';
  return 'low';
}

/* Критерии приёмки — порядок и подписи из buildJudgeReport L378–383 */
const CRITERIA = [
  ['facts', 'Факты'],
  ['quality', 'Качество'],
  ['speed', 'Скорость'],
  ['efficiency', 'Ресурсоёмкость'],
] as const;

type CriterionKey = (typeof CRITERIA)[number][0];

/* Класс «колодец с потолком» — общий с ExpandToggle (style.css L2461–2466) */
const expandableClass = expandableStyles.expandable;

function CriterionRow({ label, criterion }: {
  label: string;
  criterion: JudgeReportData[CriterionKey];
}) {
  if (!criterion || (criterion.score == null && !criterion.comment)) return null;

  const scoreClass = judgeScoreClass(criterion.score);
  const scoreCls = scoreClass === 'mid'
    ? `${styles.score} ${styles.mid}`
    : scoreClass === 'low'
      ? `${styles.score} ${styles.low}`
      : styles.score;

  return (
    <div className={styles.row}>
      <span className={styles.name}>{label}</span>
      <span className={scoreCls}>{criterion.score != null ? `${criterion.score}/10` : '—'}</span>
      {criterion.comment ? <p className={styles.comment}>{criterion.comment}</p> : null}
    </div>
  );
}

export default function JudgeReport({ judge }: { judge: JudgeReportData }) {
  const rawRef = useRef<HTMLPreElement | null>(null);
  const rawId = useId();

  const hasCriteria = Boolean(judge.facts || judge.quality || judge.speed || judge.efficiency);

  return (
    <details className={styles.report}>
      <summary>
        {Number.isFinite(judge.overall)
          ? `Заключение судьи · ${judge.overall}/10`
          : 'Заключение судьи · без итоговой оценки'}
      </summary>
      <div className={styles.body}>
        {CRITERIA.map(([key, label]) => (
          <CriterionRow key={key} label={label} criterion={judge[key]} />
        ))}

        {judge.verdict ? <p className={styles.verdict}>{judge.verdict}</p> : null}

        {judge.raw && !hasCriteria ? (
          <>
            <ExpandToggle target={rawRef} controlsId={rawId} />
            <pre ref={rawRef} id={rawId} className={`${styles.raw} ${expandableClass}`}>
              {judge.raw}
            </pre>
          </>
        ) : null}
      </div>
    </details>
  );
}
