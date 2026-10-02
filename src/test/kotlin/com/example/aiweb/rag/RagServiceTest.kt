package com.example.aiweb.rag

import com.example.aiweb.config.RagConfig
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fail-open семантика [RagService] (по образцу runJudge, Routes.kt:2476-2515):
 * любая ошибка поиска гасится в warn-лог + emptyList, основной ответ не роняется.
 * Warn обязан быть слышимым (не тихое проглатывание) — проверяем вывод в stderr.
 */
class RagServiceTest {

    private val originalErr = System.err
    private val errCapture = ByteArrayOutputStream()

    @BeforeTest
    fun captureStderr() {
        errCapture.reset()
        System.setErr(PrintStream(errCapture, true, Charsets.UTF_8))
    }

    @AfterTest
    fun restoreStderr() {
        System.setErr(originalErr)
    }

    private fun errText(): String = errCapture.toString("UTF-8")

    // ------------------------------------------------------------------
    // (б) Мёртвый порт + timeoutMs=1000 → emptyList, БЕЗ исключения
    // ------------------------------------------------------------------

    @Test
    fun `мёртвый порт - emptyList без исключения`() = runBlocking {
        val client = KtorRagClient(RagConfig(url = "http://127.0.0.1:1/search", timeoutMs = 1_000))
        val service = RagService(client)

        val result = service.retrieve("вопрос", 3)

        assertTrue(result.isEmpty(), "fail-open: RAG недоступен → пустой список, ответ не роняется")
    }

    @Test
    fun `медленный сервер по таймауту - emptyList без исключения`() = runBlocking {
        // Фейковый sidecar, спящий дольше timeoutMs: поднимаем прямо здесь.
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        http.createContext("/search") { exchange ->
            Thread.sleep(1_000)
            val bytes = "[]".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        http.start()
        try {
            val service = RagService(KtorRagClient(RagConfig(url = "http://127.0.0.1:${http.address.port}/search", timeoutMs = 300)))

            val result = service.retrieve("вопрос", 2)

            assertTrue(result.isEmpty(), "таймаут → emptyList, без исключения")
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun `fail-open НЕ тихий - в stderr есть предупреждение RAG недоступен`() = runBlocking {
        val service = RagService(KtorRagClient(RagConfig(url = "http://127.0.0.1:1/search", timeoutMs = 1_000)))

        service.retrieve("вопрос", 3)

        assertTrue(
            errText().contains("RAG недоступен"),
            "warn-лог обязателен (не тихое проглатывание), stderr: ${errText()}"
        )
    }

    // ------------------------------------------------------------------
    // Прокидывание результатов и аргументов через фейковый клиент
    // ------------------------------------------------------------------

    /** Фейковый RagClient: запоминает вызовы, отвечает заготовкой или исключением. */
    private class FakeRagClient(
        private val answer: List<Passage> = emptyList(),
        private val error: Exception? = null
    ) : RagClient {
        val queries = mutableListOf<String>()
        val ks = mutableListOf<Int>()

        override suspend fun search(query: String, k: Int): List<Passage> {
            queries.add(query)
            ks.add(k)
            error?.let { throw it }
            return answer
        }
    }

    @Test
    fun `успешный поиск прокидывается как есть`() = runBlocking {
        val passages = listOf(
            Passage("c1", "f.md", "T", "S", 0.9, "полный текст чанка")
        )
        val service = RagService(FakeRagClient(answer = passages))

        val result = service.retrieve("порталы", 5)

        assertEquals(passages, result)
    }

    @Test
    fun `query и k прокидываются в клиент`() = runBlocking {
        val fake = FakeRagClient()
        val service = RagService(fake)

        service.retrieve("запрос", 7)

        assertEquals(listOf("запрос"), fake.queries)
        assertEquals(listOf(7), fake.ks)
    }

    @Test
    fun `бросающий клиент - emptyList и warn, исключение не уходит наверх`() = runBlocking {
        val service = RagService(FakeRagClient(error = IllegalStateException("sidecar взорвался")))

        val result = try {
            service.retrieve("q", 3)
        } catch (e: Exception) {
            fail("fail-open: исключение не должно покидать retrieve(): ${e.message}")
        }

        assertTrue(result.isEmpty())
        assertTrue(errText().contains("RAG недоступен"))
        assertTrue(errText().contains("sidecar взорвался"), "в warn-логе видна причина: ${errText()}")
    }
}
