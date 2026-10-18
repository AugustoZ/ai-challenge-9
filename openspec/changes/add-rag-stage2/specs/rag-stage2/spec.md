# Спецификация: RAG Stage 2 (rag-stage2)

## ADDED Requirements

### Requirement: Порог отсечения (threshold) по финальной шкале score

`POST /search` sidecar MUST поддерживать опциональный параметр `threshold`
(число). Отсечение применяется к ФИНАЛЬНОЙ шкале score: косинус ∈ [-1,1]
при `rerank=false`, rerank-sigmoid ∈ [0,1] при `rerank=true`; шкалы MUST NOT
смешиваться. Семантика: чанк остаётся, если `score >= threshold`. Если
`threshold` не число (например, boolean) или вне диапазона активной шкалы
→ 400 с русским сообщением. Если все кандидаты отфильтрованы → 200 с
пустым списком (не 404/500). Без параметра `threshold` поведение MUST быть
байт-в-байт идентичным v1 (sha256 == эталонный снапшот).

#### Scenario: Жёсткий порог — всё отфильтровано
- **WHEN** POST /search с `threshold` выше наивысшего score индекса
  (например 0.999999, rerank=false)
- **THEN** HTTP 200 с пустым списком, никакой ошибки

#### Scenario: Порог не число или вне диапазона
- **WHEN** POST /search с `threshold=true` либо `threshold=1.5` при
  rerank=false (шкала косинуса)
- **THEN** HTTP 400 с русским сообщением о допустимом диапазоне порога

#### Scenario: Без порога — v1-инвариант
- **WHEN** POST /search без параметра `threshold`
- **THEN** тело ответа байт-в-байт как в v1 (sha256 == снапшот
  stage2-v1-snapshot.json)

### Requirement: Режим реранкинга — cross-encoder BAAI/bge-reranker-v2-m3

Sidecar MUST поддерживать опциональный параметр `rerank` (bool, дефолт
false). При `rerank=true` и настроенной модели (CLI `--rerank-model`)
кандидаты MUST быть перескорены CrossEncoder (sentence-transformers,
ЯВНАЯ sigmoid-активация, `max_length=512`, без fp16/bf16 на CPU), а финальный
score — sigmoid-значением ∈ [0,1] (округление 4 знака, как в v1). Загрузка
модели MUST быть ленивой (при первом запросе с rerank) и под lock:
конкурентные первые запросы MUST приводить к ровно ОДНОЙ загрузке. При
`rerank=true` без настроенной модели → 400 с русским сообщением
«Реранкер не настроен». Веса модели MUST NOT коммититься в репо.

#### Scenario: Реранк переупорядочивает результаты
- **WHEN** POST /search с `rerank=true` на sidecar, запущенном с
  `--rerank-model`
- **THEN** все score ∈ [0,1], результаты отсортированы по score
  убывающе, порядок/значения отличаются от raw-cosine режима

#### Scenario: Реранкер не настроен
- **WHEN** POST /search с `rerank=true` на sidecar без `--rerank-model`
- **THEN** HTTP 400 с русским текстом «Реранкер не настроен» и подсказкой
  про `--rerank-model`

#### Scenario: Конкурентные первые запросы — одна загрузка
- **WHEN** два конкурентных запроса с `rerank=true` приходят на холодный
  sidecar
- **THEN** модель загружена ровно один раз, оба запроса завершены успешно

### Requirement: k_before — кандидаты до пост-обработки, порядок пайплайна

`POST /search` MUST поддерживать опциональный параметр `k_before` (int):
число кандидатов, извлекаемых до реранка и порога. `k_before` MUST
клампиться в [1,50] как k; если `k_before < k` — автоподъём до k (не
ошибка); `null` → k. Порядок пайплайна MUST быть инвариантом:
`search(k_before)` → (если rerank) реранк → threshold-фильтр → top-k.
Score в ответе — на финальной шкале, round 4.

#### Scenario: k_before шире k — воронка уже на выходе
- **WHEN** POST /search с `k=3`, `k_before=20`
- **THEN** в ответе не более 3 элементов (k_after = k)

#### Scenario: k_before уже k — автоподъём
- **WHEN** POST /search с `k=10`, `k_before=3`
- **THEN** HTTP 200, в ответе 10 элементов (k_before автоподнято до k),
  никакой ошибки

#### Scenario: k_before больше индекса — кламп
- **WHEN** POST /search с `k_before=50` (или больше), чем чанков в индексе
- **THEN** кандидаты клампнуты в [1,50] / размер индекса, ответ 200 с не
  более чем k элементами

### Requirement: Query rewrite — LLM, fail-open, только в лог

При включённом rewrite (override `ragRewrite` в ChatRequest или значение
из RagConfig) Штурман MUST ДО retrieval переписать запрос через
СУЩЕСТВУЮЩИЙ AI-клиент: temperature=0, константная русская system-инструкция
(«переписать поисковый запрос для полнотекстового поиска по документации,
вернуть ТОЛЬКО переписанный запрос одной строкой, без пояснений»), небольшой
maxTokens (~100). Пост-обработка ответа: strip, снять кавычки, первая
строка; пустой ответ → оригинальный запрос. Любой сбой или таймаут
(rewriteTimeoutMs, дефолт 8000) MUST NOT ломать ход: возвращается
оригинальный запрос, пишется русский warn-лог. Пара «оригинал → рерайт»
MUST попадать ТОЛЬКО в серверный лог; переписанный запрос MUST NOT
возвращаться в ChatResponse и не отображаться в UI.

#### Scenario: Рерайт применяется до retrieval
- **WHEN** ragEnabled=true и rewrite включён, AI-клиент вернул
  переписанный запрос
- **THEN** запрос к sidecar выполнен с переписанным запросом; в серверном
  логе — пара «оригинал → рерайт»; в ChatResponse и UI переписанный запрос
  отсутствует

#### Scenario: Таймаут rewrite — fail-open
- **WHEN** AI-клиент не ответил в пределах rewriteTimeoutMs
- **THEN** HTTP 200, retrieval выполнен на ОРИГИНАЛЬНОМ запросе, в логах
  русский warn, исключение наружу не проброшено

#### Scenario: Пустой ответ AI — оригинал
- **WHEN** AI-клиент ответил пустой строкой
- **THEN** retrieval выполнен на оригинальном запросе, ход успешен

### Requirement: Per-request overrides и RagConfig v2

ChatRequest MUST поддерживать опциональные overrides: `ragThreshold:
Double?`, `ragRerank: Boolean?`, `ragRewrite: Boolean?`; значение null →
соответствующее поле RagConfig. RagConfig MUST получить v2-поля: `rerank`
(дефолт false), `threshold` (дефолт null), `kBefore` (дефолт null),
`rewrite` (дефолт false), `rewriteTimeoutMs` (дефолт 8000). Валидация:
конфиг — threshold ∈ (0,1], kBefore ∈ [1,20], rewriteTimeoutMs ≥ 1000,
русские сообщения в стиле существующих; запрос — ragThreshold ∈ (0,1],
иначе HTTP 400 с русским сообщением. При дефолтном конфиге тело запроса
Штурмана к sidecar MUST содержать ровно ключи `{"query","k"}` — без новых
ключей (v1-инвариант). Per-request override для kBefore НЕ добавляется
(Lock OUT).

#### Scenario: Override порога доезжает до sidecar
- **WHEN** POST /api/chat с `ragEnabled=true`, `ragThreshold=0.8`
- **THEN** тело запроса к sidecar содержит `"threshold":0.8`

#### Scenario: Невалидный override — 400 по-русски
- **WHEN** POST /api/chat с `ragThreshold=1.5` (или -0.1)
- **THEN** HTTP 400 с русским сообщением о допустимом диапазоне порога;
  ответа с контентом нет

#### Scenario: Дефолтный конфиг — v1-тело sidecar
- **WHEN** POST /api/chat с ragEnabled=true, дефолтный конфиг (threshold
  null, rerank false, rewrite false), без overrides
- **THEN** тело запроса к sidecar ровно `{"query":…,"k":N}` — новых ключей
  нет

#### Scenario: Невалидный конфиг отклоняется на старте
- **WHEN** в config.json rag.threshold=1.5 (или kBefore=21,
  rewriteTimeoutMs=999)
- **THEN** приложение не стартует: ошибка валидации с русским сообщением
  про поле

### Requirement: Fail-open — сбой реранка/rewrite не роняет ход

Сбой реранка при ВЫПОЛНЕНИИ (модель сломалась после загрузки) MUST давать
500 с русским сообщением на /search. При 400 «Реранкер не настроен» от
sidecar при включённом в Штурмане rerank Штурман MUST сделать ОДИН
повторный retrieve БЕЗ реранка и записать русский warn (деградация режима,
не общий retry: retry-механика в RagClient НЕ вводится). Сбои rewrite
обрабатываются по требованию «Query rewrite». Общий fail-open sidecar из
фазы 1 (недоступный адрес, таймаут, 5xx → HTTP 200, `retrievedSources=[]`,
warn) MUST остаться без изменений.

#### Scenario: Rerank включён в конфиге, модель не поднималась
- **WHEN** ragEnabled=true, конфиг rerank=true, sidecar запущен БЕЗ
  `--rerank-model`
- **THEN** HTTP 200, в логах sidecar — второй запрос без реранка,
  retrievedSources непуст, в логах приложения русский warn; 500 нет

#### Scenario: Sidecar недоступен (регрессия фазы 1)
- **WHEN** ragEnabled=true, но sidecar не запущен либо не ответил в
  пределах timeoutMs
- **THEN** HTTP 200, ответ как обычно, `retrievedSources=[]`, warn-лог —
  поведение фазы 1 без изменений
