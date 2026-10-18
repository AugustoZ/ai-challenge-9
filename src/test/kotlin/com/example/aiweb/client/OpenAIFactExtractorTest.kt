package com.example.aiweb.client

import com.example.aiweb.model.Fact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Проверка реального экстрактора фактов [OpenAIFactExtractor]: паттерн внечатового
 * вызова как у [OpenAIHistorySummarizer] (temperature 0.1, thinkingEnabled = false,
 * бюджет [OpenAIFactExtractor.FACTS_MAX_TOKENS]), промпт строит
 * [FactPrompts.buildMessages]. Асимметрия по дизайну: транспортные ошибки
 * пробрасываются наверх (решение о деградации — за вызывающим, паттерн
 * HistoryCompressor), а мусорный ответ LLM даёт null (парсер не бросает).
 * Транспорт подменяется FakeLLMClient, сети в тесте нет.
 */
class OpenAIFactExtractorTest {

    /** Фейковый LLMClient: запоминает вызов; отдаёт заготовку или кидает исключение. */
    private class FakeLLMClient(
        private val canned: ChatResult? = null,
        private val error: Exception? = null
    ) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            error?.let { throw it }
            return canned!!
        }
    }

    @Test
    fun `happy path - ответ LLM разбирается в факты, параметры вызова как у судьи`() = runBlocking {
        val fake = FakeLLMClient(
            ChatResult(
                reply = """[{"key":"цель","value":"тест"}]""",
                promptTokens = 10,
                completionTokens = 5,
                totalTokens = 15,
                elapsedMs = 0L
            )
        )
        val extractor = OpenAIFactExtractor(fake)
        val current = listOf(Fact("старое", "прежнее"))

        val facts = extractor.extract(current, "сообщение")

        assertEquals(listOf(Fact("цель", "тест")), facts)

        val call = fake.calls.single()
        assertEquals(OpenAIFactExtractor.FACTS_MAX_TOKENS, call.maxTokens, "бюджет фактов — 300 токенов")
        assertEquals(0.1, call.temperature, "температура как у судьи/сводки")
        assertEquals(false, call.thinkingEnabled, "рассуждение выключено")
        assertEquals(
            FactPrompts.buildMessages(current, "сообщение"),
            call.messages,
            "промпт собирает FactPrompts: текущие факты + новое сообщение"
        )
    }

    @Test
    fun `LLM кинул исключение - проброс наверх (не глотается)`() = runBlocking {
        val fake = FakeLLMClient(error = IllegalStateException("транспорт недоступен"))
        val extractor = OpenAIFactExtractor(fake)

        assertFailsWith<IllegalStateException> {
            extractor.extract(emptyList(), "сообщение")
        }
    }

    @Test
    fun `пустой reply - null (парсер защищённый, исключений нет)`() = runBlocking {
        val fake = FakeLLMClient(
            ChatResult(reply = "", promptTokens = 1, completionTokens = 1, totalTokens = 2, elapsedMs = 0L)
        )
        val extractor = OpenAIFactExtractor(fake)

        assertNull(extractor.extract(emptyList(), "сообщение"))
    }
}
