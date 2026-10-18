package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.InMemorySessionStore
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки сохранения приборных показаний хода (TurnUsage на assistant-сообщении):
 * блокирующий и стриминговый чаты пишут расход топлива в историю сессии, и
 * GET /api/session/{id} отдаёт его обратно — фронтенд восстанавливает шапку
 * ответа и топливомер сессии после переключения/перезагрузки.
 */
class TokenUsageRestoreTest {

    /** Фейковый транспорт: отдаёт заготовленный ответ с показаниями, запоминает вызовы. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    private fun config() = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m"
        )
    )

    /** Поднимает приложение с фейковым LLM (ответ 1/2/3 токенов) и передаёт harness. */
    private fun withApp(block: suspend (base: String, store: SessionStore) -> Unit) {
        val store = InMemorySessionStore()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(),
                    store = store,
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : HistorySummarizer {
                            override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>) =
                                SummarizeResult("", 0)
                        }
                    },
                    factExtractorFactory = {
                        object : FactExtractor {
                            override suspend fun extract(current: List<Fact>, userMessage: String): List<Fact>? = null
                        }
                    }
                )
            }
        }) { app ->
            kotlinx.coroutines.runBlocking { block(app.base, store) }
        }
    }

    private fun httpClient() = testHttpClient()

    /** Последнее сообщение с заданной ролью из сырого JSON сессии. */
    private fun messageByRole(sessionJson: kotlinx.serialization.json.JsonObject, role: String) =
        (sessionJson["messages"] as kotlinx.serialization.json.JsonArray)
            .map { it.jsonObject }
            .last { it["role"]!!.jsonPrimitive.content == role }

    @Test
    fun `блокирующий чат сохраняет показания топлива в истории сессии`() {
        withApp { base, _ ->
            val client = httpClient()

            val chatResponse = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "привет"))
            }.body<ChatResponse>()
            // Контракт ответа не изменился: токены на месте
            assertEquals(1, chatResponse.promptTokens)
            assertEquals(2, chatResponse.completionTokens)
            assertEquals(3, chatResponse.totalTokens)

            val sid = assertNotNull(chatResponse.sessionId)
            val sessionJson = Json.parseToJsonElement(
                client.get("$base/api/session/$sid").bodyAsText()
            ).jsonObject

            val assistant = messageByRole(sessionJson, "assistant")
            assertEquals("ответ сервера", assistant["content"]!!.jsonPrimitive.content)
            val usage = assertNotNull(assistant["usage"]?.jsonObject, "на assistant должно быть usage")
            // elapsedMs — серверное время обработки хода, неотрицательное
            assertTrue(usage["elapsedMs"]!!.jsonPrimitive.long >= 0)
            assertEquals(1, usage["promptTokens"]!!.jsonPrimitive.content.toInt())
            assertEquals(2, usage["completionTokens"]!!.jsonPrimitive.content.toInt())
            assertEquals(3, usage["totalTokens"]!!.jsonPrimitive.content.toInt())

            // user- и system-сообщения приборных показаний не несут
            val user = messageByRole(sessionJson, "user")
            assertNull(user["usage"], "у user-сообщения не должно быть usage")
            client.close()
        }
    }

    @Test
    fun `стриминговый чат тоже сохраняет показания топлива`() {
        withApp { base, _ ->
            val client = httpClient()

            val stream = client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "привет"))
            }.bodyAsText()
            // Кадр final несёт токены (контракт SSE не изменился)
            assertTrue(stream.contains("\"totalTokens\":3"), "кадр final должен содержать токены: $stream")

            val sid = Regex("\"sessionId\":\"([^\"]+)\"").find(stream)!!.groupValues[1]
            val sessionJson = Json.parseToJsonElement(
                client.get("$base/api/session/$sid").bodyAsText()
            ).jsonObject

            val usage = assertNotNull(
                messageByRole(sessionJson, "assistant")["usage"]?.jsonObject,
                "стрим-путь должен записать usage на assistant-сообщение"
            )
            assertEquals(3, usage["totalTokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(messageByRole(sessionJson, "user").containsKey("usage"))
            client.close()
        }
    }
}
