# PROJECT KNOWLEDGE BASE — shturman-bot

**Generated:** 2026-10-18
**Расположение:** обычный каталог внутри репозитория `ai-challenge-9` (не отдельный
git-репо и не сабмодуль — решение владельца); коммиты делает владелец в родительском репо.

## OVERVIEW

Standalone Telegram-бот «Штурман» (@shturman_mcp_bot): отвечает на вопросы по
документации проекта «Штурман» (веб-стенд + бот) полностью локально — LM Studio
(MLX) + RAG (E5 + FAISS + реранк). Python; прод-режим — хостовый поллер
(`bot_responder.py`, PID в `.bot_responder.pid`). Выделен из `ai-challenge-9/mcp-servers`.

## STRUCTURE

```
shturman-bot/                     # корень = весь проект (плоский)
├── bot_responder.py              # ядро: поллер, роутинг, RAG, /admin, /deps, filediff, /create
├── github_watcher.py             # наблюдатель GitHub PR: опрос 30 c, уведомления, контекст LLM
├── pr_review.py                  # AI-ревью PR: диффы+AGENTS/SKILL -> LLM, вердикт админам в личку
├── issue_create.py               # /create: черновик GitHub-issue из лички (LLM + файлы до 5)
├── deps_map.py                   # /deps: карта зависимостей Kotlin из codegraph.sqlite (DOT→PNG)
├── file_diff.py                  # присланные файлы (1–5) → дифф против ветки main на GitHub + вердикт LLM
├── storage.py                    # TgStore: SQLite/FTS5 + админ-статистика + github_prs
├── tg_server.py                  # MCP (stdio): reader снапшота | сборщик с токеном
├── common.py                     # build_server(): FastMCP-обёртка (stdout=JSON-RPC only)
├── wiki_urls.py                  # (вырезан 18.10.2026) — был: корпус → URL wiki, гидрация
├── sync_loop.py                  # снапшот БД для внешних читателей
├── smoke_client.py / smoke.sh    # MCP-smoke: boot tg_server без кредов + протокол
├── scripts/build_project_corpus.py  # сборка корпуса документации проекта + ребилд индекса
├── config-rag-project.toml       # RAG-конфиг корпуса проекта (индекс индексера index-shturman)
├── config.json                   # СЕКРЕТ (TG_BOT_TOKEN, github.token) — gitignored; шаблон config.example.json
├── .tg-db-share/                 # снапшот БД — gitignored
├── backups/                      # архивы БД — gitignored
└── README.md / ARCHITECTURE.md   # развёртывание / схема и потоки
```

## WHERE TO LOOK

| Задача | Где | Примечание |
|---|---|---|
| Логика ответа, RAG, /admin, роутинг | `bot_responder.py` | `_handle_ask`, `_build_rag_block`, `_rag_docs_root`, `_route_message`, `_handle_job` |
| Уведомления о PR / конфиг GitHub | `github_watcher.py` + секция `github` в `config.json` | опрос открытых PR (30 c), дедуп по таблице `github_prs`, baseline без уведомления |
| AI-ревью PR | `pr_review.py` | хук наблюдателя: личка админам, диффы пофайлово (`<s>`/`<i>`), AGENTS.md/SKILL.md документами, вердикт LLM |
| Дифф присланного файла vs main | `file_diff.py` | git trees API + difflib; пакет документов — окно 2 c (`_doc_batches`) |
| GitHub-issue из лички | `issue_create.py` + `_handle_create` | черновик → файлы → `/create`; LLM формирует TITLE/BODY |
| Карта зависимостей | `deps_map.py` + `_handle_deps` | codegraph.sqlite → DOT → PNG, без LLM |
| Схема БД, статистика | `storage.py` | `messages`+FTS5, `bot_questions`, `bot_users`, `meta` (счётчики `stat_*`) |
| MCP-тулы чтения чата | `tg_server.py` | read_recent/search/summary; `_readiness()` требует токен только при пустом хранилище |
| Развёртывание с нуля | `README.md` | шаги 1–6 (хост-режим) |
| Компоненты/потоки данных | `ARCHITECTURE.md` | жизненный цикл вопроса, порты, читатели снапшота |

## CRITICAL RULES

- **Один `getUpdates`-поллер на токен.** Прод-поллер = хостовый `bot_responder.py`
  (PID в `.bot_responder.pid`). Второй экземпляр или `tg_server` с токеном →
  взаимный `HTTP 409`.
- **Секреты**: `config.json` (gitignored). Не логировать, не коммитить, не печатать.
- **Снапшот БД — только через `sync_loop.py`**: `sqlite3 .backup` IN PLACE
  (rename ломает читателей с коннектом) + `PRAGMA journal_mode=DELETE`
  (ro-читатели не умеют WAL).
- **LM Studio (:1234) и RAG sidecar (:18702) — хостовые сервисы**, бот их не
  поднимает и не останавливает. В LM Studio: Keep-in-memory ON, JIT OFF.
- **stdout MCP-серверов = только JSON-RPC**, логи — в stderr.
- **Агент не коммитит** — коммиты делает владелец.

## CONVENTIONS (отклонения от стандартных)

- Версии зафиксированы: `mcp==1.30.0`, `httpx==0.28.1` — mcp 2.x переименовал
  `FastMCP` в `MCPServer`, импорт падает. Поднимать версию — только с миграцией.
- Прод-запуск — с явным env: `LM_API_BASE`, `LM_MODEL`, `RAG_SEARCH_URL`,
  `RAG_DOCS_ROOT` (дефолты в коде — лишь запасные значения).
- Доки, докстринги и логи — на русском: норма проекта.

## ANTI-PATTERNS (запрещено здесь)

- Писать в `.tg-db-share/` вне sync-цикла.
- Второй getUpdates-поллер (см. CRITICAL RULES).
- Хардкод абсолютных путей `/Users/...` в коде — только env.
- Глушить исключения / подавлять типы.
- Коммиты агентом; секреты в git.

## COMMANDS

```bash
# прод-поллер (host): убить PID из .bot_responder.pid, стартовать с env —
# см. «Шаг 5» README; лог и PID: .bot_responder.{log,pid}

# smoke tg_server (boot без кредов + JSON-RPC протокол)
bash smoke.sh

# проверка reader-режима tg_server на живом снапшоте (ожидаем ok:true)
TG_DB_FILE=$PWD/.tg-db-share/tg_messages.db TG_DB_READONLY=1 \
  .venv/bin/python smoke_client.py tg tg_server.py

# бэкап БД (скрипт живёт в соседнем репо, читает снапшот)
bash ../ai-challenge-9/scripts/backup-tg-db.sh
```

## NOTES / GOTCHAS

- Родственные каталоги: `../ai-challenge-9` (aiweb; его `config.json` запускает
  `tg_server.py` отсюда в reader-режиме). RAG sidecar и его индекс — внешний
  репозиторий индексера, конфиг корпуса — `config-rag-project.toml`.
- Реранкер (MPS) и MLX делят GPU: `Metal GPU Timeout` при бурстах — норма;
  CPU-реранк убивает процесс, если `faiss` импортирован раньше torch-модели.
- Reader-режим на свежем пустом снапшоте даст «Токен не задан» — наполнится
  с работающим ботом.
- Признак живости бота: растут `count(*) FROM messages` и `stat_total_tokens`
  в `meta`; в логе не должно быть `409`.
- Ошибки чтения: см. лог старта бота (`bot: LM API …`, `github-наблюдатель`).
- После рестарта бота черновики `/create` теряются (in-memory, TTL 60 мин).
