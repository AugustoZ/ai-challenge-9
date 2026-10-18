# Архитектура бота «Штурман»

Telegram-бот **@shturman_mcp_bot**, который отвечает на вопросы по документации
проекта «Штурман» **полностью локально**: локальная LLM + локальный векторный
поиск. Ни один пользовательский запрос не уходит в облако.

Проект: standalone `shturman-bot` (выделен из `mcp-servers/` репозитория
ai-challenge-9). Производственный стек выглядит так:

```
  Пользователь Telegram (личный чат или группа)
      │   long-poll  getUpdates,  официальный Bot API
      ▼
  ┌───────────────────────────────────────────────┐
  │  bot_responder.py                             │
  │  единственный getUpdates-поллер на токен;     │
  │  отвечает через локальную LLM + RAG            │
  └───────┬───────────────┬───────────────┬───────┘
          │               │               │
          │ пишет          │ POST /chat/   │ POST /search
          │                │ completions   │ (для /ask)
          ▼                ▼               ▼
  SQLite                 LM Studio       RAG sidecar
  .tg_messages.db        :1234           :18702
  (messages +             qwen3.5-9b-mlx (E5 + FAISS + rerank)
   admin stats)
          │
          └──> .tg-db-share/ (снапшот → tg-sqlweb, бэкапы, tg_server reader-режим)

  (Опционально, через MCP/stdio, без порта)
  tg_server.py   — MCP-сервер: reader-режим (снапшот, без токена) или
                   самостоятельный сборщик (с токеном — только ВМЕСТО бота)
  wiki_server.py — MCP-сервер: создание/чтение Wiki.js FAQ-страниц
```

## Компоненты

| Компонент | Роль | Порт / транспорт |
|---|---|---|
| `bot_responder.py` | Сервис-ответчик: поллер, роутинг, генерация, `/admin` | https, `getUpdates` long-poll → Telegram |
| `github_watcher.py` | Наблюдатель GitHub PR: опрос открытых PR раз в 30 c, уведомления о новых, контекст LLM | https → api.github.com |
| `pr_review.py` | AI-ревью PR: уведомление админам в личку, диффы пофайлово (+ AGENTS.md/SKILL.md), вердикт LLM | https → api.github.com, LM Studio |
| `storage.py` | Общий слой SQLite/FTS5 (`TgStore`) — сообщения + админ-статистика + события PR | локальный SQLite |
| `tg_server.py` | MCP-сервер (stdio): **reader-режим** — читает БД/снапшот без токена (поллер не стартует); с токеном сам поллит (см. «Устойчивость») | stdio (без порта) |
| `wiki_server.py` | MCP-сервер (stdio) для Wiki.js FAQ — **остался** в репозитории ai-challenge-9 | stdio |
| **LM Studio** | Локальная LLM (OpenAI-совместимый API) | `:1234/v1` |
| **RAG sidecar** | Векторный поиск: эмбеддинги E5 + FAISS + реранк — внешний сервис (отдельный репозиторий индексера) | `:18702/search` |
| Корпус документации | `.md`-файлы проекта (первоисточник; `RAG_DOCS_ROOT`) | каталог |

## Жизненный цикл вопроса

1. **Поллер** (`_poller_loop`) — `getUpdates` long-poll (timeout 50 c), offset
   персистентен (`meta.poller_offset`) — после рестарта старое не репроцессится.
   Каждое сообщение: пишется в `messages` (дедуп `(chat, message_id)`) + при
   вопросе — в `bot_questions`/`bot_users` (стат-слой `/admin`).
2. **Роутинг** (`_route_message`): `/start`, `/help`, `/admin`, `/ask <вопрос>`,
   свободный текст в личке = /ask, упоминание в группе. Лишнее (медиа/сервисные/
   чужие чаты) отбрасывается.
3. **Очередь + воркер** (`_jobs` queue → `_worker`): LM Studio обрабатывает
   последовательно; кулдаун ≤1 запрос/5 с на пользователя; сторож «Думаю…» после 45 с.
4. **RAG** (`_handle_ask`): `POST /search` → sidecar косинус `k_before=15` →
   реранк `BAAI/bge-reranker-v2-m3` (для контактового интента реранк пропущен) →
   top-k. При недоступном sidecar — доменный fallback без поиска.
5. **Промпт** (`_build_rag_block`): стабильная часть (правила + каталог +
   первоисточник) → переменная (чанки). `/no_think` в конце системного промпта
   отключает reasoning (генерация ×2-3 быстрее).
6. **Генерация** (`_generate` → `_post_chat`): `POST /chat/completions`
   (`temperature 0.1`, `max_tokens 2048`). Пустой `content` при `length` — ретрай
   с ×2; оборванный — достройка continuation-раундами (≤2).
7. **Двухфазный ответ**: если модель в конце запросила `<doc>путь</doc>` и путь
   валиден по каталогу — бот подгружает полный текст страницы из корпуса и
   перегенерирует. Контактовый интент — детерминированно, без раунда.
8. **Отправка**: markdown → Telegram HTML; ответы >4096 режутся.

## Хранилище (SQLite, WAL)

Хост-режим: `.tg_messages.db` в корне проекта. Таблицы:

- `messages(chat, message_id, ts, author, text)` + FTS5 `messages_fts` — буфер чата;
- `bot_questions(id, ts, user_id, username, display_name, question)` — вопросы `/admin`;
- `bot_users(user_id, username, display_name, first_seen, last_seen, questions)`;
- `meta(key, value)` — `poller_offset`, `bot_first_start`, счётчики
  `stat_prompt_tokens` / `stat_completion_tokens` / `stat_total_tokens`,
  `stat_questions`, `stat_backfilled_through`.

Рядом появляются `.db-wal`/`.db-shm` — это норма.

**Снапшот**: цикл `sync_loop.py` — `sqlite3 .backup` in place +
`journal_mode=DELETE`, интервал 30 c; читатели — `tg-sqlweb`, бэкап-скрипт,
`tg_server` (reader-режим).

## Админ-панель (`/admin`)

Команда `/admin` доступна списку админов (`_admin_usernames()`; по умолчанию
`AugustoTruck,kmnivan`, переопределяется env `BOT_ADMIN_USERNAMES` через запятую).
Показывает: токены (всего prompt/completion + за сессию), время работы сессии +
дату первого запуска, число вопросов, последних 5 пользователей, топ-10 вопросов.
Токены копятся из `usage` ответа LM Studio (в `meta`), вопросы/юзеры — из
`bot_questions`/`bot_users`. Постороннему `/admin` отвечает молчанием.

## Устойчивость

- **Один поллер**: `getUpdates` на токен — только у одного процесса. Два режима
  `tg_server`: **reader** (без токена: поллер не стартует, тулы читают БД/снапшот
  — работает, если в хранилище уже есть записи) и **сборщик** (с токеном сам
  поллит — тогда `bot_responder` должен быть остановлен, иначе взаимный
  `HTTP 409`). aiweb-dev использует reader-режим: снапшот +
  `TG_DB_READONLY=1` + без токена.
- **Перезагрузка LM Studio**: транспортные сбои (`ConnectError`/
  `RemoteProtocolError`) — воркер повторяет задание 2 раза через 60 c; HTTP —
  до 4 попыток; таймаут генерации 120 c — отдельное сообщение.
- **JIT/выгрузка модели**: чтобы модель не выгружалась по TTL — в LM Studio
  «Keep model in memory» + «Just-in-Time Model Loading» выключен
  (`justInTimeModelLoading: false` в `~/.lmstudio/.internal/http-server-config.json`).
- **GPU-конкуренция**: реранкер и MLX-движок делят один GPU; бурсты реранка могут
  давать `Metal GPU Timeout`/`SubmissionsIgnored`. Реранкер остаётся на том
  устройстве (MPS/`device=auto`); попытка CPU-реранка падает, если `faiss-cpu`
  импортирован до загрузки torch-модели (см. README, «Устранение неполадок»).

## Потоки данных

- **Буфер чата**: `bot_responder` (единственный писатель) → снапшот
  (`sync_loop.py`) → читатели: `tg-sqlweb`, бэкап-скрипт, `tg_server`
  (aiweb-dev, reader-режим).
- **RAG**: `bot_responder` → RAG sidecar (`/search`, `:18702`) → внешний
  индексер (отдельный репозиторий; индекс и корпус описаны в его доках и
  `config-rag-project.toml`).

> Подробное описание развёртывания на чистом сервере — см. `README.md`.
