package com.example.aiweb.rag

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMClient
import com.example.aiweb.client.LLMCall
import kotlinx.coroutines.delay
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
 * Поведение [LLMQueryRewriter] (задача 5 плана shturman-rag-stage2):
 * LLM-рерайт поискового запроса через существующий [LLMClient], с fail-open.
 *
 * Фейковый AI-клиент по образцу FakeLLMClient (client/FakeLLMClientTest.kt) —
 * та же семантика, что у [RagServiceTest] (System.err capture + runBlocking,
 * БЕЗ coroutines-test в зависимостях).
 */
class QueryRewriterTest {

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

    /** Фейковый [LLMClient]: запоминает вызовы, отвечает заготовкой или исключением/задержкой. */
    private class FakeLLMClient(
        private val reply: String = "",
        private val error: Exception? = null,
        private val sleepMs: Long = 0L
    ) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            if (sleepMs > 0) delay(sleepMs)
            error?.let { throw it }
            return ChatResult(
                reply = reply,
                promptTokens = 1,
                completionTokens = reply.length,
                totalTokens = 1 + reply.length,
                elapsedMs = sleepMs
            )
        }
    }

    // ------------------------------------------------------------------
    // Промпт-контракт (синхрон с mock-llm, задача 7) и параметры вызова
    // ------------------------------------------------------------------

    @Test
    fun `system-промпт содержит маркер переписываешь поисковый запрос`() {
        assertTrue(
            LLMQueryRewriter.REWRITE_SYSTEM_PROMPT.contains("переписываешь поисковый запрос"),
            "подстрока «переписываешь поисковый запрос» — жёсткий контракт с mock-llm (задача 7)"
        )
    }

    @Test
    fun `вызов клиента - system-инструкция, user-запрос, temperature 0, maxTokens 100`() = runBlocking {
        val fake = FakeLLMClient(reply = "поиск флешки")
        val rewriter = LLMQueryRewriter(fake)

        rewriter.rewrite("как правильно отформатировать флешку")

        val call = fake.calls.single()
        assertEquals(2, call.messages.size, "system + user")
        assertTrue(call.messages[0].content!!.startsWith("Ты переписываешь поисковый запрос"))
        assertEquals("как правильно отформатировать флешку", call.messages[1].content)
        assertEquals(0.0, call.temperature)
        assertEquals(100, call.maxTokens)
    }

    // ------------------------------------------------------------------
    // Happy path / очистка ответа
    // ------------------------------------------------------------------

    @Test
    fun `happy-path - грязный ответ очищается до первой строки без кавычек`() = runBlocking {
        val rewriter = LLMQueryRewriter(FakeLLMClient(reply = "  \"поиск флешки\" \nПояснение. "))

        val result = rewriter.rewrite("как правильно отформатировать флешку")

        assertEquals("поиск флешки", result)
    }

    @Test
    fun `многострочный и одиночно-кавыченный ответ - очищается`() = runBlocking {
        val rewriter = LLMQueryRewriter(FakeLLMClient(reply = "\n  'проверка прошивки'  \nПодробности\nЕщё строка"))

        val result = rewriter.rewrite("как проверить прошивку")

        assertEquals("проверка прошивки", result)
    }

    // ------------------------------------------------------------------
    // Fail-open
    // ------------------------------------------------------------------

    @Test
    fun `пустой ответ - исходный запрос и предупреждение`() = runBlocking {
        val rewriter = LLMQueryRewriter(FakeLLMClient(reply = "   \n  "))

        val result = rewriter.rewrite("как записать прошивку")

        assertEquals("как записать прошивку", result)
        assertTrue(errText().contains("Предупреждение"), "пустой ответ должен быть слышимым: ${errText()}")
    }

    @Test
    fun `таймаут (50мс) - исходный запрос и предупреждение, без исключения`() = runBlocking {
        // Задержка 200мс > rewriteTimeoutMs=50 → TimeoutCancellationException гасится
        val rewriter = LLMQueryRewriter(FakeLLMClient(reply = "переписанный", sleepMs = 200), timeoutMs = 50)

        val result = rewriter.rewrite("поиск флешки")

        assertEquals("поиск флешки", result, "fail-open: таймаут → исходный запрос")
        assertTrue(errText().contains("Предупреждение"), "warn-лог обязателен: ${errText()}")
    }

    @Test
    fun `бросающий клиент - исходный запрос, исключение не уходит наверх`() = runBlocking {
        val rewriter = LLMQueryRewriter(FakeLLMClient(error = IllegalStateException("LLM недоступен")))

        val result = try {
            rewriter.rewrite("вопрос")
        } catch (e: Exception) {
            fail("fail-open: исключение не должно покидать rewrite(): ${e.message}")
        }

        assertEquals("вопрос", result)
        assertTrue(errText().contains("Предупреждение"), "warn-лог обязателен: ${errText()}")
        assertTrue(errText().contains("LLM недоступен"), "в warn-логе видна причина: ${errText()}")
    }
}
