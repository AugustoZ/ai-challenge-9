# Tasks: mcp-integration

Чек-лист реализации, зеркалит задачи 1–15 плана `mcp-integration-shturman`.
Отмечаются по факту, после реализации и QA; spec при расхождении с кодом
правится под код. Номера G/E/Q: из proposal.md этого change.

## 1. Baseline + OpenSpec change (задача 1)

- [x] 1.1 Baseline: `./gradlew test` → BUILD SUCCESSFUL, 0 failures; число
  тестов (667, ожидаемо 644+) и результат записаны в
  `.omo/evidence/task-1-baseline.txt`; состояние `git status --short`
  зафиксировано (конкурентные правки задач 2/3 не трогать)
- [x] 1.2 Создан этот change `openspec/changes/mcp-integration/`
  (proposal + spec + tasks, русский, КОГДА/ТОГДА)
- [x] 1.3 Файлы верифицированы: 3 файла, таблица Ключевых решений ≥ 7 строк,
  spec ≥ 6 Requirement со Scenario КОГДА/ТОГДА; evidence
  `task-1-openspec-check.txt`

## 2. Python-воркспейс mcp-servers/ (задача 2)

- [ ] 2.1 `mcp-servers/requirements.txt` (`mcp>=1.0`, `telethon>=1.34`,
  `httpx>=0.27`) и `README.md` (создание venv, таблица env: TG_API_ID,
  TG_API_HASH, TG_SESSION, TG_CHAT, WIKI_BASE_URL, WIKI_API_KEY;
  предупреждение о stdout-дисциплине)
- [ ] 2.2 `mcp-servers/common.py`: `logging.basicConfig(stream=sys.stderr)`
  + helper `build_server(name)` на базе FastMCP (stdio) — stdout чист by
  design (G4)
- [ ] 2.3 `mcp-servers/generate_session.py` (Telethon StringSession, печать
  строки сессии с пометкой «скопируй в config.json, не коммить»)
- [ ] 2.4 `.gitignore` += `mcp-servers/.venv/`; venv ставится, `import mcp,
  telethon, httpx` ок; stdout не загрязняется

## 3. Конфиг-секция mcp (задача 3)

- [ ] 3.1 `model/Models.kt`: `FileConfig.mcp: List<FileMcpServerConfig>?` +
  `FileMcpServerConfig(name, command, args, env, enabled)`
- [ ] 3.2 `config/AppConfig.kt`: `AppConfig.mcpServers`, перенос в
  `applyFile()` (`fc.mcp ?: mcpServers`), валидация (уникальные имена,
  непустой command, fail-fast); env вне toString (G3)
- [ ] 3.3 `config.example.json` / `config.dev.json`: секция `mcp` (example с
  плейсхолдерами, dev disabled)
- [ ] 3.4 Тесты `AppConfigTest`: секция отсутствует → старое поведение;
  `enabled=false` сохраняется; дубликат/пустой command → validate() падает;
  env не в toString

## 4. Нативные mcp-tools (задача 4)

- [ ] 4.1 `tools/ReminderTool.kt` (`reminder`: add/list/done; JSON
  `<dataDir>/reminders.json` под Mutex; due ISO-8601, без планировщика)
- [ ] 4.2 `tools/SummarizeTool.kt` (`summarize`: шов `LLMClient.ask`; при
  недоступности ok=false)
- [ ] 4.3 `tools/SearchTool.kt` (`search`: полнотекст по
  `<dataDir>/exports/`, регистронезависимо, кап 50)
- [ ] 4.4 `tools/SaveToFileTool.kt` (`saveToFile`: только
  `<dataDir>/exports/`, запрет `..`/абсолютных, кап 1 MB)
- [ ] 4.5 Регистрация через `augmentedWith` (`NativeToolProvider`), НЕ
  forConfig; unit-тесты на каждый тул зелёные

## 5. Fake stdio echo-сервер + SPIKE Java SDK 2.0.1 (задача 5)

- [ ] 5.1 `src/test/resources/mcp/fake_echo_server.py`: FastMCP stdio, тулы
  `echo {text}` → `ECHO:<text>` и `fail {}` → исключение; логи в stderr
- [ ] 5.2 SPIKE `McpSdkSpikeTest`: `ServerParameters.builder(...)` +
  `StdioClientTransport` + `McpSyncClient.initialize()/listTools()/callTool()`
  (assert 2 тула, ECHO, ожидаемая ошибка fail, таймаут readable)
- [ ] 5.3 `task-5-spike-notes.md`: фактические сигнатуры 2.0.1 (отличия от
  1.x) — справочник для задач 6/13
- [ ] 5.4 `McpEchoIntegrationTest` (assumeTrue(python3/venv)): listTools,
  echo, fail — 3 кейса зелёные

## 6. McpServerManager + Tool-адаптеры + lifecycle (задача 6)

- [ ] 6.1 `mcp/McpServerManager.kt`: start() спавнит stdio (Dispatchers.IO),
  initialize() (таймаут 10с), listTools(); статусы Connected/Degraded/Disabled
- [ ] 6.2 `mcp/McpToolAdapter.kt`: `Tool` с именем `mcp__<server>__<tool>`,
  execute на Dispatchers.IO, конвертация в ToolResult, кап текста 20_000
- [ ] 6.3 `mcpRegistry()`: все адаптеры; дедупликация имён (коллизия →
  `_2` + warning)
- [ ] 6.4 Graceful shutdown: close + destroyForcibly (no-zombie)
- [ ] 6.5 Тесты (fake echo, assumeTrue): echo → Connected; enabled=false →
  Disabled; bad command → Degraded без краша; имена `mcp__echo__echo`;
  no-zombie после stop (G1, G3, Q3)

## 7. tg_server.py (задача 7)

- [ ] 7.1 `mcp-servers/tg_server.py`: FastMCP (common.py); Telethon
  `TelegramClient(StringSession, api_id, api_hash)`, entity = TG_CHAT
- [ ] 7.2 Тул `read_recent {hours=24, max_messages=500}`: iter_messages
  (UTC, reverse, limit), формат `[дата] <автор>: <текст>`, медиа → `[медиа]`
- [ ] 7.3 Тул `search {query, hours=168}`: get_messages(search=...), тот же
  формат + счётчик
- [ ] 7.4 Тул `summary {hours=24}`: extractive БЕЗ LLM (вопросы по "?",
  топ-авторы, топ-темы по биграммам), markdown
- [ ] 7.5 FloodWaitError → ok=false «FloodWait: подождите Ns» БЕЗ retry
  (E1); дедуп service-сообщений; логи Telethon → stderr; description < 120
  симв.

## 8. wiki_server.py (задача 8)

- [ ] 8.1 `mcp-servers/wiki_server.py`: FastMCP; httpx к
  `WIKI_BASE_URL/graphql`, Bearer, таймаут 20с
- [ ] 8.2 Тул `create_faq_page`: singleByPath → create (isPrivate:true,
  editor:markdown, locale:ru) / идемпотентный created:false; возврат
  url/path/created/pageId (E3)
- [ ] 8.3 Тул `read_faq_page`: singleByPath → {exists,url,title,content,
  updatedAt}; отсутствие → exists:false (не ошибка)
- [ ] 8.4 Тул `append_faq_page`: present → pages.update (+секция
  `## <date> <section_title>`); absent → авто-create
- [ ] 8.5 Ошибки GraphQL → ok=false errorCode/message; нормализация path
  (trim/lowercase/латиница, отказ на `..`); логи → stderr

## 9. GET /api/tools (задача 9)

- [ ] 9.1 `routes/Routes.kt`: `GET /api/tools` →
  {servers:[{name,status,error?,tools:[{name,description,source}]}],
  native:[...], builtin:[...]}; source: builtin|native-tool|mcp
- [ ] 9.2 Источники: base registry (forConfig) + NativeToolProvider +
  McpServerManager
- [ ] 9.3 Тесты (withApp/testHttpClient): 200 структура; пустой mcp →
  servers=[] + native; Degraded → status:"degraded" + error; без secrets/env

## 10. Pipeline engine + /api/pipeline/run|status (задача 10)

- [ ] 10.1 `pipeline/PipelineConfig.kt`: PipelineScenario, PipelineStep
- [ ] 10.2 `pipeline/PipelineRunner.kt`: линейные шаги, плейсхолдеры
  `${steps.<id>.result}` (структурно/строка), кап 20_000, ошибка шага →
  failed + остановка; шаги на Dispatchers.IO
- [ ] 10.3 `pipeline/PipelineService.kt`: реестр сценариев
  (config/pipelines/*.json), in-memory статус, 1 активный запуск (409)
- [ ] 10.4 Роуты: POST /run → 202/404/409; GET /status → 200/404
- [ ] 10.5 Тесты на stub-тулах: плейсхолдер, unknown ref → failed, упавший
  шаг, 409, роут-тесты 202/404/409/200 (Q3)

## 11. Frontend: кнопка+статус FAQ-пайплайна (задача 11)

- [ ] 11.1 `frontend/src/api/client.ts`: runPipeline, getPipelineStatus;
  типы в `api/types.ts` (PipelineStatus)
- [ ] 11.2 `SettingsPanel.tsx` (блок «Обслуживание», ТОЛЬКО tokens.css):
  кнопка «Запустить FAQ (24ч)» + зона статуса, автоопрос 3с пока running
- [ ] 11.3 vitest: форматирование статуса; `npm run verify` зелёный (Q2)

## 12. Сценарий faq-24h.json (задача 12)

- [ ] 12.1 `config/pipelines/faq-24h.json`: 5 шагов (read_recent → summary →
  summarize → saveToFile → append_faq_page) + README-комментарий каталога
- [ ] 12.2 Тест: сценарий парсится; runner-тест на stub-тулах (5 замоканых)
  зелёный; пустое окно → succeeded с «0 вопросов за окно» (E2)

## 13. Kotlin-интеграция E2E (задача 13)

- [ ] 13.1 `config.dev.json` + test-конфиг: секция mcp с echo-сервером
  (enabled=true)
- [ ] 13.2 E2E (withApp): GET /api/tools → echo Connected + mcp__echo__echo;
  MCP-спеки реально в LLM-запросе (mock-llm capture / FakeLLMClient)
- [ ] 13.3 Агентный ход: FakeLLMClient toolCall(mcp__echo__echo) →
  ToolResult «ECHO:ping», role="tool" (G1, Q3)

## 14. Python-серверы smoke (задача 14)

- [ ] 14.1 `mcp-servers/smoke.sh`: оба сервера с env-заглушками; tools/list
  ожидаемые наборы (tg: 3, wiki: 3); каждая строка stdout — валидный JSON
  (E4); вызовы → читаемые ошибки, не зависания (таймаут 30с)
- [ ] 14.2 Проверка stderr (логи в stderr); kill → pgrep пуст (no-zombie);
  отчёт сервер × тул × статус в evidence

## 15. Боевой запуск faq-24h + evidence (задача 15)

- [ ] 15.1 PRECONDITION (участие пользователя): config.json с TG
  (api_id/hash/session, chat) и WIKI (API-ключ fullAccess, base_url);
  page-rule deny для не-админов на `faq-admin`; preflight curl singleByPath
- [ ] 15.2 `POST /api/pipeline/run {"name":"faq-24h"}` → poll status →
  succeeded; страница создана/дополнена, URL возвращён
- [ ] 15.3 Верификация: singleByPath → id≠null, updatedAt свежий; анонимный
  GET → скрыта; markdown содержит секцию даты и ≥1 вопрос
- [ ] 15.4 Повторный прогон (идемпотентность): одна новая секция, без дублей
- [ ] 15.5 Evidence: task-15-run.json, wiki-graphql.json, wiki-hidden.txt,
  export.md
