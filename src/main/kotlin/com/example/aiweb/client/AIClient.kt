package com.example.aiweb.client

import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.OpenAIRequest
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIModelsResponse
import com.example.aiweb.model.OpenAIResponse
import com.example.aiweb.model.OpenAIThinking
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenCodeGenerateRequest
import com.example.aiweb.model.OpenCodeGenerateResponse
import com.example.aiweb.model.OpenCodeMessagePart
import com.example.aiweb.model.OpenCodeModelRef
import com.example.aiweb.model.OpenCodeModelsResponse
import com.example.aiweb.model.OpenCodeSessionModelRef
import com.example.aiweb.model.OpenCodeSessionPrompt
import com.example.aiweb.model.OpenCodeSessionResponse
import com.example.aiweb.model.OpenCodeSessionMessageResponse
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * Клиент для отправки запросов к одному провайдеру LLM.
 *
 * Поддерживаются два протокола (по provider.type):
 *  - "openai" — OpenAI-совместимый Chat Completions (POST {base}/chat/completions,
 *    список моделей — GET {base}/models);
 *  - "opencode" — локальный экземпляр OpenCode (opencode serve): генерация через
 *    POST {base}/api/generate (stateless, промт + Model.Ref), модели — GET {base}/api/model.
 *    OpenCode не принимает параметры генерации (temperature, max_tokens, stop,
 *    thinking) — они игнорируются, а сообщения склеиваются в один промт.
 */
class AIClient(private val provider: ProviderConfig) : LLMClient {

    /** Единый Json-инстанс: им сериализуется и трафик, и копия тела для самописца. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(this@AIClient.json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // Реальная LLM-нода (gpustack) делает агентный цикл/reasoning и отвечает
            // дольше минуты: 60с — отказ уже на первом ходу. Единый запас 300с.
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
        expectSuccess = false // позволит обработать ошибки API вручную
        engine {
            // Внутренний LLM-шлюз (gpustack.data.lmru.tech) отдаёт сертификат с истёкшим
            // сроком действия. Отключаем проверку сертификата ТОЛЬКО для этого LLM-клиента:
            // другие Ktor-клиенты (HttpGetTool, SandboxTools) сохраняют полную проверку.
            // Хоствифейшн в Ktor 2.3 проверяется отдельно и совпадает, поэтому достаточно
            // trust-all trustManager.
            https {
                trustManager = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String
                    ) = Unit
                    override fun checkServerTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String
                    ) = Unit
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            }
        }
    }

    /** modelID → providerID экземпляра OpenCode, заполненный при listModels(). */
    private val openCodeModelProviders = ConcurrentHashMap<String, String>()

    /**
     * Отправляет запрос в AI API с готовым контекстом (набором сообщений)
     * и возвращает ответ вместе с информацией о потраченных токенах.
     *
     * Содержимое [LLMCall]:
     *  - messages — полный контекст для отправки модели;
     *  - model — (необязательно) модель LLM; null или пусто — модель из конфигурации провайдера;
     *  - maxTokens — (необязательно) ограничение на длину ответа, либо null;
     *  - temperature — (необязательно) параметр случайности генерации;
     *  - topP — (необязательно) ядро выборки по кумулятивной вероятности;
     *  - stop — (необязательно) пользовательская стоп-последовательность;
     *          если указана и не пуста — применяется вместо стандартных STOP_SEQUENCES;
     *  - thinkingEnabled — (необязательно) управление цепочкой рассуждений модели:
     *          true → thinking {type: enabled}, false → {type: disabled}, null — не передавать;
     *  - tools — (необязательно) описания инструментов для function calling.
     */
    override suspend fun ask(call: LLMCall): ChatResult {
        val chosenModel = call.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: provider.model.takeIf { it.isNotBlank() }
        return if (provider.type == ProviderConfig.TYPE_OPENCODE) {
            // OpenCode — one-shot промт-склейка: параметры генерации и инструменты
            // протоколом не предусмотрены и молча игнорируются (агентный цикл — P1)
            executeOpenCode(call.messages, chosenModel)
        } else {
            executeOpenAI(call, chosenModel)
        }
    }

    /**
     * Запрашивает у провайдера список доступных моделей.
     */
    override suspend fun listModels(): List<String> =
        if (provider.type == ProviderConfig.TYPE_OPENCODE) listOpenCodeModels() else listOpenAIModels()

    /** Список моделей OpenAI-совместимого API: GET {base}/models. */
    private suspend fun listOpenAIModels(): List<String> {
        val response = client.get(modelsUrl()) {
            authorize()
        }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "API вернул ошибку ${response.status.value}: $bodyText"
            )
        }

        val parsed = json.decodeFromString<OpenAIModelsResponse>(bodyText)
        return parsed.data
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    /** Список моделей экземпляра OpenCode: GET {base}/api/model; попутно запоминаем providerID. */
    private suspend fun listOpenCodeModels(): List<String> {
        val response = client.get(openCodeUrl("api/model")) {
            authorize()
        }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "OpenCode вернул ошибку ${response.status.value}: $bodyText"
            )
        }

        val parsed = json.decodeFromString<OpenCodeModelsResponse>(bodyText)
        return parsed.data
            .filter { it.effectiveId.isNotBlank() }
            .onEach { openCodeModelProviders[it.effectiveId] = it.providerId }
            .map { it.effectiveId }
            .distinct()
    }

    /** OpenAI-совместимый Chat Completions (провайдер по умолчанию). */
    private suspend fun executeOpenAI(
        call: LLMCall,
        model: String?
    ): ChatResult {
        val finalStop = if (call.stop.isNullOrBlank()) STOP_SEQUENCES else listOf(call.stop.trim())
        val request = OpenAIRequest(
            model = model ?: provider.model.takeIf { it.isNotBlank() } ?: "gpt-3.5-turbo",
            // events (трейс агента) и usage (приборные показания хода) — служебные
            // поля истории сессии, а не поля протокола OpenAI: срезаем их на
            // единственной границе сборки запроса, чтобы они никогда не уезжали в API
            messages = call.messages.map { it.copy(events = emptyList(), usage = null) },
            temperature = call.temperature ?: 0.7,
            topP = call.topP,
            maxTokens = call.maxTokens,
            stop = finalStop,
            thinking = call.thinkingEnabled?.let { OpenAIThinking(type = if (it) "enabled" else "disabled") },
            tools = call.tools
        )

        ExchangeLog.record(DIR_REQ, json.encodeToString(request), ok = null, ms = null)
        val startedAt = System.currentTimeMillis()

        val response = try {
            client.post(provider.apiUrl) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: Exception) {
            ExchangeLog.record(
                DIR_RES,
                "Связь с API не состоялась: ${e.message}",
                ok = false,
                ms = System.currentTimeMillis() - startedAt
            )
            throw e
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        val bodyText = response.bodyAsText()
        ExchangeLog.record(DIR_RES, bodyText, ok = response.status.isSuccess(), ms = elapsedMs)

        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "API вернул ошибку ${response.status.value}: ${describeError(bodyText)}"
            )
        }

        return parseOpenAIResponse(bodyText, startedAt)
    }

    /**
     * Разбор успешного ответа OpenAI-совместимого API: декодирование тела,
     * извлечение текста ответа (с fallback на reasoning) и вызовов инструментов.
     *
     * ВАЖНО: tool_calls разбирается ДО проверки пустого контента — ответ модели,
     * запрашивающей вызов инструмента, содержит content=null, и это валидный ответ,
     * а не «пустой» (ошибка выдаётся, только когда нет ни текста, ни reasoning,
     * ни tool_calls).
     *
     * @param bodyText тело успешного HTTP-ответа (JSON)
     * @param startedAt момент отправки запроса — для расчёта elapsedMs (по умолчанию «сейчас»,
     *        чтобы разбор можно было вызывать в тестах отдельно)
     */
    internal fun parseOpenAIResponse(bodyText: String, startedAt: Long = System.currentTimeMillis()): ChatResult {
        val parsed = json.decodeFromString<OpenAIResponse>(bodyText)
        val message = parsed.choices.firstOrNull()?.message
        val toolCalls = message?.toolCalls.orEmpty()
        val reply = message?.content?.trim().takeUnless { it.isNullOrEmpty() }
            ?: message?.reasoning?.trim().takeUnless { it.isNullOrEmpty() }
            ?: if (toolCalls.isNotEmpty()) {
                // Ответ — только запрос вызова инструмента: текста нет, это не ошибка
                ""
            } else {
                throw IllegalStateException(
                    "API вернул пустой ответ: модель не вернула текст (проверьте лимит токенов и выбранную модель)"
                )
            }

        val usage = parsed.usage
        return ChatResult(
            reply = reply.trim(),
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            elapsedMs = System.currentTimeMillis() - startedAt,
            toolCalls = toolCalls,
            finishReason = parsed.choices.firstOrNull()?.finishReason
        )
    }

    /**
     * OpenCode: генерация текста по промту; параметры генерации протоколом не
     * предусмотрены (temperature, max_tokens и т.п. игнорируются).
     *
     * Основной путь — сессии v1 (POST /session → POST /session/{id}/message),
     * они есть в установленных версиях. Для серверов OpenCode v2 сначала пробуем
     * одноразовый POST /api/generate: если маршрут не существует (сервер отвечает
     * HTML-страницей) — переходим к сессиям.
     */
    private suspend fun executeOpenCode(messages: List<OpenAIMessage>, model: String?): ChatResult {
        val prompt = messages.joinToString("\n\n") { message ->
            val content = message.content.orEmpty()
            if (message.role == "system") "Системная инструкция:\n$content" else content
        }

        // providerID для Model.Ref: из справочника моделей, иначе — из конфига;
        // неизвестен — генерируем без model (настройка экземпляра по умолчанию)
        val upstreamId = model?.let {
            openCodeModelProviders[it] ?: provider.upstream.takeIf { u -> u.isNotBlank() }
        }

        val startedAt = System.currentTimeMillis()
        generateOnce(prompt, model, upstreamId, startedAt)?.let { return it }
        return generateViaSession(prompt, model, upstreamId, startedAt)
    }

    /** Одноразовая генерация сервера v2 (POST /api/generate); null — маршрут недоступен. */
    private suspend fun generateOnce(
        prompt: String,
        model: String?,
        upstreamId: String?,
        startedAt: Long
    ): ChatResult? {
        val request = OpenCodeGenerateRequest(
            prompt = prompt,
            model = if (model != null && !upstreamId.isNullOrBlank()) {
                OpenCodeModelRef(id = model, providerId = upstreamId)
            } else {
                null
            }
        )

        val response = try {
            client.post(openCodeUrl("api/generate")) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: Exception) {
            return null
        }

        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (!response.status.isSuccess() || !isJson) return null

        val bodyText = response.bodyAsText()
        val elapsedMs = System.currentTimeMillis() - startedAt
        ExchangeLog.record(DIR_RES, bodyText, ok = true, ms = elapsedMs)

        val parsed = json.decodeFromString<OpenCodeGenerateResponse>(bodyText)
        return parsed.data?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            ChatResult(text, promptTokens = null, completionTokens = null, totalTokens = null, elapsedMs = elapsedMs)
        }
    }

    /** Генерация через сессию (серверы v1): создание сессии, синхронное сообщение. */
    private suspend fun generateViaSession(
        prompt: String,
        model: String?,
        upstreamId: String?,
        startedAt: Long
    ): ChatResult {
        val sessionResponse = client.post(openCodeUrl("session")) {
            authorize()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val sessionIsJson = sessionResponse.contentType()?.match(ContentType.Application.Json) == true
        if (!sessionResponse.status.isSuccess() || !sessionIsJson) {
            throw IllegalStateException(
                "OpenCode-сервер не отвечает ожидаемо по адресу ${provider.apiUrl} " +
                    "(HTTP ${sessionResponse.status.value}). Проверьте apiUrl: у провайдера типа opencode " +
                    "он должен указывать на КОРЕНЬ запущенного сервера (например, http://localhost:4096), " +
                    "а не на endpoint chat/completions; OpenAI-совместимые шлюзы подключаются типом openai"
            )
        }
        val session = sessionResponse.body<OpenCodeSessionResponse>()
        if (session.id.isBlank()) {
            throw IllegalStateException("OpenCode не вернул идентификатор сессии")
        }

        val request = OpenCodeSessionPrompt(
            parts = listOf(OpenCodeMessagePart(type = "text", text = prompt)),
            model = if (model != null && !upstreamId.isNullOrBlank()) {
                OpenCodeSessionModelRef(providerId = upstreamId, modelId = model)
            } else {
                null
            }
        )
        ExchangeLog.record(DIR_REQ, json.encodeToString(request), ok = null, ms = null)

        val response = try {
            client.post(openCodeUrl("session/${session.id}/message")) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<OpenCodeSessionMessageResponse>()
        } catch (e: Exception) {
            ExchangeLog.record(
                DIR_RES,
                "Связь с OpenCode не состоялась: ${e.message}",
                ok = false,
                ms = System.currentTimeMillis() - startedAt
            )
            throw e
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        val error = response.info?.error
        if (error != null) {
            val detail = error.data?.message ?: error.name ?: "неизвестная ошибка"
            ExchangeLog.record(DIR_RES, detail, ok = false, ms = elapsedMs)
            throw IllegalStateException("OpenCode: $detail")
        }
        ExchangeLog.record(DIR_RES, json.encodeToString(response), ok = true, ms = elapsedMs)

        val reply = response.parts
            .filter { it.type == "text" && it.text.isNotBlank() }
            .joinToString("\n\n") { it.text.trim() }
            .ifBlank { null }
            ?: throw IllegalStateException("API вернул пустой ответ")

        val tokens = response.info?.tokens
        return ChatResult(
            reply = reply,
            promptTokens = tokens?.input,
            completionTokens = tokens?.output,
            totalTokens = tokens?.total,
            elapsedMs = elapsedMs
        )
    }

    /** OpenCode требует Basic-аутентификацию (имя по умолчанию — opencode); OpenAI — Bearer. */
    private fun HttpRequestBuilder.authorize() {
        if (provider.apiKey.isBlank()) return
        if (provider.type == ProviderConfig.TYPE_OPENCODE) {
            basicAuth(OPEN_CODE_AUTH_USER, provider.apiKey)
        } else {
            bearerAuth(provider.apiKey)
        }
    }

    /** Человекочитаемое описание ошибки API из тела вида {"error":{"type","message"}}. */
    private fun describeError(bodyText: String): String = try {
        val error = json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject ?: return bodyText.take(300)
        val type = error["type"]?.jsonPrimitive?.contentOrNull
        val message = error["message"]?.jsonPrimitive?.contentOrNull
            ?: error["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        listOfNotNull(type, message).joinToString(": ").ifBlank { bodyText.take(300) }
    } catch (e: Exception) {
        bodyText.take(300)
    }

    /** GET {base}/models из apiUrl вида .../v1/chat/completions. */
    private fun modelsUrl(): String {
        val base = provider.apiUrl.substringBefore("?").trimEnd('/')
        return if (base.endsWith("/chat/completions")) {
            base.removeSuffix("/chat/completions") + "/models"
        } else {
            "$base/models"
        }
    }

    /** URL маршрута экземпляра OpenCode: apiUrl трактуется как корень сервера. */
    private fun openCodeUrl(path: String): String =
        provider.apiUrl.trimEnd('/') + "/" + path

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
        private val STOP_SEQUENCES = listOf("</s>", "\n\nAssistant:")

        /** Метки направления записей самописца. */
        const val DIR_REQ = "req"
        const val DIR_RES = "res"

        /** Имя пользователя Basic-аутентификации OpenCode-сервера. */
        const val OPEN_CODE_AUTH_USER = "opencode"
    }
}

/**
 * Результат обращения к AI API: ответ, сведения о потраченных токенах и время обмена.
 * Поля toolCalls/finishReason заполняются только провайдерами с поддержкой function calling
 * (OpenAI-совместимый протокол); для остальных они пустые.
 */
data class ChatResult(
    val reply: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val elapsedMs: Long,
    /** Вызовы инструментов, запрошенные моделью; пусто, если вызовов не было. */
    val toolCalls: List<OpenAIToolCall> = emptyList(),
    /** Причина завершения генерации: "stop", "tool_calls", "length" и т.п.; null — неизвестна. */
    val finishReason: String? = null
)
