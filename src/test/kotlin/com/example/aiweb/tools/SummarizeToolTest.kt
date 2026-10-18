package com.example.aiweb.tools

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки summarize: сводка через фейковый LLMClient (форма запроса —
 * системный промт + текст), уточнение instruction и пути отказа — клиент
 * не подключён, клиент падает, пустой ответ модели.
 */
class SummarizeToolTest {

    /** Фейковый LLMClient (паттерн AgentLoopTest): записывает вызовы, отдаёт заготовку. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    /** Фейковый клиент, имитирующий недоступность модели. */
    private class ThrowingLLMClient : LLMClient {
        override suspend fun ask(call: LLMCall): ChatResult =
            throw IllegalStateException("нет соединения с API")
    }

    private fun canned(reply: String) = ChatResult(
        reply = reply,
        promptTokens = 10,
        completionTokens = 5,
        totalTokens = 15,
        elapsedMs = 0L
    )

    @Test
    fun `сводка через фейковый клиент возвращается в content`() = runBlocking {
        val fake = FakeLLMClient(canned("Краткая сводка: порт бэкенда 8083."))
        val tool = SummarizeTool(fake)

        val result = tool.execute("""{"text": "длинный текст про порты"}""")

        assertTrue(result.ok, result.content)
        assertEquals("Краткая сводка: порт бэкенда 8083.", result.content)
        assertEquals(1, fake.calls.size)
        val call = fake.calls.single()
        assertEquals(2, call.messages.size)
        assertEquals("system", call.messages[0].role)
        assertTrue(call.messages[0].content!!.contains("сводку"))
        assertEquals("user", call.messages[1].role)
        assertEquals("длинный текст про порты", call.messages[1].content)
    }

    @Test
    fun `instruction попадает в системный промт`() = runBlocking {
        val fake = FakeLLMClient(canned("Сводка"))
        val tool = SummarizeTool(fake)

        val result = tool.execute("""{"text": "текст", "instruction": "выдели сроки"}""")

        assertTrue(result.ok)
        assertTrue(fake.calls.single().messages[0].content!!.contains("выдели сроки"))
    }

    @Test
    fun `без клиента ok=false с понятным текстом`() = runBlocking {
        val tool = SummarizeTool(null)

        val result = tool.execute("""{"text": "текст"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("недоступна"), result.content)
    }

    @Test
    fun `падение клиента даёт ok=false без исключения`() = runBlocking {
        val tool = SummarizeTool(ThrowingLLMClient())

        val result = tool.execute("""{"text": "текст"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("Суммаризация не удалась"), result.content)
        assertTrue(result.content.contains("нет соединения"), result.content)
    }

    @Test
    fun `пустой ответ модели даёт ошибку`() = runBlocking {
        val tool = SummarizeTool(FakeLLMClient(canned("   ")))

        val result = tool.execute("""{"text": "текст"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("пустой ответ"))
    }

    @Test
    fun `пустой текст даёт ошибку`() = runBlocking {
        val tool = SummarizeTool(FakeLLMClient(canned("Сводка")))

        val result = tool.execute("""{"instruction": "без текста"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("text"))
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = SummarizeTool(null)
        val result = tool.execute("not json")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
