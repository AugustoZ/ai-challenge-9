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

- [ ] `ContextStrategy` enum + `fromId`/`resolve` (приоритет явный >
  legacy > конфиг) + поля `ChatRequest.contextStrategy`/`branchId`,
  `ChatResponse.contextStrategy`/`branchId` (`@EncodeDefault(ALWAYS)`) +
  конфиг `contextStrategy`/`branchCheckpointEvery` (без env) + тесты —
  дельта `specs/context-strategy-switcher/spec.md` (требования 1–4, 6)

## Хранилище (задача 3)

- [ ] `Branch`/`Fact`, поля `Session.branches`/`activeBranchId`/
  `branchSeq`/`facts`, операции fork/activate/setFacts в InMemory и
  File, back-compat старых JSON (чтение да, миграция нет) + тесты —
  дельта `specs/branching/spec.md` (требования 1, 3, 6) +
  `specs/facts-memory/spec.md` (требование «Факты хранятся на сессии»)

## View по стратегии (задача 4)

- [ ] `ContextPlanner` (view off/window/summary/facts/branching) +
  извлечение `sliceWindow` в свободную функцию (семантика гвардов без
  изменений) + проводка в `handleSingle`/`handleAgent` (капы поверх
  view, seed в агентном режиме) + тесты — дельта
  `specs/sliding-window/spec.md` (весь набор требований)

## Экстрактор фактов (задача 5)

- [ ] `FactPrompts` + `FactExtractor` (интерфейс + LLM-реализация +
  seam-factory по образцу `summarizerFactory`) + маркер «Извлечение
  фактов» в `scripts/mock-llm.py` + тесты с фейком (без сети) — дельта
  `specs/facts-memory/spec.md` (требования «LLM возвращает полный
  обновлённый KV-JSON», «Mock-LLM детерминирован для фактов»)

## Интеграция в Routes (задача 6)

- [ ] Разрешение стратегии в маршрутах, экстракция фактов после ответа
  (только при facts, `runCatching` → прежние факты), авто-форк каждые
  `branchCheckpointEvery` user-сообщений после ответа (кап 8),
  эндпоинты `GET /api/session/{id}/branches` и
  `POST /api/session/{id}/branches/activate`, SSE-событие `checkpoint`,
  echo-поля в `ChatResponse` + тесты — дельта
  `specs/context-strategy-switcher/spec.md` (требования 2–5) +
  `specs/branching/spec.md` (требования 1, 3, 5, 7) +
  `specs/facts-memory/spec.md` (требования «Экстракция…», «Сбой…»)

## Фронт: переключатель и зеркало (задача 7)

- [ ] Радио-переключатель стратегий (5 табло, паттерн annunciators),
  панель фактов read-only, зеркало «стратегия на колонку» (выбор из
  Выкл/Окно/Сводка/Факты, labels, per-side payload без
  `compressionEnabled`, метрики по каждой колонке) — дельта
  `specs/context-strategy-switcher/spec.md` (UI-аспект) +
  `specs/facts-memory/spec.md` («UI — панель фактов read-only») +
  `specs/comparison-mode/spec.md` (требования 1–4)

## Фронт: ветвление (задача 8)

- [ ] Маркеры checkpoint в ленте (SSE-событие `checkpoint`, non-terminal
  в обоих SSE-обработчиках), плоский селектор веток, активация через
  эндпоинт, `branchId` в запросах — дельта `specs/branching/spec.md`
  (требования 3, 5)
