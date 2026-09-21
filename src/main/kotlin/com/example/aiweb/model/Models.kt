package com.example.aiweb.model

import com.example.aiweb.invariants.StoredInvariant
import com.example.aiweb.memory.MemoryEntry
import com.example.aiweb.memory.WorkingEntry
import com.example.aiweb.task.TaskState
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-конфигурация из файла. Все поля опциональны.
 * Пример: {"port":8080,"apiUrl":"...","apiKey":"...","model":"..."}
 *
 * Поля apiUrl/apiKey/model задают провайдер по умолчанию; providers — дополнительные
 * провайдеры (например, OpenCode с бесплатными моделями); allowedModels — белый
 * список моделей провайдера по умолчанию.
 */
@Serializable
data class FileConfig(
    val port: Int? = null,
    val apiUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val allowedModels: List<String>? = null,
    /**
     * Каталог для данных (P1-персистентность сессий); null/пусто — только in-memory.
     * Приоритет: env AI_DATA_DIR выше этого значения.
     */
    val dataDir: String? = null,
    /** Поддержка нативного function calling — для провайдера по умолчанию. */
    val supportsTools: Boolean? = null,
    /**
     * Настройки компрессии истории диалога; null/отсутствие поля — значение по
     * умолчанию из AppConfig (выключено, окно 5, порог 5, бюджет 400).
     * Только конфиг-файл: переменных окружения для компрессии нет.
     */
    val compressionEnabled: Boolean? = null,
    /** Окно последних сообщений, уходящих в запрос «как есть» (≥ 1). */
    val compressionWindowSize: Int? = null,
    /** Порог слияния: сколько сообщений вне окна запускает один merge (≥ 1). */
    val compressionMergeEvery: Int? = null,
    /** Бюджет сводки диалога в токенах (≥ 50). */
    val compressionSummaryMaxTokens: Int? = null,
    /** Стратегия формирования контекста: off | window | summary | facts | branching (отсутствует — off). */
    val contextStrategy: String = "off",
    /**
     * Раздел инструментов: вкл/выкл встроенных и подключение sandbox-инструментов
     * (исполнение кода и чтение файлов в изолированном контейнере).
     * null/отсутствие раздела — сегодняшнее поведение: три встроенных инструмента,
     * sandbox-инструменты выключены.
     */
    val tools: FileToolsConfig? = null,
    /** Настройки памяти агента: стратегия поиска и (стадия 2) эмбеддинги; null — keyword-поиск. */
    val memory: MemoryConfig? = null,
    val providers: List<FileProvider>? = null
)

/**
 * Конфигурация инструментов из раздела "tools" конфиг-файла.
 *
 * Логические поля включают/выключают встроенные инструменты; null трактуется
 * как true (поведение по умолчанию без раздела). Sandbox-инструменты
 * ([runCode]/[readFile]) выключены по умолчанию — включаются только явным
 * {"enabled": true} в конфиге; отключённый инструмент не виден модели вообще
 * (не попадает в список спецификаций function calling).
 *
 * Переменных окружения для инструментов нет — конфигурируется только файлом.
 */
@Serializable
data class FileToolsConfig(
    @SerialName("current_datetime") val currentDatetime: Boolean? = null,
    val calculator: Boolean? = null,
    @SerialName("http_get") val httpGet: Boolean? = null,
    /** Исполнение Python-кода в sandbox-контейнере (инструмент run_code). */
    @SerialName("run_code") val runCode: SandboxToolConfig? = null,
    /** Чтение файлов из рабочей папки через sandbox-контейнер (инструмент read_file). */
    @SerialName("read_file") val readFile: SandboxToolConfig? = null,
    /** Запись файлов в рабочую папку через sandbox-контейнер (инструмент write_file). */
    @SerialName("write_file") val writeFile: SandboxToolConfig? = null
)

/**
 * Настройки памяти агента: стратегия поиска и (стадия 2) эмбеддинги.
 * null/отсутствие раздела — поведение по умолчанию: keyword-поиск.
 */
@Serializable
data class MemoryConfig(
    /** Стратегия релевантного поиска: "keyword" (по умолчанию); fts/vector/hybrid — стадии 1–2. */
    val search: String? = null,
    /** Поставщик эмбеддингов (стадия 2); в этом чейндже не активирует реализацию. */
    val embedding: EmbeddingConfig? = null
)

/** Конечная точка эмбеддингов (стадия 2). */
@Serializable
data class EmbeddingConfig(
    /** Адрес OpenAI-совместимого API эмбеддингов (POST {url}/embeddings). */
    val url: String,
    /** Модель эмбеддингов, например text-embedding-3-small. */
    val model: String,
    /** Ключ API эндпоинта; null — используется общий ключ провайдера по умолчанию. */
    val apiKey: String? = null,
    /** Размерность вектора; null — родная для модели. */
    val dimensions: Int? = null
)

/**
 * Параметры одного sandbox-инструмента: адрес HTTP-сервиса песочницы, признак
 * включения и клиентский таймаут исполнения (отправляется в теле запроса
 * POST {url}/run после зажима в диапазон 1..60 на стороне оркестратора).
 */
@Serializable
data class SandboxToolConfig(
    /** true — инструмент регистрируется в реестре и виден модели. */
    val enabled: Boolean = false,
    /** Базовый адрес sandbox-сервиса (внутреннее имя docker-compose). */
    val url: String = "http://tool-sandbox:8080",
    /** Таймаут исполнения кода, секунды (только run_code; зажимается в 1..60). */
    val timeoutSeconds: Int = 30,
    /**
     * Папка хоста, смонтированная в песочницу как /sandbox/workspace/host-files
     * (см. docker-compose). Влияет только на описания файловых инструментов:
     * модель узнаёт, что файлы хоста доступны по путям вида host-files/<имя>.
     * null — папка хоста не подключена, описание без упоминания.
     */
    val hostDir: String? = null
)

/** Дополнительный провайдер в конфиг-файле. */
@Serializable
data class FileProvider(
    /** Идентификатор — передаётся фронтендом в ChatRequest.provider. */
    val id: String,
    /** Отображаемое имя (заголовок группы в селекте моделей). */
    val name: String? = null,
    /** Протокол: openai (по умолчанию) или opencode. */
    val type: String? = null,
    val apiUrl: String,
    val apiKey: String? = null,
    /** Модель по умолчанию у провайдера. */
    val model: String? = null,
    /** Явный список моделей; пусто — список запрашивается у API провайдера. */
    val models: List<String>? = null,
    /** id провайдера внутри OpenCode для Model.Ref. */
    val upstream: String? = null,
    /**
     * Поддержка провайдером нативного function calling; null (по умолчанию) —
     * инструменты вызываются через промт-протокол (fallback), т.к. серверы без
     * флагов tools молча игнорируют их.
     */
    val supportsTools: Boolean? = null
)

/** Тело запроса, который приходит от веб-интерфейса на наш сервер. */
@Serializable
data class ChatRequest(
    val message: String,
    /** Провайдер LLM (id из /api/models); null или неизвестный — провайдер по умолчанию. */
    val provider: String? = null,
    /** Идентификатор серверной сессии диалога; null — сервер создаст новую сессию. */
    val sessionId: String? = null,
    /** Выбранная в интерфейсе модель LLM (null или пусто — модель из конфигурации провайдера). */
    val model: String? = null,
    /** Режим «Судья»: true → после ответа выполняется контрольная проверка отдельным вызовом LLM. */
    val judgeEnabled: Boolean? = null,
    /** Сжатие истории для этого запроса; null — брать из конфигурации (приоритет: поле запроса > конфиг). */
    val compressionEnabled: Boolean? = null,
    /** Стратегия формирования контекста: off | window | summary | facts | branching; null — разрешить по приоритету (legacy-флаг, затем конфиг). */
    val contextStrategy: String? = null,
    /** Ветка, с которой продолжает диалог (стратегия branching); null — без переключения ветки. */
    val branchId: String? = null,
    /** Управление цепочкой рассуждений модели: true → thinking {type: enabled}, false → {type: disabled}, null — не передавать. */
    val thinkingEnabled: Boolean? = null,
    /**
     * Инструменты и блок памяти для этого запроса: null/true — включены
     * (task_notes, memory_save, memory_recall и системное сообщение с блоком
     * памяти), false — инструменты памяти и блок памяти выключены.
     */
    val memoryTools: Boolean? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: String? = null,
    /** Клиентский UUID запроса для отмены через POST /api/cancel (стриминг); null/пусто — отменить нельзя. */
    val requestId: String? = null
)

/** Ответ, который сервер возвращает веб-интерфейсу. */
@Serializable
data class ChatResponse(
    val reply: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Токены вызова суммаризатора при сжатии истории; null — сводка не обновлялась. */
    // @EncodeDefault(ALWAYS): серверный Json имеет encodeDefaults=false — без аннотации
    // ключи omitились бы из ответа, ломая контракт DTO для фронтенда (задача 7).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val summaryTokens: Int? = null,
    /** Подставлена ли сводка в контекст этого запроса. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val summaryApplied: Boolean = false,
    /** Разрешённая для запроса стратегия контекста (echo); null — режим, где стратегия не действует. */
    // @EncodeDefault(ALWAYS): серверный Json имеет encodeDefaults=false — без аннотации
    // ключи omitились бы из ответа, ломая контракт DTO для фронтенда (echo-семантика).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val contextStrategy: String? = null,
    /** Ветка, на которую переключилась сессия (стратегия branching); null — без ветки. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val branchId: String? = null,
    /** Актуальный список фактов сессии (стратегия facts); пусто — фактов нет. */
    // @EncodeDefault(ALWAYS): пустой список должен присутствовать в JSON для клиентов
    // стратегии facts (панель фактов обновляется из ответа), а encodeDefaults=false
    // серверного Json иначе omitил бы значение по умолчанию.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val facts: List<Fact> = emptyList(),
    /** Полное время обработки запроса на сервере (все вызовы LLM), мс. */
    val elapsedMs: Long? = null,
    /** Заключение судьи (режим «Судья»); null — проверка не выполнялась. */
    val judge: JudgeReport? = null,
    /** Идентификатор серверной сессии диалога; создаётся на каждый запрос. */
    val sessionId: String? = null,
    /** Сырые обмены с LLM, совершённые при обработке этого сообщения (для режима ТО). */
    val exchanges: List<ExchangeInfo> = emptyList(),
    /** События хода работы агента. */
    val events: List<AgentEvent> = emptyList(),
    /** taskState — FSM состояния задачи; синхронно с frontend/src/api/types.ts. null — задачи нет. */
    val taskState: TaskState? = null,
    /**
     * Человекочитаемое описание последнего отклонённого перехода задачи;
     * echo каждый ход, пока есть; null когда отказов нет/очищен.
     * Синхронно с frontend/src/api/types.ts
     */
    val taskRejected: String? = null,
    /**
     * Эхо обоих скоупов инвариантов ([InvariantsPayload]): глобальный + сессия,
     * синхронно с frontend/src/api/types.ts (ChatResponse.invariants).
     * null — когда оба скоупа пусты (encodeDefaults=false — ключ из JSON omitится).
     */
    val invariants: InvariantsPayload? = null
)

/**
 * Оба скоупа инвариантов одним пейлоадом — эхо [ChatResponse.invariants] и
 * тело/ответ роутов /api/invariants.
 *
 * Зеркало frontend `InvariantsPayload` (frontend/src/api/types.ts) —
 * синхронизировать вручную, как и остальные DTO этого файла.
 * [global] — глобальные инварианты (файл-стор, задача 2);
 * [session] — инварианты сессии (Session.invariants, задача 3).
 *
 * Контракт 5 роутов (реализация — задача 8, фронтенд — задача 9); все роуты
 * за токен-интерцептором — 401 без валидного X-Auth-Token:
 * - GET    /api/invariants?sessionId=X          → 200 {global, session};
 *   400 — нет sessionId; 404 — неизвестная сессия
 * - POST   /api/invariants/global {text}        → 200 {global};
 *   400 — валидация (пустой/длинный текст), точный дубль, кап 20
 * - DELETE /api/invariants/global/{id}          → 200 {global}; 404 — такого id нет
 * - POST   /api/invariants/session {sessionId, text} → 200 {session};
 *   400 — валидация/дубль/кап; 404 — неизвестная сессия
 * - DELETE /api/invariants/session/{sessionId}/{id} → 200 {session};
 *   404 — неизвестная сессия или такого id нет
 */
@Serializable
data class InvariantsPayload(
    val global: List<StoredInvariant> = emptyList(),
    val session: List<StoredInvariant> = emptyList()
)

/** Один выделенный факт диалога (стратегия «Факты»). */
@Serializable
data class Fact(
    val key: String,
    val value: String
)

/** Тело POST /api/session/{id}/branches/activate — идентификатор ветки для переключения. */
@Serializable
data class ActivateBranchRequest(
    val branchId: String
)

/** Одна запись плоского списка веток сессии (GET /api/session/{id}/branches). */
@Serializable
data class BranchInfo(
    /** Идентификатор ветки; null — активна исходная история (до первого форка/активации). */
    val branchId: String?,
    val label: String,
    /** Провенанс: сколько user-сообщений было в состоянии на момент создания записи. */
    val forkUserCount: Int,
    /** user-сообщения, добавленные после создания (для активной записи — выводимый счётчик). */
    val userCountSinceFork: Int,
    /** Всего сообщений в состоянии (user + assistant + tool). */
    val messageCount: Int,
    /** Активна ли запись — живое состояние сессии; ровно одна запись со значением true. */
    val active: Boolean
)

/** Ответ GET /api/session/{id}/branches: активная запись первая, затем снимки веток. */
@Serializable
data class BranchesResponse(
    val branches: List<BranchInfo>
)

/** Одна запись сырого обмена с LLM: запрос или ответ (режим ТО и самописец). */
@Serializable
data class ExchangeInfo(
    /** Сквозной номер записи в самописце. */
    val seq: Long,
    /** Момент записи, epoch millis. */
    val ts: Long,
    /** Направление: req — запрос к модели, res — ответ модели. */
    val dir: String,
    /** Признак успеха (только для ответов; null — для запросов). */
    val ok: Boolean? = null,
    /** Длительность обмена, мс (только для ответов). */
    val ms: Long? = null,
    /** Тело обмена: JSON запроса или ответа (либо текст ошибки связи). */
    val payload: String,
    /** Владелец обмена (userId из токена запроса чата); null — системная запись вне обработки запроса. */
    val userId: String? = null
)

/** Ответ GET /api/log — содержимое бортового самописца. */
@Serializable
data class ExchangeLogResponse(
    val entries: List<ExchangeInfo> = emptyList()
)

// --- Модели для OpenAI-совместимого API (Chat Completions) ---

/**
 * Приборные показания одного хода (ответ агента): расход топлива и время обработки.
 * Хранится на assistant-сообщении в истории сессии — фронтенд восстанавливает
 * шапку ответа («Топливо: запрос X + ответ Y = Z») и топливомер сессии
 * (счётчик и график) после переключения сессии или перезагрузки страницы.
 */
@Serializable
data class TurnUsage(
    /** Полное время обработки хода на сервере (все вызовы LLM), мс. */
    val elapsedMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Подставлена ли сводка в контекст этого запроса. */
    val summaryApplied: Boolean = false,
    /** Токены вызова суммаризатора при сжатии истории в этом ходе. */
    val summaryTokens: Int? = null
)

@Serializable
data class OpenAIMessage(
    val role: String,
    /** API некоторых моделей возвращает null при пустом содержимом ответа. */
    val content: String? = null,
    /** Цепочка рассуждений (vLLM-совместимые серверы); fallback при пустом content. */
    val reasoning: String? = null,
    /** Вызовы инструментов, запрошенные моделью (роль assistant). */
    @SerialName("tool_calls") val toolCalls: List<OpenAIToolCall>? = null,
    /** Идентификатор вызова инструмента — обязателен в сообщениях с ролью "tool". */
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /**
     * Трейс хода работы агента ([AgentEvent] с дискриминатором "type": "tool_call",
     * "final" и т.д.); живёт только на assistant-сообщениях в истории сессии —
     * фронтенд восстанавливает блок «Ход работы агента» после перезагрузки страницы.
     *
     * НЕ поле протокола OpenAI: AIClient строит запросы с encodeDefaults = true,
     * поэтому [EncodeDefault] с Mode.NEVER — иначе даже пустой список уезжал бы
     * в каждый запрос к API. Непустой трейс из повторенной истории сессии
     * дополнительно срезается при сборке запроса (см. AIClient.executeOpenAI).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val events: List<AgentEvent> = emptyList(),
    /**
     * Приборные показания хода ([TurnUsage]); живёт только на assistant-сообщениях
     * в истории сессии — фронтенд восстанавливает по ним шапку ответа и топливомер
     * сессии (GET /api/session/{id}).
     *
     * НЕ поле протокола OpenAI: срезается при сборке запроса (см. AIClient.executeOpenAI),
     * а null не кодируется и так — EncodeDefault NEVER (см. [events]).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val usage: TurnUsage? = null
)

@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    /** Управление цепочкой рассуждений модели (thinking). */
    val thinking: OpenAIThinking? = null,
    /** Описания инструментов, доступных модели (null — параметры не передаются). */
    val tools: List<OpenAIToolSpec>? = null
)

/** Значение параметра thinking: {"type":"enabled"} — рассуждения включены, {"type":"disabled"} — выключены. */
@Serializable
data class OpenAIThinking(
    val type: String
)

/** Функция внутри вызова инструмента: имя и аргументы. */
@Serializable
data class OpenAIToolCallFunction(
    val name: String = "",
    /** Аргументы — JSON-строка (так в протоколе OpenAI), не объект. */
    val arguments: String = ""
)

/** Один вызов инструмента, запрошенный моделью (элемент message.tool_calls). */
@Serializable
data class OpenAIToolCall(
    val id: String = "",
    val type: String = "function",
    val function: OpenAIToolCallFunction? = null
)

/** Описание параметров функции — JSON-Schema в виде произвольного JSON. */
@Serializable
data class OpenAIFunctionSpec(
    val name: String,
    val description: String = "",
    /** JSON-Schema параметров в виде произвольного JSON. */
    val parameters: JsonElement? = null
)

/** Описание инструмента для OpenAIRequest.tools (протокол function calling). */
@Serializable
data class OpenAIToolSpec(
    val type: String = "function",
    val function: OpenAIFunctionSpec
)

@Serializable
data class OpenAIResponse(
    val choices: List<OpenAIChoice> = emptyList(),
    val usage: OpenAIUsage? = null
)

@Serializable
data class OpenAIChoice(
    val message: OpenAIMessage? = null,
    /** Причина завершения генерации: "stop", "tool_calls", "length" и т.п. */
    @SerialName("finish_reason") val finishReason: String? = null
)

/** Информация об использовании токенов, которую возвращает API. */
@Serializable
data class OpenAIUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

// --- Список моделей провайдеров (GET /api/models) ---

@Serializable
data class OpenAIModel(
    val id: String = ""
)

@Serializable
data class OpenAIModelsResponse(
    val data: List<OpenAIModel> = emptyList()
)

/** Группа моделей одного провайдера в ответе GET /api/models. */
@Serializable
data class ProviderModels(
    /** Идентификатор провайдера — передаётся в ChatRequest.provider. */
    val id: String,
    /** Отображаемое имя (заголовок группы в интерфейсе). */
    val name: String,
    val models: List<String> = emptyList()
)

/** Ответ GET /api/models: группы моделей по провайдерам и значение по умолчанию. */
@Serializable
data class ModelsResponse(
    val providers: List<ProviderModels> = emptyList(),
    /** id провайдера по умолчанию. */
    val defaultProvider: String = "",
    /** Модель по умолчанию у провайдера по умолчанию. */
    val default: String = ""
)

// --- Список сессий (GET /api/sessions) ---

/** Краткое описание одной сессии для панели списка сессий. */
@Serializable
data class SessionSummary(
    /** Идентификатор сессии — передаётся в ChatRequest.sessionId и GET /api/session/{id}. */
    val id: String,
    /** Заголовок: первое осмысленное user-сообщение (до 80 символов); пусто, если его нет. */
    val title: String,
    /** Момент последнего изменения сессии, epoch millis. */
    val updatedAt: Long,
    /** Число сообщений в истории сессии. */
    val messageCount: Int
)

/** Ответ GET /api/sessions: сессии пользователя, самые свежие первыми. */
@Serializable
data class SessionsResponse(
    // Без значения по умолчанию: иначе kotlinx при encodeDefaults=false опустит
    // поле для пустого списка, и ответом будет {} вместо {"sessions":[]}
    val sessions: List<SessionSummary>
)

/** Ответ DELETE /api/sessions: сколько сессий стёрто. */
@Serializable
data class SessionsCleared(
    val deleted: Int
)

// --- Память агента (рабочая и долговременная) ---

/** Тело PUT /api/session/{id}/working — заметка рабочей памяти; id null → генерируется сервером. */
@Serializable
data class WorkingUpsertRequest(
    /** Идентификатор записи; null или пусто — новая запись с UUID. */
    val id: String? = null,
    val title: String,
    val content: String
)

/** Ответ рабочих маршрутов /api/session/{id}/working — записи сессии. */
@Serializable
data class WorkingEntriesResponse(
    // Без значения по умолчанию: при encodeDefaults=false пустой список
    // опустится из JSON, и ответом будет {} вместо {"entries":[]}
    val entries: List<WorkingEntry>
)

/** Тело POST /api/memory — новая запись долговременной памяти. */
@Serializable
data class MemoryCreateRequest(
    /** Вид записи: profile | decision | knowledge. */
    val kind: String,
    val title: String,
    val content: String,
    val tags: List<String>? = null
)

/** Тело PUT /api/memory/{id} — частичное обновление; null-поля остаются прежними. */
@Serializable
data class MemoryUpdateRequest(
    val kind: String? = null,
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null
)

/**
 * Вид записи долговременной памяти для HTTP-ответов: повторяет [MemoryEntry],
 * но поля со значениями по умолчанию помечены @EncodeDefault(ALWAYS) — серверный
 * Json с encodeDefaults=false иначе omitил бы source="user" и пустые tags из
 * ответа, ломая контракт для фронтенда.
 */
@Serializable
data class MemoryEntryView(
    val id: String,
    /** Вид записи: profile | decision | knowledge. */
    val kind: String,
    val title: String,
    val content: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val tags: List<String> = emptyList(),
    /** Кто создал запись: "user" | "agent". */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val source: String = "user",
    val createdAt: Long,
    val updatedAt: Long
)

/** Ответ POST /api/memory и PUT /api/memory/{id} — сохранённая/обновлённая запись. */
@Serializable
data class MemoryEntryResponse(
    val entry: MemoryEntryView
)

/** Ответ GET /api/memory — отфильтрованные записи и полные счётчики по видам. */
@Serializable
data class MemoryListResponse(
    val entries: List<MemoryEntryView>,
    /** Число записей по каждому виду памяти; все три ключа присутствуют всегда. */
    val counts: Map<String, Int>
)

/** Ответ DELETE /api/memory/{id} — была ли запись удалена. */
@Serializable
data class MemoryDeletedResponse(
    val deleted: Boolean
)

/** Секция «короткая» (серверная сессия диалога) ответа GET /api/memory/overview. */
@Serializable
data class MemoryShortTermOverview(
    /** Число сообщений в истории сессии; 0 — сессия неизвестна. */
    val turns: Int,
    /** Есть ли у сессии rolling-summary (компрессия). */
    val hasSummary: Boolean
)

/** Секция «рабочая память» ответа GET /api/memory/overview. */
@Serializable
data class MemoryWorkingOverview(
    /** Число заметок рабочей памяти сессии; 0 — сессия неизвестна. */
    val entries: Int
)

/** Секция «долговременная память» ответа GET /api/memory/overview. */
@Serializable
data class MemoryLongTermOverview(
    val total: Int,
    val profile: Int,
    val decision: Int,
    val knowledge: Int
)

/** Ответ GET /api/memory/overview — сводка всех слоёв памяти для панели памяти. */
@Serializable
data class MemoryOverviewResponse(
    val shortTerm: MemoryShortTermOverview,
    val working: MemoryWorkingOverview,
    val longTerm: MemoryLongTermOverview
)

// --- Протокол OpenCode (локальный экземпляр opencode serve) ---

/** Ссылка на модель в API OpenCode. */
@Serializable
data class OpenCodeModelRef(
    val id: String,
    @SerialName("providerID") val providerId: String
)

/** Тело POST {base}/api/generate: одноразовая stateless-генерация. */
@Serializable
data class OpenCodeGenerateRequest(
    val prompt: String,
    val model: OpenCodeModelRef? = null
)

@Serializable
data class OpenCodeGenerateResponse(
    val data: OpenCodeGenerateData? = null
)

@Serializable
data class OpenCodeGenerateData(
    val text: String? = null
)

@Serializable
data class OpenCodeModelsResponse(
    val data: List<OpenCodeModelInfo> = emptyList()
)

/** Элемент ответа GET {base}/api/model экземпляра OpenCode.
 *  В рантайме поле называется id, в схеме доков — modelID; принимаем оба. */
@Serializable
data class OpenCodeModelInfo(
    val id: String = "",
    val modelID: String = "",
    @SerialName("providerID") val providerId: String = "",
    val name: String = ""
) {
    val effectiveId: String get() = id.ifBlank { modelID }
}

/** Создание сессии: ответ POST {base}/session (пустое тело запроса). */
@Serializable
data class OpenCodeSessionResponse(
    val id: String = ""
)

/** Сообщение пользователя в сессию: тело POST {base}/session/{id}/message. */
@Serializable
data class OpenCodeSessionPrompt(
    val role: String = "user",
    val parts: List<OpenCodeMessagePart>,
    val model: OpenCodeSessionModelRef? = null
)

@Serializable
data class OpenCodeMessagePart(
    val type: String = "text",
    val text: String = ""
)

@Serializable
data class OpenCodeSessionModelRef(
    @SerialName("providerID") val providerId: String = "",
    @SerialName("modelID") val modelId: String = ""
)

/** Ответ на сообщение сессии: сводка сообщения (ошибка, токены) и его части. */
@Serializable
data class OpenCodeSessionMessageResponse(
    val info: OpenCodeMessageInfo? = null,
    val parts: List<OpenCodeMessagePart> = emptyList()
)

@Serializable
data class OpenCodeMessageInfo(
    val error: OpenCodeMessageError? = null,
    val tokens: OpenCodeTokens? = null
)

@Serializable
data class OpenCodeMessageError(
    val name: String? = null,
    val data: OpenCodeMessageErrorData? = null
)

@Serializable
data class OpenCodeMessageErrorData(
    val message: String? = null
)

/** Токены сообщения OpenCode (плоская структура, в отличие от OpenAI). */
@Serializable
data class OpenCodeTokens(
    val input: Int? = null,
    val output: Int? = null,
    val total: Int? = null
)

// --- Режим «Судья»: контрольная проверка ответа модели ---

/** Оценка судьи по одному критерию. */
@Serializable
data class JudgeCriterion(
    /** Балл 1..10; null, если судья не выставил оценку. */
    val score: Int? = null,
    /** Краткое обоснование оценки. */
    val comment: String? = null
)

/** Заключение судьи по ответу модели. */
@Serializable
data class JudgeReport(
    /** Корректность фактов. */
    val facts: JudgeCriterion? = null,
    /** Качество ответа: полнота, структура, соответствие вопросу. */
    val quality: JudgeCriterion? = null,
    /** Скорость: оценка по приборным замерам времени и генерации. */
    val speed: JudgeCriterion? = null,
    /** Ресурсоёмкость: оправданы ли потраченные токены объёмом и пользой ответа. */
    val efficiency: JudgeCriterion? = null,
    /** Итоговый балл 1..10. */
    val overall: Int? = null,
    /** Краткий вердикт. */
    val verdict: String? = null,
    /** Сырой текст заключения — если разобрать JSON не удалось или проверка сорвалась. */
    val raw: String? = null,
    /** Время, потраченное на проверку, мс. */
    val elapsedMs: Long? = null
)

// --- Авторизация: пользователи, вход по ПИН, токены ---

/**
 * Публичный вид пользователя для списков и ответов auth-маршрутов:
 * ТОЛЬКО id и имя — никаких pinHash/salt/createdAt (guardrail auth-домена).
 */
@Serializable
data class AuthUserView(
    val id: String,
    val name: String
)

/** Тело POST /api/auth/login — выбранный пользователь и его ПИН. */
@Serializable
data class LoginRequest(
    val userId: String,
    val pin: String
)

/** Тело POST /api/auth/users — создание пользователя (имя + ПИН из 4 цифр). */
@Serializable
data class CreateUserRequest(
    val name: String,
    val pin: String
)

/** Ответ POST /api/auth/login — выданный токен (заголовок X-Auth-Token) и пользователь. */
@Serializable
data class AuthTokenResponse(
    val token: String,
    val user: AuthUserView
)

/** Ответ POST /api/auth/users — созданный пользователь и его первый токен (авто-вход). */
@Serializable
data class UserCreatedResponse(
    val token: String,
    val user: AuthUserView
)

/** Ошибка auth-маршрутов (400/401/429): русский текст для показа пользователю. */
@Serializable
data class AuthErrorResponse(
    val error: String
)
