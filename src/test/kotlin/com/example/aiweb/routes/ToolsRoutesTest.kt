package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.McpServerConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GET /api/tools (задача 9 плана mcp-integration): инвентарь инструментов и
 * статусы подключений MCP. Пустой mcp-раздел — servers=[] и нативные инструменты
 * на месте; битая команда — status failed/degraded с непустой error, без env
 * в ответе. Все тесты — на фейковом LLM, без сети.
 */
class ToolsRoutesTest {

    private class FakeLLMClient(reply: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        @Volatile
        var next: ChatResult = reply

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return next
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-tools").toString()

    private fun config(dataDir: String, mcpServers: List<McpServerConfig> = emptyList()) = AppConfig(
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
        dataDir = dataDir,
        mcpServers = mcpServers
    )

    private fun withApp(
        mcpServers: List<McpServerConfig> = emptyList(),
        block: suspend (base: String, fake: FakeLLMClient) -> Unit
    ) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir, mcpServers),
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : HistorySummarizer {
                            override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>) =
                                SummarizeResult("", 0)
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
        }) { app ->
            runBlocking { block(app.base, chat) }
        }
    }

    /** Ждёт условие (HTTP-полл), бросает исключение по таймауту. */
    private suspend fun waitUntil(timeoutMs: Long = 20_000, intervalMs: Long = 100, block: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "waitUntil: таймаут ${timeoutMs} мс" }
            delay(intervalMs)
        }
    }

    @Test
    fun `tools inventory has 200 with servers native builtin sections`() {
        withApp { base, _ ->
            val client: HttpClient = testHttpClient()
            val response: HttpResponse = client.get("$base/api/tools")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // без mcp-раздела — пустой список серверов
            val servers = json["servers"]!!.jsonArray
            assertTrue(servers.isEmpty(), "без mcp-раздела servers должен быть пустым: $json")

            // нативные инструменты задачи 4 — все четыре, с source native-tool
            val native = json["native"]!!.jsonArray
            val nativeNames = native.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("reminder", "summarize", "search", "saveToFile"), nativeNames)
            native.forEach { item ->
                assertEquals("native-tool", item.jsonObject["source"]!!.jsonPrimitive.content)
                assertTrue(item.jsonObject["description"]!!.jsonPrimitive.content.isNotBlank())
            }

            // встроенные (раздел "tools" конфига, null — три built-in)
            val builtin = json["builtin"]!!.jsonArray
            val builtinNames = builtin.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("current_datetime", "calculator", "http_get"), builtinNames)
            builtin.forEach { item ->
                assertEquals("builtin", item.jsonObject["source"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `bad command server reports failed status with non empty error`() {
        val broken = McpServerConfig(name = "broken", command = "/nonexistent-mcp-cmd-xyz")
        withApp(mcpServers = listOf(broken)) { base, _ ->
            val client: HttpClient = testHttpClient()

            // менеджер стартует асинхронно со стартом приложения — поллим, пока
            // статус не уйдёт из начального (disabled) в failed/degraded
            val seen = mutableMapOf<String, String>()
            waitUntil {
                val json = Json.parseToJsonElement(client.get("$base/api/tools").bodyAsText()).jsonObject
                val servers = json["servers"]!!.jsonArray
                check(servers.size == 1) { "один сервер в инвентаре: $json" }
                val server = servers[0].jsonObject
                assertEquals("broken", server["name"]!!.jsonPrimitive.content)
                // env в ответ не попадает (значения скрыты в конфиге)
                assertFalse(server.containsKey("env"), "env не должен присутствовать в ответе: $server")
                val status = server["status"]!!.jsonPrimitive.content
                seen["status"] = status
                status in setOf("failed", "degraded")
            }

            // финальная проверка с полным телом ответа
            val json = Json.parseToJsonElement(client.get("$base/api/tools").bodyAsText()).jsonObject
            val server = (json["servers"] as JsonArray)[0].jsonObject
            assertEquals(seen["status"], server["status"]!!.jsonPrimitive.content)
            assertTrue(server["status"]!!.jsonPrimitive.content in setOf("failed", "degraded"))
            val error = server["error"]
            assertNotNull(error, "у упавшего сервера должен быть error")
            assertTrue(error.jsonPrimitive.content.isNotBlank(), "error должен быть непустым")
            assertTrue(server["tools"]!!.jsonArray.isEmpty(), "у неподключённого сервера тулов нет")

            // нативные инструменты при этом на месте
            val nativeNames = (json["native"]!!.jsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("reminder", "summarize", "search", "saveToFile"), nativeNames)
        }
    }
}
