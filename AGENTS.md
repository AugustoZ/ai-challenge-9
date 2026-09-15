# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-03
**Commit:** 8733644
**Branch:** lesson-3

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
    │   ├── Application.kt         # старт Ktor, конфиг, «/» → index.html React-сборки, кэш статики
    │   ├── agent/AgentLoop.kt     # агентный цикл: шаги, вызовы инструментов, финал
    │   ├── memory/                # память агента: WorkingMemoryStore, LongTermMemoryStore, MemoryRetriever
    │   ├── client/                # AIClient (openai/opencode), JudgePrompts, FactPrompts, ReasoningPrompts (budgetNote/withBudget)
    │   ├── config/AppConfig.kt    # разбор config.json: провайдеры (ProviderConfig), allowedModels
    │   ├── model/                 # Models.kt, AgentEvents.kt, StreamEvents.kt, ContextStrategy.kt
    │   ├── routes/Routes.kt       # POST /api/chat(+ /stream), GET /api/models, /api/log, /api/sessions, ветки
    │   ├── session/               # SessionStore/FileSessionStore, ContextPlanner, HistoryCompressor
    │   └── tools/                 # ToolRegistry: калькулятор, HTTP GET, дата/время, песочница
    └── resources/static/          # только favicon.svg (остальная статика — сборка Vite)
```

## WHERE TO LOOK
| Задача | Location | Notes |
|---|---|---|
| Новый API-эндпоинт | routes/Routes.kt | /api/chat, /api/models, /api/log, /api/sessions, ветки, cancel |
| Поведение агентного цикла | agent/AgentLoop.kt + tools/ | шаги, инструменты, финал по гварду |
| Изменение UI/логики фронта | frontend/src/ | см. CODE MAP и frontend/AGENTS.md |
| Добавить провайдера LLM | config.json (providers) + client/AIClient.kt | типы протоколов: openai, opencode |
| Проверка ответа (Судья) | client/JudgePrompts.kt + Routes.kt (runJudge) | отчёт — JudgeReport в Models.kt |
| Стратегии контекста | session/ContextPlanner.kt + model/ContextStrategy.kt | off/window/summary/facts/branching |
| Ключ/модель/лимиты | config.json | структура — в config/AppConfig.kt |
| Дизайн-система | DESIGN.md | источник правды по палитре/типографике |
| UI-тексты | frontend/src/features/** | все надписи на русском |

## CODE MAP
| Symbol | Type | Location | Role |
|---|---|---|---|
| Application | fun | kotlin/Application.kt | точка входа, `PORT` env → порт, роут «/» → dist index.html (no-cache) |
| AgentLoop | — | kotlin/agent/AgentLoop.kt | агентный цикл: план → инструменты → финал |
| Routes | — | kotlin/routes/Routes.kt | /api/chat (прокси+лимиты), /api/log, /api/sessions, ветки |
| AIClient | class | kotlin/client/AIClient.kt | вызовы провайдеров openai/opencode, учёт max_tokens |
| JudgePrompts | object | kotlin/client/JudgePrompts.kt | промт судьи, извлечение и разбор JSON-отчёта |
| ExchangeLog | class | kotlin/client/ExchangeLog.kt | журнал обменов для «Самописца»/«Режима ТО» |

Фронтенд (`frontend/src/`):
- `api/` — `types.ts` (рукописное зеркало Models.kt — синхронизировать вручную), `sse.ts` (чистый `parseFrame` + `streamChat`), `client.ts` (postJson/getJson);
- `stores/` — zustand: `settings.ts` (persist `shturman.settings.v1`, seed со старого ключа), `session.ts` (журнал, structural sharing по id), `branches.ts`;
- `components/` — приборы: `Dial` (скрытый range поверх SVG), `AnnunciatorGroup` (радио/тумблеры), `Lamp`, `Clock`, `Overlay`, `StatusLine`, `Drawer`, `dialMath.ts`, `overlayStack.ts`;
- `features/chat/` — Journal/Entry/ExpandToggle/history; `features/send/` — SendBar, useChatStream (AbortController + requestId в refs), request.ts (чистая сборка запроса);
- `features/settings/` — SettingsPanel (селект модели, шкалы, стоп-фраза, тумблеры), `features/strategy/` — панель фактов (Facts);
- `features/mirror/` — TwinPane (локальное состояние по twinId) + Mirror; `features/sessions/` — SessionsPanel (третья колонка); `features/branches/` — чипы веток; `features/maintenance/` — режим ТО + самописец (Recorder);
- `features/judge/`, `features/trace/`, `features/fuel/` — судья, трасса агента, топливомер;
- `lib/` — format, markdown (marked + пост-обработка hljs), storage; `styles/` — tokens.css (единственный :root) + base.css.

Vitest: 182 теста, только чистые функции (parseFrame, format, dialMath, сторы…), без jsdom/RTL. Детали и смоук-чеклист — `frontend/AGENTS.md`.

## CONVENTIONS
- Все пользовательские тексты UI и коммиты — на русском.
- Агентный цикл обязан учитывать лимит токенов (max_tokens передаётся всегда).
- Тонкий клиент: без SDK, ручной HTTP к OpenAI-совместимому API.
- Новые зависимости не добавляются без веской причины (и бэк, и фронт).

## ANTI-PATTERNS (THIS PROJECT)
- Не коммитить `config.json` — там API-ключи (уже в .gitignore).
- Не расширять палитру мимо DESIGN.md: янтарь #ffb347 — управление, зелёный #5fd68f — показания, красный #ff6b5e — отказы/опасность. Новые цвета — только через CSS-токены.
- Фронтенд-стек зафиксирован: React + Vite + TypeScript в `frontend/`, слои api/lib/stores/components/features; новые зависимости фронтенда — только по веской причине.

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
- Тесты: kotlin.test (JUnit Platform), ~260 @Test в 24 файлах; фронт — Vitest, 182 теста на чистых функциях, без jsdom.
- `/api/modes` НЕ существует — режимы рассуждения удалены (волна 1), обработка всегда агентная.
- Память агента — три слоя (краткосрочная = история сессии, рабочая = заметки задачи, долговременная = профиль/решения/знания); компоненты в `src/main/kotlin/com/example/aiweb/memory/`, инструменты — `tools/TaskNotesTool`, `MemorySaveTool`, `MemoryRecallTool` (подключаются через `ToolRegistry.augmentedWith` при `memoryTools != false`). Спецификация и задачи — `openspec/changes/memory-layers/`. Хранение и поиск вынесены за порты `MemoryRepository` / `MemorySearchEngine` (реализация поиска — из конфига `memory.search`, пока только `"keyword"`); спецификация рефакторинга — `openspec/changes/memory-search-ports/`.
- Настройки фронта хранятся в localStorage ключ `shturman.settings.v1` (старый `ai-assistant.settings.v1` читается однократно как seed).
