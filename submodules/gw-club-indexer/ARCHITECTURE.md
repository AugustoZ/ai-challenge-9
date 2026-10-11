# ARCHITECTURE — gw-club-indexer

**Обновлено:** 2026-10-11 · Статус: соответствует текущему прод-состоянию.
Детали HTTP-контракта, флагов и конфига — в `README.md`; здесь — топология
системы, компоненты и интеграции.

## Роль в экосистеме

Единый RAG-бэкенд для двух продуктов «Штурман»: **офлайн-векторный поиск**
по корпусу знаний GW-CLUB (`gw-club-docs`: FAQ и руководства прошивок
HAVAL Dargo/H6, TANK 300, GWM Rox). Внешние сервисы для работы поиска не
нужны — только локальные модели и построенный индекс.

Один код — два режима:

```
              ┌─ CLI: gwclub index | search | eval | groundtruth ─ конвейер и эксперименты
gwclub_indexer ┤
              └─ HTTP: gwclub serve  ── POST /search ── потребители (бот, aiweb)
```

## Компоненты (`src/gwclub_indexer`)

| Модуль | Роль |
|---|---|
| `loader` | чтение корпуса `docs_root`: markdown, фильтры (draft, test-страницы, `min_chars`) |
| `chunking/fixed` | нарезка по токенам с перекрытием |
| `chunking/structure` | нарезка по markdown-заголовкам + заголовочный трейл; гигантские таблицы режутся пачками строк с повтором шапки |
| `embeddings/local` | `intfloat/multilingual-e5-small` (dim 384), префиксы `query:`/`passage:` |
| `embeddings/openai_compat`, `mock` | внешний эмбеддер / тестовый (prefix_mode `none`) |
| `storage/faiss_store`, `storage/sqlite_store` | векторы + метаданные; `chunk_id = sha256(source\|strategy\|chunk_index)[:16]`, JOIN по `faiss_row` |
| `indexer` | сборка индекса на стратегию → `data/index/index-{strategy}.{faiss,sqlite}` |
| `search` | векторный поиск (косинус), без BM25 |
| `rerank` | CrossEncoder `BAAI/bge-reranker-v2-m3` (stage-2), сигмоидная шкала |
| `serve` | stdlib `http.server` (ThreadingHTTPServer); `search_pipeline`: search(`k_before`) → rerank → `threshold` → top-k; глобальный `threading.Lock` вокруг embed+search |
| `eval/*` | Recall@5 / MRR@10 по `eval/groundtruth.yaml` → `reports/` |
| `cli.py` | точка входа `gwclub` |

## Процессы и порты (прод)

| Экземпляр | Где запущен | Устройство | Порт | Потребитель |
|---|---|---|---|---|
| `gwclub serve --port 18700 --rerank-model BAAI/bge-reranker-v2-m3` | **хост** (uv) | реранк на **MPS** ≈1.0 с | `:18700` | Telegram-бот (`shturman-bot`) |
| контейнер `indexer` (`--host 0.0.0.0 --port 8000`) | compose `ai-challenge-9` | реранк на **CPU** ≈1.9 с | `indexer:8000`, наружу `127.0.0.1:18701` | aiweb |

- Индекс **один на оба** экземпляра: хост читает `data/index` напрямую,
  контейнер — ro-монтирование того же каталога. После перестройки индекса
  контейнеру нужен рестарт (ro-mount не подхватывает файлы на лету).
- Модельные веса в репо не лежат: E5 ≈470 МБ, bge-reranker ≈2.2 ГБ —
  качаются с HuggingFace Hub при первом старте (`~/.cache/huggingface`;
  в контейнере — том `hf-cache`, докачка resume-able, первый старт 10–15 мин).

## Поток RAG-запроса (прод, Telegram-бот)

```
1. вопрос → контейнер shturman-bot
2. POST http://host.docker.internal:18700/search
   {query, k, k_before: min(4k,50), rerank: true, threshold: 0.5}
3. serve: E5 embed_query → FAISS top-k_before → CrossEncoder rerank
   (сигмоид) → порог 0.5 → top-k {chunk_id, score, source, title, section, content}
4. бот: полные тексты чанков → промпт → LM Studio :1235 → ответ
```

Порог 0.5 по сигмоиде: релевантные чанки ≈0.96, шум ≈0.001, тематически
близкие 0.6–0.99 (см. `reports/stage2-probe.md`). Косинусы e5 в этом
корпусе сжаты в 0.84–0.95 — абсолютный порог по косинусу не фильтр
релевантности, только защитный хвост-кат.

## Интеграции

| Система | Направление | Контакт |
|---|---|---|
| `gw-club-docs` | ← читаем | локальный каталог (`docs_root`); единственный источник данных |
| Telegram-бот (`Projects/shturman-bot`) | ← ищут | `POST :18700/search` (host sidecar, `RAG_SEARCH_URL`) |
| aiweb (`Projects/ai-challenge-9`) | ← ищут + поднимают контейнер | `POST indexer:8000/search` (compose-сеть), build-context — этот репо |
| LM Studio | ✕ не интеграция | разделяет GPU (Metal) с реранком serve-процесса; JIT у LM Studio выключен — смягчает `Metal GPU Timeout` |
| HuggingFace Hub | ← модели | первый старт (E5, bge-reranker); далее офлайн |

Вики `wiki.gwm-club.ru` напрямую этот сервис **не трогает**: корпус
зеркалится в `gw-club-docs` отдельно, индексер читает только локальные
файлы. Ссылки на вики возвращаются потребителю как метаданные `source`/
`section` (URL-гидрация — забота бота, `wiki_urls.py`).

## Инварианты и известные ограничения

1. **Fail-fast старта serve**: нет артефактов — код выхода 1; рассинхрон
   индекса (модель/размерность ≠ `index_meta`) — код 2. Здоровый сервер не
   отдаёт 500.
2. `SentenceTransformer.encode` не потокобезопасен → запросы сериализуются
   глобальным lock'ом; параллелизма поиска нет.
3. **faiss импортирован раньше torch-модели в CPU-процессе → тихая смерть**
   на первом score-вызове (без traceback; проверено 11.10.2026). Хостовый
   serve держит MPS; CPU-пути требуют ленивого импорта faiss.
4. Порядок пайплайна в `search_pipeline` (serve.py) — единственное место
   stage-2 логики; запрос без новых полей байт-в-байт = v1.
5. `chunk_id` детерминирован → переиндексация воспроизводит набор чанков
   (проверено тестами); `built_at` — единственное «живое» поле меты.
6. Контейнер без MPS: медиана реранка ≈1.9 с — бюджет `timeoutMs` секции
   `rag` потребителей рассчитан на это.
