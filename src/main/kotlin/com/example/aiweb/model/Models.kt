package com.example.aiweb.model

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
    val message: String
)

/** Ответ, который сервер возвращает веб-интерфейсу. */
@Serializable
data class ChatResponse(
    val reply: String
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
    val temperature: Double = 0.7
)

@Serializable
data class OpenAIResponse(
    val choices: List<OpenAIChoice> = emptyList()
)

@Serializable
data class OpenAIChoice(
    val message: OpenAIMessage? = null
)
