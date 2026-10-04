# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-15
**Commit:** 41dd093
**Branch:** lesson_11

## OVERVIEW
«Штурман» — веб-стенд рассуждений для AI-чата: Kotlin/Ktor бэкенд (прокси к OpenAI-совместимым API) + фронтенд в стилистике ночного кокпита «Приборная доска». Фронтенд — React 18 + Vite + TypeScript в `frontend/`; прод-сборка (`frontend/dist`) отдаётся Ktor с «/» (flip волны 7 выполнен, vanilla-версия удалена).

## STRUCTURE
```
ai-challenge-9/
├── DESIGN.md                      # дизайн-контракт: палитра, шрифты, приращения, Provenance
├── PRODUCT.md                     # продуктовое описание
├── README.md                      # запуск (бэкенд и фронтенд)
├── frontend/                      # весь фронтенд (см. frontend/AGENTS.md)
├── config.example.json            # образец конфига; config.json (gitignored) — реальные ключи
├── Dockerfile / docker-compose.yml
└── src/main/
    ├── kotlin/com/example/aiweb/
    │   ├── Application.kt         # старт Ktor, конфиг, миграция «Шумахер» (до routing), «/» → index.html, кэш статики
    │   ├── agent/AgentLoop.kt     # агентный цикл: шаги, вызовы инструментов, финал
    │   ├── auth/                  # UserAccount/UserStore, TokenManager, LoginGuard (кулдаун), ShturmanMigration
    │   ├── prefs/                 # UserPreferences (7 полей), PreferenceLimits, PreferencesStore, renderPromptBlock
    │   ├── memory/                # память агента: WorkingMemoryStore, LongTermMemoryStore (+PerUserLongTermMemory), MemoryRetriever
    │   ├── client/                # AIClient (openai/opencode), JudgePrompts, FactPrompts, ReasoningPrompts (budgetNote/withBudget), ExchangeLog
    │   ├── config/AppConfig.kt    # разбор config.json: провайдеры (ProviderConfig), allowedModels, dataDir
    │   ├── model/                 # Models.kt (вкл. auth/preferences DTO), AgentEvents.kt, StreamEvents.kt, ContextStrategy.kt
    │   ├── routes/Routes.kt       # 28 API-роутов: auth (login/users), /api/preferences, /api/chat(+ /stream), /api/log, /api/sessions, ветки, memory
    │   ├── session/               # SessionStore/FileSessionStore (Session.userId), ContextPlanner, HistoryCompressor
    │   └── tools/                 # ToolRegistry: калькулятор, HTTP GET, дата/время, песочница
    └── resources/static/          # только favicon.svg (остальная статика — сборка Vite)
```

## WHERE TO LOOK
| Задача | Location | Notes |
|---|---|---|
| Новый API-эндпоинт | routes/Routes.kt | 28 роутов; новый защищённый роут автоматически под intercept (токен), открытый — добавить в whitelist |
| Авторизация / пользователи | auth/ (UserStore, TokenManager, LoginGuard) + routes (auth-роуты, intercept) | ПИН SHA-256+salt, токен в памяти, кулдаун 3→30с |
| Миграция «Шумахер» | auth/ShturmanMigration.kt + Application.kt | идемпотентный bootstrap, PIN в stdout один раз |
| Предпочтения (инъекция в запрос) | prefs/ (UserPreferences, PreferenceLimits, PreferencesStore, renderPromptBlock) + handleAgent в Routes.kt | 7 полей, серверная валидация, единая точка инъекции |
| Изоляция данных per-user | session (Session.userId), memory (PerUserLongTermMemory), prefs, client/ExchangeLog | кросс-доступ = 404 (не 403); userId через UserIdKey |
| Поведение агентного цикла | agent/AgentLoop.kt + tools/ | шаги, инструменты, финал по гварду |
| Изменение UI/логики фронта | frontend/src/ | см. CODE MAP и frontend/AGENTS.md |
| Добавить провайдера LLM | config.json (providers) + client/AIClient.kt | типы протоколов: openai, opencode |
| Проверка ответа (Судья) | client/JudgePrompts.kt + Routes.kt (runJudge) | отчёт — JudgeReport в Models.kt |
| RAG-режим (поиск по базе) | rag/ (RagClient/RagPrompts/RetrievedSource/Passage) + handleAgent в Routes.kt (инжект после trim) | echo — ChatResponse.ragEnabled/retrievedSources (метаданные, без content); тумблер «Поиск по базе (RAG)» в секции «Память»; sidecar — gwclub serve (репо gw-club-indexer) |
| Стратегии контекста | session/ContextPlanner.kt + model/ContextStrategy.kt | off/window/summary/facts/branching |
| Ключ/модель/лимиты | config.json | структура — в config/AppConfig.kt |
| Дизайн-система | DESIGN.md | источник правды по палитре/типографике |
| UI-тексты | frontend/src/features/** | все надписи на русском |

## CODE MAP
| Symbol | Type | Location | Role |
|---|---|---|---|
| Application | fun | kotlin/Application.kt | точка входа, `PORT` env → порт, миграция «Шумахер» ДО routing, роут «/» → dist index.html (no-cache) |
| AgentLoop | — | kotlin/agent/AgentLoop.kt | агентный цикл: план → инструменты → финал |
| Routes | — | kotlin/routes/Routes.kt | 28 API-роутов: auth-роуты, /api/preferences, /api/chat (+инъекция предпочтений в handleAgent), /api/log, /api/sessions, ветки, memory |
| intercept (choke point) | — | kotlin/routes/Routes.kt | единая проверка `X-Auth-Token` на все /api*; 401 «Требуется вход»; ставит `UserIdKey` в атрибуты |
| UserIdKey | AttributeKey | kotlin/routes/Routes.kt (top-level) | атрибут владельца запроса, ставится intercept'ом, читается роутами |
| UserStore | class | kotlin/auth/UserStore.kt | файлы `<dataDir>/users/{id}.json`, create/find/list/delete, isSafeId, атомарная запись |
| UserAccount | class | kotlin/auth/UserAccount.kt | модель + валидация (имя 1..25, ПИН 4 цифры) + SHA-256(salt+pin), constant-time compare |
| TokenManager | class | kotlin/auth/TokenManager.kt | в-памяти токены (32 байта Base64URL), issue/resolve/revoke/revokeAllFor; без диска/TTL |
| LoginGuard | class | kotlin/auth/LoginGuard.kt | кулдаун подбора: 3 неудачи → 30с (429), сброс по успеху/истечении |
| ShturmanMigration | object | kotlin/auth/ShturmanMigration.kt | идемпотентная миграция legacy → «Шумахер»; PIN в stdout один раз (PIN_LINE_FORMAT) |
| PreferencesStore | class | kotlin/prefs/PreferencesStore.kt | `<dataDir>/preferences/{userId}.json`; нет файла → дефолты |
| UserPreferences | class | kotlin/prefs/UserPreferences.kt | 7 полей-строк + DEFAULTS; @EncodeDefault(ALWAYS) |
| renderPromptBlock | fun | kotlin/prefs/PromptBlock.kt | чистый рендер блока «Предпочтения пользователя» (≤2500 симв.) |
| PerUserLongTermMemory | class | kotlin/memory/PerUserLongTermMemory.kt | фабрика per-user сторов `memory/{userId}/longterm.json`; ленивый кеш |
| AIClient | class | kotlin/client/AIClient.kt | вызовы провайдеров openai/opencode, учёт max_tokens |
| JudgePrompts | object | kotlin/client/JudgePrompts.kt | промт судьи, извлечение и разбор JSON-отчёта |
| ExchangeLog | class | kotlin/client/ExchangeLog.kt | журнал обменов для «Самописца»/«Режима ТО»; записи с `userId`, фильтр `forUser` |

Фронтенд (`frontend/src/`):
- `api/` — `types.ts` (рукописное зеркало Models.kt — синхронизировать вручную), `sse.ts` (чистый `parseFrame` + `streamChat`), `client.ts` (postJson/getJson);
- `stores/` — zustand: `settings.ts` (persist `shturman.settings.v1`, seed со старого ключа), `auth.ts` (токен в `sessionStorage` `shturman.auth.v1`, restore/handle401, без persist-мидлвара), `session.ts` (журнал, structural sharing по id), `branches.ts`;
- `components/` — приборы: `Dial` (скрытый range поверх SVG), `AnnunciatorGroup` (радио/тумблеры), `Lamp`, `Clock`, `Overlay`, `StatusLine`, `Drawer`, `dialMath.ts`, `overlayStack.ts`;
- `features/chat/` — Journal/Entry/ExpandToggle/history; `features/send/` — SendBar, useChatStream (AbortController + requestId в refs), request.ts (чистая сборка запроса);
- `features/settings/` — SettingsPanel (селект модели, шкалы, стоп-фраза, тумблеры), `features/strategy/` — панель фактов (Facts);
- `features/mirror/` — TwinPane (локальное состояние по twinId) + Mirror; `features/sessions/` — SessionsPanel (третья колонка); `features/branches/` — чипы веток; `features/maintenance/` — режим ТО + самописец (Recorder);
- `features/judge/`, `features/trace/`, `features/fuel/` — судья, трасса агента, топливомер;
- `lib/` — format, markdown (marked + пост-обработка hljs), storage; `styles/` — tokens.css (единственный :root) + base.css.

Vitest: 305 тестов в 24 файлах, только чистые функции (parseFrame, format, dialMath, validators, auth/wizard-логика, сторы…), без jsdom/RTL. Детали и смоук-чеклист — `frontend/AGENTS.md`.

## CONVENTIONS
- Все пользовательские тексты UI и коммиты — на русском.
- Агентный цикл обязан учитывать лимит токенов (max_tokens передаётся всегда).
- Тонкий клиент: без SDK, ручной HTTP к OpenAI-совместимому API.
- Новые зависимости не добавляются без веской причины (и бэк, и фронт).

## ANTI-PATTERNS (THIS PROJECT)
- Не коммитить `config.json` — там API-ключи (уже в .gitignore).
- Не расширять палитру мимо DESIGN.md: янтарь #ffb347 — управление, зелёный #5fd68f — показания, красный #ff6b5e — отказы/опасность. Новые цвета — только через CSS-токены.
- Фронтенд-стек зафиксирован: React + Vite + TypeScript в `frontend/`, слои api/lib/stores/components/features; новые зависимости фронтенда — только по веской причине.
- Не «рассыпать» проверку токена по route-блокам: единый choke point — `intercept` в `aiRoutes`; владелец только из атрибута `UserIdKey` (ручная `tokens.resolve` в роуте — нет).
- Не отвечать `403` на чужие данные — всегда `404` (без утечки существования ресурса).
- Не называть домен предпочтений «profile»/«профиль» — имя занято видом долговременной памяти; только «Предпочтения»/preferences.
- Не персистить токены на диск и не вводить JWT/refresh/роли (guardrail проекта; рестарт = re-login по дизайну).
- Не инжектить блок предпочтений в саб-вызовы (судья/экстрактор/сводка) — только единая точка в `handleAgent` после trim.

## UNIQUE STYLES
«Приборная доска»: ночной кокпит, шрифты B612/B612 Mono, ламповые клавиши-табло (свечение сквозь надпись и из-под краёв), круглые шкалы с зонами допуска, журнал-«самописец» с цветными строками (зелёный — запросы, янтарь — ответы). Полная спецификация с историей приращений — DESIGN.md.

## COMMANDS
```bash
./gradlew run                      # прод-сборка фронта + Ktor, дефолтный порт 8080
./gradlew run -PskipFrontend       # бэкенд без npm-цепочки (итерация по бэкенду)
cd frontend && npm run dev         # Vite dev-сервер, /api проксируется на Ktor
cd frontend && npm run verify      # tsc -b + eslint + vitest + vite build
docker compose up                  # контейнерный запуск
```

## NOTES
- После правки фронтенда — `npm run verify`; прод-статика попадает в jar только через Gradle-цепочку (copyFrontend → build/frontend-static/static).
- Тесты: kotlin.test (JUnit Platform), 455 @Test в 50 файлах; фронт — Vitest, 305 тестов в 24 файлах на чистых функциях, без jsdom.
- `/api/modes` НЕ существует — режимы рассуждения удалены (волна 1), обработка всегда агентная.
- Память агента — три слоя (краткосрочная = история сессии, рабочая = заметки задачи, долговременная = профиль/решения/знания); компоненты в `src/main/kotlin/com/example/aiweb/memory/`, инструменты — `tools/TaskNotesTool`, `MemorySaveTool`, `MemoryRecallTool` (подключаются через `ToolRegistry.augmentedWith` при `memoryTools != false`). Спецификация и задачи — `openspec/changes/memory-layers/`. Хранение и поиск вынесены за порты `MemoryRepository` / `MemorySearchEngine` (реализация поиска — из конфига `memory.search`, пока только `"keyword"`); спецификация рефакторинга — `openspec/changes/memory-search-ports/`. **Долговременная память per-user**: `PerUserLongTermMemory.forUser(userId)` → `memory/{userId}/longterm.json` (кап 200 на пользователя); общая раскладка `memory/longterm.json` — только legacy-дефолт, через API не видна.
- **Auth-домен (ПИН, токены, изоляция)** — пакет `auth/`: `UserStore` (файлы `<dataDir>/users/{id}.json`, ПИН только как SHA-256(salt+pin), constant-time compare), `TokenManager` (токены в памяти процесса, 32 байта Base64URL; **рестарт сервера = re-login**; JWT/диск/TTL нет — guardrail), `LoginGuard` (3 неудачные попытки → кулдаун 30 с, ответ `429`; сброс по успеху/истечении). Токен — заголовок **`X-Auth-Token`** на всех /api-запросах (включая SSE). Проверка токена — **единый choke point**: `intercept` в начале `aiRoutes` (Routes.kt); открытые — `POST /api/auth/login`, `GET`/`POST /api/auth/users` и статика, остальное без токена — `401 {"error":"Требуется вход"}`. **Токен НЕ проверяется в route-блоках** — владелец кладётся в атрибут вызова `UserIdKey` (`internal`, top-level Routes.kt), роуты читают его. Всего 28 API-роутов (3 open-auth + 25 protected/user-scoped); полная таблица disposition — в комментарии intercept'а в Routes.kt.
- **Изоляция per-user, 404-не-403** — изоляция полная, на уровне хранилищ: сессии (`Session.userId`, `null` — legacy без владельца), рабочая (файл на сессию), долговременная (`memory/{userId}/`), предпочтения (`preferences/{userId}.json`), журнал обменов (`ExchangeLog` тегится `userId`; `GET /api/log` — только свои, записи без владельца не видны никому). **Кросс-доступ к чужому ресурсу = `404`, а не `403`** (существование чужих данных не раскрывается; в т.ч. `DELETE /api/memory/{id}` чужой/неизвестной записи — «Запись не найдена.»). **Задокументированные ограничения**: эвикция `MAX_SESSIONS=50` **глобальная** (по старости на всё хранилище, per-user квот нет — KDoc на константе); `GET /api/files/{name}` — **общая песочница** (файлы видны любому вошедшему, без привязки к владельцу).
- **Миграция «Шумахер» (T10) — bootstrap в `Application.module`, НЕ в `aiRoutes`** (отклонение от плана, осознанное, задокументировано комментарием в Application.kt): `ShturmanMigration.run(config.dataDir)` вызывается в `Application.module` ДО `routing {}`. План размещал bootstrap в `aiRoutes`, но в той волне Routes.kt не трогался (файлом владела задача инъекции) — `Application.module` тот же момент жизненного цикла «при старте приложения»: сторы `aiRoutes` читают диск позже, при создании, и видят уже перенесённые файлы. Миграция идемпотентна: нет «Шумахера» + есть legacy (сессии без владельца ИЛИ `memory/longterm.json`) → создать пользователя, PIN в **stdout один раз** (формат `ShturmanMigration.PIN_LINE_FORMAT`), переписать сессии, перенести `memory/longterm.json` → `memory/{userId}/longterm.json`; повторный бут — no-op.
- **Инъекция предпочтений (T12) — единая точка** — блок «Предпочтения пользователя» (`renderPromptBlock`, ≤2500 симв.) инжектится **только в `handleAgent`** (Routes.kt) **после `SessionHistory.trim`**, перед блоком памяти: `seed = trimmedSeed + [preferencesMessage, memoryMessage]` (порядок: сначала предпочтения, затем память). Инъекция безусловна (нет файла → блок дефолтов). **Саб-вызовы (судья, экстрактор фактов, суммаризатор) строят свои промпты вне seed и блок НЕ получают** (чистые по построению). `handleSingle` в коде НЕ существует — оба роута `/api/chat` и `/api/chat/stream` проходят через `handleAgent`, поэтому одна точка покрывает прямой, пошаговый (рассуждения) и стриминговый режимы. Концепт назван «Предпочтения»/preferences (НЕ «profile» — занято видом долговременной памяти).
- **Auth smoke-чеклист** (после `./gradlew run -PskipFrontend`): `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/sessions` → **401** (без токена); то же с `-H "X-Auth-Token: garbage"` → **401**; `POST /api/auth/users` `{name,pin}` → **200** с токеном; `GET /api/sessions` с этим токеном → **200**; `GET /api/auth/users` без токена → **200** (открытый); `DELETE /api/auth/users/{id}` последнего пользователя → **400** «Нельзя удалить единственного пользователя». Матрица 401 (защищённые роуты × {нет токена, мусорный токен}) покрыта `AuthInterceptTest`; изоляция — `MemoryIsolationRoutesTest`, `LogFilterTest`.
- Настройки фронта хранятся в localStorage ключ `shturman.settings.v1` (старый `ai-assistant.settings.v1` читается однократно как seed); **токен auth — в `sessionStorage` ключ `shturman.auth.v1`** (не в localStorage; вкладки независимы, рестарт бэкенда инвалидирует токен).
- **RAG-режим v1 (add-rag-mode) — контур** — поиск по базе знаний: sidecar `gwclub serve` (отдельный процесс репо gw-club-indexer, запуск из его корня) отвечает на `POST /search`; RAG активен, только если в запросе `ragEnabled=true` И в конфиге задан `config.rag.url`. Запросом к базе служит последнее сообщение пользователя; блок «РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ» (`RagPrompts.buildRagBlock`, бюджет `maxBlockChars`, полный текст чанков) инжектится в seed **после trim** как последнее system-сообщение (после блока памяти, не в саб-вызовы). Echo: `ChatResponse.ragEnabled` (сработал ли поиск) + `retrievedSources` (`RetrievedSource` = метаданные + snippet ≤ 200 символов, **без полного content**), оба с `@EncodeDefault(ALWAYS)`. Fail-open по образцу `runJudge`: sidecar сбит/таймаут/5xx → обычный ответ, `retrievedSources=[]`, warn в лог. Детерминированный mock-реплей по маркеру (точная копия `RAG_MARKER` в `scripts/mock-llm.py`). Харнесс: `scripts/rag_eval.py` + контрольный набор `eval/rag-questions.yaml` (10 вопросов, отчёт `reports/rag-comparison.md`). Спецификация — `openspec/changes/add-rag-mode/`.
- **RAG-режим v2 / stage-2 (add-rag-stage2) — контур** — три выключенных по умолчанию режима поверх v1: реранк + порог + пул `kBefore` на стороне sidecar (indexer: `serve.py`/`rerank.py` + `--rerank-model`) и LLM-рерайт запроса ДО поиска в Штурмане (`rag/QueryRewriter.kt`, fail-open, лог-пара только в stderr). Затронуто: `RagClient`/`RagService`/`Routes.kt`/`AppConfig.kt` (RagConfig v2: `rerank`/`threshold`/`kBefore`/`rewrite`/`rewriteTimeoutMs=8000`) + `Models.kt` (ChatRequest `ragThreshold`/`ragRerank`/`ragRewrite`, 400 RU на порог вне (0,1]) и тесты; харнесс `scripts/rag_eval.py` — режимы raw/filtered/rerank (± `--rewrite`, отчёт `reports/rag-stage2-modes.md`). Спецификация — `openspec/changes/add-rag-stage2/`.
