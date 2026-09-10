# Tasks: add-history-compression

Чек-лист реализации, зеркалит задачи плана (2-8). Отмечаются по факту,
после реализации и QA; spec при расхождении с кодом правится под код.

## Конфиг и DTO (задача 2)

- [ ] Поля компрессии в конфиге и DTO: `FileConfig`/`AppConfig`
  (compressionEnabled/WindowSize/MergeEvery/SummaryMaxTokens, дефолты
  5/5/400, валидация, приоритет env > file > defaults),
  `ChatRequest.compressionEnabled`, `ChatResponse.summaryTokens` +
  `summaryApplied`; `config.json` и `config.example.json` синхронно; тесты
  AppConfigTest и сериализации

## Сессия (задача 3)

- [ ] `Session.summary: String? = null` (default для back-compat) +
  атомарная операция хранилища `applyCompressionResult` (InMemory и
  File); delete/clear сбрасывают summary; тесты: round-trip, старый формат
  JSON, атомарность (TDD)

## Компрессор (задача 4)

- [ ] `HistoryCompressor` (окно, порог mergeEvery, merge на все накопившиеся,
  turn-boundary guard, planView) + `SummaryPrompts` + интерфейс
  `HistorySummarizer`; тесты с `FakeSummarizer`: порог, однократность merge,
  tool-пары на границе, oversized input/output (TDD, ноль сети)

## QA-инфраструктура (задача 5)

- [ ] `scripts/mock-llm.py` (OpenAI-совместимый детерминированный mock:
  stream/non-stream, usage = ceil(chars/2.5), маркер суммаризационного
  запроса) + `config.dev.json`; сквозной прогон Штурман → mock

## Интеграция в одиночные режимы (задача 6)

- [ ] Адаптер суммаризатора (паттерн `runJudge`) + проводка в `handleSingle`
  (синхронный merge, consume, stage-2 trim после planView, метрики
  summaryTokens/summaryApplied, деградация при сбое суммаризатора, приоритет
  запроса > конфига, guard Team/P2P); тесты Routes-уровня (TDD)

## Фронтенд (задача 7)

- [ ] Тумблер «Сжатие истории» (`#compressionToggle`) и зеркальный режим
  (`#mirrorToggle`): twin-сессии, два параллельных /api/chat/stream,
  `.mirror-grid` side-by-side, токены по каждой стороне + «+сводка: N»,
  «н/д» при null usage, изоляция ошибок сторон; настройки в
  ai-assistant.settings.v1

## Агентный режим (задача 8)

- [ ] Проводка компрессии в `handleAgent`: синхронный merge до цикла, seed =
  planView + новый user msg, stage-2 trim, tool-пары в seed не рвутся,
  AgentLoop не модифицируется; KDoc про флаттенинг opencode-протокола;
  тесты agent-пути (TDD)
