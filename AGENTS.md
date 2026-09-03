# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-03
**Commit:** 8733644
**Branch:** lesson-3

## OVERVIEW
«Штурман» — веб-стенд рассуждений для AI-чата: Kotlin/Ktor бэкенд (прокси к OpenAI-совместимому API DeepSeek, модель `deepseek-v4-flash`) + vanilla JS/CSS фронтенд в стилистике ночного кокпита «Приборная доска». Без фреймворков на фронте, без сборщика статики.

## STRUCTURE
```
ai-challenge-9/
├── DESIGN.md                      # дизайн-контракт: палитра, шрифты, приращения, Provenance
├── PRODUCT.md                     # продуктовое описание
├── README.md                      # запуск
├── config.example.json            # образец конфига; config.json (gitignored) — реальные ключи
├── Dockerfile / docker-compose.yml
└── src/main/
    ├── kotlin/com/example/aiweb/
    │   ├── Application.kt         # старт Ktor, конфиг, регистрация маршрутов и статики
    │   ├── client/AIClient.kt     # OpenAI-совместимый клиент к DeepSeek (тонкий клиент)
    │   ├── client/ExchangeLog.kt  # in-memory журнал обменов запрос↔ответ (питает «Самописец»/«Режим ТО», JSON-просмотр)
    │   ├── client/ReasoningPrompts.kt # системные промпты 4 режимов рассуждения
    │   ├── config/AppConfig.kt    # разбор config.json
    │   ├── model/Models.kt, ReasoningMode.kt # DTO и enum режимов
    │   └── routes/Routes.kt       # POST /api/chat, GET /api/log (единственные API-эндпоинты)
    └── resources/static/          # фронт (см. AGENTS.md в этой папке)
```

## WHERE TO LOOK
| Задача | Location | Notes |
|---|---|---|
| Новый API-эндпоинт | routes/Routes.kt | сейчас их ровно два: /api/chat, /api/log |
| Смена поведения модели | client/AIClient.kt + ReasoningPrompts.kt | промпты режимов — в ReasoningPrompts |
| Логи запросов/ответов | client/ExchangeLog.kt | без персистентности, живёт в памяти процесса |
| Ключ/модель/лимиты | config.json | структура — в config/AppConfig.kt |
| Дизайн-система | DESIGN.md | источник правды по палитре/типографике |
| UI-тексты | static/index.html | все надписи на русском |

## CODE MAP
| Symbol | Type | Location | Role |
|---|---|---|---|
| Application | object | kotlin/Application.kt | точка входа, `PORT` env → порт |
| Routes | — | kotlin/routes/Routes.kt (290 стр.) | /api/chat (прокси+лимиты), /api/log (журнал) |
| ReasoningMode | enum | kotlin/model/ReasoningMode.kt | 4 режима: Прямой ответ, Пошаговое решение, Команда, Анализ |
| AIClient | class | kotlin/client/AIClient.kt | POST к DeepSeek, учёт max_tokens |
| ExchangeLog | class | kotlin/client/ExchangeLog.kt | журнал обменов для UI |

Фронтенд (~2600 строк без сборки): index.html — разметка приборки; script.js — движок шкал, тултипы, самописец; style.css — вся визуальная система. Детали — `src/main/resources/static/AGENTS.md`.

## CONVENTIONS
- Все пользовательские тексты UI и коммиты — на русском.
- Режимы рассуждения обязаны учитывать лимит токенов (max_tokens передаётся всегда).
- Тонкий клиент: без SDK, ручной HTTP к OpenAI-совместимому API.
- Новые зависимости не добавляются без веской причины (и бэк, и фронт).

## ANTI-PATTERNS (THIS PROJECT)
- Не коммитить `config.json` — там API-ключи (уже в .gitignore).
- Не трогать правки статики без пересборки ресурсов — см. NOTES.
- Не расширять палитру мимо DESIGN.md: янтарь #ffb347 — управление, зелёный #5fd68f — показания, красный #ff6b5e — отказы/опасность. Новые цвета — только через CSS-токены.
- Не заводить фреймворки/бандлеры на фронте — vanilla сознательно.

## UNIQUE STYLES
«Приборная доска»: ночной кокпит, шрифты B612/B612 Mono, ламповые клавиши-табло (свечение сквозь надпись и из-под краёв), круглые шкалы с зонами допуска, журнал-«самописец» с цветными строками (зелёный — запросы, янтарь — ответы). Полная спецификация с историей приращений — DESIGN.md.

## COMMANDS
```bash
./gradlew run                      # дефолтный порт 8080
PORT=8080 ./gradlew run            # переопределение порта через env
./gradlew processResources         # см. NOTES — обязателен после правки статики
node --check src/main/resources/static/script.js   # быстрая проверка синтаксиса JS
docker compose up                  # контейнерный запуск
```

## NOTES
- Ktor отдаёт статику из `build/resources/main`, а не из `src/main/resources`. После правки static/* запусти `./gradlew processResources` (или перезапусти `run`), иначе изменения невидимы.
- Тестов нет (src/test отсутствует)
- `/api/modes` НЕ существует — режимы не отдаются бэком по HTTP; фронт знает их сам (script.js), промпты — в ReasoningPrompts.kt.
- Настройки фронта хранятся в localStorage ключ `ai-assistant.settings.v1`.
