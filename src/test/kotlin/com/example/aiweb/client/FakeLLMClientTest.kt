package com.example.aiweb.client

import com.example.aiweb.model.OpenAIMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Проверка тестового шва [LLMClient]: фейковый клиент записывает обращения
 * и возвращает заготовленный результат — так будут тестироваться фазы 1–3
 * без реальных HTTP-вызовов.
 */
class FakeLLMClientTest {

    /** Фейковый LLMClient: запоминает вызовы и отдаёт заготовленный ChatResult. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    @Test
    fun `фейковый клиент записывает вызовы и возвращает заготовленный ответ`() = runBlocking {
        val canned = ChatResult(
            reply = "заготовленный ответ",
            promptTokens = 1,
            completionTokens = 2,
            totalTokens = 3,
            elapsedMs = 0L
        )
        val fake = FakeLLMClient(canned)
        val call = LLMCall(
            messages = listOf(OpenAIMessage(role = "user", content = "вопрос")),
            model = "test-model"
        )

        val result = fake.ask(call)

        assertEquals(1, fake.calls.size)
        assertSame(call, fake.calls.single())
        assertEquals("вопрос", fake.calls.single().messages.single().content)
        assertSame(canned, result)
    }
}
