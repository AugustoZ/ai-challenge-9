/* ============================================================
   Банк табло-аннунциаторов (клавиши-радио) + строка-подсказка.
   Порт 1:1 из static/: разметка index.html L58–77, L210–227,
   логика script.js L1047–1091 (setStrategy) и L1124–1167
   (setMode + keydown групп), CSS style.css L274–359.

   Контракт a11y (APG radio, как в ваниле): role="radiogroup",
   aria-checked на клавишах, в группе tabbable только выбранная
   клавиша (roving tabindex), стрелки/Home/End двигают выбор
   и переносят фокус. Подсказка выбранной клавиши — в строке
   .hint (как reasoningHint/strategyHint в ваниле).
   ============================================================ */

import { useRef } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';
import styles from './AnnunciatorGroup.module.css';

export interface AnnunciatorOption<T extends string> {
  /** Идентификатор режима/стратегии (data-mode / data-strategy ванили). */
  value: T;
  /** Надпись на клавише-табло. */
  caption: ReactNode;
  /** Пояснение выбранной клавиши — попадает в строку-подсказку (data-caption ванили). */
  hint?: string;
}

export interface AnnunciatorGroupProps<T extends string> {
  /** Русская подпись группы для скринридера (aria-label). */
  ariaLabel: string;
  value: T;
  options: ReadonlyArray<AnnunciatorOption<T>>;
  onChange: (value: T) => void;
  /** «strategy» — хвост из нечётного числа клавиш: последняя на всю ширину
      (порт .strategy-annunciators из style.css L356–359). */
  variant?: 'grid' | 'strategy';
}

export default function AnnunciatorGroup<T extends string>({
  ariaLabel,
  value,
  options,
  onChange,
  variant = 'grid',
}: AnnunciatorGroupProps<T>) {
  const buttonRefs = useRef<Array<HTMLButtonElement | null>>([]);

  /* Полная клавиатурная навигация группы: стрелки, Home, End — выбор
     двигается и фокус переносится (порт script.js L1076–1091 и L1152–1167) */
  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const keys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (!keys.includes(e.key)) return;
    e.preventDefault();

    const idx = options.findIndex((opt) => opt.value === value);
    let next: number;
    if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = options.length - 1;
    else {
      const dir = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : -1;
      next = (idx + dir + options.length) % options.length;
    }
    onChange(options[next].value);
    buttonRefs.current[next]?.focus();
  };

  const selected = options.find((opt) => opt.value === value);
  const groupClass = variant === 'strategy'
    ? `${styles.annunciators} ${styles.strategy}`
    : styles.annunciators;

  return (
    <>
      <div className={groupClass} role="radiogroup" aria-label={ariaLabel} onKeyDown={onKeyDown}>
        {options.map((opt, i) => (
          <button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={opt.value === value}
            /* APG radio: в группе tabbable только выбранная клавиша */
            tabIndex={opt.value === value ? 0 : -1}
            className={styles.annunciator}
            ref={(el) => { buttonRefs.current[i] = el; }}
            onClick={() => onChange(opt.value)}
          >
            {opt.caption}
          </button>
        ))}
      </div>
      <p className={styles.hint}>{selected?.hint ?? ''}</p>
    </>
  );
}
