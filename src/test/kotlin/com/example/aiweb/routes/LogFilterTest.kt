package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ExchangeLog
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Журнал запросов per-user (задача 6): записи ExchangeLog помечаются userId
 * отправителя, GET /api/log отдаёт только записи текущего токена.
 */
class LogFilterTest {

    /**
     * Фейковый транспорт, повторяющий поведение настоящего AIClient: пишет пару
     * req/res в ExchangeLog при каждом вызове. Тег пользователя подставляется
     * из контекста запроса (ThreadContextElement, установленный обработчиком чата).
     */
    private class LoggingFakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            ExchangeLog.record("req", """{"fake":"запрос"}""", ok = null, ms = null)
            ExchangeLog.record("res", """{"fake":"ответ"}""", ok = true, ms = 1L)
            return canned
        }
    }

    private fun config(dataDir: String) = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m",
            supportsTools = true
        ),
        dataDir = dataDir
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun withApp(block: suspend (base: String) -> Unit) {
        val dataDir = java.nio.file.Files.createTempDirectory("aiweb-routes-log").toString()
        val chat = LoggingFakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        val port = freePort()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            routing {
                aiRoutes(
                    config = config(dataDir),
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : com.example.aiweb.session.HistorySummarizer {
                            override suspend fun summarize(
                                previous: String?,
                                batch: List<com.example.aiweb.model.OpenAIMessage>
                            ) = com.example.aiweb.session.SummarizeResult("", 0)
                        }
                    },
                    factExtractorFactory = {
                        object : FactExtractor {
                            override suspend fun extract(
                                current: List<com.example.aiweb.model.Fact>,
                                userMessage: String
                            ): List<com.example.aiweb.model.Fact>? = null
                        }
                    }
                )
            }
        }
        server.start(wait = false)
        try {
            runBlocking { block("http://127.0.0.1:$port") }
        } finally {
            server.stop(100, 500)
        }
    }

    private suspend fun chatAs(client: io.ktor.client.HttpClient, base: String, message: String) {
        val response = client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$message"}""")
        }
        check(response.status.value == 200) { "chat упал: ${response.status} ${response.bodyAsText()}" }
    }

    @Test
    fun `записи журнала помечены userId и журнал отдаёт только свои`() {
        withApp { base ->
            val client = testHttpClient()
            TestAuth.set(null)
            val userA = seedAuthUser(base, "Гонщик")
            val tokenA = TestAuth.current()!!
            chatAs(client, base, "ход пользователя A")

            TestAuth.set(null)
            val userB = seedAuthUser(base, "Механик")
            val tokenB = TestAuth.current()!!
            chatAs(client, base, "ход пользователя B")

            // Журнал A: только записи A
            TestAuth.set(tokenA)
            val entriesA = Json.parseToJsonElement(client.get("$base/api/log").bodyAsText())
                .jsonObject["entries"]!!.jsonArray
            assertTrue(entriesA.isNotEmpty(), "у A есть записи журнала")
            assertTrue(entriesA.all { it.jsonObject["userId"]!!.jsonPrimitive.content == userA })
            assertTrue(entriesA.none { it.jsonObject["userId"]!!.jsonPrimitive.content == userB })

            // Журнал B: только записи B
            TestAuth.set(tokenB)
            val entriesB = Json.parseToJsonElement(client.get("$base/api/log").bodyAsText())
                .jsonObject["entries"]!!.jsonArray
            assertTrue(entriesB.isNotEmpty(), "у B есть записи журнала")
            assertTrue(entriesB.all { it.jsonObject["userId"]!!.jsonPrimitive.content == userB })
            assertTrue(entriesB.none { it.jsonObject["userId"]!!.jsonPrimitive.content == userA })
        }
    }

    @Test
    fun `ExchangeLog-контекст - запись внутри userContext получает userId, вне контекста - null`() {
        ExchangeLog.record("req", "запись без контекста", ok = null, ms = null)
        assertEquals(null, ExchangeLog.all().last().userId)

        runBlocking {
            withContext(Dispatchers.Default) {
                (1..2).map { n ->
                    async(Dispatchers.Default) {
                        withContext(ExchangeLog.userContext("user-$n")) {
                            repeat(20) { ExchangeLog.record("req", "запись-$n", ok = null, ms = null) }
                        }
                    }
                }.awaitAll()
            }
        }
        val perUser = ExchangeLog.all().filter { it.userId?.startsWith("user-") == true }
        assertEquals(40, perUser.size)
        assertEquals(20, perUser.count { it.userId == "user-1" }, "изоляция параллельных контекстов")
        assertEquals(20, perUser.count { it.userId == "user-2" }, "изоляция параллельных контекстов")
    }
}
