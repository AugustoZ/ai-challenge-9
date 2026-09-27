/* ============================================================
   Чистые форматтеры статуса FAQ-пайплайна (план Task 11):
   подписи state, метки шагов ✓/✗, длительности и локального
   времени из ISO-строк бэкенда (Kotlin Instant.toString()).
   Никакого fetch/React — только функции для тестов и зоны
   статуса FaqPipelineSection.
   ============================================================ */

import type { PipelineState, PipelineStatus, PipelineStepState } from '../../api/types';

/** Русская подпись состояния прогона. */
export function formatPipelineState(state: PipelineState): string {
  switch (state) {
    case 'running':
      return 'Выполняется';
    case 'succeeded':
      return 'Завершён';
    case 'failed':
      return 'Ошибка';
  }
}

/** Метка шага зоны статуса: ✓ готов, ✗ упал, … идёт, · ждёт, — пропущен. */
export function formatStepMark(status: PipelineStepState): string {
  switch (status) {
    case 'done':
      return '✓';
    case 'failed':
      return '✗';
    case 'running':
      return '…';
    case 'pending':
      return '·';
    case 'skipped':
      return '—';
  }
}

/** Длительность: мс до секунды, секунды с приборной запятой, минуты с остатком. */
export function formatDuration(ms?: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} мс`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1).replace('.', ',')} с`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.floor((ms % 60_000) / 1000);
  return `${minutes} мин ${seconds} с`;
}

/** Локальные HH:MM из ISO-строки; пустая/битая строка — прочерк. */
export function formatClockTime(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Ошибки упавших шагов строками «id: message»; без message — просто id. */
export function collectPipelineErrors(status: PipelineStatus): string[] {
  return status.steps
    .filter((step) => step.status === 'failed')
    .map((step) => (step.error ? `${step.id}: ${step.error}` : step.id));
}
