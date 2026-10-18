/* Пластина-чип «скачать <файл>» рядом со строкой tool_call.
   Порт static/style.css L1932–1954 (.agent-event .dl-chip).
   Имя файла — ненадёжный ввод от LLM, поэтому рендерится текстом.
   В ваниле — <a href="/api/files/…">; здесь чип — кнопка с onClick. */

import { downloadChipPath } from './chip';
import styles from './AgentTrace.module.css';

export interface DownloadChipProps {
  argumentsJson: string;
  onDownload?: (path: string) => void;
}

export default function DownloadChip({ argumentsJson, onDownload }: DownloadChipProps) {
  const path = downloadChipPath(argumentsJson);
  if (!path) return null;
  return (
    <button
      type="button"
      className={styles.chip}
      onClick={() => onDownload?.(path)}
    >
      {`скачать ${path}`}
    </button>
  );
}
