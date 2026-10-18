# Изменение: MCP-интеграция (клиент + tg/wiki серверы + mcp-tools + пайплайн)

> Статус: draft. Capabilities: mcp-integration.
> Реализация идёт по `tasks.md` этого change; номера G/E/Q ниже — стабильные
> якоря, на которые ссылаются код и тесты задач 2–15.

## Зачем

Пользователь (сводно, 5 пунктов запроса):

1. Подключить несколько MCP-серверов; каждое подключение возвращает список
   своих инструментов.
   1.1. Создать mcp-tools: `reminder`, `summarize`, `search`, `saveToFile`.
2. Свой MCP-сервер вокруг TG-группы
   `https://t.me/tank_dargo_h6_chat`: читать чат, искать по чату, создавать
   сводку.
3. Свой MCP-сервер вокруг wiki `https://wiki.gwm-club.ru`: создать скрытую от
   всех кроме админов тему «Частые вопросы» в markdown и вернуть адрес;
   читать; дополнять.
4. Пайплайн, объединяющий mcp-tools и разные MCP-серверы для выполнения
   задачи.
5. **ВЫПОЛНИТЬ** задачу: собрать популярные вопросы + ответы за последние 24
   часа из TG-группы и сохранить в Wiki.

«Штурман» уже умеет вызывать инструменты: `ToolRegistry` агрегирует нативные
тулы, спеки уходят в LLM через `NativeToolTransport`, `AgentLoop` диспатчит
`toolCall`. Но внешних MCP-серверов нет: ни клиента, ни своих серверов, ни
механики детерминированного сценария. Change даёт MCP-платформу (клиент +
агрегация списков инструментов + два Python-сервера + нативные mcp-tools +
пайплайн-раннер) и выполняет на ней боевую задачу FAQ-24h с evidence.

## Ключевые решения

| # | Решение | Обоснование |
|---|---------|-------------|
| 1 | **Java MCP SDK `io.modelcontextprotocol.sdk:mcp:2.0.1`** (Reactor), а не Kotlin SDK 0.15.0 | Kotlin SDK 0.15.0 тянет Ktor 3.5.2, конфликтует с Ktor 2.3.12 приложения; апгрейд Ktor запрещён (G1). Java SDK 2.0.1 совместим; из Kotlin вызывается через `runBlocking`/`Dispatchers.IO` |
| 2 | **Транспорт stdio**: каждый MCP-сервер — дочерний процесс, управляемый приложением (`StdioClientTransport` + `ServerParameters`) | Подтверждено интервью; процесс живёт под контролем «Штурмана», lifecycle и degraded-статусы управляемы, без отдельных сетевых портов и оркестрации |
| 3 | **Нейминг `mcp__<server>__<tool>`** для инструментов MCP-серверов | Двухчастный префикс устраняет коллизии имён с нативными/builtin-тулами; дедупликация через суффикс `_2` + warning при совпадении (задача 6) |
| 4 | **mcp-tools = нативные Kotlin-инструменты** в `ToolRegistry` (НЕ embedded MCP-сервер) | Подтверждено интервью; `reminder`/`summarize`/`search`/`saveToFile` регистрируются через `augmentedWith` (`NativeToolProvider`), без лишних процессов |
| 5 | **Пайплайн — детерминированные конфиг-сценарии**, а не LLM-оркестрация | Подтверждено интервью: линейные шаги с плейсхолдерами `${steps.N.result}`, свой кап результата 20_000, 1 активный запуск (409); LLM — только как тул `summarize` внутри шага |
| 6 | **Секреты только в `config.json`** (gitignored); прокидываются env-переменными в Python-процессы при спавне | В репо НЕТ конвенции `.env`; секреты не попадают в код/логи/evidence; env детей не логируется (G3) |
| 7 | **Новые HTTP-роуты** `GET /api/tools` (инвентарь + статусы) и `POST /api/pipeline/run`, `GET /api/pipeline/status` | Эндпоинта `/api/tools` нет; FSM задач always-on и живёт в чат-ходах, поэтому пайплайн-раннер — новые read/run-эндпоинты (обоснованно, не дублирование чата) |
| 8 | **Коммитов нет** (конвенция репозитория: рабочее дерево остаётся незафиксированным) | Закреплено в proposal прошлых изменений; изменения верифицируются тестами и QA-сценариями, не коммитами |
| 9 | **Два Python MCP-сервера** (`mcp-servers/tg_server.py`, `wiki_server.py`), FastMCP stdio, единый `common.py` | Подтверждено интервью (Python, mcp SDK + Telethon + httpx); `common.py` — единственная точка `logging` в stderr, stdout чист by design |

## Edge cases

- **FloodWaitError (Telethon).** Считается успешным ответом API: тул
  возвращает `ok=false` с текстом `FloodWait: подождите Ns` БЕЗ retry-лупа
  (E1). Пайплайн фиксирует failed-шаг с этим текстом и останавливается.
- **Пустое 24ч-окно.** `read_recent` вернул 0 сообщений: сценарий
  завершается успешно, wiki-шаг получает явное сообщение «0 вопросов за окно»
  (E2), страница не получает пустого содержимого.
- **Страница уже существует.** `create_faq_page` идемпотентен через
  `pages.singleByPath`: при наличии страницы — `created:false` и возврат
  того же URL (update-ветка через `append_faq_page`), без дубля (E3).
- **stdout-дисциплина Python-серверов.** stdout = только JSON-RPC; всё
  логирование (включая Telethon) уходит в stderr (E4). Smoke проверяет, что
  каждая строка stdout — валидный JSON.
- **Degraded-сервер.** Падение/ошибка подключения MCP-сервера не крашнит
  приложение: статус `Degraded(error)`, старт продолжается, `/api/tools`
  показывает `status:"degraded"` + error (E5). Повторный запуск пайплайна при
  активном — 409, без гонок.

## Ограничения (G/E/Q)

> Номера — стабильные якоря: код и тесты задач 2–15 ссылаются на них из
> change-документов.

### Глобальные (G)

- **G1** Не менять `AgentLoop`, FSM (`TaskStateMachine`/`TaskStateHeuristic`),
  Sandbox, `ToolTransport`; не делать апгрейд Ktor (остаётся 2.3.12).
- **G2** Существующие 644+ тестов (факт на baseline — 667) остаются зелёными
  и **немодифицированными**; новые hermetic-тесты не зависят от Python
  (только `assumeTrue(python3 available)` в отдельных integration-тестах).
- **G3** Секреты только в `config.json` (gitignored); не в коде, логах,
  evidence, плане; Bearer-ключ маскируется; env дочерних процессов не
  логируется.
- **G4** stdout Python-серверов = только JSON-RPC; никакого `print()`/логов в
  stdout; `.block()` только на `Dispatchers.IO`.

### Краевые (E)

- **E1** FloodWaitError → `ok=false` «FloodWait: подождите Ns», без retry.
- **E2** Пустое 24ч-окно → succeeded с сообщением «0 вопросов за окно».
- **E3** Страница существует → `create_faq_page` идемпотентен
  (`created:false`), `append_faq_page` делает update-ветку.
- **E4** stdout Python-серверов — валидный JSON построчно; логи в stderr.
- **E5** Degraded-сервер: приложение живо, статус и error видны в
  `/api/tools`; повторный `POST /api/pipeline/run` активного сценария → 409.

### Качество (Q)

- **Q1** `./gradlew test` → BUILD SUCCESSFUL, 0 failures; база 667 зелёная
  немодифицированная; новые тесты (тулы, менеджер, роуты, runner) зелёные.
- **Q2** Фронтенд: цвета только из tokens.css, без `as any`/`@ts-ignore`, без
  console.log, без новых npm-зависимостей; `npm run verify` зелёный.
- **Q3** Жёсткие гарантии: MCP-тулы реально попадают в спеки LLM;
  плейсхолдеры структурно подставляются; 409 воспроизводится тестом;
  no-zombie (после stop процессов детей не остаётся).
- **Q4** Description у MCP-тулов короткие (< 200 симв.), идут в промпт LLM;
  кап результата пайплайн-шага 20_000 (не кап 8000 AgentLoop).

## Что меняется (сводка)

- `build.gradle.kts`: `implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")`.
- `model/Models.kt`: `FileConfig.mcp: List<FileMcpServerConfig>?` +
  `FileMcpServerConfig(name, command, args, env, enabled)`.
- `config/AppConfig.kt`: `AppConfig.mcpServers`, перенос в `applyFile()`,
  валидация (уникальные имена, непустой command, fail-fast); env вне toString.
- `config.example.json` / `config.dev.json`: секция `mcp` (example с
  плейсхолдерами, dev disabled).
- `mcp/McpServerManager.kt`: spawn/подключение/lifecycle/degraded-статусы,
  graceful close, no-zombie.
- `mcp/McpToolAdapter.kt`: адаптер `Tool`, неймспейс `mcp__<server>__<tool>`,
  кап 20_000.
- `tools/ReminderTool.kt`, `SummarizeTool.kt`, `SearchTool.kt`,
  `SaveToFileTool.kt` + `NativeToolProvider` (через `augmentedWith`).
- `pipeline/PipelineConfig.kt`, `PipelineRunner.kt`, `PipelineService.kt`:
  движок, плейсхолдеры, in-memory статус, 409.
- `routes/Routes.kt`: `GET /api/tools`, `POST /api/pipeline/run`,
  `GET /api/pipeline/status`.
- `config/pipelines/faq-24h.json` (+ README-комментарий каталога).
- `mcp-servers/`: `requirements.txt`, `common.py`, `tg_server.py`,
  `wiki_server.py`, `generate_session.py`, `README.md`, `smoke.sh`.
- Фронтенд: `api/client.ts` (`runPipeline`, `getPipelineStatus`),
  `api/types.ts`, секция «Обслуживание» в `SettingsPanel.tsx` (кнопка +
  статус FAQ-пайплайна).
- `.gitignore`: `mcp-servers/.venv/`.
- OpenSpec: этот change (сам документ); код и тесты идут по `tasks.md`.

## Вне скоупа

- Отправка сообщений/медиа в TG, участники, другие чаты/платформы
  (Max/VK) (G-список план-гвардов).
- Тулы edit/delete/search в wiki-сервере сверх трёх (create/read/append);
  страницы вне `faq-admin/*`.
- Ветвления/циклы/ретраи/параллель в пайплайн-движке; БД для статусов;
  enable/disable серверов по HTTP; экран конфигурации инструментов в UI.
- LLM-оркестрация сценариев; SSE/websocket в пайплайне; LLM внутри движка
  (LLM — только как тул `summarize`).
- Коммиты (конвенция репо: дерево незафиксированное).
- Модификация существующих 644+ тестов (G2); апгрейд Ktor, изменения
  AgentLoop/FSM/Sandbox (G1).
- Архивирование других OpenSpec changes, правка других specs.
