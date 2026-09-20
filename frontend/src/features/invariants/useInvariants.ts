/* ============================================================
   Панель инвариантов (план задач 9): GET/POST/DELETE
   /api/invariants — глобальный и сессионный скоупы.
   Ответы роутов оседают в стор чата тем же сеттером setInvariants,
   что применяет applyChatResponse; скоуп из ответа роута
   ({global} / {session}) вливается в текущий пейлоад.
   Ошибки API не бросаются — попадают в error-состояние панели.
   ============================================================ */

import { useCallback, useState } from 'react';
import {
  addGlobalInvariant,
  addSessionInvariant,
  deleteGlobalInvariant,
  deleteSessionInvariant,
  fetchInvariants,
} from '../../api/client';
import { useSessionStore } from '../../stores/session';

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export function useInvariants(onError?: (message: string) => void) {
  const invariants = useSessionStore((state) => state.invariants);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** GET /api/invariants?sessionId=… — оба скоупа целиком в стор. */
  const load = useCallback(
    (sessionId: string) => {
      setBusy(true);
      fetchInvariants(sessionId)
        .then((payload) => {
          useSessionStore.getState().setInvariants(payload);
          setError(null);
        })
        .catch((err: unknown) => {
          const message = `Не удалось загрузить инварианты: ${errorMessage(err)}`;
          setError(message);
          onError?.(message);
        })
        .finally(() => setBusy(false));
    },
    [onError],
  );

  /** POST /api/invariants/global {text} — пришедший {global} вливается в пейлоад стора. */
  const addGlobal = useCallback(
    (text: string) => {
      setBusy(true);
      addGlobalInvariant(text)
        .then((res) => {
          const current = useSessionStore.getState().invariants;
          useSessionStore
            .getState()
            .setInvariants({ global: res.global, session: current?.session ?? [] });
          setError(null);
        })
        .catch((err: unknown) => {
          const message = `Не удалось добавить инвариант: ${errorMessage(err)}`;
          setError(message);
          onError?.(message);
        })
        .finally(() => setBusy(false));
    },
    [onError],
  );

  /** DELETE /api/invariants/global/{id} — оставшийся {global} вливается в пейлоад стора. */
  const removeGlobal = useCallback(
    (id: string) => {
      setBusy(true);
      deleteGlobalInvariant(id)
        .then((res) => {
          const current = useSessionStore.getState().invariants;
          useSessionStore
            .getState()
            .setInvariants({ global: res.global, session: current?.session ?? [] });
          setError(null);
        })
        .catch((err: unknown) => {
          const message = `Не удалось убрать инвариант: ${errorMessage(err)}`;
          setError(message);
          onError?.(message);
        })
        .finally(() => setBusy(false));
    },
    [onError],
  );

  /** POST /api/invariants/session {sessionId, text} — пришедший {session} вливается в пейлоад стора. */
  const addSession = useCallback(
    (text: string) => {
      setBusy(true);
      const { sessionId } = useSessionStore.getState();
      if (!sessionId) {
        setBusy(false);
        return;
      }
      addSessionInvariant(sessionId, text)
        .then((res) => {
          const current = useSessionStore.getState().invariants;
          useSessionStore
            .getState()
            .setInvariants({ global: current?.global ?? [], session: res.session });
          setError(null);
        })
        .catch((err: unknown) => {
          const message = `Не удалось добавить инвариант: ${errorMessage(err)}`;
          setError(message);
          onError?.(message);
        })
        .finally(() => setBusy(false));
    },
    [onError],
  );

  /** DELETE /api/invariants/session/{sessionId}/{id} — оставшийся {session} вливается в пейлоад стора. */
  const removeSession = useCallback(
    (id: string) => {
      setBusy(true);
      const { sessionId } = useSessionStore.getState();
      if (!sessionId) {
        setBusy(false);
        return;
      }
      deleteSessionInvariant(sessionId, id)
        .then((res) => {
          const current = useSessionStore.getState().invariants;
          useSessionStore
            .getState()
            .setInvariants({ global: current?.global ?? [], session: res.session });
          setError(null);
        })
        .catch((err: unknown) => {
          const message = `Не удалось убрать инвариант: ${errorMessage(err)}`;
          setError(message);
          onError?.(message);
        })
        .finally(() => setBusy(false));
    },
    [onError],
  );

  return { invariants, error, busy, load, addGlobal, removeGlobal, addSession, removeSession };
}
