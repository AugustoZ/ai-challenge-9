# Задачи: add-rag-stage2

Чек-лист зеркалит задачи 2–12 плана shturman-rag-stage2 (нумерация сохранена
для слеживаемости). Все пункты выполняются TDD; агент не коммитит —
пользователь коммитит вручную. Требования — `specs/rag-stage2/spec.md`.

## 2. Probe: распределение score + reranker smoke (внешний индексер)

- [x] 2.1 Одноразовый probe-скрипт (в /tmp, НЕ в репо): загрузить
      существующий индекс (376 чанков), 10 вопросов из
      `eval/rag-questions.yaml` через `search()`, собрать распределение
      cosine-score топ-20
- [x] 2.2 `CrossEncoder("BAAI/bge-reranker-v2-m3")` с ЯВНОЙ
      sigmoid-активацией и `max_length=512`: те же 10 вопросов × топ-20
      кандидатов, rerank-score для каждой пары
- [x] 2.3 Замеры: cold-load модели, латентность реранка 20 кандидатов
      (CPU и, если доступен, MPS), RAM; разделение relevant (из ground
      truth) vs нерелевантных
- [x] 2.4 Артефакт `reports/stage2-probe.md`: квартили косинусов,
      статистика rerank-score, рекомендованные дефолты (cosine-guard порог
      при rerank-off, `k_before = min(4*k, 50)`), решение CPU vs MPS
- [x] 2.5 Если латентность 20 кандидатов > 5s — зафиксировать РИСК в
      reports/ + notepad (влияет на таймауты T8/T9)

## 3. Индексер: модуль реранка (`rerank.py`) — CrossEncoder-обёртка

- [x] 3.1 `Reranker(model_name, *, device=None, max_length=512)`: ленивая
      загрузка модели при первом `score()` под `threading.Lock` (двойная
      загрузка при конкаррентном первом вызове невозможна), явная
      sigmoid-активация
- [x] 3.2 `score(query, documents) -> list[float]` ∈ [0,1] — батч-вызов
      CrossEncoder; ошибка загрузки → исключение с русским сообщением
      (имя модели + подсказка)
- [x] 3.3 config.py секция `[search]`: `rerank_model: str | None = None`
      (None = реранк недоступен); дефолты существующих полей не меняются
- [x] 3.4 Тесты `tests/unit/test_rerank.py` (фейковый CrossEncoder):
      happy, lazy-once, lock-concurrency (8 потоков → 1 загрузка),
      error-message, sorting; integration-тест с реальной моделью под
      `@pytest.mark.skipif` (проверка HF-кэша)
- [x] 3.5 `uv run ruff check src/ tests/` — 0 ошибок; search.py /
      faiss_store.py / sqlite_store.py БЕЗ изменений

## 4. Штурман: контракты — RagConfig v2 + ChatRequest overrides + валидация

- [x] 4.1 `AppConfig.kt` RagConfig v2: `rerank=false`, `threshold=null`,
      `kBefore=null`, `rewrite=false`, `rewriteTimeoutMs=8000`; дефолты
      существующих полей (url/k/timeoutMs/maxBlockChars) и K_MAX=20 не
      меняются
- [x] 4.2 `Models.kt`: `RagFileConfig` v2 (nullable-поля) + маппинг в
      `applyFile`; `ChatRequest` v2: `ragThreshold: Double?`,
      `ragRerank: Boolean?`, `ragRewrite: Boolean?` (null → конфиг);
      ragKBefore НЕ добавляется
- [x] 4.3 Валидация (TDD, сначала красный тест): конфиг — threshold ∈
      (0,1], kBefore ∈ [1,20], rewriteTimeoutMs ≥ 1000; запрос —
      ragThreshold ∈ (0,1], иначе 400; все сообщения по-русски (стиль
      «rag: … должен …»)
- [x] 4.4 `config.example.json`: новые поля rag-секции (JSON без
      комментариев — значения, пояснения в README)
- [x] 4.5 Тесты: расширение `AppConfigRagTest` (граничные 0.0/1.0/1.5,
      kBefore 0/1/21, timeout 999/8000) + роут-тесты валидации overrides

## 5. Штурман: `rag/QueryRewriter.kt` — LLM-рерайт запроса (fail-open)

- [x] 5.1 `interface QueryRewriter { suspend fun rewrite(query: String):
      String }` + реализация через СУЩЕСТВУЮЩИЙ AI-клиент (тот же, что
      handleAgent); temperature=0, maxTokens ~100
- [x] 5.2 Константная русская system-инструкция: «Ты переписываешь
      поисковый запрос пользователя для полнотекстового поиска по
      документации. Верни ТОЛЬКО переписанный запрос одной строкой, без
      пояснений.»
- [x] 5.3 Пост-обработка: strip, снять кавычки, первая строка; пустой
      ответ → оригинал; лог-пара «оригинал → рерайт» (только серверный
      лог, НЕ в ChatResponse/UI)
- [x] 5.4 Fail-open: любой таймаут/ошибка → оригинальный запрос + русский
      warn (конвенция System.err репо); таймаут = rewriteTimeoutMs
- [x] 5.5 `QueryRewriterTest`: фейковый AI-клиент (happy → очищенный
      рерайт, пустой → оригинал, таймаут → оригинал + warn, многострочный/
      кавычечный → очищен); `./gradlew cleanTest test` — зелёный

## 6. Frontend: типы ChatRequest-overrides (без отправки)

- [x] 6.1 `api/types.ts`: `ChatRequest` += `ragThreshold?: number | null`,
      `ragRerank?: boolean | null`, `ragRewrite?: boolean | null`
      (опциональные, зеркало Kotlin; имена 1:1)
- [x] 6.2 `request.ts` НЕ менять — payload по умолчанию байт-в-байт прежний
- [x] 6.3 `request.test.ts`: payload НЕ содержит ключей
      ragThreshold/ragRerank/ragRewrite при дефолтных настройках (и при
      ragEnabled=true)
- [x] 6.4 `npm run verify` — зелёный (341 + ≥1); ноль новых UI-контролов,
      палитра не трогается

## 7. mock-llm: детерминированный rewrite-режим

- [x] 7.1 `mock-llm.py`: распознавание rewrite-промпта (маркер-инструкция
      из system-сообщения — точное строковое соответствие QueryRewriter)
      → `_rewrite_reply()`: ДЕТЕРМИНИРОВАННЫЙ identity-ответ (возврат
      исходного запроса пользователя без изменений)
- [x] 7.2 Проверка ДО `_pick_reply` (приоритет над RAG-маркером);
      комментарий, почему identity (сравнение rewrite on/off проверяет
      механику fail-open, качество — только с реальным LLM)
- [x] 7.3 Тесты в `scripts/test_mock_llm_rag.py`: rewrite → identity;
      приоритет над RAG_MARKER; без маркера — прежний `_pick_reply`;
      существующие 8 тестов проходят без изменений

## 8. Sidecar: пайплайн пост-обработки в serve.py (k_before → rerank → threshold → top-k)

- [x] 8.1 `POST /search` — опциональные параметры: `k_before` (кламп
      [1,50] как k; < k → автоподъём; null → k), `threshold` (финальная
      шкала; не число/вне диапазона → 400 по-русски), `rerank` (default
      false; true без модели → 400 «Реранкер не настроен: запустите serve
      с --rerank-model»)
- [x] 8.2 CLI `--rerank-model` (default none → rerank=true невозможен);
      при заданном — прогрев на старте (отдельного флага прогрета нет);
      device из рекомендации probe (T2); docstring search.py:3 —
      однострочное дополнение
- [x] 8.3 Пайплайн — ЕДИНСТВЕННОЕ место (helper в serve.py):
      `search(k_before)` → rerank под СУЩЕСТВУЮЩИМ lock →
      `score >= threshold` → top-k; score в ответе — финальная шкала,
      round 4; сбой реранка при выполнении → 500 по-русски
- [x] 8.4 v1 byte-identity-тест: sha256 ответа с v1-параметрами ==
      `.omo/evidence/stage2-v1-snapshot.json` (порт-0 тесты)
- [x] 8.5 Тесты (расширение `test_serve.py` + `test_search_stage2.py`):
      E1 (жёсткий threshold → 200 пустой список), E2 (k_before > индекса →
      кламп), E3 (rerank без модели → 400), E4 (2 потока × первый
      rerank-запрос → модель загружена 1 раз), E7 (threshold 0.0/1.0),
      k_before < k → автоподъём, bool threshold → 400; `uv run pytest -q`
      && `uv run ruff check .` — зелёные
- [x] 8.6 `git diff -- pyproject.toml uv.lock` — пусто

## 9. Штурман: интеграция в Routes.kt (RagClient v2 + rewrite-вызов)

- [x] 9.1 `RagClient.search(query, k, kBefore=null, threshold=null,
      rerank=false)` — дефолты сохраняют ВСЕ существующие вызовы
      (lsp-дисциплина, не grep-заменой); `SidecarSearchRequest`: новые
      поля сериализуются ТОЛЬКО если заданы
- [x] 9.2 `handleAgent`: ДО retrieve — если ragWanted и rewrite включён →
      `queryRewriter.rewrite(body.message)` (таймаут rewriteTimeoutMs),
      лог-пара, retrieve с переписанным запросом; retrieve получает
      kBefore/threshold/rerank из overrides или конфига; seed-блок и echo
      — без изменения формата
- [x] 9.3 Fallback-degrade: sidecar 400 «Реранкер не настроен» при
      rerank=true → ОДИН повторный retrieve без реранка + русский warn
      (retry-механика в RagClient НЕ вводится)
- [x] 9.4 Байт-инвариант: при дефолтном конфиге тело sidecar-запроса
      ровно `{"query","k"}` (перехват в тестовом сервере); путь
      ragEnabled=false не трогается
- [x] 9.5 Тесты (расширение `RagRoutesTest`): режимы через overrides,
      байт-инвариант payload, fail-open rewrite (фейк AI падает →
      оригинал), fallback 400-rerank → повтор без реранка, override
      валидация 400; `./gradlew cleanTest test` — зелёный

## 10. Харнесс: `scripts/rag_eval.py` — режимы raw/filtered/rerank (± rewrite)

- [x] 10.1 Режимы через per-request overrides ChatRequest v2: `raw` /
      `filtered` (+ragThreshold, CLI `--threshold` с дефолтом из probe) /
      `rerank` (+ragRerank); флаг `--rewrite` добавляет те же 3 режима с
      `ragRewrite: true` (до 6 прогонов × 10 вопросов)
- [x] 10.2 Отчёт: колонка «Режим» + ТЕГ шкалы на строку («score: cosine» /
      «score: rerank-sigmoid»); summary по режимам: sources_passed %
      (ground truth фазы 1), медиана латентности, число пустых ответов
- [x] 10.3 Обратная совместимость: запуск без новых флагов = прежняя
      2-режимная таблица v1; `eval/rag-questions.yaml` НЕ трогается
- [x] 10.4 Тесты `scripts/test_rag_eval.py`: разбор новой CLI-матрицы
      (с/без --rewrite), тег шкалы, summary-агрегация, payload с
      overrides; `python3 scripts/test_rag_eval.py` — зелёный (22 + ≥4)
- [x] 10.5 Живой прогон: таблица ≥3 режимов × 10 вопросов + summary;
      повторный прогон — идентичные sources/scores (кроме времени)

## 11. E2E все режимы + edge-кейсы + UI QA

> Статус: завершено 2026-10-02 (T11, волна E2E); верификация оркестратором,
> evidence-каталог `.omo/evidence/stage2-final-qa/` заполнен (6 файлов),
> скриншоты подтверждены OCR (Vision, ru+en).

- [x] 11.1 Полный чистый стек с stage2-конфигом
      (`/tmp/opencode/config.rag-stage2.json` — наследник
      config.rag-smoke.json + rag v2-поля); sidecar с `--rerank-model`,
      прогрет
- [x] 11.2 Живые curl-сценарии X-Auth-Token: raw / filtered / rerank /
      rerank+threshold — ragEnabled + retrievedSources в ответе,
      счётчики токенов, бортовой журнал
- [x] 11.3 Edge: E1 (пусто после порога → 200 [], Штурман не инжектит, UI
      без блока), E3 (rerank без --rerank-model → fallback + warn, не
      500), E9 (битая модель → 500 по-русски на /search), E10 (промпт-
      инъекция в rewrite → лог-пара, без деструктивных действий)
- [x] 11.4 Playwright: тумблер RAG → бейдж + блок источников рендерится;
      конфиг жёсткого порога → ответ без блока источников (пустое
      состояние не крашится; скриншоты)
- [x] 11.5 Evidence → `.omo/evidence/stage2-final-qa/`

## 12. Документация + openspec sync

- [x] 12.1 `внешний индексер/README.md` (секция serve): новые параметры
      /search (k_before/threshold/rerank), шкалы score (когда какая), флаг
      `--rerank-model`, цифры из `reports/stage2-probe.md`, примечание о
      размере модели (~2.2 GB, качается при первом использовании)
- [x] 12.2 `README.md` (Штурман, секция RAG): этап 2 (rewrite/threshold/
      rerank), ChatRequest overrides с curl-примерами, rewriteTimeoutMs,
      fail-open-семантика, таблица сравнения режимов из харнесса
- [x] 12.3 `AGENTS.md` (Штурман): 2–3 строки про stage-2 (какие
      файлы/тесты затронуты)
- [x] 12.4 Этот tasks.md: чекбоксы по реальному выполнению;
      `proposal.md` — раздел «Отклонения от плана» (если были)
- [x] 12.5 Формат сверен с `openspec/project.md`; README не меняются вне
      указанных секций (Lock OUT)
