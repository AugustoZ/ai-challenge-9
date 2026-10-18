# Изменение: упрощение «Штурмана» — один режим и фронтенд на React

> Статус: draft. Capabilities: `reasoning-modes` (новая),
> `frontend-architecture` (новая), `context-strategy-switcher`
> (REMOVED-дельта).
> SDD-фиксация доработки; реализация идёт по `tasks.md` этого change.
> Конвенция «vanilla сознательно» (AGENTS.md) пересматривается
> осознанно: по решению пользователя фронт переводится на
> React + Vite + TypeScript.

## Зачем

Проект перегружен, и перегрузка тормозит доработки:

- **Шесть режимов размышления** (По умолчанию / Прямой ответ /
  Пошаговое решение / Prompt-to-Prompt / Команда / Агент), из которых
  фактически используется один — «Агент». Пять лишних тянут за собой
  dispatch `when(chat.mode)`, три обработчика (`handleSingle`,
  `handleTeam`, `handlePromptToPrompt`) и блок промптов на бэке,
  пять кнопок-аннунциаторов, `MODE_LABELS`/`setMode` и собственные
  тесты на фронте — суммарно 600–800 строк мёртвого кода.
- **`script.js` вырос до 2792 строк** (186 top-level деклараций, 24
  секции), `style.css` — до 2839 строк: правки требуют держать в
  голове целые файлы; вручную синхронизируемый DOM и 18 мест записи
  настроек — источник скрытой связности.
- **Решение пользователя**: не расщеплять монолит на ES-модули, а
  перевести фронт на React + Vite + TypeScript. Мотивация: типы
  частично компенсируют отсутствие фронтенд-тестов, реактивность
  снимает класс ручных DOM-синхронизаций, карта компонентов уже
  готова (границы из анализа script.js), стек с сильными
  конвенциями удобнее для агентной разработки.

Целевое состояние: **один способ рассуждения — «Агент»** и
**фронтенд на React** в `frontend/`, собираемый Vite и подключаемый к
существующему Gradle-билду без node-плагинов.

## Ключевые решения

| # | Решение | Обоснование |
|---|---------|-------------|
| 1 | **Режимы удаляются целиком, «Агент» — единственный путь (бэкенд)** | Enum `ReasoningMode`, поле `ChatRequest.reasoningMode`, dispatch, `handleSingle`/`handleTeam`/`handlePromptToPrompt` (+ `extractPrompt`/`sumTokens`/`HISTORY_MODES`) удаляются; оба маршрута всегда ведут в `handleAgent`; сессия создаётся на каждый запрос. Десериализация толерантна к устаревшему полю — старый фронт работоспособен до flip |
| 2 | **Фронтенд — React 18 + Vite + TypeScript в `frontend/`** | Дизайн-система DESIGN.md остаётся bespoke: без Tailwind, без компонентных библиотек. Карта секций `script.js` = карта компонентов. Параллельная сборка: `frontend/dist` копируется в classpath-каталог `build/frontend-static/static`, `src/main/resources/static` не перезаписывается — старое приложение живо до flip-коммита |
| 3 | **Состояние — Zustand + persist middleware** | Три стора: `settingsStore` (persist; новый ключ `shturman.settings.v1`, `version: 1`; старый ключ читается однократно как seed), `sessionStore` (сервер), `branchesStore`. SSE-колбэки пишут в сторы вне React (`getState()`); точечные селекторы против re-render штормов; записи журнала обновляются по id. Внутреннее состояние близнецов зеркала — локально в `TwinPane` |
| 4 | **SSE — свой хук, парсер переносится дословно** | Протокол не меняется: SSE `data:`-фреймы, JSON-payload, дискриминатор `type` (agent/status/final/cancelled/error). `parseFrame` — чистая функция с Vitest-тестами. Стоп = `POST /api/cancel` + abort, записи в сторы гвардятся requestId, AbortError оседает «Передача остановлена». Fallback зеркала на блокирующий запрос сохраняется. `@microsoft/fetch-event-source` отклонён: retry не нужен, лишняя зависимость |
| 5 | **Сборка: Gradle Exec+Copy, без node-плагина** | `npmCi → buildFrontend (npm run verify) → copyFrontend`; все три задачи с `onlyIf { !hasProperty("skipFrontend") }`; у Exec объявлены inputs/outputs (иначе up-to-date слепота). `base: '/static/'` только в build-режиме (иначе `/assets/*` 404 под Ktor) |
| 6 | **Миграция — параллельная сборка, big-bang flip** | Порядок по убыванию риска: парсер+тесты → read-only рендереры → настройки → конвейер отправки → зеркало/сессии/ветки/ТО → flip (роут «/», кэш-заголовки, удаление старых static-файлов) |
| 7 | **StrictMode остаётся** | Стримы открываются только из обработчиков событий; mount-эффекты — только идемпотентные GET с abort в cleanup. Двойной invoke в dev не открывает двойных соединений |
| 8 | **Верификация: `npm run verify` + смоук** | `tsc --noEmit` + ESLint + Vitest (только чистые функции: parseFrame, format, сторы; без jsdom/RTL) + `vite build`; привязано к Gradle check. Ручной смоук-чеклист в `frontend/AGENTS.md` |

## Edge cases

- **StrictMode и mount-эффекты.** Эффект со стримом на монтировании =
  двойное подключение в dev; правило «стримы только из обработчиков»
  зафиксировано в spec.
- **Abort-семантика.** Стоп ≠ unmount ≠ superseded request: один
  контроллер на стрим, guard записей по requestId, `/api/cancel` на
  стоп.
- **Зеркальные близнецы.** Состояние по twinId (локально в каждой
  `TwinPane`), никогда общий «latest» — иначе гонки двух стримов.
- **localStorage.** Старый ключ `ai-assistant.settings.v1` не
  рехидрируется в новую схему (удалённые режимы не должны
  воскреснуть) — только однократный seed.
- **marked.** Опция `highlight` удалена в v5+ — подсветка через
  пост-обработку/`marked-highlight`; тема hljs ре-токенизируется под
  палитру DESIGN.md.
- **Gradle.** Отсутствие inputs/outputs у Exec-задач = stale dist;
  `copyFrontend` без `onlyIf` протекает старый dist в classpath при
  `-PskipFrontend`.
- **base path.** Абсолютные `/assets/*` от Vite ломаются под
  `/static/` — base задаётся условно по режиму; flip-коммит поднимает
  роут «/» → index.html.
- **CSS-модули.** Составные селекторы (`.annunciator input:checked +
  label`) живут в одном модуле компонента — разнос убивает состояния.
- **Старые сессии и конфиг.** Режимы не хранятся ни там, ни там;
  десериализация толерантна к незнакомым полям.

## Что меняется (сводка)

- **Бэкенд**: `ReasoningMode.kt` удалён; `Models.kt` — поле
  `reasoningMode`; `Routes.kt` — dispatch, `handleSingle`,
  `handleTeam`, `handlePromptToPrompt`, `extractPrompt`, `sumTokens`,
  `HISTORY_MODES`, ветка статуса «Обрабатываю запрос…», условие в
  guard активации ветки; `ReasoningPrompts.kt` — остаются только
  `budgetNote`/`withBudget`; `StreamEvents.kt` — комментарий.
  `AgentLoop.kt` не затрагивается.
- **Фронтенд**: новая папка `frontend/` (Vite + React + TS, ~20
  компонентов по карте секций); CSS расселяется из `style.css` в
  `tokens.css`/`base.css` + CSS-модули; flip-коммит удаляет
  `static/index.html`, `script.js`, `style.css`.
- **Тесты**: stateless-тесты Team/P2P удалены, агентные сохранены;
  фронт — Vitest на чистые функции (парсер, format, сторы).
- **Инфраструктура**: `frontend/package.json` (Vite, Vitest, ESLint,
  marked, highlight.js, zustand), Gradle-задачи фронтенд-сборки,
  `.nvmrc`.
- **Документация**: README (режимы, запуск dev, архитектура фронта),
  PRODUCT/PLAN (упоминания режимов), AGENTS.md (конвенция фронта,
  CODE MAP, строка «Тестов нет»), `frontend/AGENTS.md` (структура +
  смоук-чеклист), `openspec/project.md` (платформа, путь статики).

## Вне скоупа

- Поведенческие изменения фич: судья, зеркало, стратегии памяти
  переносятся 1:1.
- Tailwind, компонентные библиотеки, CSS-in-JS рантайм.
- jsdom/RTL, компонентные и e2e-тесты.
- Редизайн UI — визуальная паритет со старым приложением.
- Редизайн SSE-протокола, кодогенерация типов.
- Миграция/бэкфилл старых данных.
