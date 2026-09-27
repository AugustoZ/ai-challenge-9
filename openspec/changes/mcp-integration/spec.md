# Спецификация: mcp-integration

> Дельта change `mcp-integration`. Новая capability.

## ADDED Requirements

### Requirement: MCP-серверы из конфига возвращают списки инструментов

При запуске приложение по секции `mcp` из `config.json` (через
`AppConfig.mcpServers`) для каждого `enabled`-сервера спавнит дочерний
stdio-процесс, подключается по MCP (Java SDK 2.0.1, `StdioClientTransport` +
`McpSyncClient.initialize()`, таймаут 10с), запрашивает `listTools()` и
хранит per-server статус, список дескрипторов (`name`, `description`,
`schemaJson`) и готовый к использованию клиент. Каждое успешное подключение
отдаёт список инструментов; каждый инструмент доступен как адаптер `Tool` с
именем `mcp__<server>__<tool>`. При ошибке подключения сервер помечается
`Degraded(error)` и старт приложения продолжается. `enabled:false` → статус
`Disabled`. Graceful shutdown закрывает клиента и принудительно завершает
процесс (no-zombie).

#### Scenario: Подключённый сервер отдаёт инструменты

- КОГДА в конфиге `mcp` сервер с `enabled:true`, валидный `command`, и
  процесс отвечает на `listTools()`
- ТОГДА статус сервера `Connected`, по каждому инструменту создан адаптер
  `Tool` с именем `mcp__<server>__<tool>` и схемой из `listTools()`
- И адаптеры видны в агрегированном `ToolRegistry` (через `augmentedWith`)

#### Scenario: Несуществующий command даёт Degraded без краша

- КОГДА в конфиге `mcp` сервер с `command`, который не запускается
- ТОГДА статус сервера `Degraded` с непустым `error`, приложение остаётся
  живым, другие серверы подключаются
- И старт приложения завершается успешно (G1: AgentLoop не тронут)

#### Scenario: Сервер с enabled:false не запускается

- КОГДА сервер в конфиге `mcp` имеет `enabled:false`
- ТОГДА процесс не спавнится, статус `Disabled`, инструментов сервера нет в
  реестре

### Requirement: Инвентарь инструментов через GET /api/tools

Читающий роут `GET /api/tools` возвращает полный инвентарь: подключённые
MCP-серверы со статусами и их инструментами, нативные mcp-tools и builtin.
Ответ `{servers:[{name,status,error?,tools:[{name,description,source}]}],
native:[...], builtin:[...]}`, где `source`: `builtin | native-tool | mcp`.
В ответ не попадают секреты и env.

#### Scenario: Инвентарь без MCP

- КОГДА секция `mcp` отсутствует (или все серверы `enabled:false`)
- ТОГДА `GET /api/tools` → 200 с `servers:[]`, а `native` содержит
  `reminder`, `summarize`, `search`, `saveToFile`
- И структура ответа полная, без полей env/секретов

#### Scenario: Инвентарь с Degraded-сервером

- КОГДА один из серверов в состоянии `Degraded`
- ТОГДА `GET /api/tools` → 200, соответствующий элемент `servers` имеет
  `status:"degraded"` и непустой `error`
- И инструменты деградировавшего сервера в список не входят

### Requirement: Нативные mcp-tools

Четыре нативных инструмента регистрируются в `ToolRegistry` через
`augmentedWith` (`NativeToolProvider`), не через `forConfig`: `reminder`
(add/list/done, JSON-файл `<dataDir>/reminders.json`), `summarize`
(вызов LLM через существующий шов `LLMClient.ask`), `search` (полнотекстовый
поиск в `<dataDir>/exports/`, регистронезависимо, кап 50 совпадений),
`saveToFile` (запись ТОЛЬКО в `<dataDir>/exports/`, запрет `..`/абсолютных
путей, кап 1 MB).

#### Scenario: saveToFile пишет в exports и отказывает на опасных путях

- КОГДА вызов `saveToFile {name:"notes.md", content:"..."}`
- ТОГДА файл создаётся в `<dataDir>/exports/notes.md`
- И вызов `saveToFile {name:"../etc/passwd"}` (или абсолютный путь)
  возвращает `ok=false`, файл вне exports не создан

#### Scenario: reminder цикл add → list → done

- КОГДА `reminder add "проверить прошивку"`, затем `reminder list`, затем
  `reminder done <id>`
- ТОГДА после `add` запись видна в `list`, после `done` — исчезает
- И `<dataDir>/reminders.json` остаётся валидным JSON

#### Scenario: summarize через LLM-шов, при недоступности — ok=false

- КОГДА вызов `summarize {text, instruction?}` и LLM-клиент доступен
- ТОГДА возвращена краткая сводка, сгенерированная через `LLMClient.ask`
- И при недоступном клиенте возвращён `ok=false` с текстом, без исключения

### Requirement: Пайплайн run/status и 409

Движок исполняет линейные конфиг-сценарии (`config/pipelines/*.json`):
`PipelineRunner` идёт по шагам, подставляет плейсхолдеры
`${steps.<id>.result}` (структурно при точном совпадении значения, строковой
интерполяцией при вхождении в большую строку), каждый шаг на
`Dispatchers.IO`, результат капится 20_000; ошибка шага → `state=failed` и
остановка. `PipelineService` хранит in-memory статус
`{name, runId, state, startedAt, finishedAt?, steps:[...]}`. `POST
/api/pipeline/run {name}` → 202 `{runId}` (асинхронный запуск), 404 при
неизвестном сценарии, 409 при повторном запуске активного сценария. `GET
/api/pipeline/status?name=` → 200 статус, 404 при неизвестном.

#### Scenario: Плейсхолдер передаёт результат шага дальше

- КОГДА сценарий из двух шагов, шаг 2 имеет аргумент
  `{"x":"${steps.s1.result}"}`
- ТОГДА шаг 2 получает как `x` результат шага 1
- И при ссычке на несуществующий шаг/тул пайплайн завершается `failed` с
  текстом

#### Scenario: Повторный запуск активного сценария даёт 409

- КОГДА сценарий уже запущен и `state=running`, приходит второй
  `POST /api/pipeline/run {name}`
- ТОГДА возвращён 409 с `{error}`
- И после завершения сценария повторный `POST /run` возвращает 202

#### Scenario: Ошибка шага останавливает пайплайн как failed

- КОГДА один из шагов возвращает `ok=false` (например, FloodWait у
  tg-тула)
- ТОГДА `state=failed` с `message` упавшего шага, последующие шаги не
  исполняются
- И `GET /api/pipeline/status` отражает `failed` и `error` шага

### Requirement: Скрытая wiki-страница «Частые вопросы»

wiki-сервер (FastMCP stdio, httpx к `WIKI_BASE_URL/graphql`,
`Authorization: Bearer WIKI_API_KEY`, таймаут 20с) предоставляет
`create_faq_page` (по умолчанию path `faq-admin/chastye-voprosy`,
`isPrivate:true`, `editor:"markdown"`, идемпотентен через
`pages.singleByPath`; при наличии страницы — `created:false` и тот же URL),
`read_faq_page` (`singleByPath` → `{exists,url,title,content,updatedAt}`,
отсутствие страницы — `exists:false`, не ошибка), `append_faq_page`
(при наличии — `pages.update` добавлением секции `## <date> <section_title>`;
при отсутствии — авто-create). Ошибки GraphQL
(`responseResult.status != SUCCESS`) → `ok=false` с errorCode/message.
Скрытость обеспечивается `isPrivate:true` + page-rule (deny для не-админов,
настраивается вручную в админке Wiki), админы (manage:system) байпасят.

#### Scenario: Создание скрытой страницы возвращает URL

- КОГДА `create_faq_page` вызван и страницы с path ещё нет
- ТОГДА создана страница с `isPrivate:true` и `editor:"markdown"`,
  возвращён `url: "<WIKI_BASE_URL>/ru/<path>"` и `created:true`
- И анонимный `GET` страницы НЕ видит её (проверка скрытости, задача 15)

#### Scenario: Повторное создание идемпотентно

- КОГДА `create_faq_page` вызван повторно для существующего path
- ТОГДА возвращён `created:false` и тот же URL, новая страница не создана
- И `append_faq_page` добавляет ровно одну секцию за прогон (без дублей)

#### Scenario: Append к существующей странице

- КОГДА страница существует и вызов `append_faq_page {content, section_title}`
- ТОГДА контент страницы увеличивается на секцию
  `## <date> <section_title>` + новый контент, возвращены url и новые размеры
- И при отсутствии страницы `append_faq_page` сначала авто-создаёт её

### Requirement: Сценарий faq-24h собирает вопросы за 24ч в Wiki

Сценарий `faq-24h` (в `config/pipelines/faq-24h.json`) из пяти шагов:
(1) `mcp__tg__read_recent {hours:24, max_messages:500}`; (2)
`mcp__tg__summary {hours:24}`; (3) `summarize` с плейсхолдерами, выбирающий
5–10 популярных вопросов за сутки с краткими ответами; (4) `saveToFile`
`faq-24h-<timestamp>.md`; (5) `mcp__wiki__append_faq_page
{section_title:"Сводка за 24ч"}`. tg-сервер: `read_recent`
(`iter_messages`, окно UTC, `max_messages` ≤ 500, медиа → `[медиа]`),
`search` (`get_messages(search=...)`), `summary` (extractive, БЕЗ LLM:
кандидат-вопросы по "?", топ-авторы, топ-темы по биграммам).

#### Scenario: Боевой прогон faq-24h публикует сводку

- КОГДА `POST /api/pipeline/run {"name":"faq-24h"}` с подключёнными
  tg/wiki-серверами и валидными кредитами
- ТОГДА `state=succeeded`, страница `faq-admin/chastye-voprosy` создана или
  дополнена секцией с текущей датой, возвращён URL
- И `GET <WIKI_BASE_URL>/ru/faq-admin/chastye-voprosy` анонимно → страница
  НЕ видна (hidden), markdown содержит секцию даты и ≥1 вопрос

#### Scenario: Пустое 24ч-окно завершается успешно

- КОГДА за последние 24 часа в TG-чате нет сообщений
- ТОГДА сценарий `state=succeeded`, wiki-шаг получает явное сообщение
  «0 вопросов за окно», пустое содержимое не публикуется
- И прогон не падает и не зависает (E2)
