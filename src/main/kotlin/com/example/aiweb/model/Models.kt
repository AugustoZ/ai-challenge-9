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
    val totalTokens: Int? = null
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
