# README — «Штурман»: локальный Telegram-бот с RAG

Telegram-бот **@shturman_mcp_bot**, который отвечает на вопросы по базе знаний
GW-CLUB (прошивки мультимедиа HAVAL Dargo / H6, TANK 300, GWM Rox) **полностью
локально** — локальная LLM + локальный векторный поиск. Ни один пользовательский
запрос не уходит в облако.

Этот README — инструкция по развёртыванию стека на **другом компьютере/сервере**.
Внутренняя схема работы (компоненты, потоки данных, жизненный цикл вопроса) — в
[`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Состав стека

| Компонент | Что делает | Порт |
|---|---|---|
| **LM Studio** | Локальная LLM `qwen3-8b-mlx` (OpenAI-совместимый API) | `:1235/v1` |
| **gw-club-indexer** (`gwclub serve`) | RAG sidecar: эмбеддинги E5 + FAISS + реранк | `:18700/search` |
| **bot_responder** | Единственный `getUpdates`-поллер на токен; отвечает, пишет в БД | длинный poll → Telegram |
| `tg_server` (опц.) | MCP-сервер: reader-режим (снапшот, без токена) или самостоятельный сборщик (с токеном) | stdio |
| `wiki_server` | MCP-сервер Wiki.js FAQ — **остался** в репозитории ai-challenge-9 | stdio |
| `gw-club-docs` | Корпус знаний `.md` | — |

## Требования

- **Python ≥ 3.12** (`shturman-bot` + `gw-club-indexer`).
- **[`uv`](https://docs.astral.sh/uv/)** — для `gw-club-indexer`.
- **Docker** — для контейнерного режима бота (основной; см. «Режим Docker»).
- **LM Studio** (или любой OpenAI-совместимый сервер локальной модели).
- **Диск**: модель 8B (~6 ГБ) + E5 (~470 МБ) + реранкер `BAAI/bge-reranker-v2-m3` (~2.2 ГБ) + FAISS-индекс.
- **GPU** желателен (эмбеддинги/реранк на GPU заметно быстрее); CPU тоже работает, но медленнее.

> **Headless-сервер / Linux без GUI**: LM Studio — GUI-приложение. Для чистого
> сервера используйте его headless-режим (`lmstudio serve`) или любой
> OpenAI-совместимый сервер (например, `llama-server`/`vllm`) и укажите его
> в `LM_API_BASE`. Всё остальное работает одинаково.

---

## Планировка каталогов (важно)

Пути зашиты в `start_stack.sh` и `bot_responder.py` относительно корня проекта.
Дефолтная структура:

```
<workspace>/
├── gw-club-docs/            # корпус знаний (.md)
├── gw-club-indexer/         # RAG-проект (uv)
└── Projects/
    └── shturman-bot/        # этот проект
        ├── bot_responder.py
        ├── storage.py
        ├── tg_server.py
        ├── config.json      # токен/чат (НЕ коммитится)
        ├── Dockerfile / docker-compose.yml / sync_loop.py   # контейнерный режим (основной)
        ├── start_stack.sh / stop_stack.sh                   # хост-режим (фолбэк)
        ├── .tg-db-share/    # снапшот БД (пишет контейнер tg-db-sync)
        └── backups/         # архивные копии БД
```

- `start_stack.sh` ищет `gw-club-indexer` как `../../gw-club-indexer` от проекта.
- `bot_responder.py` и `wiki_urls.py` ищут корпус обходом вверх от себя
  (первый родительский каталог с `gw-club-docs` — работает и на хосте, и в
  контейнере); явный override — `RAG_DOCS_ROOT` / `DOCS_ROOT`.

Если раскладываете иначе — задайте явно (см. env ниже): `RAG_DOCS_ROOT`,
`DOCS_ROOT`, и `[paths].docs_root` в `config.toml` индексера.

---

## Шаг 1. Python + venv

```bash
cd shturman-bot
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt     # mcp==1.30.0, httpx==0.28.1, telethon>=1.34
```

> Версии `mcp`/`httpx` зафиксированы не случайно: в mcp 2.x `FastMCP`
> переименован в `MCPServer` — код серверов написан под 1.x API.

## Шаг 2. Локальная LLM

1. Установите и запустите LM Studio, загрузите модель **`qwen3-8b-mlx`**.
2. Включите **«Keep model in memory»** и выключите **«Just-in-Time Model Loading»**
   — иначе модель выгружается по TTL простоя и «холодный» вопрос переплачивает
   префилл (30–60 с недоступности). Флаг в файле конфига:
   `~/.lmstudio/.internal/http-server-config.json` → `justInTimeModelLoading: false`.
3. Проверка API:
   ```bash
   curl -s http://localhost:1235/v1/models
   ```
4. Если другой сервер — задайте `LM_API_BASE` (по умолчанию `http://localhost:1235/v1`).

## Шаг 3. RAG sidecar (`gw-club-indexer`)

```bash
cd gw-club-indexer
uv sync --all-extras
uv run gwclub index --strategy structure                 # собрать FAISS-индекс из корпуса
uv run gwclub serve --port 18700 --rerank-model BAAI/bge-reranker-v2-m3
```

- `config.toml` → `[paths].docs_root` содержит **абсолютный** путь разработчика
  (`/Users/augustoz/AI Advent Challenge 9/gw-club-docs`) — **смените** на путь к
  вашему корпусу (или настройте через `--config`).
- Первый запуск скачает E5 (`intfloat/multilingual-e5-small`) и реранкер.
- Проверка:
  ```bash
  curl -s -X POST http://127.0.0.1:18700/search \
    -H 'Content-Type: application/json' -d '{"query":"прошивка Dargo","k":1}'
  ```

> Индексатор и бот — отдельные репозитории. `gw-club-indexer` здесь описан
> постольку, поскольку он входит в прод-стек бота.

## Шаг 4. Корпус знаний

Разместите `.md`-файлы вики в `gw-club-docs/` (структура с локалью в пути,
`locale`/`path` во frontmatter). Индексатор строит из них FAISS-индекс.

## Шаг 5. Конфигурация бота

Токен/чат берутся из env или, как фолбэк, из `<repo-root>/config.json`
(`mcp[].env.TG_BOT_TOKEN`). Переменные окружения (`bot_responder.py`):

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `TG_BOT_TOKEN` | фолбэк `../config.json` | токен от @BotFather (`/newbot`) |
| `TG_CHAT` | `tank_dargo_h6_chat` | целевой чат (username или числовой id) |
| `TG_DB_FILE` | `<проект>/.tg_messages.db` (хост-режим); в Docker задаёт compose (`/app/data/...`) | SQLite-база (сообщения + статистика) |
| `TG_RETENTION_DAYS` | `0` | >0 — хранить сообщения не старше N дней |
| `TG_API_BASE` | `https://api.telegram.org` | для локальных тестов |
| `LM_API_BASE` | `http://localhost:1235/v1` | OpenAI-совместимый API LLM |
| `LM_MODEL` | `qwen3-8b-mlx` | модель генерации |
| `LM_MAX_TOKENS` | `2048` | бюджет генерации |
| `LM_TEMPERATURE` | `0.1` | температура |
| `RAG_SEARCH_URL` | `http://127.0.0.1:18700/search` | поисковый sidecar |
| `RAG_K` | `5` | сколько чанков надо |
| `RAG_STRATEGY` | `structure` | стратегия индекса |
| `RAG_DOCS_ROOT` | `<workspace>/gw-club-docs` | корпус (для URL-маппинга/гидрации) |
| `WIKI_BASE` | `https://wiki.gwm-club.ru` | база вики для ссылок (`wiki_urls`) |
| `DOCS_ROOT` | `<workspace>/gw-club-docs` | корпус для `wiki_urls` |
| `BOT_ADMIN_USERNAMES` | `AugustoTruck,kmnivan` | админы `/admin` (через запятую) |

## Шаг 6. Запуск / остановка стека (хост-режим)

> Основной способ запуска — **контейнерный** (см. следующий раздел). Хост-режим
> (venv + `start_stack.sh`) остаётся рабочим фолбэком.

```bash
bash start_stack.sh    # LM Studio + gwclub serve (:18700) + bot_responder + снапшот БД
bash stop_stack.sh     # остановить bot_responder + gwclub serve + снапшот
```

`start_stack.sh` идемпотентен (проверяет `:1235`, `:18700` и PID-файлы; дубли не
создаёт; `gwclub serve` ждёт прогрева модели до 120 с). Логи (в корне проекта):
`.gwclub-serve.log`, `.bot_responder.log`, `.tg-db-sync.log`;
PID-файлы: `.gwclub-serve.pid`, `.bot_responder.pid`, `.tg-db-sync.pid`.
LM Studio `stop_stack.sh` **не трогает** (внешний сервис).
Ручной старт поллера: `TG_BOT_TOKEN=... .venv/bin/python bot_responder.py`.

## Шаг 7. Проверка

- `/admin` в Telegram от админа → отчёт (токены, время работы, вопросы, юзеры, топ-10).
- Вопрос (`/ask или свободный текст`) → ответ со ссылками на `wiki.gwm-club.ru`.
- `tg_server`/`wiki_server` — через MCP-клиент (opencode/web), stdio.

## Режим Docker (основной)

Контейнерный запуск — рекомендуемый: `restart: unless-stopped` вместо nohup,
предсказуемое окружение (python 3.12), старт одной командой.

Файлы: `Dockerfile`, `docker-compose.yml`, `sync_loop.py` (снапшот БД).

```bash
cd shturman-bot
docker compose up -d --build
docker logs -f shturman-bot
```

### Архитектура v1 (RAG — хостовый sidecar)

| Слой | Где живёт | Как достаётся из контейнера |
|---|---|---|
| LM Studio | хост (`:1235`, MLX/GPU) | `host.docker.internal:1235` |
| RAG sidecar `gwclub serve` | хост (`:18700`, MPS-реранк) | `host.docker.internal:18700` |
| SQLite-БД | named volume `bot-data` (WAL на bind mount Docker Desktop ненадёжен) | — |
| Снапшот БД | bind `./.tg-db-share/` — пишет контейнер `shturman-bot-db-sync` | читают `tg-sqlweb`, бэкап-скрипт, `tg_server` (aiweb-dev, reader-режим) |
| Корпус | хост `gw-club-docs` | bind `:ro` → `/app/docs` |
| Токен | `./config.json` | bind `:ro` → `/app/config.json` |

Контейнеры: `shturman-bot` (поллер+генерация) и `shturman-bot-db-sync`
(цикл снапшота `sync_loop.py`: `sqlite3 .backup` in place + `journal_mode=DELETE`).

### Правила эксплуатации

- **Один поллер**: пока контейнерный бот запущен, хостовый `bot_responder.py`
  должен быть ОСТАНОВЛЕН — иначе `HTTP 409`. `start_stack.sh`/`stop_stack.sh` —
  только для хост-режима.
- Хостовый sidecar и LM Studio контейнеры **не трогают** — они внешние сервисы.
- Бэкап БД: снапшот в `.tg-db-share/tg_messages.db` уже консистентен
  (DELETE-journal) — достаточно копировать его файл.
- Смена модели/лимитов: правьте env в `docker-compose.yml` → `docker compose up -d`.
- v2 (не реализовано): перевести `RAG_SEARCH_URL` на контейнерный `indexer:8000`
  и убрать хостовый sidecar — ценой перехода реранка на CPU.

## Автозапуск (systemd, Linux-сервер)

> В контейнерном режиме systemd не нужен — перезапусками управляет
> `restart: unless-stopped` в compose.

Пример юнита для `bot_responder` (`/etc/systemd/system/shturman-bot.service`):

```ini
[Unit]
Description=Shturman bot responder
After=network.target

[Service]
WorkingDirectory=/opt/shturman/shturman-bot
Environment=TG_BOT_TOKEN=ВАШ_ТОКЕН
Environment=TG_CHAT=tank_dargo_h6_chat
ExecStart=/opt/shturman/shturman-bot/.venv/bin/python bot_responder.py
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Аналогично для `gwclub serve` (с вашим индексом) и через `User=` для ограничения
прав. LM Studio — отдельный сервис.

---

## Админ-панель

`/admin` — только для списка из `BOT_ADMIN_USERNAMES` (по умолчанию
`AugustoTruck,kmnivan`; регистронезависимо, `@` игнорируется). Посторонний `/admin`
получает **молчание**. Отчёт: токены всего (prompt/completion) и за сессию, время
работы, число вопросов, последние 5 пользователей, топ-10 вопросов. Токены копятся
из `usage` ответа LM Studio; вопросы/юзеры — из таблиц `bot_questions`/`bot_users`.
Их можно бэкфиллить из логов LM Studio (`stat_*` в `meta`).

## Безопасность

- **Секреты — только в env/конфиге.** `config*.json` уже в `.gitignore`
  (исключение — `config.example.json`). Токен бота не логируем и не коммитим.
- **stdout MCP-серверов = только JSON-RPC**, все логи — в stderr.
- **Один поллер на токен.** `tg_server` при работающем боте запускайте в
  **reader-режиме**: без `TG_BOT_TOKEN`, `TG_DB_FILE`=снапшот, `TG_DB_READONLY=1`
  — тогда его поллер не стартует (чтение работает, если в снапшоте уже есть
  сообщения). Токен в `tg_server` включает его собственный поллер → взаимный
  `HTTP 409` с ботом.

## Устранение неполадок

| Проблема | Причина / решение |
|---|---|
| `HTTP 409` | второй `getUpdates`-поллер: хостовый `bot_responder` рядом с контейнерным, либо `tg_server` с токеном. Поллер — один: бот (контейнер); `tg_server` — reader-режим (без токена). |
| `tg_server`: «Токен бота не задан» при чтении | reader-режим читает только непустое хранилище: снапшот пуст (бот ещё не собирал сообщения) — наполнится при работающем боте; либо это сценарий «сборщик» → нужен токен (и тогда бот остановлен). |
| Контейнер бота в restart-loop | `docker logs shturman-bot`. Типовые причины: не смонтирован `config.json`/корпус, недоступен `host.docker.internal`, нет volume `bot-data`. |
| Модель «выгружается», холодный вопрос 30–60 с | JIT-загрузка. В LM Studio: «Keep model in memory» + JIT off; `justInTimeModelLoading: false`. |
| `Metal GPU Timeout`/`SubmissionsIgnored` | реранкер и MLX-движок делят GPU. Реранкер на `device=auto`/MPS; снизить частоту бурстов (контактовый интент — без реранка). |
| CPU-реранк убивает процесс | `faiss-cpu` импортирован до загрузки torch-модели → процесс молча падает. Не смешивайте CPU-эмбеддер с CUDA-реранком; оставьте `device=auto`. |
| `docs_root`/корпус не найден | сменить `[paths].docs_root` в `config.toml` и `RAG_DOCS_ROOT`/`DOCS_ROOT`, либо разложить по дефолтной схеме `../..`. |
| `sidecar` не отвечает | не собран индекс (`uv run gwclub index`), модель не скачана, порт занят. См. `.gwclub-serve.log`. |
| Нет ответа / «Не уложился в лимит» | уменьшить вопрос; для reasoning-моделей увеличить `LM_MAX_TOKENS` и оставить `/no_think` в системном промпте. |

---

Схема работы, компоненты и потоки данных — [`ARCHITECTURE.md`](ARCHITECTURE.md).
