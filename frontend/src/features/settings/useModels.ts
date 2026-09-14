/* ============================================================
   Загрузка списка моделей для селекта — GET /api/models.
   Mount-эффект — идемпотентный GET с abort() в cleanup: StrictMode
   удваивает эффекты, первый запрос абортируется тихо, второй
   довозит до конца (см. «Подводные камни» tasks.md). Ошибка —
   честное состояние для панели; повтор — по кнопке «Повторить».
   ============================================================ */

import { useCallback, useEffect, useState } from 'react';
import { getJson } from '../../api/client';
import type { ModelsResponse } from '../../api/types';
import { buildModelSelect, modelSelectValues, type BuiltModelSelect } from './models';

export type ModelsStatus = 'loading' | 'ready' | 'error';

export interface ModelsState {
  status: ModelsStatus;
  /** Собранный селект: группы провайдеров + дефолт (может быть пустым). */
  built: BuiltModelSelect;
  /** Все допустимые значения опций — проверка сохранённого выбора. */
  values: string[];
}

const NO_MODELS = buildModelSelect([], '', '');
const LOADING: ModelsState = { status: 'loading', built: NO_MODELS, values: [] };
const ERROR: ModelsState = { status: 'error', built: NO_MODELS, values: [] };

/** Загрузка /api/models; retry — новая попытка (для кнопки «Повторить»). */
export default function useModels(): ModelsState & { retry: () => void } {
  const [state, setState] = useState<ModelsState>(LOADING);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getJson<ModelsResponse>('models', controller.signal)
      .then((data) => {
        // Провод недоверенный — как в ваниле, поля проверяются по одному
        const built = buildModelSelect(
          data?.providers,
          typeof data?.defaultProvider === 'string' ? data.defaultProvider : '',
          typeof data?.default === 'string' ? data.default : '',
        );
        setState({ status: 'ready', built, values: modelSelectValues(built) });
      })
      .catch(() => {
        /* Abort (unmount / StrictMode / новая попытка) — тихий выход */
        if (controller.signal.aborted) return;
        setState(ERROR);
      });
    return () => controller.abort();
  }, [attempt]);

  /* Состояние «loading» при повторе ставится в обработчике клавиши, а не в
     теле эффекта — эффект пишет в React только из колбэков промиса. */
  const retry = useCallback(() => {
    setState(LOADING);
    setAttempt((current) => current + 1);
  }, []);
  return { ...state, retry };
}
