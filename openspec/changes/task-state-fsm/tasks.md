# Tasks: task-state-fsm

Чек-лист реализации, зеркалит задачи 2–6 плана. Отмечаются по факту,
после реализации и QA; spec при расхождении с кодом правится под код.

## 1. Домен (задача 2)

- [x] `TaskStage` (enum: PLANNING, EXECUTION, VALIDATION, DONE, PAUSED) и
  `TaskState` — `@Serializable` data class (stage, step, expectedAction,
  prevStage?) с капами на входе в автомат: step ≤ 120, expectedAction ≤ 200
  символов
- [x] `TaskStateMachine` — чистый класс с таблицей рёбер: null→planning;
  planning→execution; execution→validation; validation→execution;
  validation→done; done→planning; любой не-paused этап (включая done)→paused
  (запомнить prevStage); paused→prevStage; переход «в тот же этап» легален
  (обновляются step/expectedAction); всё прочее → no-op. Методы `apply`,
  `pause`, `resume`
- [x] `TaskStateJson` — парсер/стриппер fenced-блока ```task-state``` из
  текста: выигрывает последний блок, толерантный разбор JSON по образцу
  `JudgePrompts.extractJsonObject`, unknown-поля игнорируются, отсутствующие
  step/expected_action наследуются от текущего состояния; `strip` удаляет все
  блоки, сохраняя остальной текст дословно; нет блока / битый JSON /
  неизвестный stage → null
- [x] Юнит-тесты: `TaskStateMachineTest` (каждая легальная кромка графа;
  нелегальные done→execution, planning→done, null→execution, paused→execution;
  PAUSED→prevStage; same-stage обновляет step/expectedAction; капы длин) и
  `TaskStateJsonTest` (блок в длинном смешанном ответе; нет блока → null;
  битый JSON → null; два блока → последний; неизвестный stage → null; strip
  сохраняет остальной текст)

## 2. Стор (задача 3)

- [x] `Session.taskState: TaskState? = null` по back-compat паттерну с KDoc
  (legacy-файлы сессий без поля читаются с null)
- [x] Интерфейс `SessionStore`: `updateTaskState`/`getTaskState` по образцу
  `setFacts`; реализации в `InMemorySessionStore` (mutable Entry, включение в
  `snapshot()`) и `FileSessionStore`
- [x] Branch не захватывает taskState: KDoc на `Branch` об осознанном
  ограничении (состояние общее на сессию, не входит в снимок ветки)
- [x] Тесты: round-trip состояния через InMemory и FileSessionStore,
  legacy-JSON-фикстура без поля (taskState=null), `updateTaskState` по
  неизвестному sessionId — no-op без исключения

## 3. Контракт (задача 4)

- [x] `ChatResponse.taskState: TaskState? = null` в `Models.kt` (nullable с
  дефолтом — старые клиенты/ответы совместимы)
- [x] Зеркальное поле `taskState` в `frontend/src/api/types.ts` (синхронно в
  одной задаче) + комментарии-ссылки в обоих файлах
- [x] Smoke-компиляция обеих сторон: `./gradlew compileKotlin` и
  `npm run build` — exit 0

## 4. Серверная интеграция (задача 5)

- [x] Инъекция состояния в промпт: `TaskStatePrompts` (обычный / paused /
  null-варианты), эфемерная system-строка в seed `handleAgent` рядом с
  preferences/memory (в `Session.messages` не попадает)
- [x] Pipeline в `finalizeResponse` (строгий порядок мутаций): стрип
  task-state-блоков → свежее перечитывание store → `TaskStateJson.parse` +
  `TaskStateMachine.apply` → `updateTaskState` (только если результат не
  null) → `setFacts` → echo `taskState` в `ChatResponse`; свежее состояние
  PAUSED → применение перехода запрещено (paused-wins)
- [x] POST `/api/task/pause` и `/api/task/resume` (body `{sessionId}`, ответ
  `{taskState}`, 404 на неизвестную сессию, идемпотентны; пауза при null →
  no-op + `{taskState: null}`) + оба роута в таблице защищённых маршрутов и
  в Triple-списке `AuthInterceptTest`
- [x] Chat-команды «пауза», «/пауза», «продолжить», «/продолжить»
  (trim+lowercase, точное совпадение) до LLM-вызова → pause/resume с
  системным ответом; «сделай паузу» — обычный LLM-путь (негативный тест)
- [x] Тесты на моке (без сети): legal-переход применяется и эхоится;
  illegal-переход → состояние прежнее; нет блока → состояние прежнее; пауза
  в полёте не перезаписывается финализацией; команда «пауза» → LLM не
  вызван; «сделай паузу» → ушло в LLM; 404 на неизвестную сессию; отмена
  стрима → taskState нетронут; reply в истории без блока ```task-state```;
  инъекция: исходящий запрос содержит системный блок с текущим step
  (paused-вариант — запрет переходов)

## 5. Фронтенд (задача 6)

- [x] `useChatStream.applyChatResponse`: приём `data.taskState` в состояние
  чата (по образцу `data.facts` → `setFacts`, безусловно, каждый ход)
- [x] Индикатор этапа в `StatusLine.tsx`: бейдж (Планирование / Исполнение /
  Проверка / Готово / Пауза) + строка «Шаг: <step> · Далее:
  <expectedAction>» с CSS-ellipsis; taskState == null → зона пуста; цвета
  только из `tokens.css`
- [x] Кнопка Пауза↔Продолжить в зоне SendBar (по образцу кнопки «Стоп»,
  aria-pressed по образцу Toggle): label «Пауза» при активной задаче,
  «Продолжить» при paused, disabled при taskState == null; клик → POST
  `/api/task/pause` или `/api/task/resume` через `api/client.ts`, ответ
  кладётся в состояние чата
- [x] Defense-in-depth-фильтр блоков ```task-state``` при рендере записей
  журнала (`Entry.tsx` или lib-утилита) + юнит-проверка функции через
  node-скрипт (тест-инфраструктуру не добавлять)
