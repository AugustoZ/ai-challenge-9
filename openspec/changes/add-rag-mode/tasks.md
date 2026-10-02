# Задачи: add-rag-mode

Чек-лист зеркалит задачи 2–12 плана shturman-rag-mode (нумерация сохранена для
слеживаемости). Все пункты выполняются TDD; агент не коммитит — пользователь
коммитит вручную.

## 2. `gwclub serve` — HTTP-sidecar поиска в индексере (gw-club-indexer)

- [x] 2.1 Модуль `serve.py`: `build_server` (ThreadingHTTPServer) + `POST /search`
      → 200 JSON `[{chunk_id, score, source, title, section, content}]` с полным
      content (сборка из SQLite row_map); k из тела или `--k` (default 5), кламп [1,50]
- [x] 2.2 Ошибки: пустой/whitespace query → 400 `{"error": "Пустой запрос"}`;
      неизвестный путь/метод → 404; сбой индекса → 500 `{"error": "<msg>"}`
- [x] 2.3 Preload при старте (config + embedder + FAISS + row_map), warm-up
      embed, `threading.Lock` вокруг embed+search; запуск из корня репо
      индексера (относительный index_dir) — задокументировано в docstring
- [x] 2.4 CLI-подкоманда `serve`: `--strategy` (default null → конфиг), `--host`
      (127.0.0.1), `--port` (18700), `--k` (5), `--config`; wiring по образцу
      существующих subparser'ов
- [x] 2.5 pytest (офлайн, mock-эмбеддер, tmp-индекс): ≥6 тестов (200+content,
      400, 404, кламп, warm-up-лог, параллельные запросы); `uv run ruff check` — 0

## 3. Kotlin: rag-секция конфига + RagConfig

- [x] 3.1 `Models.kt`: `RagFileConfig` (url/k/timeoutMs/maxBlockChars — все
      nullable) + `FileConfig.rag: RagFileConfig? = null` (по образцу memory)
- [x] 3.2 `AppConfig.kt`: `RagConfig` (url=null, k=5, timeoutMs=10000,
      maxBlockChars=6000); маппинг в `applyFile`; `validate()` — url не пуст,
      k∈[1,20], timeoutMs≥1000, maxBlockChars≥500, русские тексты ошибок
- [x] 3.3 Тесты: старый config.json без rag валиден (регрессия!); частичная
      секция → дефолты; невалидные значения → падение старта с русским текстом
- [x] 3.4 `config.example.json`: валидная секция rag «как есть» (JSON не
      поддерживает комментарии; url http://localhost:18700/search; тест
      «поставляемый пример конфига остаётся валидным» проходит)

## 4. Kotlin: RagClient + RagPrompts + RetrievedSource

- [x] 4.1 `rag/RetrievedSource.kt`: chunkId/source/title/section/score/snippet
      (≤200 символов) — БЕЗ полного content; плюс `Passage` (content +
      метаданные) и `Passage.toRetrievedSource()` (контракт задачи 6)
- [x] 4.2 `rag/RagClient.kt`: интерфейс + `KtorRagClient` (ktor-client-cio,
      POST config.url, явный requestTimeout, парсинг массива sidecar'а;
      k≤0 клампится в 1 в клиенте). Контракт финализирован в задаче 6:
      `search` возвращает `List<Passage>` (полный content), snippet
      (content.take(200)) считается в `Passage.toRetrievedSource()`
- [x] 4.3 `rag/RagPrompts.kt`: `RAG_MARKER = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"` +
      `buildRagBlock` (заголовок, инструкция цитировать `[файл · раздел]`,
      нумерация `[N]`, суммарный кап maxChars, разметка без кавычек/фенсов)
- [x] 4.4 `rag/RagService.kt`: `retrieve` fail-open (runCatching + warn-лог +
      emptyList, по образцу runJudge)
- [x] 4.5 Тесты: фейк-HTTP через com.sun.net.httpserver (порт 0); мёртвый порт
      → emptyList без исключения; блок (маркер, нумерация, кап, чанк с ```
      не ломает структуру); k=0/отрицательный — кламп

## 5. Frontend контракт: ragEnabled (types + settings + request)

- [x] 5.1 `api/types.ts`: `ChatRequest.ragEnabled?: boolean | null`;
      `ChatResponse` += `ragEnabled?: boolean`, `retrievedSources?:
      RetrievedSource[]`; `interface RetrievedSource` (зеркало Kotlin)
- [x] 5.2 `stores/settings.ts`: `ragEnabled` (default false, coerce, сеттер,
      partialize) — точная копия цикла memoryTools
- [x] 5.3 `features/send/request.ts`: `ragEnabled` в Pick + payload — явный
      boolean в обоих состояниях
- [x] 5.4 Vitest: request.test.ts (on/off) + settings.test.ts (default false,
      coerce, persist round-trip)

## 6. Kotlin: handleAgent RAG-интеграция (retrieval + инжект + echo)

- [x] 6.1 seam `ragFactory` в `aiRoutes` (по образцу clientFactory);
      клиент создаётся один раз
- [x] 6.2 `handleAgent`: при `ragEnabled==true && config.rag.url!=null` —
      retrieval (query = последнее user-сообщение; пустое → скип), system-
      сообщение с RAG-блоком ПОСЛЕ memoryMessage (последним в seed)
- [x] 6.3 `ChatResponse` (Models.kt): `ragEnabled: Boolean` +
      `retrievedSources: List<RetrievedSource>` с `@EncodeDefault(ALWAYS)`
- [x] 6.4 Тесты: инжект+echo (RAG_MARKER в fake.calls.last().messages,
      retrievedSources.size); off-путь (sidecar не вызывается, sources=[]);
      fail-open (мёртвый порт → 200, sources=[]); ключи JSON при выключенном
      RAG; combo memoryTools+rag (оба блока, rag последним); stream: Final-
      событие несёт retrievedSources
- [x] 6.5 Регрессия: `ragEnabled=false` — все существующие тесты зелёные без
      правок ассертов

## 7. Frontend UI: тумблер + блок «Источники» + бейдж

- [x] 7.1 Тумблер «Поиск по базе (RAG)» в SettingsPanel (рядом с «Инструменты
      памяти агента»), персистится в localStorage
- [x] 7.2 `features/sources/SourcesReport.tsx` (+module.css): клон JudgeReport
      (`<details>/<summary>`, амбер-summary, body в `--well`), строки
      `[N] source · section · score` (score в 3 знака), только токены tokens.css
- [x] 7.3 `features/chat/Entry.tsx`: `JournalEntry.rag` (enabled + sources),
      рендер блока под ответом, бейдж-счётчик источников в шапке записи
- [x] 7.4 `useChatStream.applyChatResponse`: маппинг
      `rag: data.ragEnabled ? { enabled: true, sources: data.retrievedSources ?? [] } : undefined`
- [x] 7.5 Vitest: чистая функция форматирования строки источника
      (`features/sources/format.test.ts`)

## 8. mock-llm: RAG_MARKER-детекция (детерминированный RAG-реплай)

- [x] 8.1 Константа `RAG_MARKER` (точная копия Kotlin) в mock-llm.py; проверка
      в `_pick_reply` ПОСЛЕ FACTS/SUMMARY-маркеров и ДО rotation
- [x] 8.2 Детерминированный реплай, цитирующий источники (парсинг строк
      `[N] source: <файл> · раздел: <section>`), без ```-фенсов
- [x] 8.3 Тесты: маркер+plain → стабильный реплай с именем source; refuse
      приоритетнее маркера; без маркера — прежняя ротация не сломана

## 9. Контрольный набор: eval/rag-questions.yaml (10 вопросов)

- [x] 9.1 10 разнотематичных вопросов по корпусу gw-club-docs (покрыть ≥5
      разных source-файлов; seed — 30 FAQ из groundtruth.yaml индексера)
- [x] 9.2 У каждого: question + expectedSubstrings (1–3, проверены grep'ом по
      тексту корпуса) + expectedSources (1–2) + комментарий `# ожидание: ...`
- [x] 9.3 Валидность: для каждого вопроса `gwclub search --strategy structure
      -k 5 --json` находит expectedSource в top-5
- [x] 9.4 YAML валиден (yaml.safe_load, 10 элементов, полные ключи)

## 10. Харнесс сравнения: scripts/rag_eval.py

- [x] 10.1 CLI: `--base-url` (127.0.0.1:8099), `--model` (обязательный),
      `--provider`, `--questions` (eval/rag-questions.yaml),
      `--judge/--no-judge`, `--out` (reports/rag-comparison.md),
      `--rag-url` (127.0.0.1:18700/search, диагностика «sidecar не
      запущен»); auth через POST /api/auth; precheck sidecar
- [x] 10.2 Прогон 10 вопросов × 2 режима через POST /api/chat; авто-проверки:
      expectedSubstrings (case-insensitive) в ответе; expectedSources — в
      ответе или в retrievedSources (для RAG-режима)
- [x] 10.3 Отчёт: markdown-таблица 10×2 (вопрос | режим | проверки | источники
      | Judge | токены | время) + строка итогов; выход в файл и stdout;
      exit code всегда 0
- [x] 10.4 Юнит-тесты чистых функций (парсинг yaml, check_substrings,
      check_sources, format_report) без сети
- [x] 10.5 Прогон на mock-LLM: 20 строк, RAG-строки со sources ✓,
      детерминизм при повторе; sidecar выключен → диагностика, без traceback

## 11. E2E wiring + README Штурмана (раздел RAG)

- [x] 11.1 E2E: sidecar (18700) + mock-llm (18080) + приложение
      (config.dev.json, 8099): ragEnabled=true → retrievedSources непустой,
      RAG-маркер в запросе к LLM (детерминированный mock-реплай с источниками);
      ragEnabled=false → `retrievedSources:[]`, ключ присутствует
- [x] 11.2 README.md (Штурман): раздел «RAG: поиск по базе знаний» — тумблер,
      config-секция rag (пример), запуск sidecar (из корня индексера),
      контрольный набор + харнесс (команды), ограничения v1
- [x] 11.3 `config.example.json`: валидная секция rag (пример url)

## 12. Финальная документация: README индексера + AGENTS.md + openspec

- [x] 12.1 `gw-club-indexer/README.md`: раздел «gwclub serve» — назначение,
      флаги, формат ответа (полный content), запуск из корня репо, lock,
      warm-up, примеры curl
- [x] 12.2 `AGENTS.md` (Штурман): строка в WHERE TO LOOK (RAG → `rag/` +
      `Routes.handleAgent`) и в NOTES (контур: sidecar, инжект после trim,
      echo-поля, fail-open)
- [x] 12.3 Этот tasks.md: чекбоксы проставлены по реальному выполнению;
      proposal.md дополнен отклонениями от плана (если были)
- [x] 12.4 Формат сверен с `openspec/project.md`
