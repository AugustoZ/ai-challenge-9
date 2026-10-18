# Спецификация: frontend-architecture

> Дельта change `simplify-frontend`. Новая capability — целевая
> организация фронтенд-кода на React + Vite + TypeScript.

## ADDED Requirements

### Requirement: Стек и раскладка

Фронтенд — React + Vite + TypeScript, проект в `frontend/` в корне
репозитория. TypeScript strict. Node-версия зафиксирована (`.nvmrc` +
`engines`), `package-lock.json` в git. Зависимости — минимум: react,
zustand, marked, highlight.js; без Tailwind, компонентных библиотек,
CSS-in-JS.

#### Scenario: Dev-режим

- КОГДА разработчик работает над фронтом
- ТОГДА `npm run dev` в `frontend/` поднимает приложение, проксируя
  `/api` на Ktor (localhost:8080)
- И пересборка Gradle для правок фронта не требуется

### Requirement: Интеграция сборки с Gradle

Задачи `npmCi → buildFrontend (npm run verify) → copyFrontend` —
Exec/Copy, без node-плагина. `dist` копируется в
`build/frontend-static/static`; каталог подключён как srcDir
ресурсов; `src/main/resources/static` фронтенд-сборкой не
перезаписывается. Все три задачи — под `-PskipFrontend` (`onlyIf`);
у `buildFrontend` объявлены inputs/outputs. `processResources`
зависит от `copyFrontend`, `check` — от `buildFrontend`. `base`
Vite — `/static/` только в build-режиме.

#### Scenario: Итерация по бэкенду

- КОГДА выполняется `./gradlew run -PskipFrontend`
- ТОГДА npm-сборка не выполняется, dist не попадает в classpath
- И текущее содержимое `static/` отдаётся как раньше

### Requirement: Дерево исходников

```
frontend/src/
  api/        types.ts, client.ts, sse.ts
  lib/        format.ts, markdown.ts, storage.ts
  stores/     settings.ts, session.ts, branches.ts
  components/ Dial.tsx, AnnunciatorGroup.tsx, Panel.tsx, Lamp.tsx,
              Clock.tsx, Overlay.tsx, StatusLine.tsx
  features/
    chat/        ChatConsole.tsx, Journal.tsx, Entry.tsx
    params/      ParamsPanel.tsx, ModelSelect.tsx
    strategy/    StrategyRadios.tsx, Facts.tsx
    judge/       JudgePanel.tsx
    trace/       AgentTrace.tsx
    fuel/        FuelMeter.tsx
    maintenance/ Maintenance.tsx, Recorder.tsx
    sessions/    SessionsOverlay.tsx
    branches/    Branches.tsx
    mirror/      Mirror.tsx, TwinPane.tsx
    send/        SendBar.tsx, useChatStream.ts
  App.tsx (layout + 3 оверлея), main.tsx
```

Карта переноса секций `script.js` в модули — в `tasks.md`. Приборные
шкалы (`Dial`) — обычные React-компоненты с сохранённым a11y-контрактом
(скрытый реальный `<input type="range">` поверх SVG).

### Requirement: Типы API — рукописные, зеркалят Models.kt

`api/types.ts`: `ChatRequest`, `ChatResponse`, `AgentEvent` и
дискриминированный union `StreamEvent`
(`type: 'agent' | 'status' | 'final' | 'cancelled' | 'error'`).
Шапка-комментарий «Зеркало Models.kt — синхронизировать вручную».
Кодогенерация не вводится.

### Requirement: Состояние — Zustand + persist

`stores/settings.ts` — persist (ключ `shturman.settings.v1`,
`version: 1`; старый ключ `ai-assistant.settings.v1` читается
однократно как seed, в новую схему не рехидрируется), `stores/session.ts`
и `stores/branches.ts` — серверные, не персистятся. SSE-колбэки пишут
в сторы через `getState()`; селекторы точечные; записи журнала
обновляются по id (structural sharing). Внутреннее состояние близнецов
зеркала — локально в `TwinPane`, стором не является.

#### Scenario: Единая точка записи настроек

- КОГДА любая панель меняет параметр
- ТОГДА изменение идёт через `settingsStore`, персист выполняет
  persist-middleware
- И подписанные компоненты перерисовываются без ручных вызовов

### Requirement: SSE-конвейер

`api/sse.ts`: чистая функция `parseFrame` (разбор `data:`-фрейма,
`JSON.parse`, дискриминация по `type`) + `streamChat` (POST с
`requestId` и `signal`, reader-loop с буфером по `\n\n`, терминальные
`final|cancelled|error`). `features/send/useChatStream.ts`:
AbortController и requestId в `useRef`; стоп = `POST /api/cancel` +
`abort()`; все записи в сторы гвардятся requestId; AbortError оседает
как «Передача остановлена», а не как ошибка. Fallback зеркала на
блокирующий запрос при отсутствии фреймов сохраняется.

### Requirement: Правила эффектов

StrictMode включён. Сетевые стримы открываются только из обработчиков
событий; mount-эффекты — только идемпотентные GET (`/api/models`,
восстановление сессии) с `abort()` в cleanup.

### Requirement: CSS-стратегия

`styles/tokens.css` — единственный блок `:root` (перенос дословно из
`style.css`), `styles/base.css` — body, шрифты B612/B612 Mono,
скроллбары, `visually-hidden`, ре-токенизированная тема hljs. Далее —
CSS-модули по компонентам; составные селекторы (соседние комбинаторы,
состояния аннуниаторов и ламп) живут в одном модуле. Цвета — только
токены.

### Requirement: Верификация фронтенда

`npm run verify` = `tsc --noEmit` + ESLint + `vitest run` + `vite
build`; привязано к Gradle check (с учётом `-PskipFrontend`). Vitest —
только чистые функции (`parseFrame`, `format`, сторы), без jsdom/RTL.
Ручной смоук-чеклист зафиксирован в `frontend/AGENTS.md`: отправка,
зеркало, сессии, ветки, топливомер, судья, режим ТО.

#### Scenario: Правка фронтенда

- КОГДА изменён любой файл `frontend/`
- ТОГДА `./gradlew check` прогоняет `npm run verify`
- И перед коммитом разработчик проходит смоук-чеклист

### Requirement: Переезд (flip)

Финальный коммит миграции: Ktor-роут «/» отдаёт `index.html` из dist
(без кэша), хэшированные `/static/assets/*` — с кэшем; старые
`static/index.html`, `script.js`, `style.css` удалены. До flip старое
приложение остаётся работоспособным.

#### Scenario: Flip

- КОГДА волна flip выполнена
- ТОГДА «/» открывает React-приложение
- И vanilla-файлов в репозитории нет (история — в git)
