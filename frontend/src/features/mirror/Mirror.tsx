/* ============================================================
   Пульт сравнения — порт static/index.html L283–316 (mirrorBlock)
   и script.js L2177–2493. Две колонки TwinPane (состояние каждой —
   локально в ней, по twinId); итоговая строка сравнения
   пересчитывается после оседания каждой стороны (renderMirrorTotals).
   ============================================================ */

import { useCallback, useState } from 'react';
import { useSettingsStore } from '../../stores/settings';
import { mirrorVerdictText, MIRROR_STRATEGY_LABELS as MIRROR_LABELS } from './twin';
import { getTwin } from './twinRegistry';
import TwinPane from './TwinPane';
import styles from './Mirror.module.css';

export default function Mirror() {
  const [verdict, setVerdict] = useState('Сравнение появится после первой передачи');

  /* Пересчёт итоговой строки после оседания стороны: подписи —
     действующие стратегии колонок (ваниль renderMirrorTotals L2307–2334) */
  const refreshVerdict = useCallback(() => {
    const full = getTwin('full')?.totals();
    const comp = getTwin('compressed')?.totals();
    if (!full || !comp) return;
    const s = useSettingsStore.getState();
    setVerdict(mirrorVerdictText(
      full,
      comp,
      MIRROR_LABELS[s.mirrorStrategyFull],
      MIRROR_LABELS[s.mirrorStrategyComp],
    ));
  }, []);

  return (
    <div className={styles.mirror}>
      <div className={styles.grid}>
        <TwinPane side="full" onSettle={refreshVerdict} />
        <TwinPane side="compressed" onSettle={refreshVerdict} />
      </div>
      <p className={styles.verdict} role="status">{verdict}</p>
    </div>
  );
}
