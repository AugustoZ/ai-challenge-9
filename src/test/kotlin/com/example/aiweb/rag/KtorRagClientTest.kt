package com.example.aiweb.rag

import com.example.aiweb.config.RagConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KtorRagClient против фейкового sidecar: com.sun.net.httpserver.HttpServer
 * (порт 0, JDK built-in; ktor-client-mock запрещён — новых зависимостей нет).
 * Каноничный JSON sidecar: [{chunk_id,score,source,title,section,content}].
 * Клиент возвращает [Passage] с ПОЛНЫМ content (контракт задачи 6); обрезка
 * до snippet — на этапе [Passage.toRetrievedSource].
 */
class KtorRagClientTest {

    private var server: HttpServer? = null
    /** Тела запросов, пойманные фейковым сервером. */
    private val capturedBodies = Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun setUp() {
        capturedBodies.clear()
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    /** Запускает фейковый sidecar на свободном порту; возвращает полный URL /search. */
    private fun startFakeSidecar(responder: (HttpExchange) -> Unit): String {
        val http = HttpServer.create(InetSocketAddress(0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/search") { exchange ->
            try {
                capturedBodies.add(exchange.requestBody.readBytes().decodeToString())
                responder(exchange)
            } finally {
                exchange.close()
            }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/search"
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    // ------------------------------------------------------------------
    // (а) Каноничный JSON sidecar → парсинг в Passage с полным content
    // ------------------------------------------------------------------

    @Test
    fun `парсит каноничный JSON sidecar в Passage с полным content`() = runBlocking {
        val canonical = """
            [
              {"chunk_id":"c1","score":0.87,"source":"gw-club-docs/faq.md","title":"FAQ","section":"Порталы","content":"Порталы открываются через меню."},
              {"chunk_id":"c2","score":0.42,"source":"gw-club-docs/guide.md","title":"Гайд","section":"Ритуалы","content":"Ритуал начинается с приветствия."},
              {"chunk_id":"c3","score":0.11,"source":"gw-club-docs/lore.md","title":"Лор","section":"История","content":"Клуб основан давно."}
            ]
        """.trimIndent()
        val url = startFakeSidecar { respondJson(it, 200, canonical) }

        val result = KtorRagClient(RagConfig(url = url)).search("порталы", 3)

        assertEquals(3, result.size)
        val first = result[0]
        assertEquals("c1", first.chunkId)
        assertEquals("gw-club-docs/faq.md", first.source)
        assertEquals("FAQ", first.title)
        assertEquals("Порталы", first.section)
        assertEquals(0.87, first.score)
        assertEquals("Порталы открываются через меню.", first.content)
        assertEquals(0.42, result[1].score)
        assertEquals("c3", result[2].chunkId)
    }

    @Test
    fun `клиент возвращает полный content без обрезки, срез до snippet - в toRetrievedSource`() = runBlocking {
        val longContent = "А" .repeat(500) + "ХВОСТ"
        val canonical = """[{"chunk_id":"c1","score":0.9,"source":"f.md","title":"T","section":"S","content":"$longContent"}]"""
        val url = startFakeSidecar { respondJson(it, 200, canonical) }

        val passage = KtorRagClient(RagConfig(url = url)).search("q", 1).single()

        // Клиент отдаёт ПОЛНЫЙ content в Passage (обрезка не на клиенте)
        assertEquals(longContent, passage.content)
        assertEquals(longContent.length, passage.content.length)
        // Срез до snippet (≤ 200 симв.) — на этапе toRetrievedSource
        val source = passage.toRetrievedSource()
        assertEquals(200, source.snippet.length)
        assertEquals(longContent.take(200), source.snippet)
        assertTrue(source.snippet.length < passage.content.length)
    }

    @Test
    fun `тело запроса - POST с query и k`() = runBlocking {
        val url = startFakeSidecar { respondJson(it, 200, "[]") }

        KtorRagClient(RagConfig(url = url)).search("тестовый запрос", 4)

        val sent = capturedBodies.single()
        val parsed = Json.parseToJsonElement(sent).jsonObject
        assertEquals("тестовый запрос", parsed["query"]!!.jsonPrimitive.content)
        assertEquals(4, parsed["k"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `k=0 клампится в 1 в исходящем теле`() = runBlocking {
        val url = startFakeSidecar { respondJson(it, 200, "[]") }

        KtorRagClient(RagConfig(url = url)).search("q", 0)

        val parsed = Json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals(1, parsed["k"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `отрицательный k клампится в 1`() = runBlocking {
        val url = startFakeSidecar { respondJson(it, 200, "[]") }

        KtorRagClient(RagConfig(url = url)).search("q", -7)

        val parsed = Json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals(1, parsed["k"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `HTTP 500 - понятная ошибка IllegalStateException`() = runBlocking {
        val url = startFakeSidecar { respondJson(it, 500, """{"error":"index down"}""") }

        try {
            KtorRagClient(RagConfig(url = url, timeoutMs = 2_000)).search("q", 2)
            fail("ожидалось исключение при HTTP 500")
        } catch (e: IllegalStateException) {
            assertTrue(
                e.message!!.contains("500"),
                "сообщение должно называть код ответа: ${e.message}"
            )
        }
    }

    @Test
    fun `таймаут honored - медленный сервер роняет запрос`() = runBlocking {
        val url = startFakeSidecar {
            Thread.sleep(1_500)
            respondJson(it, 200, "[]")
        }

        try {
            KtorRagClient(RagConfig(url = url, timeoutMs = 300)).search("q", 1)
            fail("ожидался срыв запроса по таймауту")
        } catch (e: IOException) {
            // HttpRequestTimeoutException — подкласс IOException; это ожидаемый путь
        }
    }

    @Test
    fun `пустой массив sidecar - пустой список`() = runBlocking {
        val url = startFakeSidecar { respondJson(it, 200, "[]") }

        val result = KtorRagClient(RagConfig(url = url)).search("q", 5)

        assertTrue(result.isEmpty())
    }
}

/** Отдельная проверка: отказ соединения (мёртвый порт) пробрасывается клиентом — RagService его гасит. */
class KtorRagClientDeadPortTest {

    @Test
    fun `мёртвый порт - IOException, а не зависание`() = runBlocking {
        val deadClient = KtorRagClient(RagConfig(url = "http://127.0.0.1:1/search", timeoutMs = 1_000))

        try {
            deadClient.search("q", 2)
            fail("ожидалось исключение при отказе соединения")
        } catch (expected: IOException) {
            // ConnectException — подкласс IOException; ок
        }
    }
}
