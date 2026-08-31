package com.example.aiweb.client

import com.example.aiweb.config.AppConfig
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.OpenAIRequest
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Клиент для отправки запросов к OpenAI-совместимому AI API.
 */
class AIClient(private val config: AppConfig) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false // позволит обработать ошибки API вручную
    }

    /**
     * Отправляет сообщение пользователя в AI API и возвращает ответ
     * вместе с информацией о потраченных токенах.
     */
    suspend fun ask(userMessage: String): ChatResult {
        val request = OpenAIRequest(
            model = config.model,
            messages = listOf(
                OpenAIMessage(role = "system", content = "Ты полезный ассистент."),
                OpenAIMessage(role = "user", content = userMessage)
            )
        )

        val response: HttpResponse = client.post(config.apiUrl) {
            contentType(ContentType.Application.Json)
            bearerAuth(config.apiKey)
            setBody(request)
        }

        // Проверка статуса ответа
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "API вернул ошибку ${response.status.value}: $errorBody"
            )
        }

        val parsed = response.body<OpenAIResponse>()
        val reply = parsed.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("API вернул пустой ответ")

        // Извлекаем данные об использовании токенов (могут отсутствовать)
        val usage = parsed.usage
        return ChatResult(
            reply = reply.trim(),
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens
        )
    }

    /** Закрытие HTTP-клиента. */
    fun close() = client.close()
}

/** Результат обращения к AI API: ответ и сведения о потраченных токенах. */
data class ChatResult(
    val reply: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)
