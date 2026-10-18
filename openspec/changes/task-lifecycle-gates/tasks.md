# Tasks: task-lifecycle-gates

Чек-лист реализации, зеркалит задачи 2–8 плана. Отмечаются по факту,
после реализации и QA; spec при расхождении с кодом правится под код.
Номера G/E/Q — из proposal.md этого change.

## 1. Домен FSM (задача 2)

- [x] 1.1 `TaskStage.kt`: `PLAN_APPROVED` в enum между PLANNING и EXECUTION
  (порядок: PLANNING, PLAN_APPROVED, EXECUTION, VALIDATION, DONE, PAUSED);
  сериализация по имени, старые JSON читаются как прежде
- [x] 1.2 `TaskStateMachine.kt`: ребро PLANNING→EXECUTION заменено на
  PLANNING→PLAN_APPROVED, добавлено PLAN_APPROVED→EXECUTION; остальные
  рёбра (EXECUTION→VALIDATION; VALIDATION→{EXECUTION, DONE};
  DONE→PLANNING) и механика паузы/резюм без изменений
- [x] 1.3 KDoc-контракт `TaskStateMachine` обновлён: полный новый граф
  (включая same-stage) + пояснение гейта: EXECUTION достижим только через
  PLAN_APPROVED, утверждение — прерогатива пользователя, сервер
  утверждения не детектирует (G2)
- [x] 1.4 `TaskStateMachineTest`: полная цепочка
  null→PLANNING→PLAN_APPROVED→EXECUTION→VALIDATION→EXECUTION→VALIDATION→
  DONE→PLANNING; нелегальный набор (PLANNING→EXECUTION, PLANNING→VALIDATION,
  PLANNING→DONE, PLAN_APPROVED→VALIDATION, PLAN_APPROVED→DONE,
  EXECUTION→DONE, EXECUTION→PLANNING, DONE→EXECUTION, null→EXECUTION,
  PAUSED→EXECUTION напрямую); пауза из PLAN_APPROVED (prevStage) и резюме
  обратно; same-stage обновление; капы длин step/expectedAction
  сохраняются
- [x] 1.5 Тест-пин `TaskStateJsonTest`: два блока ```task-state``` в одном
  ответе — текущее поведение парсера зафиксировано в ассерте с
  комментарием-пином (E2); парсер не правится (G6)

## 2. Эвристика (задача 3)

- [x] 2.1 `TaskStateHeuristic.kt` `stepToward`: арма
  `PLANNING -> null` (фолбэк никогда не покидает PLANNING вперёд, G3),
  добавлена арма `PLAN_APPROVED -> if (target == EXECUTION) EXECUTION
  else null` (по маркерам кода); остальные армы (null, EXECUTION,
  VALIDATION, DONE, PAUSED) без изменений
- [x] 2.2 KDoc объекта дополнен асимметричным правилом: PLANNING для
  фолбэка непроходим вперёд; из PLAN_APPROVED фолбэк умеет
  PLAN_APPROVED→EXECUTION; нелегальное предложение фолбэка — тихий no-op
  без записи отказа (E1, G4)
- [x] 2.3 `TaskStateHeuristicTest`: EXEC-маркеры при stage=PLANNING →
  null (ранее давали EXECUTION — кейсы переписаны в негативные);
  EXEC-маркеры при stage=PLAN_APPROVED → EXECUTION; VALIDATION/DONE-маркеры
  при PLANNING → null; фолбэк никогда не возвращает нелегальное
  предложение; регресс: создание из null/DONE и лестница
  EXECUTION→VALIDATION→DONE работают как прежде

## 3. Стор (задача 4)

- [x] 3.1 `Session.lastRejectedTransition: String? = null` + KDoc по
  back-compat паттерну taskState/invariants: legacy-файлы без поля
  читаются с null (дефолт поля + `ignoreUnknownKeys`), пишется только
  при нелегальном LLM-блоке вне паузы, очищается любым легальным
  переходом, в Branch-снимки не входит (G7)
- [x] 3.2 `Entry`: `@Volatile var lastRejectedTransition`; `snapshot()`
  включает поле; KDoc `Branch` дополнен зеркально taskState
- [x] 3.3 Интерфейс `SessionStore`: `updateTaskRejection(sessionId,
  rejected)` / `getTaskRejection(sessionId)` + реализации в
  `InMemorySessionStore` и `FileSessionStore` (copy + persist) по образцу
  taskState-мутаторов
- [x] 3.4 Тесты (SessionStoreTest + FileSessionStoreTest): round-trip
  через оба стора; legacy-JSON без поля → null; неизвестный sessionId →
  no-op без исключения; очистка до null; Branch-снимок не содержит
  lastRejectedTransition

## 4. Контракт (задача 5)

- [x] 4.1 `ChatResponse.taskRejected: String? = null` в `Models.kt`
  (KDoc: человекочитаемое описание последнего отклонённого перехода; echo
  каждым ходом, пока есть; null когда нет; синхронно с types.ts)
- [x] 4.2 Зеркало `taskRejected?: string | null` в `frontend/src/api/
  types.ts` + комментарий-ссылка в обоих файлах (ручная синхронизация —
  конвенция репо)
- [x] 4.3 Smoke: `./gradlew compileKotlin` и `cd frontend && npm run
  build` — exit 0

## 5. Серверная интеграция (задача 6)

- [x] 5.1 `TaskStatePrompts.kt`: блок состояния дополнен чекпоинт-
  дисциплиной: одна итерация этапа за ответ; вопрос о продолжении в конце
  ответа; при вариативности — пронумерованные варианты; переход только в
  ответ на реплику пользователя и не более одного за ответ; список
  разрешённых переходов (включая два такта: ход PLAN_APPROVED — только
  объявление + «Приступаю к реализации?», без работы)
- [x] 5.2 `TaskStatePrompts.kt`: при непустом `lastRejectedTransition` —
  строка «ПОСЛЕДНИЙ ПРЕДЛОЖЕННЫЙ ПЕРЕХОД ОТКЛОНЁН: …» с перечнем
  разрешённых переходов и инструкцией объяснить причину; инъекция
  эфемерная, в `Session.messages` не попадает
- [x] 5.3 `Routes.kt` `finalizeResponse`: запись отказа — только нелегальный
  LLM-блок вне паузы (G4, E3, E7: без записи при создании задачи и
  same-stage); любой легальный переход (LLM или фолбэк) очищает отказ;
  фолбэк с null-результатом `apply()` — тихий no-op без записи; порядок
  существующих мутаций (стрип → fresh read → paused-wins → apply → echo)
  сохраняется
- [x] 5.4 `Routes.kt` `finalizeResponse`: echo `taskRejected` = свежее
  `getTaskRejection` после мутаций, каждым ходом; существующий echo
  taskState сохраняется (Q5)
- [x] 5.5 Routes-тесты (FakeLLMClient, без сети, Q1): нелегальный блок →
  200, stage прежний, `taskRejected != null`; следующий промпт содержит
  «ОТКЛОНЁН» и разрешённые переходы; легальный переход очищает отказ;
  эвристика с код-маркерами при planning без блока — тихий no-op; пауза в
  полёте — отказ не пишется; отказ переживает паузу/резюм; «нет,
  переделай» без блока — без перехода и без отказа (E6)
- [x] 5.6 `handleAgent`: инъекция идёт в существующий слот seed; новых
  слотов нет, комментарий порядка не меняется

## 6. Фронтенд (задача 7)

- [x] 6.1 `useChatStream.ts` `applyChatResponse`: приём
  `data.taskRejected` каждым ходом (по образцу `data.taskState`) →
  `stores/session.ts`: state + `setTaskRejected`
- [x] 6.2 `StatusLine.tsx` (+ CSS module): метка PLAN_APPROVED → «План
  утверждён» в карте этапов; строка «Переход отклонён: <taskRejected>»
  при непустом отказе, не рендерится при null; цвета только из
  tokens.css (`:root`), CSS-ellipsis; без `as any`/`@ts-ignore`/
  console.log (Q2)
- [x] 6.3 Отметить выполненные пункты раздела 6 этого tasks.md по факту

## 7. Мок (задача 8)

- [x] 7.1 `scripts/mock-llm.py`: режимы `plan-approve` (блок
  stage=PLAN_APPROVED, expected_action «Приступаю к реализации?»; ответ —
  объявление без работы, заканчивается вопросом), `start-execution`
  (блок stage=EXECUTION; фрагмент работы + вопрос о продолжении),
  `jump-approval` (нелегальный блок stage=EXECUTION из planning),
  `checkpoint` (ответ-вопрос без блока)
- [x] 7.2 `/control`: допустимое множество mode и строка списка в
  400-ответе расширены; скрипт остаётся stdlib-only
- [x] 7.3 Регресс: существующие режимы (planning/execution/validation/
  done/illegal, delay_ms) и SSE-поведение не сломаны (Q4)
