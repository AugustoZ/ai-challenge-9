/* ============================================================
   Каркас «Штурмана» — порт static/index.html L17–342 и
   style.css L69–100, L818–845, ≤900px L2710–2784, ≤600px
   L2786–2828. Раскладка: грид «полоса состояния / приборы /
   журнал / сессии». Волна 6: панель сессий (третья колонка),
   чипы веток над журналом, пульт сравнения под журналом,
   «Обслуживание» в приборах (слот) и самописец.
   ============================================================ */

import { useCallback, useEffect, useRef, useState } from 'react';
import Clock from './components/Clock';
import Drawer from './components/Drawer';
import Overlay from './components/Overlay';
import StatusLine from './components/StatusLine';
import Branches from './features/branches/Branches';
import Journal from './features/chat/Journal';
import FuelMeter from './features/fuel/FuelMeter';
import Maintenance from './features/maintenance/Maintenance';
import MemoryPanel from './features/memory/MemoryPanel';
import Recorder from './features/maintenance/Recorder';
import Mirror from './features/mirror/Mirror';
import SendBar from './features/send/SendBar';
import { statusKindFor } from './features/send/request';
import { useChatStream } from './features/send/useChatStream';
import AuthGate from './features/auth/AuthGate';
import UserChip from './features/auth/UserChip';
import { resetUserState } from './features/auth/resetState';
import OnboardingWizard from './features/onboarding/OnboardingWizard';
import SessionsPanel from './features/sessions/SessionsPanel';
import SettingsPanel from './features/settings/SettingsPanel';
import { useAuthStore } from './stores/auth';
import { useSessionStore } from './stores/session';
import { useSettingsStore } from './stores/settings';
import styles from './App.module.css';

export default function App() {
  /* Гейт входа: checking — идёт restore(); anon — экран входа;
     authed — бортовая консоль. Селектор до остальных хуков. */
  const authStatus = useAuthStore((s) => s.status);
  const restore = useAuthStore((s) => s.restore);
  /* Онбординг-визард (Task 13): поднимается после создания пользователя */
  const onboardingRequired = useAuthStore((s) => s.onboardingRequired);
  const completeOnboarding = useAuthStore((s) => s.completeOnboarding);

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [fuelOpen, setFuelOpen] = useState(false);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [recorderOpen, setRecorderOpen] = useState(false);
  /* Режим ТО не персистится (как в ваниле): тумблер класса body и директивы */
  const [maintOn, setMaintOn] = useState(false);

  /* Селекторы точечные: журнал и топливомер перерисовываются отдельно */
  const entries = useSessionStore((s) => s.entries);
  const totalTokens = useSessionStore((s) => s.totalTokens);
  const tokenHistory = useSessionStore((s) => s.tokenHistory);
  const mirrorMode = useSettingsStore((s) => s.mirrorMode);

  const { send, stop, inFlight, stoppable, directive, setDirective } = useChatStream();

  /* Режим ТО (setMaint, script.js L1242–1248): класс body; директива
     переписывается, только пока состояние — готовность */
  const toggleMaint = useCallback((on: boolean) => {
    setMaintOn(on);
    setDirective((d) => d.kind === 'ready'
      ? { kind: 'ready', text: on ? 'Режим ТО: сырой обмен виден в журнале' : 'Готов к передаче' }
      : d);
  }, [setDirective]);

  useEffect(() => {
    document.body.classList.toggle('maint-on', maintOn);
    return () => document.body.classList.remove('maint-on');
  }, [maintOn]);

  /* Выход/смена пользователя/401 (переход authed → anon): чистим
     sessionId, журнал, топливомер, перечень сессий, факты, ветки,
     память и реестр зеркала (reset-лист плана Task 14) — пользователь
     B не видит данных пользователя A. Перечни нового пользователя
     перечитываются сами: SessionsPanel запрашивает /api/sessions в
     mount-эффекте после повторного входа. */
  const prevAuthStatusRef = useRef(authStatus);
  useEffect(() => {
    if (prevAuthStatusRef.current === 'authed' && authStatus === 'anon') {
      resetUserState();
    }
    prevAuthStatusRef.current = authStatus;
  }, [authStatus]);

  /* Восстановление сессии вкладки (один раз): токен из sessionStorage
     проверяется запросом → authed, иначе anon → AuthGate. Идемпотентно:
     StrictMode удваивает эффекты, повторный restore безвреден. */
  useEffect(() => {
    void restore();
  }, [restore]);

  /* Полноэкранный гейт: заглушка на время проверки, экран входа —
     вместо консоли (условный рендер, не Overlay). После 401 в любом
     вызове auth-стор уводит в anon — гейт возвращается сам. */
  if (authStatus === 'checking') {
    return <div className={styles.boot}>Проверка сессии…</div>;
  }
  if (authStatus !== 'authed') {
    return <AuthGate />;
  }

  return (
    <div className={styles.container}>
      <header className={styles.topStrip}>
        <h1 className={styles.brand}>Штурман</h1>
        <div className={styles.stripRight}>
          <UserChip />
          <Clock className={styles.clock} />
          {tokenHistory.length > 0 && (
            <button
              type="button"
              className={styles.fuel}
              aria-haspopup="dialog"
              aria-controls="fuelOverlay"
              onClick={() => setFuelOpen(true)}
            >
              {`Топливо за сессию ${totalTokens}`}
            </button>
          )}
          <button
            type="button"
            className={styles.fuel}
            aria-label="Память агента"
            aria-haspopup="dialog"
            aria-controls="memoryOverlay"
            onClick={() => setMemoryOpen(true)}
          >
            Память
          </button>
          <button
            type="button"
            className={styles.drawerToggle}
            aria-expanded={settingsOpen}
            aria-controls="settings"
            aria-label="Показать приборы"
            onClick={() => setSettingsOpen(true)}
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false">
              <line x1="4" y1="7" x2="20" y2="7" />
              <line x1="4" y1="12" x2="20" y2="12" />
              <line x1="4" y1="17" x2="20" y2="17" />
              <circle cx="9" cy="7" r="2.2" fill="currentColor" stroke="none" />
              <circle cx="15" cy="12" r="2.2" fill="currentColor" stroke="none" />
              <circle cx="8" cy="17" r="2.2" fill="currentColor" stroke="none" />
            </svg>
            Приборы
          </button>
        </div>
      </header>

      <Drawer
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        closeLabel="Закрыть приборы"
        id="settings"
      >
        <SettingsPanel
          open={settingsOpen}
          maintenance={(
            <Maintenance
              maintOn={maintOn}
              onMaintToggle={toggleMaint}
              onOpenRecorder={() => setRecorderOpen(true)}
              onDirective={setDirective}
            />
          )}
        />
      </Drawer>

      <main className={styles.logColumn}>
        {/* Чипы веток (ваниль branchBar) — видны только под «Ветками» */}
        <Branches onDirective={setDirective} />
        <Journal entries={entries} />
        {/* Пульт сравнения (ваниль mirrorBlock) — только в режиме сравнения */}
        {mirrorMode && <Mirror />}
        <StatusLine kind={statusKindFor(inFlight, directive)} text={directive.text} />
        <SendBar inFlight={inFlight} stoppable={stoppable} onSend={(text) => void send(text)} onStop={stop} />
      </main>

      {/* Панель сессий — третья колонка каркаса (ваниль sessions-panel) */}
      <SessionsPanel />

      <Overlay
        open={fuelOpen}
        onClose={() => setFuelOpen(false)}
        title="Топливомер"
        subtitle="расход топлива за сессию"
        closeLabel="Закрыть топливомер"
        id="fuelOverlay"
      >
        <FuelMeter history={tokenHistory} />
      </Overlay>

      {/* Память агента: три слоя — краткосрочный, рабочий, долговременный */}
      <MemoryPanel open={memoryOpen} onClose={() => setMemoryOpen(false)} />

      {/* Самописец: протокол обменов с моделью (ваниль recorderOverlay) */}
      <Recorder open={recorderOpen} onClose={() => setRecorderOpen(false)} />

      {/* Онбординг-визард предпочтений (Task 13): поднимается после создания
          пользователя; прерывание ничего не сохраняет (серверные дефолты) */}
      <OnboardingWizard
        open={onboardingRequired}
        onClose={completeOnboarding}
        onSaved={completeOnboarding}
      />
    </div>
  );
}
