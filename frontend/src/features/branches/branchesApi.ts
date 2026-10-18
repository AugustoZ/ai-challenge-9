/* ============================================================
   Опрос веток сессии — порт script.js loadBranches L2043–2084.
   Бар виден ТОЛЬКО под стратегией «Ветки» и вне зеркала — на любое
   число записей; при недоступности сервера прежние чипы остаются.
   Развилка и переключение — в Branches.tsx (обработчики кликов).
   ============================================================ */

import { ApiError, getJson } from '../../api/client';
import type { BranchInfo, BranchesResponse } from '../../api/types';
import { useBranchesStore } from '../../stores/branches';
import { useSettingsStore } from '../../stores/settings';
import { activeBranchIdFrom } from './branchList';

export async function loadBranches(sessionId: string | null, signal?: AbortSignal): Promise<void> {
  const { mirrorMode } = useSettingsStore.getState();
  const store = useBranchesStore.getState();
  if (!sessionId || mirrorMode) {
    store.hideBranches(); // L2049–2052
    return;
  }

  let branches: BranchInfo[];
  try {
    const data = await getJson<BranchesResponse>(
      `session/${encodeURIComponent(sessionId)}/branches`,
      signal,
    );
    branches = Array.isArray(data?.branches) ? data.branches : [];
  } catch (err) {
    /* Неуспех (404 и прочий не-2xx) — бар убирается (L2057–2060); сеть
       недоступна или запрос отменён — прежние чипы остаются как есть */
    if (err instanceof ApiError) store.hideBranches();
    return;
  }

  /* activeBranchId по активной записи снимка; бар — только под «Ветками»
     (L2067–2083: повторная проверка стратегии после ответа сервера) */
  store.setBranches(
    branches,
    activeBranchIdFrom(branches),
    useSettingsStore.getState().contextStrategy === 'branching',
  );
}
