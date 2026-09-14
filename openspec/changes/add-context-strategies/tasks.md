# Tasks: add-context-strategies

Чек-лист реализации, зеркалит задачи плана 2–8 (задача 1 — bootstrap
этого change — и задача 9 — документация и чек-офф — сюда не входят).
Отмечаются по факту, после реализации и QA; spec при расхождении с
кодом правится под код.

CLI `openspec` в среде не установлен (факт, прецедент
`add-history-compression`) — структура change проверена вручную:
`proposal.md` + `tasks.md` + 5 spec-дельт на месте, формат
`### Requirement` / `#### Scenario` сохранён.

## Конфиг, enum и DTO (задача 2)

- [x] `ContextStrategy` enum + `fromId`/`resolve` (приоритет явный >
  legacy > конфиг) + поля `ChatRequest.contextStrategy`/`branchId`,
  `ChatResponse.contextStrategy`/`branchId` (`@EncodeDefault(ALWAYS)`) +
  конфиг `contextStrategy`/`branchCheckpointEvery` (без env) + тесты —
  дельта `specs/context-strategy-switcher/spec.md` (требования 1–4, 6)

## Хранилище (задача 3)

- [x] `Branch`/`Fact`, поля `Session.branches`/`activeBranchId`/
  `branchSeq`/`facts`, операции fork/activate/setFacts в InMemory и
  File, back-compat старых JSON (чтение да, миграция нет) + тесты —
  дельта `specs/branching/spec.md` (требования 1, 3, 6) +
  `specs/facts-memory/spec.md` (требование «Факты хранятся на сессии»)
  Пометка: `activateBranch` сворачивает прежнее активное состояние в новую
  запись ветки (следующий seq) — своп без потерь; корень «Основная»
  (branchId=null) — display-only (disabled-чип во фронте, активировать POST-ом нельзя).

## View по стратегии (задача 4)

- [x] `ContextPlanner` (view off/window/summary/facts/branching) +
  извлечение `sliceWindow` в свободную функцию (семантика гвардов без
  изменений) + проводка в `handleSingle`/`handleAgent` (капы поверх
  view, seed в агентном режиме) + тесты — дельта
  `specs/sliding-window/spec.md` (весь набор требований)
  Пометка (post-completion, изменение согласовано пользователем):
  семантика Окна стала деструктивной — после каждого успешного обмена
  хранилище физически урезается до последних windowSize сообщений
  (новая store-операция `applyWindow` + хук WINDOW в `finalizeResponse`);
  «Факты» остаются недеструктивным view (окно 3 — только view).

## Экстрактор фактов (задача 5)

- [x] `FactPrompts` + `FactExtractor` (интерфейс + LLM-реализация +
  seam-factory по образцу `summarizerFactory`) + маркер «Извлечение
  фактов» в `scripts/mock-llm.py` + тесты с фейком (без сети) — дельта
  `specs/facts-memory/spec.md` (требования «LLM возвращает полный
  обновлённый KV-JSON», «Mock-LLM детерминирован для фактов»)

## Интеграция в Routes (задача 6)

- [x] Разрешение стратегии в маршрутах, экстракция фактов после ответа
  (только при facts, `runCatching` → прежние факты), авто-форк каждые
  `branchCheckpointEvery` user-сообщений после ответа (кап 8),
  эндпоинты `GET /api/session/{id}/branches` и
  `POST /api/session/{id}/branches/activate`, SSE-событие `checkpoint`,
  echo-поля в `ChatResponse` + тесты — дельта
  `specs/context-strategy-switcher/spec.md` (требования 2–5) +
  `specs/branching/spec.md` (требования 1, 3, 5, 7) +
  `specs/facts-memory/spec.md` (требования «Экстракция…», «Сбой…»)
  Пометка: форк выполняется после добавления user+assistant обмена в
  историю (`finalizeResponse`), поэтому снимок близнеца включает 5-й обмен.

## Фронт: переключатель и зеркало (задача 7)

- [x] Радио-переключатель стратегий (5 табло, паттерн annunciators),
  панель фактов read-only, зеркало «стратегия на колонку» (выбор из
  Выкл/Окно/Сводка/Факты, labels, per-side payload без
  `compressionEnabled`, метрики по каждой колонке) — дельта
  `specs/context-strategy-switcher/spec.md` (UI-аспект) +
  `specs/facts-memory/spec.md` («UI — панель фактов read-only») +
  `specs/comparison-mode/spec.md` (требования 1–4)
  Пометка: во фронте однократная legacy-миграция — сохранённое
  `compressionEnabled=true` без `contextStrategy` → `contextStrategy='summary'`.

## Фронт: ветвление (задача 8)

- [x] Маркеры checkpoint в ленте (SSE-событие `checkpoint`, non-terminal
  в обоих SSE-обработчиках), плоский селектор веток, активация через
  эндпоинт, `branchId` в запросах — дельта `specs/branching/spec.md`
  (требования 3, 5)
  Пометка (post-completion, добавление согласовано пользователем): ручная
  развилка — эндпоинт `POST /api/session/{id}/branches` и клавиша
  «+ Развилка» в селекторе (снимок активного состояния без переключения,
  кап 8 → 409) — дельта `specs/branching/spec.md` (требование «Ручная
  развилка»).

## Синхронизация с кодом (задача 9)

Чек-лист сверен с `git log` (коммиты d2f8554..ed8f505): задачи 2–8
реализованы и закоммичены, отмечены по факту.

Покрываемость 5 дельт change `add-context-strategies` задачами 2–8:

- `specs/context-strategy-switcher/spec.md` — задача 2 (enum/DTO/конфиг,
  приоритет разрешения, echo, ключи конфига), задача 6 (разрешение в
  маршрутах, неизвестный id → 400, echo-поля, no-op в Team/P2P), задача 7
  (фронт-переключатель из 5 позиций).
- `specs/sliding-window/spec.md` — задача 4 (view `ContextPlanner`
  off/window/summary/facts/branching, `sliceWindow`, turn-boundary guard,
  капы поверх view, seed агентного режима).
- `specs/facts-memory/spec.md` — задача 3 (факты хранятся на сессии,
  персист), задача 5 (экстрактор, LLM KV-JSON, маркер mock-LLM), задача 6
  (экстракция после ответа только при facts, сбой → прежние факты),
  задача 7 (read-only панель фактов).
- `specs/branching/spec.md` — задача 3 (fork/activate в хранилище, кап 8,
  персист, back-compat), задача 6 (авто-форк после ответа, эндпоинты,
  SSE-событие `checkpoint`, `branchId` в запросе), задача 8 (маркеры
  checkpoint в ленте, плоский селектор, активация через эндпоинт).
- `specs/comparison-mode/spec.md` — задача 7 (стратегия на колонку, labels,
  per-side payload без `compressionEnabled`, метрики по каждой колонке),
  задача 8 (зеркало не ветвится — маркеров `checkpoint` в зеркале нет).

Расхождения реализация ↔ дельта-формулировка (пометки под задачами 3, 6, 7):
снимок форка включает 5-й обмен (форк после append); `activateBranch`
сворачивает активное состояние в новую ветку (корень «Основная» —
display-only); однократная legacy-миграция `compressionEnabled=true` →
`contextStrategy='summary'`.
