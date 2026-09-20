# Tasks: assistant-invariants

Чек-лист реализации, зеркалит задачи 2–10 плана. Отмечаются по факту,
после реализации и QA; spec при расхождении с кодом правится под код.

## 1. Домен (задача 2)

- [ ] `StoredInvariant` — `@Serializable` data class (id: String, text:
  String), companion-фабрика `create(text)` с UUID, константы `MAX_TEXT =
  300`, `MAX_COUNT = 20`, `normalizeAndValidate(text)` (trim; пустой/
  whitespace-текст и > 300 символов — исключение валидации с русским
  текстом ошибки)
- [ ] `GlobalInvariantStore` — клон `PreferencesStore` минус per-user
  путь: файл `<dataDir>/invariants.json` (форма `{"invariants":[{id,
  text}, …]}`), atomic write (tmp + ATOMIC_MOVE), `@Synchronized`-мутации,
  missing/corrupt → пустой дефолт (не исключение), свой `Json {
  ignoreUnknownKeys = true }`, traversal-гварды; методы `list()` /
  `add(text)` (валидация + отклонение дубля + кап) / `remove(id)`
- [ ] Юнит-тесты `GlobalInvariantStoreTest` (temp dir): roundtrip
  add/list/remove; точный дубль → ошибка; 21-й → ошибка; 301 символ →
  ошибка; whitespace → ошибка; заранее corrupt-файл → пустой список, add
  работает, файл перезаписан; atomic roundtrip

## 2. Стор (задача 3)

- [ ] `Session.invariants: List<StoredInvariant> = emptyList()` с KDoc про
  оба механизма (дефолт поля — back-compat, `ignoreUnknownKeys` —
  rollback)
- [ ] `Entry`: `@Volatile var invariants` (mirror поля состояния);
  `snapshot()` включает поле
- [ ] Интерфейс `SessionStore`: `updateInvariants(sessionId, invariants)` /
  `getInvariants(sessionId)` (mirror updateTaskState/getTaskState);
  реализации в `InMemorySessionStore` (synchronized-мутация Entry +
  snapshot) и `FileSessionStore` (copy + persist; machinery персистентности
  не менять)
- [ ] KDoc на `Branch` дополнен: invariants в снимок ветки не входит
  (состояние общее на сессию, зеркало прецедента); `activeDiverged()` не
  тронут
- [ ] Тесты (SessionStoreTest + FileSessionStoreTest): round-trip через
  оба стора; legacy-JSON-фикстура без поля → emptyList; неизвестный
  sessionId → null/no-op без исключения; branch-снимок не содержит
  invariants

## 3. Контракт (задача 4)

- [ ] `InvariantsPayload(global, session)` + `ChatResponse.invariants:
  InvariantsPayload? = null` в `Models.kt` (KDoc: echo обоих скоупов, null
  когда оба пусты)
- [ ] Зеркало в `frontend/src/api/types.ts`: `Invariant {id, text}`,
  `InvariantsPayload {global, session}`, в `ChatResponse`:
  `invariants?: InvariantsPayload | null`; комментарии-ссылки в обоих
  файлах; `ChatRequest` не трогается (инварианты не отправляются
  клиентом)
- [ ] Задокументирован контракт 5 роутов (таблица в proposal) и
  определены data-bearing места конструирования ChatResponse
  (finalizeResponse, command, main, caller toResponse()) с обоснованием в
  notes
- [ ] Smoke: `./gradlew compileKotlin` и `cd frontend && npm run build` —
  exit 0

## 4. Мок (задача 5)

- [ ] `INVARIANT_MODES` в mock-llm.py: `refuse` (маркер «НЕПРЕЛОЖНЫЕ
  ИНВАРИАНТЫ» в system → отказ с цитированием первого инварианта; маркера
  нет → обычный ротационный ответ), `violate` (безусловно нарушает —
  passthrough), `echo-system` (dump полученных system-сообщений)
- [ ] Ветки режимов в `_pick_reply` после существующих маркеров, до
  ротации; `/control`: допустимые mode = plain | TASK_MODES |
  INVARIANT_MODES, строка допустимых режимов в 400-ответе обновлена
- [ ] Регресс: существующие режимы и SSE-поведение не сломаны
  (planning/execution/validation/done/illegal, delay_ms)

## 5. Сервер (задачи 6–8)

### Задача 6. Инъекция

- [ ] `InvariantPrompts`: `INVARIANTS_MARKER = "НЕПРЕЛОЖНЫЕ ИНВАРИАНТЫ"`,
  константа `REFUSAL_INSTRUCTION`, `renderInvariantBlock(global, session):
  String?` (null при обоих пустых — G8; маркер-заголовок «(нарушать
  запрещено)», секции «Глобальные:»/«Сессия:», сквозная нумерация внутри
  секции, кап ~2000 + эллипсис по образцу PromptBlock)
- [ ] `handleAgent`: `invariantsMessage` строится после
  taskStateMessage, сборка `listOfNotNull(preferencesMessage,
  taskStateMessage, invariantsMessage, memoryMessage)`; комментарий порядка
  (предпочтения → состояние → инварианты → память) обновлён, пункт про
  саб-вызовы сохранён; позиция ПОСЛЕ trim не меняется
- [ ] Тесты `InvariantInjectionTest` (FakeLLMClient, перехват LLMCall):
  маркер + оба текста + `REFUSAL_INSTRUCTION` в captured system; оба
  скоупа пусты → маркера нет; trim-pressure (длинная история) → блок на
  месте
- [ ] `AgentLoop.kt`, `SessionHistory`, промпт-файлы не тронуты (G1)

### Задача 7. Команды

- [ ] Константы: exact `инварианты`/`/инварианты` (проверяются ПЕРВЫМИ),
  префиксы `инвариант `/`/инвариант ` (add), `убери инвариант
  `/`/убери инвариант ` (remove по 1-based номеру, только цифры)
- [ ] `handleInvariantCommand(message, sessionId, userId): ChatResponse?` —
  add (валидация; ошибка → системный ответ с причиной; успех →
  `updateInvariants` + подтверждение + echo), list (сессионные 1..N +
  глобальные без номеров с пометкой «управляются через панель»), remove
  (вне диапазона/не число → подсказка с примером, 200); только с начала
  сообщения; без LLM; поведение истории — точное зеркало
  handleTaskCommand
- [ ] Вызовы в `/api/chat` и `/api/chat/stream` рядом с handleTaskCommand;
  командные ответы содержат invariants echo
- [ ] Тесты `InvariantsCommandTest`: полный цикл add/list/remove с echo и
  FakeLLMClient.calls == 0; «убери инвариант 99»/«убери инвариант абв» →
  подсказка (200); кап/дубль → системное сообщение; «инварианты» не
  съедается add-префиксом; «инвариантность важна», «сделай инвариант х» →
  LLM вызван; регресс: существующие pre-LLM-команды работают как раньше

### Задача 8. API

- [ ] 5 роутов `/api/invariants…` (контракт из proposal): GET `?sessionId`
  (400 без параметра, 404 неизвестная сессия, 200 `{global, session}`),
  POST global, DELETE global/{id}, POST session, DELETE
  session/{sessionId}/{id}; ошибки `{"error": "…"}` на русском
- [ ] Таблица защищённых маршрутов + `AuthInterceptTest`: 5 Triple-записей
  + garbage-token representatives
- [ ] Тесты `InvariantsRoutesTest` (FakeLLMClient, withAuthApp, TestAuth):
  happy CRUD по всем 5; 400-набор (GET без sessionId, пустой текст, 301,
  дубль, 21-й); 404-набор (неизвестная сессия, неизвестный id в обоих
  скоупах); персистентность: глобальный инвариант переживает пересоздание
  store; traversal-гвард id в DELETE-пути

## 6. Фронтенд (задачи 9–10)

### Задача 9. Логика

- [ ] `client.ts`: `fetchInvariants(sessionId)`, `addGlobalInvariant`,
  `deleteGlobalInvariant`, `addSessionInvariant`, `deleteSessionInvariant`
  (по образцу memory-блока; ApiError 40x пробрасывается, не превращается в
  null)
- [ ] `stores/session.ts`: `invariants: InvariantsPayload | null` в
  SessionStateData + `setInvariants` (по образцу setFacts)
- [ ] `useChatStream.applyChatResponse`: приём `data.invariants` →
  `setInvariants(data.invariants ?? null)` — безусловно каждый ход (echo
  командных и обычных ответов обновляет панель без refetch)
- [ ] `useInvariants.ts` (клон useTaskControl): `load` / `addGlobal` /
  `removeGlobal` / `addSession` / `removeSession` + loading/error-состояния
  для панели (ошибки мутаций ловятся и выставляются в error state)
- [ ] buildChatRequest не трогается; тест-инфраструктура не добавляется

### Задача 10. Панель

- [ ] `InvariantsPanel.tsx` + module css: секция «Инварианты»
  (panelSection/sectionCaption), подсписки «Глобальные»/«Сессия» (строки +
  кнопка удаления с aria-label, пустые состояния), формы добавления на
  каждый скоуп (input maxlength=300, hint «До 300 символов, максимум 20»),
  loading/error/retry по образцу PreferencesSection, fetch-on-open,
  sessionId из session store
- [ ] Вставка в `SettingsPanel` между концом блока «Память» и
  `<PreferencesSection/>`
- [ ] CSS: только `var(--token)` из tokens.css, без hex; кнопка удаления
  деактивируется на время запроса; ошибка — инлайн-текст в секции
- [ ] Playwright: секция видна в дровере между Память и Предпочтения,
  add/delete работают на обоих скоупах, fetch-on-open актуализирует
  список; UI-валидация (maxlength, disabled на пустом input)
