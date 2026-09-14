# Tasks: simplify-frontend

Чек-лист реализации по волнам (архитектура согласована с
консультациями Oracle). Отмечается по факту, после реализации и
смоука; spec при расхождении с кодом правится под код. Верификация
каждой волны: `./gradlew build` (после волны 0 включает `npm run
verify`) + ручной смоук-чеклист.

CLI `openspec` в среде не установлен (прецедент
`add-context-strategies`) — структура change проверена вручную.

## Волна 0 — инфраструктура React

- [x] `frontend/`: Vite + React 18 + TS (шаблон react-ts, tsconfig
  strict, ESLint из шаблона); `.nvmrc` + `engines`; `.gitignore`
  (dist, node_modules) — дельта `specs/frontend-architecture/spec.md`
- [x] `vite.config.ts`: плагин react; `base` = `/static/` только в
  build; proxy `/api` → localhost:8080
- [x] `styles/tokens.css` (перенос `:root` дословно) +
  `styles/base.css` (body, шрифты B612, скроллбары,
  `visually-hidden`, тема hljs под токены)
- [x] Gradle: `npmCi → buildFrontend (npm run verify) → copyFrontend`
  (dist → build/frontend-static/static), srcDir ресурсов, зависимости
  processResources/check, `-PskipFrontend` (onlyIf на всех трёх),
  inputs/outputs у Exec объявлены
- [x] `npm run verify` = tsc --noEmit + eslint + vitest + vite build
- [x] Доки: AGENTS.md — конвенция «vanilla сознательно» заменена на
  React-конвенцию; смоук-чеклист в `frontend/AGENTS.md`
- [x] Смоук: `npm run dev` против работающего Ktor; старое приложение
  живо и не перезаписано

## Волна 1 — бэкенд: удаление режимов

Дельта `specs/reasoning-modes/spec.md`. Фронтенд-часть удаления
отменена: старый фронт шлёт `reasoningMode`, который бэкенд
толерантно игнорирует; vanilla-UI умирает в flip (волна 7).

- [x] `ReasoningMode.kt` удалён; `Models.kt`: поле `reasoningMode` и
  упоминания режимов в doc-комментариях вычищены
- [x] `Routes.kt`: dispatch `when(chat.mode)` в обоих маршрутах →
  всегда `handleAgent`; удалены `handleSingle`, `handleTeam`,
  `handlePromptToPrompt`, `extractPrompt`, `sumTokens`,
  `HISTORY_MODES`, ветка `Status("Обрабатываю запрос…")`; guard
  активации ветки без условия по режиму
- [x] `ReasoningPrompts.kt`: удалены промпты DIRECT/STEP_BY_STEP/P2P,
  `TEAM_ROLES`, `TEAM_SYNTHESIS_SYSTEM`, `teamSynthesisUser`,
  `systemFor`, `TeamRole`; остаются `budgetNote`/`withBudget`
- [x] Десериализация `ChatRequest` толерантна к устаревшему полю
  `reasoningMode` (старый фронт и curl — без ошибки)
- [x] `StreamEvents.kt`: комментарий про «не-агентные режимы» обновлён
- [x] Тесты: stateless-тесты Team/P2P удалены, helper `postChat` без
  `reasoningMode`, агентные тесты сохранены; `./gradlew build` зелёный
- [x] Доки: README (таблица режимов, распределение бюджета,
  curl-пример), PRODUCT, PLAN, AGENTS.md (CODE MAP без ReasoningMode,
  строка «Тестов нет»)

## Волна 2 — ядро: типы, парсер, сторы

- [x] `api/types.ts`: зеркало `Models.kt` + `StreamEvents.kt`
  (ChatRequest/ChatResponse/AgentEvent + дискриминированный union),
  шапка «синхронизировать вручную»
- [x] `api/sse.ts`: `parseFrame` (чистая) + `streamChat` (POST +
  requestId + signal, буфер по `\n\n`, терминальные
  final|cancelled|error, fallback) — логика переносится из `script.js`
  дословно
- [x] `stores/`: settings (persist, `shturman.settings.v1`, version 1,
  seed из старого ключа), session, branches
- [x] `lib/`: format.ts, storage.ts
- [x] Vitest: parseFrame (кейсы с реальных фреймов бэка), format,
  сторы — без jsdom

## Волна 3 — read-only рендереры

- [x] `components/`: Dial, AnnunciatorGroup, Panel, Lamp, Clock,
  Overlay, StatusLine — a11y-контракт сохранён (скрытый
  `input[type=range]` поверх SVG)
- [x] `features/chat/`: Journal + Entry (marked + пост-обработка
  hljs, тема ре-токенизирована)
- [x] `features/judge/`, `features/trace/`, `features/fuel/`
  (SVG-графики топливомера)
- [x] CSS-модули компонентов из `style.css` (составные селекторы
  не разносить)
- [x] Смоук: визуальная сверка со старым приложением через dev-proxy

## Волна 4 — настройки и панели

- [x] `features/params/` (ModelSelect + слайдеры + тумблеры
  «мышление»/«судья»), `features/strategy/` (radiogroup + факты)
- [x] Привязка к `settingsStore` (persist), гвардии гидрации
- [x] Смоук: настройки персистятся, циферблаты обновляются, сброс
  работает

## Волна 5 — конвейер отправки

- [x] `features/send/`: SendBar + `useChatStream` (AbortController +
  requestId в refs; стоп = `/api/cancel` + abort; guard записей;
  AbortError → «Передача остановлена») — правило «стримы только из
  обработчиков событий»
- [x] Учёт токенов, статус-строка, директива, лампы печати
- [x] Смоук полного цикла: стрим, стоп, фолбэк без SSE

## Волна 6 — зеркало, сессии, ветки, ТО

- [x] `features/mirror/`: TwinPane с локальным состоянием по twinId,
  fallback на блокирующий запрос, метрики колонок
- [x] `features/sessions/` (панель + восстановление),
  `features/branches/`, `features/maintenance/` (режим ТО + самописец)
- [x] Смоук: зеркало (обе колонки, стоп одной), сессии/ветки,
  самописец, топливомер, судья

## Волна 7 — flip и документация

- [x] Ktor: роут «/» → dist `index.html` (no-cache); кэш для
  хэшированных `/static/assets/*`
- [x] Удаление `static/index.html`, `script.js`, `style.css`
- [x] Доки: README (запуск: npm run dev / gradlew run, архитектура
  фронта), AGENTS.md (CODE MAP, факты о Vitest), `frontend/AGENTS.md`
  (структура + смоук-чеклист сверены с кодом), `openspec/project.md`
  (платформа: React+Vite+TS; путь статики)
- [x] Финальный прогон: `./gradlew build` зелёный, смоук-чеклист
  полностью пройден; чек-лист сверен с `git log`

## Подводные камни (учитывать в каждой волне)

- Стримы не открывать в mount-эффектах (StrictMode → двойные
  соединения)
- Один AbortController на стрим; стоп ≠ unmount ≠ superseded request
- Зеркало: состояние по twinId, никогда общий «latest»
- marked: опция highlight удалена — пост-обработка hljs; тему
  ре-токенизировать
- Exec-задачи Gradle без inputs/outputs = stale dist
- `base: '/static/'` только в build

## Примечание: расхождения spec-эскиза и кода

Фактическая структура сверена с кодом по завершении волны 7; spec-дерево
(`specs/frontend-architecture/spec.md`, «Дерево исходников») — эскиз, не
правился. Расхождения:

- Волна 3: `components/Panel.tsx` из эскиза не реализован — секции
  панелей собраны через `components/Drawer.tsx` (бэкдроп, возврат фокуса)
  и каркас `App.tsx`; в `components/` также `dialMath.ts` и
  `overlayStack.ts`, которых эскиз не перечислял.
- Волна 4: эскизные `features/params/` (ParamsPanel, ModelSelect) и
  `features/strategy/` (StrategyRadios, Facts) превратились в
  `features/settings/` (SettingsPanel, models.ts, useModels.ts) и
  `features/strategy/` (Facts.tsx); переключатель стратегий —
  `AnnunciatorGroup variant="strategy"` внутри SettingsPanel, а не
  отдельный StrategyRadios.
- Волна 6: `features/sessions/SessionsOverlay.tsx` из эскиза — фактически
  `SessionsPanel.tsx`: панель сессий — третья колонка каркаса, не оверлей.
- Волна 7: приветственная запись журнала и подсказка шкалы «Топливо»
  переписаны под единственный агентный режим — их дословный порт
  перечислял режимы рассуждения, удалённые в волне 1.
