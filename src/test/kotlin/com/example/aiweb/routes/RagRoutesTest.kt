package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.config.RagConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.rag.KtorRagClient
import com.example.aiweb.rag.Passage
import com.example.aiweb.rag.RagClient
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RAG-интеграция в путь агента (задача 6 плана rag-mode): при ragEnabled=true
 * и настроенном сервером RAG (config.rag.url != null) блок документов
 * инжектится последним system-сообщением seed, а ChatResponse эхом несёт
 * ragEnabled и retrievedSources (snippet ≤ 200). Fail-open при недоступном RAG;
 * @EncodeDefault(ALWAYS) на ключах ответа.
 */
class RagRoutesTest {

    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    private class FakeRagClient(
        private val passages: List<Passage> = emptyList(),
        private val throwError: Exception? = null
    ) : RagClient {
        var calls = 0
        val queries = mutableListOf<String>()
        val ks = mutableListOf<Int>()

        override suspend fun search(query: String, k: Int): List<Passage> {
            calls++
            queries.add(query)
            ks.add(k)
            throwError?.let { throw it }
            return passages
        }
    }

    private fun passage(
        id: String,
        content: String,
        source: String = "docs/$id.md",
        section: String = "Раздел"
    ) = Passage(chunkId = id, source = source, title = "Заголовок $id", section = section, score = 0.9, content = content)

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-rag").toString()

    private fun config(dataDir: String, rag: RagConfig = RagConfig()) = AppConfig(
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
        rag = rag
    )

    private fun withApp(
        ragConfig: RagConfig = RagConfig(),
        ragFactory: (RagConfig) -> RagClient = { FakeRagClient() },
        block: suspend (base: String, fake: FakeLLMClient) -> Unit
    ) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir, ragConfig),
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
                    },
                    ragFactory = ragFactory
                )
            }
        }) { app ->
            kotlinx.coroutines.runBlocking { block(app.base, chat) }
        }
    }

    private suspend fun createSession(client: io.ktor.client.HttpClient, base: String): String {
        val response = client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = "разговор для создания сессии"))
        }
        val raw = response.bodyAsText()
        val sid = Json.parseToJsonElement(raw).jsonObject["sessionId"]!!.jsonPrimitive.content
        return sid
    }

    private suspend fun postMemory(client: io.ktor.client.HttpClient, base: String, body: String) {
        client.post("$base/api/memory") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun LLMCall.ragBlock(): String? =
        messages.lastOrNull { it.role == "system" && it.content.orEmpty().contains("РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ") }?.content

    // (а) ragEnabled=true + FakeRagClient(2 чанка)
    @Test
    fun `ragEnabled true - блок документов в seed и sources в ответе`() {
        val chunks = listOf(
            passage("c1", "Порталы открываются через меню.", "docs/faq.md", "Порталы"),
            passage("c2", "А".repeat(300) + "ХВОСТ", "docs/guide.md", "Ритуалы")
        )
        val rag = FakeRagClient(passages = chunks)
        withApp(ragConfig = RagConfig(url = "http://fake/search", k = 2), ragFactory = { rag }) { base, fake ->
            val client = testHttpClient()
            val response = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "порталы", ragEnabled = true))
            }.body<ChatResponse>()

            val block = assertNotNull(fake.calls.last().ragBlock(), "RAG-блок должен быть в seed")
            assertTrue(block.contains("РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"), "маркер блока: $block")
            assertTrue(block.contains("Порталы открываются через меню."), "первый чанк в блоке")
            assertTrue(block.contains("ХВОСТ"), "полный текст второго чанка в блоке (без обрезки на клиенте)")

            assertEquals(true, response.ragEnabled)
            assertEquals(2, response.retrievedSources.size)
            assertEquals("c1", response.retrievedSources[0].chunkId)
            assertEquals("Порталы открываются через меню.", response.retrievedSources[0].snippet)
            assertTrue(response.retrievedSources.all { it.snippet.length <= 200 }, "snippet ≤ 200 символов")
            assertEquals("А".repeat(200), response.retrievedSources[1].snippet)
            client.close()
        }
    }

    // (б) ragEnabled=false — RAG не вызывается
    @Test
    fun `ragEnabled false - RAG не вызывается, sources пустые, echo false`() {
        val rag = FakeRagClient(passages = listOf(passage("c1", "текст")))
        withApp(ragConfig = RagConfig(url = "http://fake/search"), ragFactory = { rag }) { base, _ ->
            val client = testHttpClient()
            val response = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос", ragEnabled = false))
            }.body<ChatResponse>()

            assertEquals(0, rag.calls, "RAG-поиск не вызывается при ragEnabled=false")
            assertEquals(false, response.ragEnabled)
            assertEquals(emptyList(), response.retrievedSources)
            client.close()
        }
    }

    // (в) Fail-open: мёртвый порт
    @Test
    fun `fail-open - недоступный RAG даёт 200 и пустые sources`() {
        withApp(
            ragConfig = RagConfig(url = "http://127.0.0.1:1/search", timeoutMs = 1_000),
            ragFactory = { KtorRagClient(it) }
        ) { base, _ ->
            val client = testHttpClient()
            val response = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос", ragEnabled = true))
            }.body<ChatResponse>()

            assertTrue(response.reply.isNotBlank(), "нормальный ответ при недоступном RAG")
            assertEquals(emptyList(), response.retrievedSources, "sources пустые (fail-open)")
            assertEquals(true, response.ragEnabled, "echo ragEnabled как запрошено")
            client.close()
        }
    }

    // (г) Сериализация: @EncodeDefault(ALWAYS)
    @Test
    fun `сериализация - при выключенном RAG ключи ragEnabled и retrievedSources всегда в JSON`() {
        withApp(ragConfig = RagConfig()) { base, _ ->
            val client = testHttpClient()
            val raw = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос"))
            }.bodyAsText()

            assertTrue(raw.contains("\"retrievedSources\":[]"), "ключ retrievedSources всегда сериализуется: $raw")
            assertTrue(raw.contains("\"ragEnabled\":false"), "ключ ragEnabled всегда сериализуется: $raw")
            client.close()
        }
    }

    // (д) Combo: память + RAG
    @Test
    fun `combo - память и RAG оба в seed, RAG-блок последний`() {
        val rag = FakeRagClient(passages = listOf(passage("c1", "текст RAG")))
        withApp(ragConfig = RagConfig(url = "http://fake/search"), ragFactory = { rag }) { base, fake ->
            val client = testHttpClient()
            val sid = createSession(client, base)
            postMemory(client, base, """{"kind":"profile","title":"Профиль","content":"Аугусто"}""")

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос", sessionId = sid, ragEnabled = true))
            }.body<ChatResponse>()

            val messages = fake.calls.last().messages
            val memIdx = messages.indexOfLast { it.role == "system" && it.content.orEmpty().contains("Долговременная память:") }
            val ragIdx = messages.indexOfLast { it.role == "system" && it.content.orEmpty().contains("РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ") }
            assertTrue(memIdx >= 0, "блок памяти в seed")
            assertTrue(ragIdx >= 0, "RAG-блок в seed")
            assertTrue(ragIdx > memIdx, "RAG-блок после блока памяти")
            assertEquals(messages.lastIndex, ragIdx, "RAG-блок остаётся последним сообщением seed")
            client.close()
        }
    }

    // (е) ragEnabled=true, но url не настроен
    @Test
    fun `ragEnabled true но url не настроен - клиент не создаётся, echo false`() {
        withApp(
            ragConfig = RagConfig(),
            ragFactory = { throw AssertionError("RAG-клиент не должен создаваться при url == null") }
        ) { base, _ ->
            val client = testHttpClient()
            val response = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос", ragEnabled = true))
            }.body<ChatResponse>()

            assertEquals(false, response.ragEnabled, "нет серверного RAG — echo false")
            assertEquals(emptyList(), response.retrievedSources, "sources пустые")
            client.close()
        }
    }

    // (ж) Стрим: Final несёт retrievedSources
    @Test
    fun `стрим - Final несёт retrievedSources размером 2`() {
        val rag = FakeRagClient(passages = listOf(passage("c1", "текст один"), passage("c2", "текст два")))
        withApp(ragConfig = RagConfig(url = "http://fake/search"), ragFactory = { rag }) { base, _ ->
            val client = testHttpClient()
            val raw = client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "вопрос", ragEnabled = true))
            }.bodyAsText()

            val frames = raw.split("\n\n").mapNotNull { chunk ->
                chunk.lineSequence()
                    .firstOrNull { it.startsWith("data: ") }
                    ?.removePrefix("data: ")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it).jsonObject }
            }
            val finalFrame = frames.firstOrNull { it["type"]?.jsonPrimitive?.content == "final" }
            assertNotNull(finalFrame, "поток завершается кадром final: ${frames.map { it["type"]?.jsonPrimitive?.content }}")

            val response = finalFrame["response"]!!.jsonObject
            val sources = response["retrievedSources"]!!.jsonArray
            assertEquals(2, sources.size, "Final несёт retrievedSources.size == 2")
            assertEquals("c1", sources[0].jsonObject["chunkId"]!!.jsonPrimitive.content)
            assertEquals("c2", sources[1].jsonObject["chunkId"]!!.jsonPrimitive.content)
            client.close()
        }
    }
}
