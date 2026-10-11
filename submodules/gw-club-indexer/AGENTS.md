# PROJECT KNOWLEDGE BASE — gw-club-indexer

**Generated:** 2026-10-11
**Commit:** нет (репозиторий инициализирован, 0 коммитов — коммитит владелец)
**Branch:** —

## OVERVIEW

RAG-бэкенд продуктов «Штурман»: офлайн векторный поиск по корпусу
`gw-club-docs` (E5-small + FAISS + реранк bge-reranker-v2-m3). Один код —
CLI-конвейер (`gwclub index/search/eval`) и HTTP-sidecar (`gwclub serve`,
stdlib, без новых зависимостей). Python 3.12, uv.

## STRUCTURE

```
gw-club-indexer/
├── src/gwclub_indexer/       # весь код пакета (см. WHERE TO LOOK)
│   ├── cli.py                # точка входа `gwclub` (все подкоманды)
│   ├── serve.py              # HTTP-sidecar; search_pipeline = вся stage-2 логика
│   ├── config.py             # DEFAULTS — единственный источник дефолтов
│   ├── chunking/             # fixed | structure (таблицы режутся с повтором шапки)
│   ├── embeddings/           # local (E5), openai_compat, mock (тесты)
│   ├── storage/              # faiss_store + sqlite_store (chunk_id, faiss_row)
│   └── eval/                 # engine/report/groundtruth → reports/
├── tests/                    # unit + integration; conftest.py
├── eval/groundtruth.yaml     # эталон: 30 faq + 0 heading — коммитится, правится руками
├── reports/                  # отчёты eval (comparison.md — нормативный формат) — коммитятся
├── data/index/               # индексы (faiss+sqlite) — gitignored, перестраиваются
├── config.toml               # ЛОКАЛЬНЫЙ override — gitignored (пути машины владельца)
├── Dockerfile                # runtime-образ; uv pin 0.12.7, venv в /opt/venv
└── README.md / ARCHITECTURE.md  # контракт API и конфиг / топология и интеграции
```

## WHERE TO LOOK

| Задача | Где |
|---|---|
| Контракт `POST /search`, флаги serve, конфиг | `README.md` (секция `gwclub serve`) |
| Пайплайн запроса, порты, интеграции | `ARCHITECTURE.md` |
| Изменить пост-обработку поиска (rerank/threshold/k_before) | `serve.py` → `search_pipeline` |
| Дефолт конфига | `config.py` → `DEFAULTS` (единственный источник) |
| Стратегии нарезки | `chunking/structure.py`, `chunking/fixed.py` |
| Проблема качества поиска | `reports/comparison.md` (правило победителя) → `gwclub eval` |

## CRITICAL RULES

- **Агент не коммитит** — коммиты делает владелец.
- **Дефолты — только в `config.py::DEFAULTS`**; `config.toml` — опциональный
  локальный override (gitignored). Не хардкодить значения в других модулях.
- **Секретов в репо нет и быть не должно**: ключ эмбеддера читается из
  env по имени (`api_key_env` в конфиге — имя переменной, не значение).
- **`--host 0.0.0.0` в контейнере обязателен**: дефолт serve = `127.0.0.1`
  → контейнер молча недоступен. Хостовый sidecar наоборот держит `127.0.0.1`.
- **Порядок импортов**: faiss раньше torch-модели в CPU-процессе → тихая
  смерть на первом score-вызове. Не «оптимизировать» импорты; CPU-пути —
  только с ленивым импортом faiss.
- **Порт/эндпоинты — контракт**: `:18700` (бот, `RAG_SEARCH_URL` в
  `shturman-bot/docker-compose.yml`) и `indexer:8000` (aiweb, секция `rag`).
  Менять — синхронно с обоими потребителями.

## CONVENTIONS (отклонения от стандартных)

- `uv.lock --frozen` — лок единственный источник правды; uv закреплён на
  0.12.7 и в Dockerfile (совпадение версий хост/образ — договор по
  воспроизводимости). Поднимать — синхронно в обоих местах.
- Версии зависимостей зафиксированы: `faiss-cpu==1.15.1`,
  `sentence-transformers>=6.1,<7`.
- Доки, докстринги, ошибки API — на русском: норма проекта.
- `config.toml` в git не идёт; правки дефолтов — только через `config.py`.

## ANTI-PATTERNS (запрещено здесь)

- Коммиты агентом.
- Хардкод абсолютных путей вне `config.py::DEFAULTS`.
- Убирать правила из `.dockerignore` (контекст сборки раздувается до
  ~920 МБ: .venv с torch+faiss) — целевой размер контекста < 5 МБ.
- Добавлять зависимости в serve-путь: HTTP-слой намеренно на stdlib.
- Глушить исключения; параллелить `encode` без lock'а (не потокобезопасен).
- Коммитить `data/`, `config.toml`, веса моделей.

## COMMANDS

```bash
# окружение
uv sync --all-extras

# конвейер (первый запуск качает E5 ~470 МБ; далее офлайн)
uv run gwclub groundtruth                      # эталон (без LLM)
uv run gwclub index --strategy both            # обе стратегии
uv run gwclub search --strategy structure -k 5 "вопрос"
uv run gwclub eval                             # → reports/comparison.md

# хостовый sidecar (потребитель — Telegram-бот)
uv run gwclub serve --port 18700 --rerank-model BAAI/bge-reranker-v2-m3

# проверки
uv run pytest
curl -s -X POST http://127.0.0.1:18700/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"прошивка","k":3,"rerank":true}'

# контейнерный экземпляр (потребитель — aiweb) поднимает ЧУЖОЙ compose:
# Projects/ai-challenge-9/docker-compose.yml, сервис indexer; после
# перестройки индекса на хосте — docker compose restart indexer
```

## NOTES / GOTCHAS

- Два продукта называются «Штурман» (веб и ТГ-бот) — оба потребители этого
  сервиса; контейнер поднимает только compose ai-challenge-9.
- serve стартует fail-fast: нет индекса — код 1, рассинхрон меты — код 2;
  здоровый сервер не отдаёт 500.
- Первый старт контейнера качает ~2.7 ГБ весов в том `hf-cache` (10–15 мин,
  start_period 600s); повторный — с тёплого тома.
- serve однопоточный по факту: глобальный lock вокруг embed+search —
  SentenceTransformer не потокобезопасен; параллелизма нет, это осознанно.
- Косинусы e5 в этом корпусе сжаты в 0.84–0.95: порог по косинусу — не
  фильтр релевантности; фильтр — сигмоида реранка (`threshold: 0.5`).
- Реранк на MPS ≈1.0 с / CPU ≈1.9 с на 20 кандидатов; GPU делит с LM
  Studio — `Metal GPU Timeout` при бурстах смягчается малым `k_before` и
  JIT OFF у LM Studio.
