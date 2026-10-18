# Проект: «Штурман» (AI Web Client)

## Платформа

Kotlin-сервер на Ktor (JVM 17+, Gradle, запуск и сборка через `./gradlew`) +
фронтенд **React 18 + Vite + TypeScript (strict)** в папке `frontend/`.
Состояние — Zustand (настройки персистятся в localStorage), SSE-парсер —
собственный, markdown — marked, подсветка — highlight.js (npm-зависимости).
Тесты: kotlin.test (`./gradlew test`) и Vitest на чистых функциях без jsdom
(`cd frontend && npm run verify` = tsc -b + eslint + vitest + vite build);
в тестах ноль сети (фейковый `LLMClient`).

## Путь статики

Исходники фронтенда — в `frontend/src/` (api/, lib/, stores/, components/,
features/, styles/; конвенции — `frontend/AGENTS.md`, цвета — только токены
`tokens.css`). Прод-сборка: Gradle-цепочка `npmCi → buildFrontend (npm run
verify) → copyFrontend` кладёт `frontend/dist` в classpath
(`build/frontend-static/static`); Ktor отдаёт index.html с «/» (no-cache),
хэшированные ассеты — `/static/assets/*` (immutable). В
`src/main/resources/static` остаётся только favicon.svg. Разработка
фронта — `npm run dev` (Vite проксирует `/api` на Ktor :8080) или
`./gradlew run -PskipFrontend` для итерации по бэкенду. Язык интерфейса:
русский.

## Конвенции коммитов

Сообщения коммитов на русском языке, в стиле существующей истории:
«Задание 8. Токены», «Компрессия: …», «P1. Файловое хранилище сессий: …».
Работа идёт в lesson-ветках (lesson-1 … lesson-9); все правки текущего
цикла вносятся в `lesson-9`.

## Доктрина конфигурации

Приоритет источников, от высшего к низшему:

1. Переменные окружения (`PORT`, `AI_API_URL`, `AI_API_KEY`, `AI_MODEL`,
   `CONFIG_FILE`, `AI_DATA_DIR`)
2. Конфиг-файл (JSON, путь из `CONFIG_FILE`, по умолчанию `config.json`)
3. Значения по умолчанию (блок defaults в `AppConfig`)

Новые поля конфига добавляются nullable с default: старый `config.json`
обязан оставаться рабочим (сериализатор Kotlin, `ignoreUnknownKeys`).

## Документация

- `PLAN.md`: план внедрения. Ключевые решения (нумерованная таблица с
  обоснованием), фазы, заготовки под следующие волны
- `PRODUCT.md`: продуктовая модель: пользователь, цель, принципы
- `DESIGN.md`: дизайн-система «приборной доски»: палитра, типографика,
  спецификации компонентов
- `README.md`: пользовательская документация: запуск, конфиг, провайдеры,
  режимы рассуждения

## Формат спецификаций (OpenSpec)

Change лежит в `openspec/changes/<id>/`: `proposal.md` (зачем, ключевые
решения, edge cases), `specs/<capability>/spec.md` (дельта требований),
`tasks.md` (чек-лист). Требование оформляется блоком `### Requirement: …`
со сценариями `#### Scenario: …`. Все артефакты на русском языке.
