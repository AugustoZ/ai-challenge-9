/* ============================================================
   Defense-in-depth (спека task-state-fsm, G3): служебные
   fenced-блоки ```task-state``` рассчитаны на парсер клиента
   (TasksStateJson на сервере стрипает их из reply) и не должны
   попадать в бортовой журнал. Фильтр вырезает ВСЕ такие блоки
   из текста перед рендером записи. Чистая функция без DOM —
   проверяется node-скриптом (vitest-инфраструктура не требуется).
   ============================================================ */

/** Открывающая строка колодца: ``` (+отступ) с языком task-state. */
const FENCE_OPEN = /^`{3,}[ \t]*task-state\b/;
/** Любая строка-ограждение — закрывает вырезаемый блок. */
const FENCE_ANY = /^`{3,}/;

/**
 * Вырезать все ```task-state …``` колодцы из текста. Незакрытый
 * блок режется до конца текста: маскировка служебного JSON
 * важнее сохранности хвоста. Остальной текст сохраняется дословно.
 */
export function stripTaskStateBlocks(text: string): string {
  const kept: string[] = [];
  let skipping = false;
  for (const line of text.split('\n')) {
    if (skipping) {
      if (FENCE_ANY.test(line.trimStart())) {
        skipping = false;
      }
      continue;
    }
    if (FENCE_OPEN.test(line.trimStart())) {
      skipping = true;
      continue;
    }
    kept.push(line);
  }
  return kept.join('\n');
}
