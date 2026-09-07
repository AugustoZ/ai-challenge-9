# План внедрения P0: агентные возможности «Штурмана»

> Статус: утверждён, не начат.
> Цель P0: память диалога + инструменты + агентный цикл.
> Принцип: каждый шаг закладывает заготовку под P1 (стриминг, персистентность, отмена), не реализуя их.

## Ключевые решения

| # | Решение | Обоснование |
|---|---------|-------------|
| 1 | **Контракты первыми**: `interface LLMClient`, `ChatResult + toolCalls/finishReason`, `OpenAIMessage + toolCalls/toolCallId` | Ретрофит `ChatResult`/`OpenAIMessage` позже = правка каждого вызова. Сейчас — единственный момент сделать это бесплатно |
| 2 | **Сессии серверные** за интерфейсом `SessionStore`; `sessionId` выдаёт сервер, возвращается в `ChatResponse` | P1-персистентность = замена InMemory-реализации, контракт не меняется. Клиентскую историю переделывать дороже |
| 3 | **Нативные tools-first, но `supportsTools=false` по умолчанию** + текстовый fallback | vLLM без `--enable-auto-tool-choice` молча игнорирует `tools` — агент «успешно» ничего не делает. GPUStack флаги сам не ставит. Безопасный дефолт — фолбэк |
| 4 | **`reasoningMode="agent"`** на существующем `/api/chat`; события через **sink** (`suspend (AgentEvent) -> Unit`), не через `List` | Отдельный эндпоинт дублирует валидацию/judge/ExchangeLog. Sink — заготовка под SSE: P1 просто начнёт стримить тот же поток |
| 5 | **OpenCode в P0 остаётся one-shot** | Его цикл с инструментами транслировать в `AgentEvent` — задача P1 (у `opencode serve` уже есть SSE `GET /event` с `message.part.updated`) |
| 6 | **Docker — корпус агента, не источник агентности**: named volume под данные + sandbox-контейнер для опасных инструментов | Агентность дают инструменты + цикл (Фазы 2–3); докер даёт персистентность, изоляцию и безопасное исполнение действий |

## Фаза 0 — Контракты и тестовый каркас (~2 ч)

**Файлы:** `build.gradle.kts`, `model/Models.kt`, `client/AIClient.kt`, новый `client/LLMClient.kt`

1. `build.gradle.kts`: добавить `kotlin("test")` + JUnit5 (сейчас тестовых зависимостей ноль)
2. Извлечь `interface LLMClient { suspend fun ask(call: LLMCall): ChatResult }`; `AIClient` его реализует — шов для фейков в тестах
3. `Models.kt`:
   - `OpenAIMessage` + `toolCalls: List<ToolCall>?`, `toolCallId: String?` (для `role="tool"`)
   - `OpenAIRequest` + `tools: List<ToolSpec>?`
   - `ChatResult` + `toolCalls: List<ToolCall> = emptyList()`, `finishReason: String? = null`
4. **Критичный фикс** в `executeOpenAI`: парсить `tool_calls` ДО проверки пустого контента
   (`AIClient.kt` ~198–203) — иначе валидный tool-call ответ (content=null) падает с
   «API вернул пустой ответ». Сейчас `ignoreUnknownKeys` молча выбрасывает `tool_calls`
5. Тесты: парсинг `tool_calls`, фейковый `LLMClient`

## Фаза 1 — Память диалога (~3 ч)

**Файлы:** новый `session/SessionStore.kt`, `Routes.kt`, `Models.kt`, `script.js`

1. `interface SessionStore` + `InMemorySessionStore` (ConcurrentHashMap);
   `Session(id, messages, createdAt, provider, model)` — сериализуемая доменная модель
2. `/api/chat`: без `sessionId` → создать сессию; контекст = `[system] + история + [user]`;
   после ответа дописать `user` + `assistant`
3. `ChatResponse` + `sessionId`; фронт кладёт его в localStorage (ключ `ai-assistant.settings.v1`)
   и отдаёт обратно
4. `DELETE /api/session/{id}` — сброс диалога
5. **Правила триммирования** (частая точка 400-х от API):
   - резать историю только по границам user-сообщений — не разрывать пары
     `assistant(tool_calls) → role="tool"`
   - запас: история + новый запрос + «Топливо» (maxTokens) должны влезать в окно модели
   - оценка токенов: `chars/2.5` для русского (DeepSeek ~2–3 символа/токен, `chars/4` занижает)
6. История подключается только к одиночным режимам (`default/direct/step_by_step/agent`);
   TEAM и Prompt-to-Prompt остаются stateless — конвейеры множат промты на роли,
   история там утроит расход и ломает семантику
7. Фронтенд: кнопка «Новый диалог»

## Фаза 2 — Инструменты (~4 ч)

**Файлы:** новый пакет `tools/`, `AIClient.kt`, `config/AppConfig.kt`

1. `interface Tool { val name; val description; val parametersJsonSchema; suspend fun execute(args: String): String }` + `ToolRegistry`
2. Встроенные (минимум для проверки цикла):
   - `current_datetime` — тривиальный
   - `calculator` — shunting-yard ~60 строк, без сторонних зависимостей
   - `http_get` — кап размера ответа, таймаут, блок private/loopback-диапазонов (~10 строк, защита от SSRF)
3. Два транспорта за одним интерфейсом:
   - **Native**: `supportsTools=true` → `OpenAIRequest.tools`, парсинг `tool_calls`
   - **Fallback-промт**: `false` → system-промт со схемами инструментов; модель отвечает
     либо ```json {"tool":…, "arguments":…}``` (парсим, исполняем), либо финальным ответом
     (парсинг не удался = это ответ)
4. `ProviderConfig` + `supportsTools: Boolean = false`; поле в `config.example.json`
5. Если API вернул 400 с упоминанием tools → показать сырую ошибку через существующий
   `describeError` (диагностика = «включи флаги бэкенду GPUStack», а не отладка)

## Фаза 3 — Агентный цикл (~3 ч)

**Файлы:** новый `agent/AgentLoop.kt`, `model/ReasoningMode.kt`, `Routes.kt`

1. `ReasoningMode.AGENT("agent")` — помнить: `fromId` молча падает в DEFAULT на опечатке
2. `AgentLoop.run(state, onEvent: suspend (AgentEvent) -> Unit)` — НЕ возвращает список, эмитит в sink (заготовка SSE)
3. `AgentEvent` — sealed + `classDiscriminator="type"`:
   `StepStarted / ToolCalled / ToolResult / AssistantMessage / Final`
4. Гварды:
   - `maxSteps = 8`
   - общий дедлайн `withTimeout` (~2–3 мин): иначе 8 шагов × 60 с таймаут провайдера = 8+ минут спиннера
   - бюджет шага — по образцу TEAM (`maxTokens / шаги`)
   - детект зацикливания: 3 одинаковых вызова (tool+args) → принудительный финал
5. **Правило ошибок инструментов**: упавший инструмент → `role="tool"` с текстом ошибки модели,
   цикл продолжается. Без этого один плохой `http_get` убивает прогон
6. Цикл — в корутине запроса (не `GlobalScope`): P1-отмена = отмена Job запроса
7. `Routes.kt`: собирает события в `ChatResponse.events[]` (пусто у остальных режимов)

## Фаза 4 — Фронтенд (~2 ч)

**Файлы:** `index.html`, `script.js`

1. Радиокнопка «Агент» в «Способ рассуждения» (+ подпись в `MODE_LABELS`)
2. `sessionId` в localStorage, отправка в `ChatRequest`, кнопка «Новый диалог» → `DELETE /api/session/{id}`
3. Рендер `events[]`: сворачиваемый блок «Ход работы агента» под ответом — вызовы инструментов
   и результаты, стилистика Режима ТО
4. Токены — сумма всех шагов (механика `sumTokens` уже есть)

## Фаза 5 — Docker-упаковка: корпус агента (~1.5 ч)

**Текущее состояние:** Dockerfile (двухстадийная сборка, запуск от **root**) и docker-compose
(только `config.json` read-only) уже есть. Добавляем данные и изоляцию.

**Файлы:** `Dockerfile`, `docker-compose.yml`, `config/AppConfig.kt`, `config.example.json`

### Volumes под сессии (заготовка P1-персистентности)

1. `Dockerfile`: `ENV AI_DATA_DIR=/app/data` + `RUN mkdir -p /app/data`
2. `AppConfig`: `dataDir` (приоритет: env `AI_DATA_DIR` > config.json > пусто = in-memory only)
3. `docker-compose.yml`: named volume `aiweb-data:/app/data` рядом с существующим
   read-only монтированием конфига
4. В P0 `SessionStore` остаётся InMemory (Фаза 1) — volume монтируется и каталог создаётся,
   но пуст. P1-реализация (файл/SQLite) просто начнёт писать в `AI_DATA_DIR`
   **без единой правки compose**
5. Non-root user в Dockerfile (сейчас контейнер от root): `useradd`, `chown /app/data`, `USER` —
   минимальная гигиена до появления инструментов с эффектами

### Sandbox-требования к инструментам

**Классификация по риску (что где исполняется):**

| Класс | Инструменты | Где исполняется |
|---|---|---|
| Вычисления без эффектов | `current_datetime`, `calculator` | In-process, безопасно |
| Сетевой доступ | `http_get` | P0 — in-process с гардами; sidecar — при расширении |
| Исполнение кода / файловый доступ | будущие `run_code`, `read_file` | **Только** sandbox-контейнер, никогда in-process |

**Гарды `http_get` in-process (P0, детализация к Фазе 2):**

- блок private/loopback/link-local: `10/8`, `172.16–31`, `192.168/16`, `127/8`,
  `169.254/16` (cloud metadata), `::1`, `localhost`, `*.local`, `*.internal`
- только схемы `http`/`https` (запрет `file://`, `data://`)
- кап размера ответа (2 МБ), таймаут, редиректы ≤ 3 **с повторной проверкой цели
  на каждом хопе** (иначе редирект на `169.254.169.254` обходит блок)

**Sandbox-контейнер (P1, в P0 только спецификация):**

- отдельный сервис `tool-sandbox` в docker-compose с `profiles: ["sandbox"]` —
  не стартует по умолчанию
- связь с `aiweb` по внутренней docker-сети, порты наружу не публикуются
- требования: non-root, read-only rootfs, `cap_drop: ALL`, docker socket не монтируется,
  лимиты mem/cpu/pids, сеть `internal: true` + явный egress allowlist,
  таймаут на стороне оркестратора (HTTP-вызов к sandbox)

**Правило доверия (критичное):**

Аргументы инструментов приходит **от LLM** → это ненадежный ввод (промпт-инъекции
могут прийти через загруженный контент). Перед исполнением — валидация по JSON-схеме
инструмента; URL из `http_get` дополнительно проходит гарды. Ошибка валидации →
`role="tool"` с текстом ошибки (правило Фазы 3), не падение цикла.

## P2 волна 1 — Sandbox-исполнение и инструменты из конфига

**Объём:**
1. **Инструменты из конфига** — секция `"tools"` в config.json: вкл/выкл встроенных
   (`current_datetime`, `calculator`, `http_get`, по умолчанию true) и подключение
   sandbox-инструментов. `ToolRegistry.forConfig()` — точка регистрации из плана;
   отключённый инструмент не виден модели вообще (не попадает в specs()).
2. **`run_code`** — Python-код в sandbox-контейнере: HTTP-вызов оркестратора с таймаутом
   (clamp 1..60, HTTP-таймаут = таймаут + 5 с), сервер ставит `resource`-лимиты
   (RLIMIT_AS 512 МиБ, CPU, FSIZE, NPROC), убивает группу процессов по таймауту,
   капы stdout/stderr 64 КиБ. CWD — рабочая папка.
3. **`read_file`** — чтение файла из рабочей папки sandbox (только она): отказ
   абсолютных путей и `..`, отказ симлинков наружу, кап 2 МиБ. Двойная валидация —
   на клиенте (Kotlin) и на сервере (python).
4. **Сеть** — `sandbox` (`internal: true`): aiweb в двух сетях (интернет для LLM API +
   internal для sandbox), sandbox только в internal — полный запрет egress =
   строжайший allowlist. Порты по-прежнему не публикуются, docker socket не монтируется.
5. **Валидация аргументов по JSON-схеме до HTTP** (правило доверия: аргументы от LLM);
   sandbox недоступен → `ToolResult(ok=false)` с человеческим текстом, цикл продолжается.

**Контракт HTTP оркестратор↔sandbox:** `POST /run {language, code, timeoutSeconds}` →
`{ok, exitCode?, stdout?, stderr?, durationMs?, error?}`; `POST /read {path}` →
`{ok, content?, truncated?, error?}`; ошибки исполнения — 200 с `ok:false`, битый
запрос — 400.

**Отложено в P3:** auth-токен на `/api/*`, retry/backoff на 429/5xx, OpenCode
SSE-трансляция, `write_file`/список файлов, egress-allowlist с выборочным доступом.

## P1 — Стриминг, отмена, персистентность

**Объём (ядро P1 из гэп-анализа):**
1. **Персистентность сессий** — `FileSessionStore` в `AI_DATA_DIR` (атомарная запись
   tmp+rename, выселение по `MAX_SESSIONS` с удалением файлов, устойчивость к битым
   файлам); выбор реализации фабрикой `sessionStoreFor(dataDir)` в точке сборки
   `Routes.aiRoutes` — маршруты не меняются (заготовка Фазы 1).
2. **Стриминг SSE** — `POST /api/chat/stream`: тот же `ChatRequest`, ответ — кадры
   `data: <JSON>\n\n` (Ktor 2.3.12 без плагина SSE — ручной `respondTextWriter`).
   Конверт `StreamEvent`: `agent` (живые `AgentEvent` из sink Фазы 3) → `final`
   (полный `ChatResponse`) + `status`/`error`/`cancelled` для остальных режимов.
   Фронтенд: стрим через `fetch`+`getReader`, живой блок «Ход работы агента»,
   fallback на блокирующий `/api/chat` до первого кадра.
3. **Отмена** — `ChatRequest.requestId` (клиентский UUID) + реестр активных Job;
   `POST /api/cancel {requestId}` → `job.cancel()`; в стриме — кадр `cancelled`
   и чистое закрытие. Кнопка «Стоп»: `AbortController` + `/api/cancel`.

**Контракт событий:** дискриминатор `"type"`; `final.response` — тот же `ChatResponse`,
что вернул бы `/api/chat` (events уже внутри) — фронтенд рендерит финал существующим
путём `addBotMessage`.

**Отложено в P2:** sandbox-исполнение кода/файлов (нужны сами инструменты `run_code`/
`read_file`), трансляция SSE `opencode serve` в `AgentEvent`, инструменты из конфига.

## Заготовки под P1 (встроены дизайном, не кодом)

| P1-задача | Заготовка из P0 |
|---|---|
| Персистентность | `SessionStore` — интерфейс; замена impl без правки маршрутов |
| Стриминг SSE | `onEvent`-sink; P1 = `/api/chat/stream`, стримящий те же `AgentEvent` |
| Отмена запроса | Цикл в корутине запроса; `/api/cancel` = отмена Job |
| Инструменты из конфига | `ToolRegistry` — точка регистрации |
| Агентные события OpenCode | Трансляция SSE-событий `opencode serve` (`GET /event`: `message.part.updated`, `session.idle`) в `AgentEvent` вместо собственного цикла |
| Персистентность в Docker | Volume `aiweb-data:/app/data` + `AI_DATA_DIR`; P1-impl пишет туда без правки compose |
| Sandbox инструментов | Классификация риска + сервис `tool-sandbox` (`profiles: ["sandbox"]`), включается с появлением code/file-инструментов |

## Факты по стеку (проверено по исходникам и документации)

- **vLLM**: `tools` без флагов запуска → молча игнорируется (обычный текстовый ответ,
  verified в `chat_completion/serving.py`). Парсеры для DeepSeek: `deepseek_v3`, `deepseek_v31`
  (+ `deepseek_v32`/`deepseek_v4`). Нужны флаги: `--enable-auto-tool-choice --tool-call-parser=<имя>`
- **GPUStack**: пробрасывает `tools` в бэкенд; для vLLM-деплоя флаги добавляются вручную
  в параметры модели; llama-box (GGUF) — включено по умолчанию для поддерживающих моделей
- **OpenCode serve**: `POST /session/:id/message` — блокирующий и НЕ принимает
  temperature/max_tokens (они на уровне AgentConfig); прогресс — через SSE `GET /event`

## Верификация

1. `./gradlew build` — юнит-тесты (фейковый `LLMClient`: цикл из 2 шагов с tool-call,
   fallback-парсинг, триммирование не рвёт пары tool-сообщений)
2. Smoke: два запроса подряд с `sessionId`, второй — «а теперь подробнее про это» →
   модель отвечает в контексте
3. `reasoningMode=agent` + «какая сейчас дата?» → в логе виден вызов `current_datetime` → финальный ответ
4. Fallback: `supportsTools=false` → инструмент вызывается через промт-протокол
5. Детект зацикливания: задача без решения упирается в 3 повтора → внятный финал, а не зависание
6. Docker: `docker compose up` → внутри контейнера `/app/data` существует и доступен на запись
   (не root); `docker compose exec aiweb touch /app/data/.keep`, затем
   `docker compose down && up` → `.keep` на месте (volume переживает пересоздание)

## Оценка и порядок

~2–2.5 рабочих дня суммарно. Порядок: **0 → 1 → 2 → 3 → 4 → 5**
(Фазы 1 и 2 можно параллелить после Фазы 0; Фаза 5 независима от 2–4 —
можно делать параллельно с Фазой 1).
