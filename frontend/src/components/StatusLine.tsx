/* ============================================================
   Директива состояния — строка под журналом с лампой.
   Порт 1:1 из static/: разметка index.html L318–321, логика
   setDirective (script.js L93–97: kind на data-kind, у отказа —
   title с текстом), CSS style.css L1200–1246.
   Живое обновление придёт с фичевыми волнами — сейчас kind/text
   приходят через props.
   ============================================================ */

import Lamp from './Lamp';
import styles from './StatusLine.module.css';

export type StatusKind = 'ready' | 'busy' | 'fault';

export interface StatusLineProps {
  kind: StatusKind;
  text: string;
}

export default function StatusLine({ kind, text }: StatusLineProps) {
  return (
    <div className={styles.directive} data-kind={kind} aria-live="polite">
      <Lamp
        color={kind === 'fault' ? 'red' : kind === 'busy' ? 'amber' : 'green'}
        blinking={kind === 'busy'}
      />
      <span className={styles.text} title={kind === 'fault' ? text : ''}>{text}</span>
    </div>
  );
}
