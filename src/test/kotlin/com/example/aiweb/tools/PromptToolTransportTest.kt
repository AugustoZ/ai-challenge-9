package com.example.aiweb.tools

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.model.OpenAIMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки fallback-транспорта: блок ```json в ответе превращается в вызов инструмента,
 * обычный текст возвращается как финальный ответ. Без реальных обращений к LLM.
 */
class PromptToolTransportTest {

    /** Фейковый LLMClient: запоминает вызовы и отдаёт заготовленный ChatResult (паттерн client/FakeLLMClientTest). */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    private val canned = ChatResult(
        reply = "заготовленный ответ",
        promptTokens = 11,
        completionTokens = 22,
        totalTokens = 33,
        elapsedMs = 44L,
        finishReason = "stop"
    )

    private fun call() = LLMCall(messages = listOf(OpenAIMessage(role = "user", content = "вопрос")))

    @Test
    fun `блок json в ответе превращается в вызов инструмента`() = runBlocking {
        val fake = FakeLLMClient(
            canned.copy(
                reply = "Использую калькулятор.\n```json\n" +
                    "{\"tool\": \"calculator\", \"arguments\": {\"expression\": \"2+2\"}}\n```"
            )
        )

        val result = PromptToolTransport().ask(call(), fake, ToolRegistry.withBuiltIns())

        assertEquals(1, result.toolCalls.size)
        val toolCall = result.toolCalls.single()
        assertEquals("prompt", toolCall.id)
        assertEquals("function", toolCall.type)
        assertEquals("calculator", toolCall.function?.name)
        assertEquals("{\"expression\":\"2+2\"}", toolCall.function?.arguments)
        assertEquals("", result.reply)
        assertEquals(33, result.totalTokens)
        assertEquals(44L, result.elapsedMs)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `обычный текст возвращается как финальный ответ без вызовов`() = runBlocking {
        val fake = FakeLLMClient(canned.copy(reply = "Просто ответ текстом, инструменты не нужны."))

        val result = PromptToolTransport().ask(call(), fake, ToolRegistry.withBuiltIns())

        assertEquals("Просто ответ текстом, инструменты не нужны.", result.reply)
        assertEquals(0, result.toolCalls.size)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `неизвестное имя инструмента весь ответ трактуется как финальный`() = runBlocking {
        val fencedReply = "```json\n{\"tool\": \"nope\", \"arguments\": {}}\n```"
        val fake = FakeLLMClient(canned.copy(reply = fencedReply))

        val result = PromptToolTransport().ask(call(), fake, ToolRegistry.withBuiltIns())

        assertEquals(0, result.toolCalls.size)
        assertEquals(fencedReply, result.reply)
    }

    @Test
    fun `в базовый вызов подставляется system промт и tools обнуляются`() = runBlocking {
        val fake = FakeLLMClient(canned)

        PromptToolTransport().ask(call(), fake, ToolRegistry.withBuiltIns())

        val sent = fake.calls.single()
        assertEquals(null, sent.tools)
        assertEquals("system", sent.messages.last().role)
        val prompt = sent.messages.last().content.orEmpty()
        assertTrue(prompt.contains("current_datetime"))
        assertTrue(prompt.contains("calculator"))
        assertTrue(prompt.contains("http_get"))
        assertEquals("вопрос", sent.messages.first().content)
    }
}
