/* ============================================================
   Панель приборов — порт 1:1 из static/.
   Разметка: index.html L55–261 — sidebar-body без «Способа
   рассуждения» (режимы удалены в волне 1); футер с клавишей
   сброса — L258–261. Логика: script.js L840–867 (readSettings/
   saveSettings → пишет settingsStore, персист выполняет
   persist-middleware — прямого доступа к хранилищу здесь нет),
   L925–980 (выбор модели), L982–1006 (тумблеры), L1169–1183
   (сброс), L812–838 (тултипы «?»). Панель фактов (L228–231,
   волна 6 — компонент Facts) и секция «Обслуживание» (L244–255,
   волна 6 — слот maintenance из App) встроены в порядок секций.
   Дизайн заморожен.
   ============================================================ */

import { useEffect, useId, useRef, useState } from 'react';
import type { FocusEvent as ReactFocusEvent, KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react';
import AnnunciatorGroup from '../../components/AnnunciatorGroup';
import type { AnnunciatorOption } from '../../components/AnnunciatorGroup';
import annunciatorStyles from '../../components/AnnunciatorGroup.module.css';
import Dial from '../../components/Dial';
import { DIALS } from '../../components/dialMath';
import Facts from '../strategy/Facts';
import InvariantsPanel from '../invariants/InvariantsPanel';
import type { ContextStrategy } from '../../stores/settings';
import { useSettingsStore } from '../../stores/settings';
import PreferencesSection from './PreferencesSection';
import FaqPipelineSection from './FaqPipelineSection';
import { resolveModelValue } from './models';
import useModels from './useModels';
import styles from './SettingsPanel.module.css';

/* Стратегии памяти — подписи и подсказки дословно из static/index.html
   (банк #strategyMode). Пять клавиш: нечётный хвост — «Ветки» на всю ширину. */
const STRATEGY_OPTIONS: ReadonlyArray<AnnunciatorOption<ContextStrategy>> = [
  { value: 'off', caption: 'Выкл', hint: 'Контекст: полная история диалога' },
  { value: 'window', caption: 'Окно', hint: 'Контекст: последние 5 сообщений, старые удаляются из истории' },
  { value: 'summary', caption: 'Сводка', hint: 'Контекст: сводка старых сообщений плюс окно последних 5' },
  { value: 'facts', caption: 'Факты', hint: 'Контекст: факты диалога плюс 3 последних сообщения' },
  { value: 'branching', caption: 'Ветки', hint: 'Контекст: авто-ветвление диалога' },
];

/* Текст недоступного списка — дословно из script.js L969 (директива ванили). */
const MODELS_UNAVAILABLE = 'Список моделей недоступен — работает провайдер по умолчанию';

/* Строка настройки «label + «?» + control» с закрепляемой подсказкой —
   порт script.js L812–838 (клик по «?» — тач, Esc снимает, клик мимо
   закрывает закреплённую) и style.css L605–714 (показ по hover/focus). */
function SettingInline(props: {
  label: string;
  /** aria-label кнопки «?» (у режимов — «Описание режима …», как в ваниле). */
  ariaLabel: string;
  tip: string;
  /** htmlFor подписи; без него — span (клавишных строк подпись не подписывает). */
  htmlFor?: string;
  children?: ReactNode;
}) {
  const { label, ariaLabel, tip, htmlFor, children } = props;
  const tipId = useId();
  const btnRef = useRef<HTMLButtonElement | null>(null);
  const [pinned, setPinned] = useState(false);

  /* Клик мимо подсказки закрывает закреплённую; клик по своей «?» —
     исключение: её обработчик уже переключил состояние (script.js L829–838) */
  useEffect(() => {
    if (!pinned) return;
    const onDocClick = (e: MouseEvent) => {
      if (e.target instanceof Element && btnRef.current?.contains(e.target)) return;
      setPinned(false);
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [pinned]);

  const onBtnKeyDown = (e: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== 'Escape') return;
    setPinned(false);
    e.currentTarget.blur();
  };

  return (
    <div className={pinned ? `${styles.settingInline} ${styles.tipOpen}` : styles.settingInline}>
      {htmlFor ? (
        <label className={styles.settingLabel} htmlFor={htmlFor}>{label}</label>
      ) : (
        <span className={styles.settingLabel}>{label}</span>
      )}
      <button
        type="button"
        ref={btnRef}
        className={styles.infoBtn}
        aria-expanded={pinned}
        aria-controls={tipId}
        aria-describedby={tipId}
        aria-label={ariaLabel}
        onClick={() => setPinned((open) => !open)}
        onKeyDown={onBtnKeyDown}
      >
        ?
      </button>
      {children}
      <span className={styles.tooltip} role="tooltip" id={tipId}>{tip}</span>
    </div>
  );
}

/* Тумблер-табло (aria-pressed) — клавиша из банка табло на тех же классах,
   как и задумано в волне 3a (см. комментарий в AnnunciatorGroup.module.css). */
function Toggle(props: {
  pressed: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className={annunciatorStyles.annunciator}
      aria-pressed={props.pressed}
      onClick={props.onToggle}
    >
      {props.children}
    </button>
  );
}

export default function SettingsPanel({ open, maintenance }: { open: boolean; maintenance?: ReactNode }) {
  /* Селекторы точечные: перерисовывается только изменившийся прибор. */
  const model = useSettingsStore((s) => s.model);
  const temperature = useSettingsStore((s) => s.temperature);
  const maxTokens = useSettingsStore((s) => s.maxTokens);
  const topP = useSettingsStore((s) => s.topP);
  const stop = useSettingsStore((s) => s.stop);
  const thinking = useSettingsStore((s) => s.thinking);
  const judge = useSettingsStore((s) => s.judge);
  const contextStrategy = useSettingsStore((s) => s.contextStrategy);
  const mirrorMode = useSettingsStore((s) => s.mirrorMode);
  const memoryTools = useSettingsStore((s) => s.memoryTools);
  const setModel = useSettingsStore((s) => s.setModel);
  const setTemperature = useSettingsStore((s) => s.setTemperature);
  const setMaxTokens = useSettingsStore((s) => s.setMaxTokens);
  const setTopP = useSettingsStore((s) => s.setTopP);
  const setStop = useSettingsStore((s) => s.setStop);
  const setThinking = useSettingsStore((s) => s.setThinking);
  const setJudge = useSettingsStore((s) => s.setJudge);
  const setContextStrategy = useSettingsStore((s) => s.setContextStrategy);
  const setMirrorMode = useSettingsStore((s) => s.setMirrorMode);
  const setMemoryTools = useSettingsStore((s) => s.setMemoryTools);
  const reset = useSettingsStore((s) => s.reset);

  const models = useModels();

  /* Выбор селекта: сохранённая модель, если ещё в списке, иначе дефолт
     (script.js L966–967). Устаревшее сохранённое значение заменяется
     дефолтом — в ваниле оно не пережило бы загрузку списка. */
  const modelValue =
    models.status === 'ready'
      ? resolveModelValue(models.values, model, models.built.fallbackValue)
      : '';

  useEffect(() => {
    if (models.status !== 'ready') return;
    if (model && !models.values.includes(model)) {
      setModel(models.built.extraOption?.value ?? '');
    }
  }, [models.status, models.values, models.built.extraOption, model, setModel]);

  /* Стоп-фраза — неконтролируемое поле, как в ваниле: набор не пишет в стор,
     по change (blur) сохраняется обрезанное значение (script.js L852, L1169);
     key=stop — внешняя запись в стор («Сбросить параметры») пересоздаёт поле. */
  const modelSelectId = useId();
  const stopInputId = useId();

  const onStopBlur = (e: ReactFocusEvent<HTMLInputElement>) => {
    const trimmed = e.currentTarget.value.trim();
    if (trimmed !== stop) setStop(trimmed);
  };

  return (
    <>
      <section className={styles.panelSection}>
        <h2 className={styles.sectionCaption}>Параметры модели</h2>

        <SettingInline
          label="Модель"
          htmlFor={modelSelectId}
          ariaLabel="Описание параметра «Модель»"
          tip="Модели LLM по провайдерам: группы — провайдеры, запросы уходят выбранной модели её провайдера"
        >
          <select
            id={modelSelectId}
            className={styles.selectInput}
            autoComplete="off"
            value={modelValue}
            onChange={(e) => {
              /* Пока список не готов, выбирать нечего — пишем только из готового */
              if (models.status === 'ready') setModel(e.target.value);
            }}
          >
            {models.status === 'loading' && <option value="">Загрузка…</option>}
            {models.status === 'error' && <option value="">{MODELS_UNAVAILABLE}</option>}
            {models.status === 'ready' && !models.values.length && (
              <option value="" disabled>{MODELS_UNAVAILABLE}</option>
            )}
            {models.built.groups.map((group) => (
              <optgroup key={group.label} label={group.label}>
                {group.options.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </optgroup>
            ))}
            {models.built.extraOption && (
              <option value={models.built.extraOption.value}>{models.built.extraOption.label}</option>
            )}
          </select>
        </SettingInline>
        {models.status === 'error' && (
          <p className={annunciatorStyles.hint}>
            {MODELS_UNAVAILABLE}.{' '}
            <button type="button" className={styles.retry} onClick={models.retry}>Повторить</button>
          </p>
        )}

        <div className={styles.gauges}>
          <Dial
            label="Температура"
            value={temperature}
            min={DIALS.temperature.min}
            max={DIALS.temperature.max}
            step={DIALS.temperature.step}
            fmt={DIALS.temperature.fmt}
            zones={DIALS.temperature.zones}
            tip="Контроль случайности: меньше — предсказуемее; 0 — детерминированно"
            onChange={setTemperature}
          />
          <Dial
            label="Топливо"
            value={maxTokens}
            min={DIALS.maxTokens.min}
            max={DIALS.maxTokens.max}
            step={DIALS.maxTokens.step}
            fmt={DIALS.maxTokens.fmt}
            log
            zones={DIALS.maxTokens.zones}
            tip="Бюджет всего ответа в токенах: расходуется на шаги агентного цикла"
            onChange={setMaxTokens}
          />
          <Dial
            label="Top P"
            value={topP}
            min={DIALS.topP.min}
            max={DIALS.topP.max}
            step={DIALS.topP.step}
            fmt={DIALS.topP.fmt}
            zones={DIALS.topP.zones}
            tip="Контроль разнообразия через ядерную выборку: 0.5 — половина вариантов. Минимум 0.01: ноль API отвергает"
            onChange={setTopP}
          />
        </div>

        <SettingInline
          label="Стоп-фраза"
          htmlFor={stopInputId}
          ariaLabel="Описание параметра «Стоп-фраза»"
          tip="Стоп-последовательность: генерация остановится, когда этот текст появится в выводе"
        >
          <input
            key={stop}
            id={stopInputId}
            type="text"
            className={styles.textInput}
            defaultValue={stop}
            placeholder="Не задана"
            autoComplete="off"
            spellCheck={false}
            onBlur={onStopBlur}
          />
        </SettingInline>

        <SettingInline
          label="Рассуждения"
          ariaLabel="Описание параметра «Рассуждения»"
          tip={'Цепочка рассуждений модели (параметр thinking): ВКЛ → {"type":"enabled"}, ВЫКЛ → {"type":"disabled"}'}
        >
          <Toggle pressed={thinking} onToggle={() => setThinking(!thinking)}>
            {`Рассуждения: ${thinking ? 'ВКЛ' : 'ВЫКЛ'}`}
          </Toggle>
        </SettingInline>
      </section>

      <section className={styles.panelSection}>
        <h2 className={styles.sectionCaption}>Приёмка</h2>
        <SettingInline
          label="Судья"
          ariaLabel="Описание режима «Судья»"
          tip="Контрольная проверка ответа отдельным вызовом LLM: факты, качество, скорость и ресурсоёмкость; заключение появится под ответом"
        >
          <Toggle pressed={judge} onToggle={() => setJudge(!judge)}>
            {`Судья: ${judge ? 'ВКЛ' : 'ВЫКЛ'}`}
          </Toggle>
        </SettingInline>
        <p className={annunciatorStyles.hint}>
          Каждый ответ перепроверяется судёй; под ответом появится его заключение
        </p>
      </section>

      <section className={styles.panelSection}>
        <h2 className={styles.sectionCaption}>Память</h2>
        <SettingInline
          label="Стратегия"
          ariaLabel="Описание параметра «Стратегия памяти»"
          tip="Стратегия памяти диалога: полная история, окно последних 5 сообщений (старые удаляются из истории), сводка старых плюс окно, факты плюс 3 последних сообщения или авто-ветвление"
        />
        <AnnunciatorGroup
          ariaLabel="Стратегия памяти"
          variant="strategy"
          value={contextStrategy}
          options={STRATEGY_OPTIONS}
          onChange={setContextStrategy}
        />
        {/* Панель фактов (index.html L228–231): данные — из ChatResponse
            через session store; видна только под стратегией «Факты» */}
        <Facts />
        <SettingInline
          label="Сравнение"
          ariaLabel="Описание режима «Режим сравнения»"
          tip="Зеркальный режим: каждый запрос уходит параллельно в две сессии — каждая со своей стратегией памяти (селект в шапке колонки); ответы и расход топлива сравниваются бок о бок под журналом"
        >
          <Toggle pressed={mirrorMode} onToggle={() => setMirrorMode(!mirrorMode)}>
            {`Режим сравнения: ${mirrorMode ? 'ВКЛ' : 'ВЫКЛ'}`}
          </Toggle>
        </SettingInline>
        <p className={annunciatorStyles.hint}>
          Стратегия памяти задаёт, что уходит в контекст; «Режим сравнения» ведёт
          параллельную пару сессий со своими стратегиями и показывает расход топлива обеих
        </p>
        <SettingInline
          label="Инструменты памяти"
          ariaLabel="Описание параметра «Инструменты памяти агента»"
          tip="Выключите, чтобы агент не читал и не сохранял память"
        >
          <Toggle pressed={memoryTools} onToggle={() => setMemoryTools(!memoryTools)}>
            {`Инструменты памяти агента: ${memoryTools ? 'ВКЛ' : 'ВЫКЛ'}`}
          </Toggle>
        </SettingInline>
        <p className={annunciatorStyles.hint}>
          Выключите, чтобы агент не читал и не сохранял память
        </p>
      </section>

      {/* Секция «Инварианты» (план Task 10): глобальный и сессионный
          скоупы непреложных правил; чтение — на смену sessionId */}
      <InvariantsPanel />

      {/* Секция «Предпочтения» (план Task 14): табло текущих значений
          опросника и визард правки; чтение — при открытии Drawer'а */}
      <PreferencesSection open={open} />

      {/* Секция «Обслуживание» (index.html L244–255) — слот App: состояние
          Режима ТО и самописца живёт в каркасе страницы, как в ваниле */}
      {maintenance}

      {/* Секция «Обслуживание: FAQ» (план Task 11): клавиша запуска
          faq-24h и зона статуса с автоопросом /api/pipeline/status */}
      <FaqPipelineSection />

      <div className={styles.panelFoot}>
        <button type="button" className={styles.resetBtn} onClick={reset}>
          Сбросить параметры
        </button>
      </div>
    </>
  );
}
