package com.example.aiweb.rag

import com.example.aiweb.config.RagConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Контракт RAG-поиска: шов для подмены клиента фейком в тестах
 * (по образцу LLMClient). Реализация по умолчанию — [KtorRagClient].
 */
interface RagClient {
    /** Ищет релевантные чанки по запросу; k — число источников. */
    suspend fun search(query: String, k: Int): List<Passage>
}

/**
 * HTTP-клиент RAG-sidecar (ktor-client-cio, стиль AIClient).
 *
 * Контракт sidecar (задача 2): POST на [RagConfig.url] — он трактуется как
 * ПОЛНЫЙ адрес search-endpoint (например http://127.0.0.1:18700/search) —
 * с телом {"query": "...", "k": N}; ответ — JSON-массив
 * [{chunk_id, score, source, title, section, content}] с ПОЛНЫМ content.
 * Здесь content НЕ срезается: клиент возвращает полный [Passage], а обрезка до
 * snippet ([RetrievedSource.SNIPPET_MAX_CHARS]) выполняется на этапе сведения
 * к ответному DTO — [Passage.toRetrievedSource] (задача 6).
 *
 * Ошибки (сеть, таймаут, HTTP != 2xx, кривой JSON) пробрасываются наверх —
 * их гасит fail-open [RagService.retrieve] (по образцу runJudge).
 */
class KtorRagClient(config: RagConfig) : RagClient {

    /** url обязателен для рабочего клиента: строится только при включённом RAG (config.rag.url != null). */
    private val searchUrl: String = config.url?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("KtorRagClient: rag.url не настроен — клиент создаётся только при включённом RAG")

    private val timeoutMs: Long = config.timeoutMs

    /** Единый Json-инстанс клиента (по образцу AIClient): кодирование тела и разбор ответа. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            // Явный таймаут из конфига: RAG должен отказывать быстро, а не висеть.
            requestTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
        expectSuccess = false // статус проверяем вручную — понятное сообщение об ошибке
    }

    override suspend fun search(query: String, k: Int): List<Passage> {
        // k=0/отрицательный не уезжает в sidecar: кламп в 1 (понятное поведение
        // вместо ошибки 400 на ровном месте)
        val safeK = k.coerceAtLeast(1)
        val request = buildSearchRequest(query, safeK)

        val response = client.post(searchUrl) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val bodyText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "RAG sidecar вернул ошибку ${response.status.value}: ${bodyText.take(300)}"
            )
        }

        return parseSearchResponse(bodyText)
    }

    /** Тело запроса к sidecar: {"query": "...", "k": N} (внутреннее — для тестов). */
    internal fun buildSearchRequest(query: String, k: Int): String =
        json.encodeToString(SidecarSearchRequest(query = query, k = k))

    /** Разбор массива sidecar → [Passage] с ПОЛНЫМ content (без обрезки на клиенте). */
    internal fun parseSearchResponse(bodyText: String): List<Passage> =
        json.decodeFromString<List<SidecarSource>>(bodyText).map { raw ->
            Passage(
                chunkId = raw.chunkId,
                source = raw.source,
                title = raw.title,
                section = raw.section,
                score = raw.score,
                content = raw.content
            )
        }

    /** Закрытие HTTP-клиента. */
    fun close() = client.close()

    /** Тело запроса к sidecar (протокол задачи 2). */
    @Serializable
    private data class SidecarSearchRequest(
        val query: String,
        val k: Int
    )

    /**
     * Сырая запись sidecar: snake_case-ключи протокола + ПОЛНЫЙ content.
     * Промежуточный DTO — в ответе не фигурирует; полный content уходит в
     * [Passage], а не в [RetrievedSource] (срез — на этапе toRetrievedSource).
     */
    @Serializable
    private data class SidecarSource(
        @SerialName("chunk_id") val chunkId: String,
        val score: Double,
        val source: String,
        val title: String,
        val section: String,
        val content: String
    )
}
