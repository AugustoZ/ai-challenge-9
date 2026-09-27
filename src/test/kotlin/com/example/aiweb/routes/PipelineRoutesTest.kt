package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Роуты пайплайна (задача 10): POST /api/pipeline/run (202/404/409) и
 * GET /api/pipeline/status (200/404). Stub-тулы передаются через
 * pipelineExtraTools, сценарии — через pipelineScenarioDir (temp-директория).
 */
class PipelineRoutesTest {

    private class FakeLLMClient(private val reply: ChatResult) : LLMClient {
        override suspend fun ask(call: LLMCall): ChatResult = reply
    }

    private class Stub(
        override val name: String,
        private val handler: suspend (String) -> ToolResult
    ) : Tool {
        override val description = "stub"
        override val parametersJsonSchema: JsonElement = JsonObject(emptyMap())
        val calls = mutableListOf<String>()
        override suspend fun execute(argsJson: String): ToolResult {
            calls.add(argsJson)
            return handler(argsJson)
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-pipeline").toString()

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

    private fun writeScenario(dir: Path, fileName: String, content: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(fileName), content)
    }

    private fun withPipelineApp(
        scenarioDir: String,
        extraTools: List<Tool>,
        block: suspend (base: String) -> Unit
    ) {
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(tempDataDir()),
                    clientFactory = { chat },
                    summarizerFactory = { _ ->
                        object : com.example.aiweb.session.HistorySummarizer {
                            override suspend fun summarize(
                                previous: String?,
                                batch: List<com.example.aiweb.model.OpenAIMessage>
                            ) = com.example.aiweb.session.SummarizeResult("", 0)
                        }
                    },
                    factExtractorFactory = { _ ->
                        object : FactExtractor {
                            override suspend fun extract(
                                current: List<com.example.aiweb.model.Fact>,
                                userMessage: String
                            ): List<com.example.aiweb.model.Fact>? = null
                        }
                    },
                    pipelineScenarioDir = scenarioDir,
                    pipelineExtraTools = extraTools
                )
            }
        }) { app ->
            runBlocking { block(app.base) }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 20_000, intervalMs: Long = 100, block: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "waitUntil: таймаут ${timeoutMs} мс" }
            delay(intervalMs)
        }
    }

    private suspend fun runPipeline(client: HttpClient, base: String, name: String): HttpResponse =
        client.post("$base/api/pipeline/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }

    @Test
    fun `run returns 202 and status becomes succeeded`() {
        val dir = Files.createTempDirectory("pipeline-routes")
        writeScenario(
            dir, "doc.json",
            """{"name":"doc","steps":[{"id":"s1","tool":"echo","args":{}}]}"""
        )
        val echo = Stub("echo") { ToolResult(true, "ECHO:OK") }

        withPipelineApp(dir.toString(), listOf(echo)) { base ->
            val client = testHttpClient()
            val runResp = runPipeline(client, base, "doc")
            assertEquals(HttpStatusCode.Accepted, runResp.status)
            val runId = Json.parseToJsonElement(runResp.bodyAsText()).jsonObject["runId"]!!.jsonPrimitive.content
            assertTrue(runId.isNotBlank())

            waitUntil {
                val s = Json.parseToJsonElement(
                    client.get("$base/api/pipeline/status?name=doc").bodyAsText()
                ).jsonObject
                s["state"]!!.jsonPrimitive.content != "running"
            }
            val status = Json.parseToJsonElement(
                client.get("$base/api/pipeline/status?name=doc").bodyAsText()
            ).jsonObject
            assertEquals("succeeded", status["state"]!!.jsonPrimitive.content)
            val step0 = status["steps"]!!.jsonArray[0].jsonObject
            assertEquals("done", step0["status"]!!.jsonPrimitive.content)
            assertEquals("ECHO:OK", step0["resultPreview"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown scenario returns 404`() {
        val dir = Files.createTempDirectory("pipeline-routes")
        withPipelineApp(dir.toString(), emptyList()) { base ->
            val client = testHttpClient()
            val resp = runPipeline(client, base, "nope")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            assertTrue(resp.bodyAsText().contains("Сценарий не найден"))
        }
    }

    @Test
    fun `status of unknown scenario returns 404`() {
        val dir = Files.createTempDirectory("pipeline-routes")
        withPipelineApp(dir.toString(), emptyList()) { base ->
            val response = testHttpClient().get("$base/api/pipeline/status?name=missing")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `second run of active scenario returns 409 then 202 after completion`() {
        val dir = Files.createTempDirectory("pipeline-routes")
        writeScenario(
            dir, "blk.json",
            """{"name":"blk","steps":[{"id":"s1","tool":"block","args":{}}]}"""
        )
        val gate = CompletableDeferred<Unit>()
        val block = Stub("block") { gate.await(); ToolResult(true, "done") }

        withPipelineApp(dir.toString(), listOf(block)) { base ->
            val client = testHttpClient()

            assertEquals(HttpStatusCode.Accepted, runPipeline(client, base, "blk").status)
            waitUntil {
                val s = Json.parseToJsonElement(
                    client.get("$base/api/pipeline/status?name=blk").bodyAsText()
                ).jsonObject
                s["state"]!!.jsonPrimitive.content == "running"
            }

            assertEquals(HttpStatusCode.Conflict, runPipeline(client, base, "blk").status)

            gate.complete(Unit)
            waitUntil {
                val s = Json.parseToJsonElement(
                    client.get("$base/api/pipeline/status?name=blk").bodyAsText()
                ).jsonObject
                s["state"]!!.jsonPrimitive.content != "running"
            }
            assertEquals("succeeded", Json.parseToJsonElement(
                client.get("$base/api/pipeline/status?name=blk").bodyAsText()
            ).jsonObject["state"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.Accepted, runPipeline(client, base, "blk").status)
        }
    }
}
