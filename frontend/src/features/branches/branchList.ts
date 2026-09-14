/* Чистый маппинг плоского списка веток — порт script.js loadBranches
   L2066–2073 (активная запись) и renderBranches L2001 (валидность
   чипов). Покрыто Vitest (волна 6). */

import type { BranchInfo } from '../../api/types';

/** Активная ветка по снимку: null — активна исходная история, чипы
    не подсвечены (script.js L2067–2073). */
export function activeBranchIdFrom(branches: BranchInfo[]): string | null {
  for (const branch of branches) {
    if (branch && branch.active === true) {
      return branch.branchId == null ? null : String(branch.branchId);
    }
  }
  return null;
}

/** Записи с непустой подписью — чипы; прочие молча пропускаются (L2001). */
export function chipBranches(branches: BranchInfo[]): BranchInfo[] {
  return branches.filter(
    (b) => b != null && typeof b.label === 'string' && b.label.trim().length > 0,
  );
}
