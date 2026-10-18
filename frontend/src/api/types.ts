/* Зеркало Models.kt / StreamEvents.kt / AgentEvents.kt / SessionStore.kt —
   синхронизировать вручную. Имена полей = имена на проводе (kotlinx без
   @SerialName пишет camelCase как есть; проверять по исходникам DTO). */

/* ---------- Запрос/ответ чата (Models.kt: ChatRequest / ChatResponse) ---------- */

/** Тело POST /api/chat и /api/chat/stream. */
export interface ChatRequest {
  message: string;
  /** Провайдер LLM (id из /api/models); null — провайдер по умолчанию. */
  provider?: string | null;
  /** Серверная сессия диалога; null — сервер создаст новую. */
  sessionId?: string | null;
  /** Выбранная модель (null/пусто — модель из конфигурации провайдера). */
  model?: string | null;
  /** Режим «Судья»: контрольная проверка ответа отдельным вызовом LLM. */
  judgeEnabled?: boolean | null;
  /** Сжатие истории для этого запроса; null — брать из конфигурации. */
  compressionEnabled?: boolean | null;
  /** Стратегия контекста: off | window | summary | facts | branching. */
  contextStrategy?: string | null;
  /** Ветка, с которой продолжается диалог (стратегия branching). */
  branchId?: string | null;
  /** Управление thinking модели: true/false/null — не передавать. */
  thinkingEnabled?: boolean | null;
  maxTokens?: number | null;
  temperature?: number | null;
  topP?: number | null;
  stop?: string | null;
  /** Клиентский UUID для отмены через POST /api/cancel (стриминг). */
  requestId?: string | null;
  /** Инструменты и блок памяти агента: null/true — включены, false — выключены. */
  memoryTools?: boolean | null;
}

/** Полный ответ сервера: и блокирующий /api/chat, и кадр final стрима. */
export interface ChatResponse {
  reply: string;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  /** Токены вызова суммаризатора; null — сводка не обновлялась. */
  summaryTokens?: number | null;
  /** Подставлена ли сводка в контекст этого запроса. */
  summaryApplied?: boolean;
  /** Разрешённая стратегия контекста (echo); null — стратегия не действует. */
  contextStrategy?: string | null;
  /** Ветка, на которую переключилась сессия; null — без ветки. */
  branchId?: string | null;
  /** Актуальный список фактов сессии (стратегия facts). */
  facts: Fact[];
  /** Полное время обработки на сервере, мс. */
  elapsedMs?: number | null;
  judge?: JudgeReport | null;
  /** Серверная сессия диалога; создаётся на каждый запрос. */
  sessionId?: string | null;
  /** Сырые обмены с LLM (режим ТО/самописец). */
  exchanges: ExchangeInfo[];
  /** События хода работы агента. */
  events: AgentEvent[];
  /** taskState — FSM состояния задачи; синхронно с Models.kt (ChatResponse.taskState). */
  taskState?: TaskState | null;
  /**
   * Человекочитаемое описание последнего отклонённого перехода задачи;
   * echo каждый ход, пока есть; null когда отказов нет/очищен
   * (Kotlin: Models.kt: ChatResponse.taskRejected) — синхронизировать вручную.
   */
  taskRejected?: string | null;
  /**
   * Эхо обоих скоупов инвариантов (Kotlin: InvariantsPayload,
   * Models.kt: ChatResponse.invariants) — синхронизировать вручную.
   * null/отсутствует — когда оба скоупа пусты (encodeDefaults=false).
   */
  invariants?: InvariantsPayload | null;
}

/** Этап задачи (Kotlin: TaskStage); enum в JSON сериализуется строкой. */
export type TaskStage =
  | 'PLANNING'
  | 'PLAN_APPROVED'
  | 'EXECUTION'
  | 'VALIDATION'
  | 'DONE'
  | 'PAUSED';

/**
 * Состояние задачи конечного автомата (Kotlin: TaskState,
 * Models.kt: ChatResponse.taskState) — синхронизировать вручную.
 */
export interface TaskState {
  stage: TaskStage;
  /** Текущий шаг (свободный текст, ≤120 символов). */
  step: string;
  /** Ожидаемое следующее действие (свободный текст, ≤200 символов). */
  expectedAction: string;
  /** Этап, с которого задача поставлена на паузу; null/отсутствует — не на паузе. */
  prevStage?: TaskStage | null;
}

/**
 * Ответ POST /api/task/pause|resume (Kotlin: TaskStateEnvelope) — синхронизировать вручную.
 */
export interface TaskStateEnvelope {
  taskState: TaskState | null;
  /** true — в сессии есть checkpoint прерванного паузой прогона; по нему на resume отправляется continuation-запрос. */
  resumeRun?: boolean;
}

/** Один инвариант — непреложное правило (Kotlin: StoredInvariant, пакет invariants). */
export interface Invariant {
  id: string;
  text: string;
}

/**
 * Оба скоупа инвариантов одним пейлоадом: global — файл-стор, session — сессия
 * (Kotlin: InvariantsPayload, Models.kt) — синхронизировать вручную.
 * Тело/ответ роутов /api/invariants и эхо ChatResponse.invariants.
 */
export interface InvariantsPayload {
  global: Invariant[];
  session: Invariant[];
}

/** Один выделенный факт диалога (стратегия «Факты»). */
export interface Fact {
  key: string;
  value: string;
}

/** Оценка судьи по одному критерию. */
export interface JudgeCriterion {
  /** Балл 1..10; null — судья не выставил оценку. */
  score?: number | null;
  comment?: string | null;
}

/** Заключение судьи по ответу модели. */
export interface JudgeReport {
  facts?: JudgeCriterion | null;
  quality?: JudgeCriterion | null;
  speed?: JudgeCriterion | null;
  efficiency?: JudgeCriterion | null;
  overall?: number | null;
  verdict?: string | null;
  /** Сырой текст заключения, если JSON разобрать не удалось. */
  raw?: string | null;
  /** Время проверки, мс. */
  elapsedMs?: number | null;
}

/** Одна запись сырого обмена с LLM (режим ТО и самописец). */
export interface ExchangeInfo {
  /** Сквозной номер записи в самописце. */
  seq: number;
  /** Момент записи, epoch millis. */
  ts: number;
  /** Направление: req — запрос к модели, res — ответ модели. */
  dir: string;
  /** Признак успеха (только для ответов; null — для запросов). */
  ok?: boolean | null;
  /** Длительность обмена, мс (только для ответов). */
  ms?: number | null;
  /** Тело обмена: JSON запроса/ответа либо текст ошибки. */
  payload: string;
}

/** Ответ GET /api/log — содержимое бортового самописца. */
export interface ExchangeLogResponse {
  entries: ExchangeInfo[];
}

/* ---------- События агента (AgentEvents.kt: sealed AgentEvent) ---------- */

/**
 * Событие хода работы агента; дискриминатор — wire-поле `type`
 * (@SerialName в Kotlin: step_started/tool_call/tool_result/
 * assistant_message/final). То же событие живёт в ChatResponse.events
 * и внутри SSE-кадра agent.
 */
export type AgentEvent =
  | AgentStepStarted
  | AgentToolCalled
  | AgentToolResult
  | AgentAssistantMessage
  | AgentFinal;

export interface AgentStepStarted {
  type: 'step_started';
  /** Номер шага цикла, начиная с 1. */
  step: number;
}

export interface AgentToolCalled {
  type: 'tool_call';
  step: number;
  tool: string;
  /** JSON-строка аргументов. */
  arguments: string;
}

export interface AgentToolResult {
  type: 'tool_result';
  step: number;
  tool: string;
  ok: boolean;
  /** Усечён до 500 символов; полный текст уходит модели. */
  content: string;
}

export interface AgentAssistantMessage {
  type: 'assistant_message';
  step: number;
  text: string;
}

export interface AgentFinal {
  type: 'final';
  step: number;
  /** Итоговый ответ. */
  text: string;
  /** готово | лимит шагов | дедлайн | зацикливание. */
  reason: string;
}

/* ---------- SSE-кадры (StreamEvents.kt: sealed StreamEvent) ---------- */

/**
 * Кадр стрима /api/chat/stream: `data: <одна строка JSON>`, дискриминатор —
 * поле `type` (@SerialName: agent/status/final/cancelled/error).
 */
export type StreamEvent =
  | { type: 'agent'; event: AgentEvent }
  /* Ранний кадр «задача заведена» (StreamEvents.kt): taskState — состояние
     задачи на старте хода, шлётся ПЕРЕД первым agent-кадром; absent/null —
     состояние не менялось (штатные статусы «Судья проверяет ответ…» поле
     не несут). sessionId — id сессии на первом ходе, когда клиент ещё не
     знает id (клик паузы в полёт без id ушёл бы в 400); absent/null — id
     не менялся. */
  | { type: 'status'; text: string; taskState?: TaskState | null; sessionId?: string | null }
  | { type: 'final'; response: ChatResponse }
  | { type: 'cancelled'; requestId: string }
  | { type: 'error'; message: string };

/** Терминальные типы: любой из них завершает чтение потока. */
export type TerminalStreamEventType = 'final' | 'cancelled' | 'error';

export const TERMINAL_STREAM_EVENT_TYPES: readonly TerminalStreamEventType[] = [
  'final',
  'cancelled',
  'error',
];

export function isTerminalStreamEvent(event: StreamEvent): event is Extract<StreamEvent, { type: TerminalStreamEventType }> {
  switch (event.type) {
    case 'final':
    case 'cancelled':
    case 'error':
      return true;
    default:
      return false;
  }
}

/* ---------- Модели (GET /api/models) ---------- */

/** Группа моделей одного провайдера. */
export interface ProviderModels {
  /** Передаётся в ChatRequest.provider. */
  id: string;
  /** Отображаемое имя (заголовок группы в селекте). */
  name: string;
  models: string[];
}

/** Ответ GET /api/models: группы по провайдерам и значения по умолчанию. */
export interface ModelsResponse {
  providers: ProviderModels[];
  defaultProvider: string;
  default: string;
}

/* ---------- Сессии и ветки (Models.kt + SessionStore.kt) ---------- */

/** Показания топлива одного хода (Kotlin: TurnUsage) на assistant-сообщении. */
export interface TurnUsage {
  /** Полное время обработки хода на сервере, мс. */
  elapsedMs?: number | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  /** Подставлена ли сводка в контекст этого запроса. */
  summaryApplied?: boolean;
  /** Токены вызова суммаризатора при сжатии истории в этом ходе. */
  summaryTokens?: number | null;
}

/** Сообщение в истории серверной сессии (Kotlin: OpenAIMessage). */
export interface ChatMessage {
  role: string;
  /** null у API некоторых моделей при пустом ответе. */
  content?: string | null;
  /** Цепочка рассуждений (vLLM-совместимые серверы). */
  reasoning?: string | null;
  /** Вызовы инструментов, запрошенные моделью (роль assistant). */
  tool_calls?: ChatToolCall[] | null;
  /** Обязателен в сообщениях с ролью "tool". */
  tool_call_id?: string | null;
  /** Трейс агента; при пустом списке ключ с провода не приходит. */
  events?: AgentEvent[];
  /** Показания топлива хода; приходит только на assistant-сообщениях. */
  usage?: TurnUsage | null;
}

/** Вызов инструмента внутри message.tool_calls. */
export interface ChatToolCall {
  id: string;
  type: string;
  function?: { name: string; arguments: string } | null;
}

/** Снимок сессии: ответ GET /api/session/{id} и POST …/branches/activate. */
export interface SessionState {
  id: string;
  messages: ChatMessage[];
  /** Rolling-summary старой истории (компрессия); null — не выполнялась. */
  summary?: string | null;
  /** Близнец-ветки сессии; у legacy-сессий пусто. */
  branches: BranchSnapshot[];
  /** Активная ветка; null — активна исходная история. */
  activeBranchId?: string | null;
  /** Порядковый номер последней созданной ветки (id «b<seq>»). */
  branchSeq: number;
  /** Факты активного состояния (стратегия «Факты»). */
  facts: Fact[];
  createdAt: number;
  updatedAt: number;
}

/** Снимок ветки-близнеца в сессии (Kotlin: Branch). */
export interface BranchSnapshot {
  branchId: string;
  label: string;
  forkUserCount: number;
  userCountSinceFork: number;
  messages: ChatMessage[];
  summary?: string | null;
  facts: Fact[];
  createdAt: number;
}

/** Запись плоского списка веток (GET /api/session/{id}/branches). */
export interface BranchInfo {
  /** null — активна исходная история (до первого форка/активации). */
  branchId: string | null;
  label: string;
  forkUserCount: number;
  userCountSinceFork: number;
  messageCount: number;
  /** Ровно одна запись со значением true. */
  active: boolean;
}

/** Ответ GET /api/session/{id}/branches: активная запись первая. */
export interface BranchesResponse {
  branches: BranchInfo[];
}

/** Тело POST /api/session/{id}/branches/activate. */
export interface ActivateBranchRequest {
  branchId: string;
}

/** Тело POST /api/cancel. */
export interface CancelRequest {
  requestId: string;
}

/** Ответ POST /api/cancel (Kotlin: JsonObject {cancelled: bool}). */
export interface CancelResponse {
  cancelled: boolean;
}

/** Краткое описание сессии для панели списка (GET /api/sessions). */
export interface SessionSummary {
  id: string;
  /** Первое осмысленное user-сообщение (до 80 символов). */
  title: string;
  /** Момент последнего изменения, epoch millis. */
  updatedAt: number;
  messageCount: number;
}

/** Ответ GET /api/sessions: самые свежие первыми. */
export interface SessionsResponse {
  sessions: SessionSummary[];
}

/** Ответ DELETE /api/sessions. */
export interface SessionsCleared {
  deleted: number;
}

/* ---------- Память: working-заметки (/api/session/{id}/working)
   и long-term записи (/api/memory) ---------- */

/** Категория long-term записи памяти. */
export type MemoryKind = 'profile' | 'decision' | 'knowledge';

/** Рабочая заметка сессии (краткоживущий слой памяти). */
export interface WorkingEntry {
  id: string;
  title: string;
  content: string;
  createdAt: number;
  updatedAt: number;
}

/** Long-term запись памяти. */
export interface MemoryEntry {
  id: string;
  kind: MemoryKind;
  title: string;
  content: string;
  tags: string[];
  /** Кто добавил: пользователь или агент. */
  source: 'user' | 'agent';
  createdAt: number;
  updatedAt: number;
}

/** Ответы ручек working: актуальный список заметок сессии. */
export interface WorkingListResponse {
  entries: WorkingEntry[];
}

/** Счётчики long-term записей по категориям. */
export interface MemoryCounts {
  profile: number;
  decision: number;
  knowledge: number;
}

/** Ответ GET /api/memory: отфильтрованный список + счётчики. */
export interface MemoryListResponse {
  entries: MemoryEntry[];
  counts: MemoryCounts;
}

/** Сводка трёх слоёв памяти сессии (GET /api/memory/overview). */
export interface MemoryOverview {
  shortTerm: { turns: number; hasSummary: boolean };
  working: { entries: number };
  longTerm: { total: number; profile: number; decision: number; knowledge: number };
}

/* ---------- Auth (Models.kt: AuthUserView / LoginRequest /
   CreateUserRequest / AuthTokenResponse / UserCreatedResponse) ---------- */

/** Пользователь без секретов: wire-ответ несёт ТОЛЬКО id и name. */
export interface AuthUserView {
  id: string;
  name: string;
}

/** Тело POST /api/auth/login. */
export interface LoginRequest {
  userId: string;
  pin: string;
}

/** Тело POST /api/auth/users. */
export interface CreateUserRequest {
  name: string;
  pin: string;
}

/** Ответ успешного входа: токен для заголовка X-Auth-Token + пользователь. */
export interface AuthTokenResponse {
  token: string;
  user: AuthUserView;
}

/**
 * Ответ POST /api/auth/users: создание пользователя сразу с токеном
 * (авто-вход). Форма совпадает с AuthTokenResponse — отдельный тип для
 * читаемости контракта (зеркало Models.kt).
 */
export interface UserCreatedResponse {
  token: string;
  user: AuthUserView;
}

/* ---------- Предпочтения (prefs/UserPreferences.kt + PreferenceLimits.kt) ---------- */

/** Форма обращения: словарь поля addressForm. */
export const ADDRESS_FORMS = ['ты', 'вы', 'пилот', 'гонщик'] as const;
export type AddressForm = (typeof ADDRESS_FORMS)[number];

/** Тон ответов: словарь поля tone. */
export const TONES = ['формальный', 'дружелюбный', 'панибратский', 'роботизированный'] as const;
export type Tone = (typeof TONES)[number];

/** Язык ответов: словарь поля language. */
export const LANGUAGES = ['Русский', 'Английский'] as const;
export type Language = (typeof LANGUAGES)[number];

/** Готовые варианты длины ответов; произвольные строки не допускаются. */
export const LENGTHS = ['кратко', 'средне', 'развернуто'] as const;
export type Length = (typeof LENGTHS)[number];

/** Лимиты свободных полей (зеркало PreferenceLimits.kt). */
export const ADDRESS_MAX = 255; // answerStructure (свободный текст)
export const TEXT_MAX = 1000; // avoidTopics, interests

/** Семь полей предпочтений пользователя (Kotlin: UserPreferences). */
export interface UserPreferences {
  /** Форма обращения: ты | вы | пилот | гонщик. */
  addressForm: AddressForm;
  /** Тон: формальный | дружелюбный | панибратский | роботизированный. */
  tone: Tone;
  /** Язык ответов: Русский | Английский. */
  language: Language;
  /** Структура ответов: таблицы | текст | стихи | произвольное ≤255. */
  answerStructure: string;
  /** Длина ответов: кратко | средне | развернуто. */
  length: Length;
  /** Темы, которых избегать, ≤1000 символов. */
  avoidTopics: string;
  /** Интересы пользователя, ≤1000 символов. */
  interests: string;
}

/** Тело PUT /api/preferences — тот же контракт (зеркало UpdatePreferencesRequest). */
export type UpdatePreferencesRequest = UserPreferences;

/**
 * Дефолты сервера (Kotlin: PreferencesStore.DEFAULTS): применяются при
 * первом входе и когда пользователь ещё не сохранял предпочтения.
 */
export const DEFAULT_USER_PREFERENCES: UserPreferences = {
  addressForm: 'ты',
  tone: 'дружелюбный',
  language: 'Русский',
  answerStructure: 'текст',
  length: 'средне',
  avoidTopics: '',
  interests: '',
};

/* ---------- Пайплайны (Kotlin: pipeline/PipelineService.kt — синхронизировать вручную) ---------- */

/** Состояние прогона сценария (Kotlin: PipelineStatus.state). */
export type PipelineState = 'running' | 'succeeded' | 'failed';

/**
 * Состояние шага: enum Kotlin PipelineStepState (running/done/failed/skipped)
 * плюс стартовый 'pending', которым PipelineService.run засевает все шаги.
 */
export type PipelineStepState = 'pending' | 'running' | 'done' | 'failed' | 'skipped';

/** Один шаг сценария в отчёте (Kotlin: PipelineStepStatus). */
export interface PipelineStep {
  id: string;
  tool: string;
  status: PipelineStepState;
  /** Длительность шага, мс; null — шаг ещё не завершён. */
  durationMs?: number | null;
  /** Сообщение об ошибке упавшего шага. */
  error?: string | null;
  /** Усечённый до 500 символов результат шага. */
  resultPreview?: string | null;
}

/** Статус текущего/последнего прогона сценария (Kotlin: PipelineStatus). */
export interface PipelineStatus {
  name: string;
  runId: string;
  state: PipelineState;
  /** Момент старта, ISO-8601 (Kotlin Instant.toString()). */
  startedAt: string;
  /** Момент завершения, ISO-8601; null/absent — прогон ещё идёт. */
  finishedAt?: string | null;
  steps: PipelineStep[];
}

/** Ответ 202 POST /api/pipeline/run (Kotlin: PipelineRunAccepted). */
export interface PipelineRunAccepted {
  runId: string;
}
