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
     * Отправляет запрос в AI API с готовым контекстом (набором сообщений)
     * и возвращает ответ вместе с информацией о потраченных токенах.
     *
     * @param messages полный контекст для отправки модели
     * @param maxTokens (необязательно) ограничение на длину ответа, либо null
     * @param temperature (необязательно) параметр случайности генерации
     * @param topP (необязательно) ядро выборки по кумулятивной вероятности
     * @param stop (необязательно) пользовательская стоп-последовательность;
     *              если указана и не пуста — применяется вместо стандартных STOP_SEQUENCES
     */
    suspend fun ask(
        messages: List<OpenAIMessage>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
        stop: String? = null
    ): ChatResult {
        val finalStop = if (stop.isNullOrBlank()) STOP_SEQUENCES else listOf(stop.trim())
        val request = OpenAIRequest(
            model = config.model,
            messages = messages,
            temperature = temperature ?: 0.7,
            topP = topP,
            maxTokens = maxTokens,
            stop = finalStop
        )
        return execute(request)
    }

    /** Выполняет HTTP-запрос и разбирает ответ API. */
    private suspend fun execute(request: OpenAIRequest): ChatResult {
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

    companion object {
        private val SYSTEM_INSTRUCTION = buildString {
            append("Ты полезный ассистент. Отвечай структурированно, если пользователь не просит иного формата. ")
            append("Оформляй ответ в Markdown: заголовки, абзацы, маркированные и нумерованные списки, жирный и курсив. ")
            append("Фрагменты кода заключай в блоки с тройными обратными кавычками и указывай язык (например ```kotlin, ```python). ")
            append("Если в ответе присутствуют данные JSON, XML или YAML — оборачивай их в соответствующие блоки кода. ")
            append("Если пользователь явно запросил конкретный формат — следуй именно его инструкции. ")
            append("Завершай ответ, когда тема раскрыта полностью; не продолжай после логического конца.")
        }

        /**
         * Возвращает явное описание формата ответа (структурированный Markdown,
         * блоки кода, JSON/XML/YAML) для system-сообщения. Модель следует ему,
         * только если пользователь не просит конкретный формат.
         */
        fun formatInstruction(): String = SYSTEM_INSTRUCTION

        /**
         * Условие завершения ответа (stop sequences): применяется безусловно (априори).
         * Модель прекращает генерацию при появлении этих последовательностей.
         */
        private val STOP_SEQUENCES = listOf("<|endoftext|>", "\n\nAssistant:")
    }
}

/** Результат обращения к AI API: ответ и сведения о потраченных токенах. */
data class ChatResult(
    val reply: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)
