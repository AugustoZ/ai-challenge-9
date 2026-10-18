/* ============================================================
   Секция «Предпочтения» панели приборов (план Task 14): показания
   семи значений опросника и клавиша «Изменить в опроснике». Чтение
   — GET /api/preferences при каждом открытии Drawer'а (панель живёт
   в DOM постоянно, триггер — переход open). Правка ТОЛЬКО через
   онбординг-визард (Task 13, собственный экземпляр с собственным
   open-состоянием): инлайн-контролы запрещены планом — единая
   модель данных опросника. Значения — табло-цифры прибора: зелёный
   моно в колодце; пустые свободные поля — гравировка «не задано».
   Отказ чтения — строка-подсказка с «Повторить» (паттерн списка
   моделей). Цвета — только токены tokens.css.
   ============================================================ */

import { useCallback, useEffect, useState } from 'react';
import { fetchPreferences } from '../../api/client';
import type { UserPreferences } from '../../api/types';
import annunciatorStyles from '../../components/AnnunciatorGroup.module.css';
import OnboardingWizard from '../onboarding/OnboardingWizard';
import settingsStyles from './SettingsPanel.module.css';
import styles from './PreferencesSection.module.css';

const EMPTY_VALUE = 'не задано';

/** Строки табло в порядке вопросов опросника (план Task 13). */
function prefRows(prefs: UserPreferences): Array<{ label: string; value: string }> {
  return [
    { label: 'Форма обращения', value: prefs.addressForm },
    { label: 'Тон', value: prefs.tone },
    { label: 'Язык', value: prefs.language },
    { label: 'Структура ответов', value: prefs.answerStructure },
    { label: 'Длина ответов', value: prefs.length },
    { label: 'Избегаемые темы', value: prefs.avoidTopics },
    { label: 'Интересы', value: prefs.interests },
  ];
}

export default function PreferencesSection({ open }: { open: boolean }) {
  const [prefs, setPrefs] = useState<UserPreferences | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [wizardOpen, setWizardOpen] = useState(false);

  /* Флаг «Drawer уже открывался»: на десктопе приборы видны всегда и
     клавиши-переключателя нет — секция читает при монтаже; на мобильном
     первое открытие Drawer'а договаривает перечитывание. Выводится во
     время рендера (документированный React-паттерн «adjusting state
     when a prop changes»): setState в теле эффекта запрещён правилом
     react-hooks/set-state-in-effect. */
  const [hasOpened, setHasOpened] = useState(open);
  if (open && !hasOpened) setHasOpened(true);

  /* Чтение: при монтаже, при первом открытии Drawer'а (hasOpened),
     после сохранения в опроснике и по «Повторить»; закрытие Drawer'а
     не перечитывает (hasOpened остаётся true). StrictMode удваивает
     эффекты — abort в cleanup и гвард signal.aborted перед записью
     (паттерн AuthGate): переживёт двойной монтирование, первый запрос
     абортируется, второй доставляет данные. */
  useEffect(() => {
    const controller = new AbortController();
    fetchPreferences(controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return;
        setPrefs(data);
        setFailed(false);
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setFailed(true);
      });
    return () => controller.abort();
  }, [hasOpened, reloadTick]);

  /* Повтор: сброс отказа возвращает табло в состояние «чтение…» */
  const retry = useCallback(() => {
    setFailed(false);
    setReloadTick((tick) => tick + 1);
  }, []);

  /* Визард сам сделал PUT: закрыть и перечитать сохранённое */
  const onWizardSaved = useCallback(() => {
    setWizardOpen(false);
    setReloadTick((tick) => tick + 1);
  }, []);

  const closeWizard = useCallback(() => setWizardOpen(false), []);

  return (
    <section className={settingsStyles.panelSection}>
      <h2 className={settingsStyles.sectionCaption}>Предпочтения</h2>

      {prefs === null && !failed && (
        <p className={styles.note}>Чтение предпочтений…</p>
      )}
      {failed && (
        <p className={styles.note}>
          Не удалось загрузить предпочтения.{' '}
          {prefs === null && (
            <button type="button" className={settingsStyles.retry} onClick={retry}>
              Повторить
            </button>
          )}
        </p>
      )}

      {prefs && (
        <>
          <dl className={styles.rows}>
            {prefRows(prefs).map((row) => (
              <div key={row.label} className={styles.row}>
                <dt className={styles.term}>{row.label}</dt>
                <dd
                  className={row.value ? styles.value : `${styles.value} ${styles.valueEmpty}`}
                  title={row.value || undefined}
                >
                  {row.value || EMPTY_VALUE}
                </dd>
              </div>
            ))}
          </dl>
          <button
            type="button"
            className={annunciatorStyles.annunciator}
            onClick={() => setWizardOpen(true)}
          >
            Изменить в опроснике
          </button>
          <p className={styles.note}>
            Блок «Предпочтения пользователя» прикладывается к каждому запросу модели;
            правка выполняется только в опроснике
          </p>
        </>
      )}

      {/* Экземпляр визарда секции: не совпадает с онбордингом App —
          своё open-состояние, предзаполнение берёт из GET сам */}
      <OnboardingWizard open={wizardOpen} onClose={closeWizard} onSaved={onWizardSaved} />
    </section>
  );
}
