package com.example.aiweb.client

import com.example.aiweb.model.OpenAIMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Проверка реального суммаризатора [OpenAIHistorySummarizer]: паттерн внечатового
 * вызова как у runJudge (temperature 0.1, бюджет maxTokens в prompt и лимите ответа),
 * промпт строит [SummaryPrompts.buildMessages] с previous «как есть», а токены
 * результата берутся из ChatResult. Суммаризатор фейкабелен — транспорт подменяется
 * FakeLLMClient, сети в тесте нет.
 */
class OpenAIHistorySummarizerTest {

    /** Фейковый LLMClient: запоминает вызов и отдаёт заготовленный ChatResult. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    @Test
    fun `суммаризует через LLMCall по промпту SummaryPrompts, токены — totalTokens`() = runBlocking {
        val fake = FakeLLMClient(
            ChatResult(reply = "новая сводка", promptTokens = 10, completionTokens = 5, totalTokens = 15, elapsedMs = 0L)
        )
        val summarizer = OpenAIHistorySummarizer(fake, maxTokens = 300)
        val batch = listOf(
            OpenAIMessage(role = "user", content = "вопрос"),
            OpenAIMessage(role = "assistant", content = "ответ")
        )

        val result = summarizer.summarize("старая сводка", batch)

        assertEquals("новая сводка", result.summary)
        assertEquals(15, result.tokens)

        val call = fake.calls.single()
        assertEquals(300, call.maxTokens, "бюджет сводки уходит в лимит ответа")
        assertEquals(0.1, call.temperature, "температура как у судьи (runJudge)")
        assertEquals(
            SummaryPrompts.buildMessages("старая сводка", batch, 300),
            call.messages,
            "промпт собирается SummaryPrompts, previous — как есть"
        )
    }

    @Test
    fun `previous без сводки - сборка промпта без блока «Сводка ранее»`() = runBlocking {
        val fake = FakeLLMClient(
            ChatResult(reply = "сводка", promptTokens = 1, completionTokens = 1, totalTokens = 2, elapsedMs = 0L)
        )
        val summarizer = OpenAIHistorySummarizer(fake, maxTokens = 400)
        val batch = listOf(OpenAIMessage(role = "user", content = "текст"))

        val result = summarizer.summarize(null, batch)

        val call = fake.calls.single()
        assertEquals(
            SummaryPrompts.buildMessages(null, batch, 400),
            call.messages,
            "previous=null — маркер «Сводка ранее» не добавляется"
        )
        assertEquals("сводка", result.summary)
    }
}
