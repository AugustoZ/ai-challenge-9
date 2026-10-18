/* Тесты маппинга плоского списка веток (волна 6) — порт script.js
   loadBranches L2066–2073 и renderBranches L2001. */

import { describe, expect, it } from 'vitest';
import type { BranchInfo } from '../../api/types';
import { activeBranchIdFrom, chipBranches } from './branchList';

function branch(overrides: Partial<BranchInfo>): BranchInfo {
  return {
    branchId: 'b1',
    label: 'Ветка 1',
    forkUserCount: 2,
    userCountSinceFork: 0,
    messageCount: 3,
    active: false,
    ...overrides,
  };
}

describe('activeBranchIdFrom — активная запись снимка (script.js L2067–2073)', () => {
  it('активная ветка даёт свой id', () => {
    const list = [branch({ branchId: null, active: false, label: 'Корень' }), branch({ active: true })];
    expect(activeBranchIdFrom(list)).toBe('b1');
  });

  it('активна исходная история (branchId null) — активной ветки нет', () => {
    expect(activeBranchIdFrom([branch({ branchId: null, active: true })])).toBeNull();
  });

  it('без активной записи — null; пустой перечень — null', () => {
    expect(activeBranchIdFrom([branch({ active: false })])).toBeNull();
    expect(activeBranchIdFrom([])).toBeNull();
  });
});

describe('chipBranches — чипы с непустой подписью (script.js L2001)', () => {
  it('пустые/пробельные подписи молча пропускаются', () => {
    const list = [
      branch({ branchId: null, label: 'Корень' }),
      branch({ branchId: 'b2', label: '   ' }),
      branch({ branchId: 'b3', label: 'Ветка 3' }),
    ];
    expect(chipBranches(list).map((b) => b.branchId)).toEqual([null, 'b3']);
  });
});
