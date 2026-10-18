/* ============================================================
   Онбординг-визард предпочтений (план Task 13): 7 шагов-вопросов
   поверх консоли (Overlay). Открывается после создания
   пользователя (auth-стор: onboardingRequired, Task 5) и позже по
   кнопке «Изменить в опроснике» (Task 14). Каждый шаг: клавиши-
   аннунциаторы или колодец ввода со счётчиком символов; «Назад»/
   «Далее», в конце «Сохранить» → PUT /api/preferences.
   Прерывание (Esc, крестик, фон) НИЧЕГО не сохраняет — работают
   серверные дефолты (Task 4). Стиль — приборная доска AuthGate:
   клавиши-аннунциаторы, колодцы, янтарная пластина; только
   токены tokens.css.
   ============================================================ */

import { useEffect, useState } from 'react';

import { fetchPreferences, savePreferences } from '../../api/client';
import {
  ADDRESS_FORMS,
  ADDRESS_MAX,
  LANGUAGES,
  TEXT_MAX,
  TONES,
} from '../../api/types';
import type { AddressForm, Language, Length, Tone } from '../../api/types';
import AnnunciatorGroup from '../../components/AnnunciatorGroup';
import type { AnnunciatorOption } from '../../components/AnnunciatorGroup';
import Overlay from '../../components/Overlay';
import {
  answersFromPreferences,
  buildPayload,
  canAdvance,
  capFreeText,
  counterText,
  createInitialAnswers,
  isLastStep,
  nextStep,
  payloadErrors,
  prevStep,
  sanitizeFreeText,
  saveErrorText,
  STEP_TITLES,
  stepError,
  STRUCTURE_CHOICES,
  WIZARD_STEP_COUNT,
} from './wizard';
import type { StructureChoice, WizardAnswers } from './wizard';
import styles from './OnboardingWizard.module.css';

export interface OnboardingWizardProps {
  open: boolean;
  /** Прерывание (Esc/крестик/клик по фону): закрыть без сохранения. */
  onClose: () => void;
  /** Успешный PUT /api/preferences — визард закрывается. */
  onSaved: () => void;
}

/* Клавиши-аннунциаторы: подписи = значения словарей сервера;
   подсказка длины ответов — по тексту плана (≈1/3/10 тыс. токенов) */
const ADDRESS_OPTIONS: ReadonlyArray<AnnunciatorOption<AddressForm>> =
  ADDRESS_FORMS.map((value) => ({ value, caption: value }));
const TONE_OPTIONS: ReadonlyArray<AnnunciatorOption<Tone>> =
  TONES.map((value) => ({ value, caption: value }));
const LANGUAGE_OPTIONS: ReadonlyArray<AnnunciatorOption<Language>> =
  LANGUAGES.map((value) => ({ value, caption: value }));
const LENGTH_OPTIONS: ReadonlyArray<AnnunciatorOption<Length>> = [
  { value: 'кратко', caption: 'кратко', hint: 'ориентир ≈1 тыс. токенов' },
  { value: 'средне', caption: 'средне', hint: 'ориентир до 3 тыс. токенов' },
  { value: 'развернуто', caption: 'развернуто', hint: 'ориентир ≈10 тыс. токенов' },
];
const STRUCTURE_OPTIONS: ReadonlyArray<AnnunciatorOption<StructureChoice>> =
  STRUCTURE_CHOICES.map((value) => ({
    value,
    caption: value,
    hint: value === 'иное' ? 'опишите свой вариант в поле ниже' : undefined,
  }));

export default function OnboardingWizard({ open, onClose, onSaved }: OnboardingWizardProps) {
  /* Без хуков: закрытый визард не монтируется, при открытии диалог
     создаётся заново — шаги и ответы сбрасываются сами */
  if (!open) return null;
  return <WizardDialog onClose={onClose} onSaved={onSaved} />;
}

function WizardDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [step, setStep] = useState(0);
  const [answers, setAnswers] = useState<WizardAnswers>(createInitialAnswers);
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  /* Предзаполнение сохранёнными предпочтениями: у нового пользователя
     GET отдаёт серверные дефолты (не 404). Отказ запроса опросник не
     блокирует — остаёмся на дефолтах (401 уводит в гейт сам).
     StrictMode удваивает эффекты — abort в cleanup (паттерн AuthGate). */
  useEffect(() => {
    const controller = new AbortController();
    fetchPreferences(controller.signal)
      .then((prefs) => {
        if (!controller.signal.aborted) setAnswers(answersFromPreferences(prefs));
      })
      .catch(() => { /* остаёмся на дефолтах */ });
    return () => controller.abort();
  }, []);

  const patch = (partial: Partial<WizardAnswers>) => {
    setAnswers((prev) => ({ ...prev, ...partial }));
  };

  const stepErr = stepError(step, answers);
  const last = isLastStep(step);
  const finalInvalid = payloadErrors(answers).length > 0;

  const goBack = () => {
    if (!busy) setStep(prevStep(step));
  };

  const goNext = () => {
    if (!busy && canAdvance(step, answers)) setStep(nextStep(step));
  };

  const save = async () => {
    if (busy || finalInvalid) return;
    setBusy(true);
    setSaveError(null);
    try {
      await savePreferences(buildPayload(answers));
      onSaved();
    } catch (err) {
      setSaveError(saveErrorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Overlay
      open
      onClose={onClose}
      title="Настройка предпочтений"
      subtitle="7 вопросов о том, как вам отвечать"
      closeLabel="Закрыть без сохранения"
    >
      <div className={styles.wizard}>
        <div className={styles.progressRow}>
          <div className={styles.segments} aria-hidden="true">
            {Array.from({ length: WIZARD_STEP_COUNT }, (_, i) => (
              <span
                key={i}
                className={i === step ? styles.segCurrent : i < step ? styles.segDone : styles.seg}
              />
            ))}
          </div>
          <span className={styles.stepLabel}>{`Шаг ${step + 1} из ${WIZARD_STEP_COUNT}`}</span>
        </div>

        <p className={styles.question}>{STEP_TITLES[step]}</p>

        {step === 0 && (
          <AnnunciatorGroup
            ariaLabel="Форма обращения"
            value={answers.addressForm}
            options={ADDRESS_OPTIONS}
            onChange={(addressForm) => patch({ addressForm })}
          />
        )}
        {step === 1 && (
          <AnnunciatorGroup
            ariaLabel="Тон общения"
            value={answers.tone}
            options={TONE_OPTIONS}
            onChange={(tone) => patch({ tone })}
          />
        )}
        {step === 2 && (
          <AnnunciatorGroup
            ariaLabel="Язык общения"
            value={answers.language}
            options={LANGUAGE_OPTIONS}
            onChange={(language) => patch({ language })}
          />
        )}
        {step === 3 && (
          <div className={styles.stack}>
            <AnnunciatorGroup
              ariaLabel="Структура ответов"
              value={answers.structureChoice}
              options={STRUCTURE_OPTIONS}
              onChange={(structureChoice) => patch({ structureChoice })}
            />
            {/* Поле активно только при «иное» (план Task 13) */}
            {answers.structureChoice === 'иное' && (
              <label className={styles.field}>
                <span className={styles.fieldLabel}>Свой вариант структуры</span>
                <input
                  className={styles.input}
                  value={answers.structureCustom}
                  onChange={(e) => patch({
                    structureCustom: capFreeText(sanitizeFreeText(e.target.value), ADDRESS_MAX),
                  })}
                  maxLength={ADDRESS_MAX}
                  placeholder="Например: таблицы со сводкой и выводом"
                  autoComplete="off"
                  disabled={busy}
                />
                <span className={styles.counter}>{counterText(answers.structureCustom, ADDRESS_MAX)}</span>
                {stepErr && <span className={styles.error}>{stepErr}</span>}
              </label>
            )}
          </div>
        )}
        {step === 4 && (
          <AnnunciatorGroup
            ariaLabel="Длина ответов"
            value={answers.length}
            options={LENGTH_OPTIONS}
            variant="strategy"
            onChange={(length) => patch({ length })}
          />
        )}
        {step === 5 && (
          <label className={styles.field}>
            <span className={styles.fieldLabel}>Через запятую; можно оставить пустым</span>
            <textarea
              className={styles.textarea}
              value={answers.avoidTopics}
              onChange={(e) => patch({
                avoidTopics: capFreeText(sanitizeFreeText(e.target.value), TEXT_MAX),
              })}
              maxLength={TEXT_MAX}
              rows={4}
              placeholder="Например: политика, спойлеры к фильмам"
              disabled={busy}
            />
            <span className={styles.counter}>{counterText(answers.avoidTopics, TEXT_MAX)}</span>
            {stepErr && <span className={styles.error}>{stepErr}</span>}
          </label>
        )}
        {step === 6 && (
          <label className={styles.field}>
            <span className={styles.fieldLabel}>Хобби, работа, темы для разговора; можно оставить пустым</span>
            <textarea
              className={styles.textarea}
              value={answers.interests}
              onChange={(e) => patch({
                interests: capFreeText(sanitizeFreeText(e.target.value), TEXT_MAX),
              })}
              maxLength={TEXT_MAX}
              rows={5}
              placeholder="Например: космос, гонки, авиация"
              disabled={busy}
            />
            <span className={styles.counter}>{counterText(answers.interests, TEXT_MAX)}</span>
            {stepErr && <span className={styles.error}>{stepErr}</span>}
          </label>
        )}

        {saveError && <p className={styles.error} role="alert">{saveError}</p>}

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.btn}
            onClick={goBack}
            disabled={busy || step === 0}
          >
            Назад
          </button>
          {last ? (
            <button
              type="button"
              className={styles.btnPrimary}
              onClick={() => { void save(); }}
              disabled={busy || finalInvalid}
            >
              {busy ? 'Сохранение…' : 'Сохранить'}
            </button>
          ) : (
            <button
              type="button"
              className={styles.btnPrimary}
              onClick={goNext}
              disabled={busy || stepErr !== null}
            >
              Далее
            </button>
          )}
        </div>
      </div>
    </Overlay>
  );
}
