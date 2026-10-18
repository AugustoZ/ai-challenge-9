/* ============================================================
   AuthGate — полноэкранный гейт входа «Штурмана». Пока
   authStore.status !== 'authed', рендерится ВМЕСТО содержимого
   App (условный рендер в App.tsx — это НЕ Overlay). Поток:
   список пользователей (клавиши в стиле аннунциаторов) → выбор →
   поле ПИН («ВВЕДИТЕ ПИН пользователя», inputmode=numeric, маска
   4 цифры) → POST /api/auth/login. Рядом — «Создать пользователя»
   (имя ≤25 + ПИН 4 цифры, валидация Task 5) с авто-входом.
   Ошибки на русском; 429 показывает кулдаун-текст сервера.
   Стиль — приборная доска: только токены tokens.css.
   ============================================================ */

import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';

import { listUsers } from '../../api/client';
import type { AuthUserView } from '../../api/types';
import { isValidName, NAME_MAX_LENGTH } from '../../lib/validators';
import { useAuthStore } from '../../stores/auth';
import {
  createErrorText,
  isPinComplete,
  loginErrorText,
  maskPin,
  validateCreateForm,
} from './authGateLogic';
import styles from './AuthGate.module.css';

type GateView = 'login' | 'create';

const USERS_ERROR = 'Не удалось загрузить список пользователей';

/* Иконки «глаз» для клавиши видимости ПИН (порт стиля SVG из App.tsx);
   перечёркнутый показывается, когда ПИН открыт (клик — скрыть) */
const eyeOpenIcon = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false">
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);
const eyeOffIcon = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false">
    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
    <line x1="1" y1="1" x2="23" y2="23" />
  </svg>
);

export default function AuthGate() {
  const login = useAuthStore((s) => s.login);
  const createUser = useAuthStore((s) => s.createUser);

  const [view, setView] = useState<GateView>('login');

  /* Список пользователей экрана входа (open-роут, id+name) */
  const [users, setUsers] = useState<AuthUserView[] | null>(null);
  const [usersLoading, setUsersLoading] = useState(true);
  const [usersError, setUsersError] = useState<string | null>(null);

  /* Вход по ПИН */
  const [selectedUser, setSelectedUser] = useState<AuthUserView | null>(null);
  const [pin, setPin] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /* Форма создания пользователя */
  const [name, setName] = useState('');
  const [createPin, setCreatePin] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [createPinTouched, setCreatePinTouched] = useState(false);

  /* Видимость ПИН: по умолчанию оба поля маскированы (type=password) */
  const [showPin, setShowPin] = useState(false);
  const [showCreatePin, setShowCreatePin] = useState(false);

  const pinInputRef = useRef<HTMLInputElement | null>(null);
  const nameInputRef = useRef<HTMLInputElement | null>(null);

  /* Список пользователей: идемпотентный GET с abort в cleanup
     (mount-эффект, StrictMode удваивает эффекты). Повтор после
     отказа — через reloadTick: сброс состояния в обработчике клавиши,
     не в теле эффекта. Список пуст → сразу форма создания (первому
     пользователю нечего выбирать). */
  const [reloadTick, setReloadTick] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    listUsers(controller.signal)
      .then((list) => {
        setUsers(list);
        if (list.length === 0) setView('create');
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setUsers(null);
        setUsersError(USERS_ERROR);
      })
      .finally(() => {
        if (!controller.signal.aborted) setUsersLoading(false);
      });
    return () => controller.abort();
  }, [reloadTick]);

  const retryUsers = () => {
    setUsersLoading(true);
    setUsersError(null);
    setReloadTick((tick) => tick + 1);
  };

  const selectUser = (user: AuthUserView) => {
    setSelectedUser(user);
    setPin('');
    setFormError(null);
  };

  const backToLogin = () => {
    setView('login');
    setName('');
    setCreatePin('');
    setNameTouched(false);
    setCreatePinTouched(false);
    setFormError(null);
  };

  /* Фокус за выбором режима: пользователь выбран → в поле ПИН,
     открыта форма создания → в поле имени */
  useEffect(() => {
    if (view === 'create') nameInputRef.current?.focus();
  }, [view]);
  useEffect(() => {
    if (view === 'login' && selectedUser) pinInputRef.current?.focus();
  }, [view, selectedUser]);

  const createValidation = validateCreateForm(name, createPin);
  const shownNameError = nameTouched ? createValidation.nameError : null;
  const shownPinError = createPinTouched ? createValidation.pinError : null;

  const submitLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedUser || busy || !isPinComplete(pin)) return;
    setBusy(true);
    setFormError(null);
    try {
      await login(selectedUser.id, pin);
      /* стор переключит status → authed; App сам сменит экран */
    } catch (err) {
      setFormError(loginErrorText(err));
      setPin('');
    } finally {
      setBusy(false);
    }
  };

  const submitCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    setNameTouched(true);
    setCreatePinTouched(true);
    if (createValidation.nameError !== null || createValidation.pinError !== null) return;
    setBusy(true);
    setFormError(null);
    try {
      await createUser(name.trim(), createPin);
      /* авто-вход выполняет стор; онбординг-визард — Task 13 */
    } catch (err) {
      setFormError(createErrorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.gate}>
      <section className={styles.plate}>
        <header className={styles.head}>
          <h1 className={styles.brand}>Штурман</h1>
          <p className={styles.subtitle}>вход в бортовую консоль</p>
        </header>

        {view === 'login' ? (
          <div className={styles.loginView}>
            <p className={styles.sectionLabel}>Пользователи</p>
            {usersLoading && <p className={styles.hint}>Загрузка пользователей…</p>}
            {usersError && (
              <div className={styles.usersError}>
                <p className={styles.error} role="alert">{usersError}</p>
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={styles.btn}
                    disabled={busy}
                    onClick={retryUsers}
                  >
                    Повторить
                  </button>
                  <button
                    type="button"
                    className={styles.btn}
                    disabled={busy}
                    onClick={() => { setView('create'); setFormError(null); }}
                  >
                    Создать пользователя
                  </button>
                </div>
              </div>
            )}
            {users !== null && users.length > 0 && (
              <div className={styles.userGrid}>
                {users.map((user) => (
                  <button
                    key={user.id}
                    type="button"
                    className={styles.userKey}
                    aria-pressed={selectedUser?.id === user.id}
                    disabled={busy}
                    onClick={() => selectUser(user)}
                  >
                    {user.name}
                  </button>
                ))}
              </div>
            )}

            <form className={styles.pinForm} onSubmit={(e) => { void submitLogin(e); }}>
              <label className={styles.fieldLabel} htmlFor="gate-pin">
                ВВЕДИТЕ ПИН пользователя
              </label>
              <div className={styles.pinWrap}>
                <input
                  id="gate-pin"
                  ref={pinInputRef}
                  className={styles.pinInput}
                  type={showPin ? 'text' : 'password'}
                  value={pin}
                  onChange={(e) => { setPin(maskPin(e.target.value)); setFormError(null); }}
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={4}
                  placeholder="••••"
                  disabled={busy || !selectedUser}
                  aria-invalid={formError ? true : undefined}
                />
                <button
                  type="button"
                  className={styles.pinToggle}
                  aria-label={showPin ? 'Скрыть ПИН' : 'Показать ПИН'}
                  aria-pressed={showPin}
                  disabled={busy || !selectedUser}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => setShowPin((shown) => !shown)}
                >
                  {showPin ? eyeOffIcon : eyeOpenIcon}
                </button>
              </div>
              <p className={styles.hint}>
                {selectedUser
                  ? `Пользователь: ${selectedUser.name}`
                  : 'Сначала выберите пользователя в списке выше'}
              </p>
              {formError && (
                <p className={styles.error} role="alert">{formError}</p>
              )}
              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.btn}
                  disabled={busy}
                  onClick={() => { setSelectedUser(null); setPin(''); setFormError(null); }}
                >
                  Сброс
                </button>
                <button
                  type="submit"
                  className={styles.btnPrimary}
                  disabled={!isPinComplete(pin) || busy || !selectedUser}
                >
                  {busy ? 'Проверка…' : 'Войти'}
                </button>
              </div>
            </form>

            <div className={styles.secondary}>
              <button
                type="button"
                className={styles.btn}
                disabled={busy}
                onClick={() => { setView('create'); setFormError(null); }}
              >
                Создать пользователя
              </button>
            </div>
          </div>
        ) : (
          <form
            className={styles.createForm}
            onSubmit={(e) => { void submitCreate(e); }}
            noValidate
          >
            <p className={styles.sectionLabel}>Новый пользователь</p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Имя пользователя</span>
              <input
                ref={nameInputRef}
                className={styles.input}
                value={name}
                onChange={(e) => setName(e.target.value)}
                onBlur={() => setNameTouched(true)}
                maxLength={NAME_MAX_LENGTH}
                placeholder="Пилот Тест"
                autoComplete="off"
                disabled={busy}
                aria-invalid={shownNameError ? true : undefined}
              />
              {shownNameError && <span className={styles.error}>{shownNameError}</span>}
            </label>
            <div className={styles.field}>
              <label className={styles.fieldLabel} htmlFor="create-pin">ПИН (4 цифры)</label>
              <div className={styles.pinWrap}>
                <input
                  id="create-pin"
                  className={styles.pinInput}
                  type={showCreatePin ? 'text' : 'password'}
                  value={createPin}
                  onChange={(e) => {
                    setCreatePin(maskPin(e.target.value));
                    setCreatePinTouched(true);
                    setFormError(null);
                  }}
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={4}
                  placeholder="••••"
                  disabled={busy}
                  aria-invalid={shownPinError ? true : undefined}
                />
                <button
                  type="button"
                  className={styles.pinToggle}
                  aria-label={showCreatePin ? 'Скрыть ПИН' : 'Показать ПИН'}
                  aria-pressed={showCreatePin}
                  disabled={busy}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => setShowCreatePin((shown) => !shown)}
                >
                  {showCreatePin ? eyeOffIcon : eyeOpenIcon}
                </button>
              </div>
              {shownPinError && <span className={styles.error}>{shownPinError}</span>}
            </div>
            <p className={styles.hint}>Запомните ПИН — восстановить его нельзя</p>
            {formError && (
              <p className={styles.error} role="alert">{formError}</p>
            )}
            <div className={styles.actions}>
              <button
                type="button"
                className={styles.btn}
                disabled={busy}
                onClick={backToLogin}
              >
                Ко входу
              </button>
              <button
                type="submit"
                className={styles.btnPrimary}
                disabled={busy || !isValidName(name) || createValidation.pinError !== null}
              >
                {busy ? 'Создание…' : 'Создать и войти'}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
