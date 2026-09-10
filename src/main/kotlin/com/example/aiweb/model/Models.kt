package com.example.aiweb.model

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
    /**
     * Раздел инструментов: вкл/выкл встроенных и подключение sandbox-инструментов
     * (исполнение кода и чтение файлов в изолированном контейнере).
     * null/отсутствие раздела — сегодняшнее поведение: три встроенных инструмента,
     * sandbox-инструменты выключены.
     */
    val tools: FileToolsConfig? = null,
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
    /** Способ рассуждения: default | direct | step_by_step | prompt_to_prompt | team (null — default). */
    val reasoningMode: String? = null,
    /** Провайдер LLM (id из /api/models); null или неизвестный — провайдер по умолчанию. */
    val provider: String? = null,
    /** Идентификатор серверной сессии диалога; null — сервер создаст новую сессию для одиночных режимов. */
    val sessionId: String? = null,
    /** Выбранная в интерфейсе модель LLM (null или пусто — модель из конфигурации провайдера). */
    val model: String? = null,
    /** Режим «Судья»: true → после ответа выполняется контрольная проверка отдельным вызовом LLM. */
    val judgeEnabled: Boolean? = null,
    /** Сжатие истории для этого запроса; null — брать из конфигурации (приоритет: поле запроса > конфиг). */
    val compressionEnabled: Boolean? = null,
    /** Управление цепочкой рассуждений модели: true → thinking {type: enabled}, false → {type: disabled}, null — не передавать. */
    val thinkingEnabled: Boolean? = null,
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
    /** Полное время обработки запроса на сервере (все вызовы LLM), мс. */
    val elapsedMs: Long? = null,
    /** Заключение судьи (режим «Судья»); null — проверка не выполнялась. */
    val judge: JudgeReport? = null,
    /** Идентификатор серверной сессии диалога; null — режим stateless (TEAM, Prompt-to-Prompt), история не ведётся. */
    val sessionId: String? = null,
    /** Сырые обмены с LLM, совершённые при обработке этого сообщения (для режима ТО). */
    val exchanges: List<ExchangeInfo> = emptyList(),
    /** События хода работы агента (режим «Агент»); у остальных режимов пусто. */
    val events: List<AgentEvent> = emptyList()
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
    val payload: String
)

/** Ответ GET /api/log — содержимое бортового самописца. */
@Serializable
data class ExchangeLogResponse(
    val entries: List<ExchangeInfo> = emptyList()
)

// --- Модели для OpenAI-совместимого API (Chat Completions) ---

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
    val events: List<AgentEvent> = emptyList()
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
