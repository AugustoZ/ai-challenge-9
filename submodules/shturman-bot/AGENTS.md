# PROJECT KNOWLEDGE BASE — shturman-bot

**Generated:** 2026-10-11
**Commit:** нет (репозиторий инициализирован, 0 коммитов — коммитит владелец)
**Branch:** —

## OVERVIEW

Standalone Telegram-бот «Штурман» (@shturman_mcp_bot): отвечает на вопросы по
базе знаний GW-CLUB (прошивки HAVAL Dargo/H6, TANK 300, GWM Rox) полностью
локально — LM Studio (MLX) + RAG (E5 + FAISS + реранк). Python; прод-режим —
Docker. Выделен из `ai-challenge-9/mcp-servers`.

## STRUCTURE

```
shturman-bot/                     # корень = весь проект (плоский)
├── bot_responder.py              # ядро (~1380 строк): поллер, роутинг, RAG, /admin
├── storage.py                    # TgStore: SQLite/FTS5 + админ-статистика
├── tg_server.py                  # MCP (stdio): reader снапшота | сборщик с токеном
├── common.py                     # build_server(): FastMCP-обёртка (stdout=JSON-RPC only)
├── wiki_urls.py                  # корпус → URL wiki.gwm-club.ru, гидрация страниц
├── sync_loop.py                  # снапшот БД для внеконтейнерных читателей
├── smoke_client.py / smoke.sh    # MCP-smoke: boot tg_server без кредов + протокол
├── day30_check.py                # health-скрипт
├── Dockerfile / docker-compose.yml  # ОСНОВНОЙ режим: bot + tg-db-sync, volume bot-data
├── start_stack.sh / stop_stack.sh   # хост-режим (фолбэк)
├── config.json                   # СЕКРЕТ (TG_BOT_TOKEN) — gitignored; шаблон config.example.json
├── .tg-db-share/                 # снапшот БД (пишет контейнер tg-db-sync) — gitignored
├── backups/                      # архивы БД — gitignored
└── README.md / ARCHITECTURE.md   # развёртывание / схема и потоки
```

## WHERE TO LOOK

| Задача | Где | Примечание |
|---|---|---|
| Логика ответа, RAG, /admin | `bot_responder.py` | `_handle_ask`, `_build_rag_block`, `_rag_docs_root`, `_admin_usernames` |
| Схема БД, статистика | `storage.py` | `messages`+FTS5, `bot_questions`, `bot_users`, `meta` (счётчики `stat_*`) |
| MCP-тулы чтения чата | `tg_server.py` | read_recent/search/summary; `_readiness()` требует токен только при пустом хранилище |
| Ссылки на вики | `wiki_urls.py` | `DOCS_ROOT`: env → поиск корпуса обходом вверх |
| Развёртывание с нуля | `README.md` | Шаги 1–7 (хост) + «Режим Docker (основной)» |
| Компоненты/потоки данных | `ARCHITECTURE.md` | жизненный цикл вопроса, порты, читатели снапшота |

## CRITICAL RULES

- **Один `getUpdates`-поллер на токен.** Прод-поллер = контейнер `shturman-bot`.
  Хостовый `bot_responder.py` рядом не запускать; `tg_server` — reader-режим
  (БЕЗ `TG_BOT_TOKEN`) либо сборщик ВМЕСТО бота. Нарушение → взаимный `HTTP 409`.
- **Секреты**: токен живёт в `config.json` (монтируется в контейнер `:ro`).
  Не логировать, не коммитить, не класть в compose env.
- **SQLite WAL на bind mount Docker Desktop ненадёжен** → живая БД в volume
  `bot-data`. Снапшот — только через `sync_loop.py`: `sqlite3 .backup` IN PLACE
  (rename ломает читателей с коннектом) + `PRAGMA journal_mode=DELETE`
  (ro-читатели не умеют WAL).
- **LM Studio (:1235) и `gwclub serve` (:18700) — хостовые сервисы**, контейнеры
  их не поднимают и не останавливают. В LM Studio: Keep-in-memory ON, JIT OFF
  (`justInTimeModelLoading: false`).
- **stdout MCP-серверов = только JSON-RPC**, логи — в stderr.
- **Агент не коммитит** — коммиты делает владелец.

## CONVENTIONS (отклонения от стандартных)

- Версии зафиксированы: `mcp==1.30.0`, `httpx==0.28.1` — mcp 2.x переименовал
  `FastMCP` в `MCPServer`, импорт падает. Поднимать версию — только с миграцией.
- Хост-venv: Python 3.14 (работает); образ: `python:3.12-slim`.
- Поиск корпуса — обход родителей от файла (работает хост/контейнер), override
  `RAG_DOCS_ROOT`/`DOCS_ROOT`. НЕ возвращать `parents[N]` — IndexError при
  другой глубине (уже ловили в контейнере).
- Доки, докстринги и логи — на русском: норма проекта.

## ANTI-PATTERNS (запрещено здесь)

- Писать в `.tg-db-share/` вне sync-цикла.
- Второй getUpdates-поллер (см. CRITICAL RULES).
- Хардкод абсолютных путей `/Users/...` в коде — только env или обход вверх.
- Глушить исключения / подавлять типы.
- Коммиты агентом; секреты в git.

## COMMANDS

```bash
# прод (основной)
docker compose up -d --build && docker logs -f shturman-bot

# smoke tg_server (boot без кредов + JSON-RPC протокол)
bash smoke.sh

# проверка reader-режима tg_server на живом снапшоте (ожидаем ok:true)
TG_DB_FILE=$PWD/.tg-db-share/tg_messages.db TG_DB_READONLY=1 \
  .venv/bin/python smoke_client.py tg tg_server.py

# хост-режим (фолбэк; сначала остановить контейнерный бот — правило поллера)
bash start_stack.sh   # / bash stop_stack.sh

# бэкап БД (скрипт живёт в соседнем репо, читает снапшот)
bash ../ai-challenge-9/scripts/backup-tg-db.sh
```

## NOTES / GOTCHAS

- Родственные каталоги: `../ai-challenge-9` (aiweb; его `config.json` запускает
  `tg_server.py` отсюда в reader-режиме), `../../gw-club-indexer` (RAG, uv,
  индекс `data/index`), `../../gw-club-docs` (корпус; монтируется в контейнер `:ro`).
- Реранкер (MPS) и MLX делят GPU: `Metal GPU Timeout` при бурстах — норма;
  CPU-реранк убивает процесс, если `faiss` импортирован раньше torch-модели.
- Reader-режим на свежем пустом снапшоте даст «Токен не задан» — наполнится
  с работающим ботом.
- Признак живости бота: растут `count(*) FROM messages` и `stat_total_tokens`
  в `meta`; в логе контейнера не должно быть `409`.
- Ошибки чтения: `wiki_urls: N страниц корпуса смаппировано` при старте —
  0 страниц = корпус не смонтирован/не найден.
