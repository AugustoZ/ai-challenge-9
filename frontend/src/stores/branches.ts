/* Селектор веток диалога (стратегия branching) — серверное состояние,
   не персистится. activeBranchId: null — активна исходная история.
   Снимок чипов и видимость бара — волна 6 (script.js loadBranches
   L2043–2084, hideBranches L1984–1988). */

import { create } from 'zustand';
import type { BranchInfo } from '../api/types';

export interface BranchesState {
  /** Активная ветка запроса; null — активна исходная история. */
  activeBranchId: string | null;
  /** Снимок плоского списка веток (GET /api/session/{id}/branches). */
  list: BranchInfo[];
  /** Бар чипов виден только под стратегией «Ветки» и вне зеркала. */
  barVisible: boolean;
  setActiveBranchId: (branchId: string | null) => void;
  /** Новый снимок веток: список, активная запись, видимость бара. */
  setBranches: (list: BranchInfo[], activeBranchId: string | null, barVisible: boolean) => void;
  /** Бар убирается целиком; протухший activeBranchId обнуляется (L1984–1988). */
  hideBranches: () => void;
}

export const useBranchesStore = create<BranchesState>()((set) => ({
  activeBranchId: null,
  list: [],
  barVisible: false,
  setActiveBranchId: (activeBranchId) => set({ activeBranchId }),
  setBranches: (list, activeBranchId, barVisible) =>
    set({ list, activeBranchId, barVisible }),
  hideBranches: () => set({ activeBranchId: null, list: [], barVisible: false }),
}));
