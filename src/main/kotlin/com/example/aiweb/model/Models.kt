package com.example.aiweb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON-конфигурация из файла. Все поля опциональны.
 * Пример: {"port":8080,"apiUrl":"...","apiKey":"...","model":"..."}
 */
@Serializable
data class FileConfig(
    val port: Int? = null,
    val apiUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null
)

/** Тело запроса, который приходит от веб-интерфейса на наш сервер. */
@Serializable
data class ChatRequest(
    val message: String,
    /** Способ рассуждения: direct | step_by_step | prompt_to_prompt | team (null — как direct). */
    val reasoningMode: String? = null,
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
    val content: String
)

@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null
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
