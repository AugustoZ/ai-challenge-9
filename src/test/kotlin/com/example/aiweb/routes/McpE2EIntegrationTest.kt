package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.McpServerConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.agent.AgentLoop
import com.example.aiweb.mcp.McpServerManager
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenAIToolCallFunction
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult
import com.example.aiweb.tools.NativeToolTransport
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin-интеграция E2E (задача 13 плана mcp-integration-shturman): echo-сервер
 * из test-ресурсов → /api/tools → спеки в LLM. Реальный python-процесс MCP
 * (venv), Java SDK 2.0.1, фейковый LLM — сети нет.
 *
 *  1. GET /api/tools: servers[0].status = connected, тул mcp__echo__echo в
 *     инвентаре (description < 200 символов); рост tool-листа —
 *     3 builtin + 4 native + 1 mcp = 8 specs суммарно по секциям ответа.
 *  2. Агентный ход (паттерн AgentLoopTest): FakeLLMClient отдаёт toolCall
 *     (mcp__echo__echo, {"text":"ping"}) → AgentLoop диспатчит через
 *     McpToolAdapter живого сервера → ToolResult "ECHO:ping", в контексте
 *     следующего вызова появляется сообщение role="tool".
 *
 * Скипается (assumeTrue), если venv-python или echo-скрипт недоступны —
 * как McpEchoIntegrationTest; путь venv/скрипта относительный от корня репо
 * (cwd gradle-тестов = repo root, как и cwd gradle run).
 */
class McpE2EIntegrationTest {

    private val venvPython: Path = Path.of("mcp-servers/.venv/bin/python")
    private val echoServer: Path = Path.of("src/test/resources/mcp/fake_echo_server.py")

    /** Конфиг echo-сервера — ровно как в config.dev.json (относительные пути). */
    private fun echoConfig() = McpServerConfig(
        name = "echo",
        command = venvPython.toString(),
        args = listOf(echoServer.toString()),
        enabled = true
    )

    private var manager: McpServerManager? = null

    @AfterTest
    fun tearDownManager() {
        manager?.close()
        manager = null
    }

    // ── Тест 1: /api/tools показывает подключённый echo и рост tool-листа ──

    private class FakeLLMClient(reply: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        @Volatile
        var next: ChatResult = reply

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return next
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-mcp-e2e").toString()

    private fun config(dataDir: String, mcpServers: List<McpServerConfig>) = AppConfig(
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
        mcpServers: List<McpServerConfig>,
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
    fun `api tools shows connected echo server and grown tool list`() {
        assumeTrue(Files.exists(venvPython)) { "Python venv missing: $venvPython" }
        assumeTrue(Files.exists(echoServer)) { "Echo server missing: $echoServer" }

        withApp(mcpServers = listOf(echoConfig())) { base, _ ->
            val client: HttpClient = testHttpClient()

            // менеджер стартует асинхронно со стартом приложения — поллим
            // до перехода статуса в connected (запуск python + handshake)
            waitUntil {
                val json = Json.parseToJsonElement(client.get("$base/api/tools").bodyAsText()).jsonObject
                val servers = json["servers"]!!.jsonArray
                servers.isNotEmpty() &&
                    servers[0].jsonObject["status"]!!.jsonPrimitive.content == "connected"
            }

            val response: HttpResponse = client.get("$base/api/tools")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // (a) echo подключён, тул mcp__echo__echo в инвентаре
            val servers = json["servers"]!!.jsonArray
            assertEquals(1, servers.size, "один mcp-сервер в инвентаре: $json")
            val server = servers[0].jsonObject
            assertEquals("echo", server["name"]!!.jsonPrimitive.content)
            assertEquals("connected", server["status"]!!.jsonPrimitive.content)
            assertFalse(server.containsKey("error"), "у подключённого сервера error нет: $server")

            val mcpTools = server["tools"]!!.jsonArray
            // echo-сервер (task 5) отдаёт два тула: echo и fail; в инвентаре —
            // сырые имена сервера (префикс mcp__<сервер>__<тул> живёт в спеках)
            val mcpNames = mcpTools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("echo", "fail"), mcpNames, "тулы echo-сервера: $mcpTools")
            mcpTools.forEach { item ->
                assertEquals("mcp", item.jsonObject["source"]!!.jsonPrimitive.content)
                val description = item.jsonObject["description"]!!.jsonPrimitive.content
                assertTrue(description.isNotBlank(), "description mcp-тула непустой")
                assertTrue(description.length < 200, "description mcp-тула < 200 символов (сейчас ${description.length}): $description")
            }

            // (c) рост tool-листа: 3 builtin + 4 native + 2 mcp (echo+fail) = 9 specs
            val builtin = json["builtin"]!!.jsonArray
            val builtinNames = builtin.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("current_datetime", "calculator", "http_get"), builtinNames)
            val native = json["native"]!!.jsonArray
            val nativeNames = native.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("reminder", "summarize", "search", "saveToFile"), nativeNames)
            assertEquals(3, builtin.size, "builtin: $builtin")
            assertEquals(4, native.size, "native: $native")
            assertEquals(9, builtin.size + native.size + mcpTools.size, "сумма specs по секциям: $json")
        }
    }

    // ── Тест 2: агентный ход — toolCall диспатчится в живой echo-сервер ──

    /** Заготовленный ответ с одним вызовом инструмента (паттерн AgentLoopTest). */
    private fun toolCallResult(tool: String, args: String) = ChatResult(
        reply = "",
        promptTokens = 10,
        completionTokens = 3,
        totalTokens = 13,
        elapsedMs = 0L,
        toolCalls = listOf(
            OpenAIToolCall(
                id = "call-1",
                type = "function",
                function = OpenAIToolCallFunction(name = tool, arguments = args)
            )
        )
    )

    /** Заготовленный ответ обычным текстом (финальный ответ модели). */
    private fun textResult(text: String) = ChatResult(
        reply = text,
        promptTokens = 7,
        completionTokens = 2,
        totalTokens = 9,
        elapsedMs = 0L
    )

    /** LLMClient с очередью ответов (паттерн AgentLoopTest). */
    private class QueuedFakeLLMClient(private val canned: List<ChatResult>) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned.getOrNull(calls.size - 1)
                ?: error("Фейк: очередь ответов исчерпана на вызове №${calls.size}")
        }
    }

    @Test
    fun `agent loop dispatches tool call to live echo server and gets ECHO ping as tool role`() {
        assumeTrue(Files.exists(venvPython)) { "Python venv missing: $venvPython" }
        assumeTrue(Files.exists(echoServer)) { "Echo server missing: $echoServer" }

        runBlocking {
            // Реальный менеджер с echo-сервером: start() синхронно дожидается
            // handshake и tools/list — после него адаптер mcp__echo__echo готов
            val mcpManager = McpServerManager(listOf(echoConfig()))
            manager = mcpManager
            mcpManager.start()
            assertEquals(
                com.example.aiweb.mcp.McpConnectionStatus.CONNECTED,
                mcpManager.status("echo"),
                "echo-сервер должен подключиться: ${mcpManager.error("echo")}"
            )
            val registry = mcpManager.mcpRegistry()
            // echo-сервер отдаёт два тула: echo и fail
            assertEquals(
                setOf("mcp__echo__echo", "mcp__echo__fail"),
                registry.specs().map { it.function.name }.toSet(),
                "mcp-реестр: спеки тулов echo-сервера"
            )
            registry.specs().forEach { spec ->
                assertTrue(spec.function.description.length < 200, "description в спеке < 200 символов: ${spec.function.description}")
            }

            val fake = QueuedFakeLLMClient(
                listOf(
                    toolCallResult("mcp__echo__echo", """{"text":"ping"}"""),
                    textResult("Эхо вернулось: ECHO:ping")
                )
            )
            val events = mutableListOf<AgentEvent>()
            val seed = listOf(OpenAIMessage(role = "user", content = "пингани echo"))

            val outcome = AgentLoop(fake, NativeToolTransport(), registry)
                .run(seed, maxTokens = null, onEvent = { events.add(it) })

            // События хода: диспатч в MCP-тул и успешный результат ECHO:ping
            assertEquals(
                listOf(
                    AgentEvent.StepStarted(1),
                    AgentEvent.ToolCalled(1, "mcp__echo__echo", """{"text":"ping"}"""),
                    AgentEvent.ToolResult(1, "mcp__echo__echo", ok = true, content = "ECHO:ping"),
                    AgentEvent.StepStarted(2),
                    AgentEvent.Final(2, "Эхо вернулось: ECHO:ping", "готово")
                ),
                events
            )
            assertEquals("Эхо вернулось: ECHO:ping", outcome.reply)
            assertEquals("готово", outcome.reason)

            // Спека mcp-тула доносится до модели: tools первого вызова — оба тула echo-сервера,
            // префиксованные именем сервера из конфига
            assertEquals(
                setOf("mcp__echo__echo", "mcp__echo__fail"),
                fake.calls.first().tools.orEmpty().map { it.function.name }.toSet()
            )

            // В контексте второго вызова: assistant(tool_calls) → role="tool" с ECHO:ping
            val followUp = fake.calls[1].messages
            val assistant = followUp[followUp.size - 2]
            assertEquals("assistant", assistant.role)
            assertEquals("mcp__echo__echo", assistant.toolCalls?.single()?.function?.name)
            val toolReply = followUp.last()
            assertEquals("tool", toolReply.role)
            assertEquals("ECHO:ping", toolReply.content)
            assertEquals("call-1", toolReply.toolCallId)
            assertNotNull(toolReply.toolCallId)
        }
    }
}
