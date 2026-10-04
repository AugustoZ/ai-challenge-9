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
 * любая ошибка поиска гасится в warn-лог + RetrieveResult.Unavailable, основной
 * ответ не роняется. Warn обязан быть слышимым (не тихое проглатывание) —
 * проверяем вывод в stderr.
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
    // (б) Мёртвый порт + timeoutMs=1000 → Unavailable, БЕЗ исключения
    // ------------------------------------------------------------------

    @Test
    fun `мёртвый порт - Unavailable без исключения`() = runBlocking {
        val client = KtorRagClient(RagConfig(url = "http://127.0.0.1:1/search", timeoutMs = 1_000))
        val service = RagService(client)

        val result = service.retrieve("вопрос", 3)

        assertTrue(result is RetrieveResult.Unavailable, "fail-open: RAG недоступен → Unavailable, ответ не роняется")
    }

    @Test
    fun `медленный сервер по таймауту - Unavailable без исключения`() = runBlocking {
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

            assertTrue(result is RetrieveResult.Unavailable, "таймаут → Unavailable, без исключения")
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
        val kBefores = mutableListOf<Int?>()
        val thresholds = mutableListOf<Double?>()
        val reranks = mutableListOf<Boolean>()

        override suspend fun search(
            query: String,
            k: Int,
            kBefore: Int?,
            threshold: Double?,
            rerank: Boolean
        ): List<Passage> {
            queries.add(query)
            ks.add(k)
            kBefores.add(kBefore)
            thresholds.add(threshold)
            reranks.add(rerank)
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

        assertTrue(result is RetrieveResult.Ok, "успешный ответ sidecar → Ok")
        assertEquals(passages, (result as RetrieveResult.Ok).passages)
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
    fun `kBefore threshold и rerank прокидываются в клиент`() = runBlocking {
        val fake = FakeRagClient()
        val service = RagService(fake)

        service.retrieve("запрос", 7, kBefore = 20, threshold = 0.8, rerank = true)

        assertEquals(listOf<Int?>(20), fake.kBefores)
        assertEquals(listOf<Double?>(0.8), fake.thresholds)
        assertEquals(listOf(true), fake.reranks)
    }

    @Test
    fun `rerank-degrade - 400 Реранкер не настроен повторяет БЕЗ реранка`() = runBlocking {
        val fake = object : RagClient {
            val calls = mutableListOf<Boolean>()
            override suspend fun search(
                query: String,
                k: Int,
                kBefore: Int?,
                threshold: Double?,
                rerank: Boolean
            ): List<Passage> {
                calls.add(rerank)
                if (rerank) throw IllegalStateException("RAG sidecar вернул ошибку 400: Реранкер не настроен")
                return listOf(Passage("c1", "f.md", "T", "S", 0.9, "полный текст"))
            }
        }
        val service = RagService(fake)

        val result = service.retrieve("запрос", 3, rerank = true)

        assertEquals(2, fake.calls.size, "ОДИН повторный поиск без реранка")
        assertEquals(listOf(true, false), fake.calls, "сначала с реранком, потом без")
        val ok = result as RetrieveResult.Ok
        assertEquals(1, ok.passages.size, "после деградации результаты приходят")
        assertTrue(errText().contains("Реранкер не настроен"), "русский warn о деградации: ${errText()}")
    }

    @Test
    fun `бросающий клиент - Unavailable и warn, исключение не уходит наверх`() = runBlocking {
        val service = RagService(FakeRagClient(error = IllegalStateException("sidecar взорвался")))

        val result = try {
            service.retrieve("q", 3)
        } catch (e: Exception) {
            fail("fail-open: исключение не должно покидать retrieve(): ${e.message}")
        }

        assertTrue(result is RetrieveResult.Unavailable)
        assertEquals("sidecar взорвался", (result as RetrieveResult.Unavailable).reason)
        assertTrue(errText().contains("RAG недоступен"))
        assertTrue(errText().contains("sidecar взорвался"), "в warn-логе видна причина: ${errText()}")
    }
}
