# Tasks: add-history-compression

Чек-лист реализации, зеркалит задачи плана (2-8). Отмечаются по факту,
после реализации и QA; spec при расхождении с кодом правится под код.

## Конфиг и DTO (задача 2)

- [x] Поля компрессии в конфиге и DTO: `FileConfig`/`AppConfig`
  (compressionEnabled/WindowSize/MergeEvery/SummaryMaxTokens, дефолты
  5/5/400, валидация при старте; **только конфиг-файл, env-оверрайдов нет** —
  решение T2, т.к. `System.getenv` в JVM не подменяется в тестах),
  `ChatRequest.compressionEnabled`, `ChatResponse.summaryTokens` +
  `summaryApplied` (`@EncodeDefault(ALWAYS)`); `config.example.json` синхронно;
  тесты AppConfigTest и сериализации

## Сессия (задача 3)

- [x] `Session.summary: String? = null` (default для back-compat) +
  атомарная операция хранилища `applyCompressionResult` (InMemory и
  File); delete/clear сбрасывают summary; тесты: round-trip, старый формат
  JSON, атомарность (TDD)

## Компрессор (задача 4)

- [x] `HistoryCompressor` (окно, порог mergeEvery, merge на все накопившиеся,
  turn-boundary guard, planView) + `SummaryPrompts` + интерфейс
  `HistorySummarizer`; тесты с `FakeSummarizer`: порог, однократность merge,
  tool-пары на границе, oversized input/output (TDD, ноль сети)

## QA-инфраструктура (задача 5)

- [x] `scripts/mock-llm.py` (OpenAI-совместимый детерминированный mock:
  stream/non-stream, usage = ceil(chars/2.5), маркер суммаризационного
  запроса) + `config.dev.json`; сквозной прогон Штурман → mock

## Интеграция в одиночные режимы (задача 6)

- [x] Адаптер суммаризатора (паттерн `runJudge`) + проводка в `handleSingle`
  (синхронный merge, consume, stage-2 trim после planView, метрики
  summaryTokens/summaryApplied, деградация при сбое суммаризатора, приоритет
  запроса > конфига, guard Team/P2P); тесты Routes-уровня (TDD)

## Фронтенд (задача 7)

- [x] Тумблер «Сжатие истории» (`#compressionToggle`) и зеркальный режим
  (`#mirrorToggle`): twin-сессии, два параллельных /api/chat/stream,
  `.mirror-grid` side-by-side, токены по каждой стороне + «+сводка: N»,
  «н/д» при null usage, изоляция ошибок сторон; настройки в
  ai-assistant.settings.v1

## Агентный режим (задача 8)

- [x] Проводка компрессии в `handleAgent`: синхронный merge до цикла, seed =
  planView + новый user msg, stage-2 trim, tool-пары в seed не рвутся,
  AgentLoop не модифицируется; KDoc про флаттенинг opencode-протокола;
  тесты agent-пути (TDD)

## Синхронизация с кодом (задача 9)

Чек-лист сверен с `git log lesson-8..lesson-9` (8 коммитов, 173 теста / 0
failures): все 7 пунктов реализованы и закоммичены, отмечены по факту.
Спецификации сведены с реализацией (spec следует за кодом):

- `specs/history-compression/spec.md`:
  - требование «Порог mergeEvery» уточнено: батч суммаризации **предварительно
    обрезается** до `HistoryCompressor.SUMMARY_BATCH_TOKEN_BUDGET` (12 000
    оценочных токенов) по user-границам, поэтому переданный суммаризатору батч
    может быть меньше полного внеоконного набора; `removeCount` при этом равен
    всему накопившемуся. Добавлено требование «Оversized summary» (один retry
    «сожми жёстче» через `previous`, затем hardCut по границе предложений).
  - требование «Окно … turn-boundary guard»: срабатывание гварда привязано к
    `role="tool"` на границе среза (осиротевший tool), как в коде
    `sliceWindow`; `assistant(tool_calls)` на границе безопасен, пока его
    `tool`-ответ внутри окна.
- `specs/comparison-mode/spec.md`: имена сторон приведены к фактическим ключам
  `full`/`compressed` (`data-side`, `MIRROR_SIDES`), указано, что при недоступном
  стриминге twin уходит на блокирующий `POST /api/chat`.

CLI `openspec` в среде не установлен (факт T1) — структура change проверена
вручную: `proposal.md` + `tasks.md` + `specs/history-compression/spec.md` +
`specs/comparison-mode/spec.md` на месте, формат `### Requirement` /
`#### Scenario` сохранён.
