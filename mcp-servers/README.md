# mcp-servers/

Python-воркспейс MCP-серверов «Штурмана» (план `mcp-integration-shturman`):
`tg_server.py` (задача 7c, Bot API + SQLite/FTS5) и `wiki_server.py` (задача 8)
+ общие куски.

- `common.py` — единая точка сборки серверов: логи в stderr,
  `build_server(name) -> FastMCP` (stdio).
- `requirements.txt` — зависимости (mcp, telethon, httpx).

## Быстрый старт

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Нужен Python ≥ 3.12.

## Переменные окружения (`tg_server.py`)

| Переменная       | Обязательная | По умолчанию                  | Назначение |
|------------------|--------------|-------------------------------|------------|
| `TG_BOT_TOKEN`   | да*          | —                             | токен бота от @BotFather (`/newbot`) |
| `TG_CHAT`        | нет          | `tank_dargo_h6_chat`          | чат: username (с/без `@`) или числовой chat id |
| `TG_DB_FILE`     | нет          | `mcp-servers/.tg_messages.db` | файл SQLite-хранилища (относительно repo root) |
| `TG_RETENTION_DAYS` | нет       | `0` (хранить всё)             | >0 — хранить сообщения не старше N дней; чистится при старте и каждые 6ч |
| `TG_BUFFER_FILE` | (legacy)    | `mcp-servers/.tg_buffer.jsonl`| **только источник одной миграции** (см. «Хранилище») |
| `TG_BUFFER_MAX_LINES` | (obsolete) | —                           | игнорируется безвредно |
| `TG_API_BASE`    | нет          | `https://api.telegram.org`    | базовый URL Bot API (для локальных тестов) |

*Для живого режима. Сервер стартует и отвечает на `tools/list` и без токена:
тогда тулы читают уже накопленное хранилище (read-only), либо возвращают
читаемую ошибку про отсутствие токена. Больше НЕ нужны `TG_API_ID`/
`TG_API_HASH`/`TG_SESSION` — боту по Bot API api_id не требуется, а
StringSession не используется (`generate_session.py` больше не нужен для
`tg_server`).

## Настройка бота (BotFather)

1. В Telegram → @BotFather → `/newbot` → придумать имя и username →
   скопировать токен в `TG_BOT_TOKEN`.
2. **Чтобы бот видел сообщения группы**, сделать одно из:
   - @BotFather → `/setprivacy` → выбранному боту → **Disable**; или
   - добавить бота в группу и сделать его **админом**.
3. Добавить бота в нужный чат (для приватных супергрупп username нет — узнать
   числовой `chat_id` через @getidsbot и положить в `TG_CHAT`).

## Хранилище (SQLite + FTS5)

Бот по Bot API **не читает историю** чата, поэтому сервер сам пишет сообщения
в persistent-БД `TG_DB_FILE` (SQLite, WAL). Дедуп по `(chat, message_id)`
(`INSERT OR IGNORE`) — рестарты/повторная доставка `getUpdates` не создают
дублей; полнотекстовый поиск идёт через FTS5 (`messages_fts`). Рядом с `.db`
появляются WAL-файлы `.db-wal` / `.db-shm` — это норма.

**Миграция из legacy JSONL.** Если таблица `messages` пуста и существует файл
`TG_BUFFER_FILE` (или `.tg_buffer.jsonl`), при первом открытии он импортируется
в БД, после чего переименовывается в `<имя>.imported` (повторный запуск — no-op).
`TG_BUFFER_MAX_LINES` больше не используется.

**Холодный старт**: история накапливается только с момента старта сервера
(когда бот уже в чате), обратной дозагрузки нет. Пустое хранилище в первые
минуты — нормальное поведение.

## ⚠️ Правила, которые нельзя нарушать

- **stdout серверов = только JSON-RPC.** Все логи — в stderr. Любая лишняя
  строка в stdout (print, traceback, pip-сообщения) ломает stdio-транспорт.
- **Секреты — только в env/конфиге.** `config.json` уже в `.gitignore`; не
  коммитить и не выводить секреты в логи (токен бота в т.ч.).
- **`.venv/` не коммитится** — в `.gitignore` репо: `mcp-servers/.venv/`.

## Docker

- **venv живёт в образе** (стадия `mcp-python`, путь `/app/mcp-servers/.venv`)
  и НЕ берётся с хоста: `config.json` запускает серверы как
  `mcp-servers/.venv/bin/python` относительно cwd `/app`.
- **БД Telegram — в named volume `aiweb-data`** (`TG_DB_FILE=/app/data/tg_messages.db`,
  задан в docker-compose). WAL-сайдкары (`.db-wal`/`.db-shm`) лежат в том же
  каталоге. **Не размещать этот том на NFS** — SQLite на сети портится.
- **Резервная копия БД без остановки сервиса:**
  ```bash
  bash scripts/backup-tg-db.sh [каталог-бэкапа] [имя-тома]
  ```
  Использует временный контейнер `python:3.12-slim` с `VACUUM INTO` — консистентный
  снимок на лету при активном WAL. Том монтируется read-only, приложение не трогаем.
