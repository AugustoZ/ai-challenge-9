/* ============================================================
   Селектор веток — порт static/index.html L264–269 (branchBar),
   script.js L1996–2175 (renderBranches, addBranch, activateBranch)
   и style.css L1495–1593. Ряд чипов над журналом, виден только
   под стратегией «Ветки» и вне зеркала; опрос — идемпотентный GET
   по смене стратегии/сессии/зеркала с abort в cleanup; развилка и
   переключение — из обработчиков кликов (правила эффектов).
   ============================================================ */

import { useEffect } from 'react';
import { ApiError, postJson } from '../../api/client';
import type { BranchInfo, SessionState } from '../../api/types';
import { nowStamp } from '../../lib/format';
import { useBranchesStore } from '../../stores/branches';
import { useSessionStore } from '../../stores/session';
import { useSettingsStore } from '../../stores/settings';
import { applyHistory } from '../chat/history';
import type { Directive } from '../send/request';
import { loadBranches } from './branchesApi';
import { chipBranches } from './branchList';
import styles from './Branches.module.css';

export interface BranchesProps {
  /** Директива состояния — отказы развилки/переключения (ваниль setDirective). */
  onDirective: (directive: Directive) => void;
}

function systemMessage(text: string): void {
  useSessionStore.getState().appendEntry({
    kind: 'system',
    text,
    meta: { time: nowStamp() },
  });
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export default function Branches({ onDirective }: BranchesProps) {
  const list = useBranchesStore((s) => s.list);
  const visible = useBranchesStore((s) => s.barVisible);
  const activeBranchId = useBranchesStore((s) => s.activeBranchId);
  const sessionId = useSessionStore((s) => s.sessionId);
  const contextStrategy = useSettingsStore((s) => s.contextStrategy);
  const mirrorMode = useSettingsStore((s) => s.mirrorMode);

  /* Опрос при смене стратегии, зеркала или сессии — ванильные вызовы
     setStrategy (L1067–1068), setMirror (L1105, L1115), switchToSession
     (L1896), restoreSession (L1967), «Новый диалог» (L1725) */
  useEffect(() => {
    if (mirrorMode || contextStrategy !== 'branching') {
      useBranchesStore.getState().hideBranches();
      return;
    }
    const controller = new AbortController();
    void loadBranches(sessionId, controller.signal);
    return () => controller.abort();
  }, [mirrorMode, contextStrategy, sessionId]);

  if (!visible) return null;

  /* Переключение на ветку: снимок цели становится журналом, sessionId
     не меняется — ветки живут внутри одной сессии (L2140–2175) */
  const activate = async (branchId: string) => {
    if (!sessionId || branchId === activeBranchId) return;
    try {
      const data = await postJson<SessionState>(
        `session/${encodeURIComponent(sessionId)}/branches/activate`,
        { branchId },
      );
      /* Пустой снимок — журнал к исходному виду (L2166–2169) */
      if (!applyHistory(data.messages)) useSessionStore.getState().clearEntries();
      void loadBranches(sessionId);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        systemMessage('Ветка не найдена');
      } else if (err instanceof ApiError) {
        onDirective({ kind: 'fault', text: `Ветка не переключена (${err.status})` });
      } else {
        onDirective({ kind: 'fault', text: 'Не удалось переключить ветку: ' + errorMessage(err) });
      }
      void loadBranches(sessionId);
    }
  };

  /* Ручная развилка: снимок активного состояния в новую ветку-близнец,
     активной остаётся текущая (L2104–2138) */
  const create = async () => {
    if (!sessionId) return;
    try {
      const data = await postJson<BranchInfo>(
        `session/${encodeURIComponent(sessionId)}/branches`,
      );
      systemMessage(
        '─ Чекпоинт: создана '
        + (typeof data?.label === 'string' && data.label ? data.label : 'ветка') + ' ─',
      );
      void loadBranches(sessionId);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        systemMessage('Достигнут лимит веток (8)');
      } else if (err instanceof ApiError && err.status === 404) {
        systemMessage('Сессия не найдена');
      } else if (err instanceof ApiError) {
        onDirective({ kind: 'fault', text: `Развилка не создана (${err.status})` });
      } else {
        onDirective({ kind: 'fault', text: 'Не удалось создать развилку: ' + errorMessage(err) });
      }
    }
  };

  return (
    <div className={styles.bar} role="group" aria-label="Ветки диалога">
      {chipBranches(list).map((branch, i) => {
        const isActive = branch.active === true;
        return (
          /* Root-запись (branchId null) повторно рендерится с null-ключом,
             ветки — по собственному id */
          <button
            key={branch.branchId ?? `root-${i}`}
            type="button"
            className={styles.chip}
            aria-current={isActive}
            disabled={branch.branchId == null}
            onClick={() => branch.branchId != null && void activate(branch.branchId)}
          >
            {isActive && <span className={styles.lamp} aria-hidden="true">●</span>}
            <span className={styles.label}>{branch.label}</span>
          </button>
        );
      })}
      <button
        type="button"
        className={`${styles.chip} ${styles.add}`}
        title="Создать развилку — снимок текущего диалога в новую ветку"
        onClick={() => void create()}
      >
        <span className={styles.label}>+ Развилка</span>
      </button>
    </div>
  );
}
