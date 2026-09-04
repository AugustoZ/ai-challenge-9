package com.example.aiweb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val providers: List<FileProvider>? = null
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
    val upstream: String? = null
)

/** Тело запроса, который приходит от веб-интерфейса на наш сервер. */
@Serializable
data class ChatRequest(
    val message: String,
    /** Способ рассуждения: default | direct | step_by_step | prompt_to_prompt | team (null — default). */
    val reasoningMode: String? = null,
    /** Провайдер LLM (id из /api/models); null или неизвестный — провайдер по умолчанию. */
    val provider: String? = null,
    /** Выбранная в интерфейсе модель LLM (null или пусто — модель из конфигурации провайдера). */
    val model: String? = null,
    /** Режим «Судья»: true → после ответа выполняется контрольная проверка отдельным вызовом LLM. */
    val judgeEnabled: Boolean? = null,
    /** Управление цепочкой рассуждений модели: true → thinking {type: enabled}, false → {type: disabled}, null — не передавать. */
    val thinkingEnabled: Boolean? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: String? = null
)

/** Ответ, который сервер возвращает веб-интерфейсу. */
@Serializable
data class ChatResponse(
    val reply: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Полное время обработки запроса на сервере (все вызовы LLM), мс. */
    val elapsedMs: Long? = null,
    /** Заключение судьи (режим «Судья»); null — проверка не выполнялась. */
    val judge: JudgeReport? = null,
    /** Сырые обмены с LLM, совершённые при обработке этого сообщения (для режима ТО). */
    val exchanges: List<ExchangeInfo> = emptyList()
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
    val reasoning: String? = null
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
    val thinking: OpenAIThinking? = null
)

/** Значение параметра thinking: {"type":"enabled"} — рассуждения включены, {"type":"disabled"} — выключены. */
@Serializable
data class OpenAIThinking(
    val type: String
)

@Serializable
data class OpenAIResponse(
    val choices: List<OpenAIChoice> = emptyList(),
    val usage: OpenAIUsage? = null
)

@Serializable
data class OpenAIChoice(
    val message: OpenAIMessage? = null
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
