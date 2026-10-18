package com.example.aiweb.session

import com.example.aiweb.client.SummaryPrompts
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки чистой логики компрессии истории [HistoryCompressor]: порог merge,
 * однократный вызов суммаризатора на все накопившиеся внеоконные сообщения,
 * turn-boundary guard среза окна (пара assistant(tool_calls) → role="tool" не
 * разрывается, срез начинается с user), сборка view запроса без мутации истории,
 * pre-trim сверхбюджетного батча, повторное сжимание oversized-сводки и проброс
 * исключений суммаризатора вызывающему.
 *
 * Сеть в тестах исключена: суммаризатор — фейковый ([FakeSummarizer]), детерминированный,
 * со счётчиком вызовов и настраиваемым результатом.
 */
class HistoryCompressorTest {

    private fun user(text: String) = OpenAIMessage(role = "user", content = text)

    private fun assistant(text: String) = OpenAIMessage(role = "assistant", content = text)

    private fun assistantToolCall(id: String) = OpenAIMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(OpenAIToolCall(id = id))
    )

    private fun tool(id: String, text: String) = OpenAIMessage(role = "tool", content = text, toolCallId = id)

    /** Ход диалога: вопрос и ответ по [chars] символов (оценка — ceil(chars / 2.5) токенов на сообщение). */
    private fun turn(index: Int, chars: Int): List<OpenAIMessage> = listOf(
        user("вопрос $index ".padEnd(chars, 'х')),
        assistant("ответ $index ".padEnd(chars, 'х'))
    )

    /** Ход с обращением к инструменту: user → assistant(tool_calls) → role="tool". */
    private fun toolTurn(index: Int, chars: Int): List<OpenAIMessage> = listOf(
        user("вопрос $index ".padEnd(chars, 'х')),
        assistantToolCall("call-$index"),
        tool("call-$index", "результат $index")
    )

    private fun compressor(
        windowSize: Int,
        mergeEvery: Int,
        summaryMaxTokens: Int,
        summarizer: HistorySummarizer
    ) = HistoryCompressor(windowSize, mergeEvery, summaryMaxTokens, summarizer)

    /** Есть ли в срезе tool-сообщение без предшествующего assistant(tool_calls)? */
    private fun hasOrphanTool(slice: List<OpenAIMessage>): Boolean = slice.indices.any { i ->
        slice[i].role == "tool" &&
            slice.subList(0, i).none { it.role == "assistant" && it.toolCalls != null }
    }

    // --- 1. Порог и однократность merge (QA-сценарий «Порог и однократность merge») ---

    @Test
    fun `beyondWindowCount — сколько сообщений вне последних windowSize`() {
        val c = compressor(5, 5, 100, FakeSummarizer())

        val seventeen = (1..8).fold(emptyList<OpenAIMessage>()) { acc, i -> acc + turn(i, 10) } + user("вопрос 9")
        assertEquals(12, c.beyondWindowCount(seventeen))
        assertEquals(0, c.beyondWindowCount(turn(1, 10) + turn(2, 10)))
        assertEquals(0, c.beyondWindowCount(turn(1, 10)))
        assertEquals(0, c.beyondWindowCount(emptyList()))
    }

    @Test
    fun `порог не достигнут — merge null без единого вызова суммаризатора`() = runBlocking {
        // windowSize=5, mergeEvery=5; 9 сообщений → beyondWindow=4
        val fake = FakeSummarizer()
        val c = compressor(5, 5, 100, fake)
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10) + turn(4, 10) + user("вопрос 5")

        assertEquals(4, c.beyondWindowCount(messages))
        assertNull(c.merge(null, messages))
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `порог достигнут — один вызов суммаризатора и корректный removeCount`() = runBlocking {
        // windowSize=5, mergeEvery=5; 10 сообщений → beyondWindow=5
        val fake = FakeSummarizer()
        val c = compressor(5, 5, 100, fake)
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10) + turn(4, 10) + turn(5, 10)

        val result = c.merge(null, messages)

        assertEquals(5, result?.removeCount)
        assertEquals("сводка", result?.newSummary)
        assertEquals(7, result?.tokens)
        assertEquals(1, fake.calls.size)
        assertEquals(messages.subList(0, 5), fake.calls.single().batch)
    }

    @Test
    fun `все накопившиеся внеоконные сообщения закрывает один merge`() = runBlocking {
        // 17 сообщений, окно 5 → 12 вне окна; не 12 вызовов, а ровно один
        val fake = FakeSummarizer()
        val c = compressor(5, 5, 100, fake)
        val messages = (1..8).fold(emptyList<OpenAIMessage>()) { acc, i -> acc + turn(i, 10) } + user("вопрос 9")

        val result = c.merge(null, messages)

        assertEquals(12, result?.removeCount)
        assertEquals(1, fake.calls.size)
        assertEquals(12, fake.calls.single().batch.size)
        assertEquals(messages.subList(0, 12), fake.calls.single().batch)
    }

    // --- 3. sliceWindow: turn-boundary guard ---

    @Test
    fun `sliceWindow — ровно последние windowSize сообщений, когда граница безопасна`() {
        val c = compressor(5, 5, 100, FakeSummarizer())
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val slice = c.sliceWindow(messages)

        assertEquals(messages.subList(1, 6), slice)
        assertFalse(hasOrphanTool(slice))
    }

    @Test
    fun `sliceWindow — срез, начинающийся с tool, расширяется назад до ближайшего user`() {
        // user, assistant(tool_calls), tool, user, assistant(tool_calls), tool, user, assistant
        val messages = listOf(
            user("вопрос 1"),
            assistantToolCall("call-1"),
            tool("call-1", "результат 1"),
            user("вопрос 2"),
            assistantToolCall("call-2"),
            tool("call-2", "результат 2"),
            user("вопрос 3"),
            assistant("ответ 3")
        )
        val c = compressor(3, 5, 100, FakeSummarizer())

        // Наивный срез последних 3 начался бы с tool("call-2") — пара разорвалась бы
        val slice = c.sliceWindow(messages)

        assertEquals(messages.subList(3, 8), slice)
        assertEquals("user", slice.first().role)
        assertFalse(hasOrphanTool(slice))
    }

    @Test
    fun `sliceWindow — история короче окна возвращается целиком`() {
        val c = compressor(5, 5, 100, FakeSummarizer())
        val messages = turn(1, 10) + turn(2, 10)

        assertEquals(messages, c.sliceWindow(messages))
    }

    // --- 4. planView ---

    @Test
    fun `planView — без сводки — окно без system-сообщения`() {
        val c = compressor(5, 5, 100, FakeSummarizer())
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val view = c.planView(null, messages)

        assertEquals(c.sliceWindow(messages), view)
        assertTrue(view.none { it.role == "system" })
    }

    @Test
    fun `planView — со сводкой — system-сводка плюс окно`() {
        val c = compressor(5, 5, 100, FakeSummarizer())
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val view = c.planView("сводка прошлого", messages)

        assertEquals(OpenAIMessage(role = "system", content = "сводка прошлого"), view.first())
        assertEquals(c.sliceWindow(messages), view.drop(1))
    }

    @Test
    fun `planView — не мутирует исходный список`() {
        val c = compressor(5, 5, 100, FakeSummarizer())
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)
        val snapshot = messages.toList()

        c.planView("сводка прошлого", messages)

        assertEquals(snapshot, messages)
    }

    // --- 5. Pre-trim сверхбюджетного батча ---

    @Test
    fun `merge — батч больше бюджета уходит в суммаризатор pre-trimmed`() = runBlocking {
        // 5 внеоконных ходов (~14,4k токенов) > бюджета 12k; окно — 2 сообщения
        val fake = FakeSummarizer()
        val c = compressor(2, 1, 1000, fake)
        val messages = toolTurn(1, 4000) + turn(2, 4000) + turn(3, 4000) + turn(4, 4000) + turn(5, 4000) + turn(6, 10)

        val result = c.merge(null, messages)

        assertEquals(11, result?.removeCount)
        assertEquals(1, fake.calls.size)
        val batch = fake.calls.single().batch
        // Отброшены старейшие ходы до границы user-сообщения: остался ход 3..5
        assertEquals(messages.subList(5, 11), batch)
        assertEquals("user", batch.first().role)
        assertFalse(hasOrphanTool(batch))
        assertTrue(batch.sumOf { SessionHistory.estimateTokens(it) } <= HistoryCompressor.SUMMARY_BATCH_TOKEN_BUDGET)
    }

    // --- 6. Oversized сводка: повторный вызов и жёсткая обрезка ---

    private val oversizedSummary =
        "Это черновая сводка, которая заметно превышает заданный лимит в токенах, поэтому нужен повтор."

    @Test
    fun `merge — oversized сводка — повторный вызов с инструкцией, результат второго`() = runBlocking {
        val fake = FakeSummarizer { _, _, callNo ->
            if (callNo == 1) SummarizeResult(oversizedSummary, 38)
            else SummarizeResult("короткая сводка", 6)
        }
        val c = compressor(2, 1, 20, fake)
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val result = c.merge("старая сводка", messages)

        assertEquals(2, fake.calls.size)
        assertEquals("старая сводка", fake.calls[0].previous)
        val retry = fake.calls[1].previous!!
        assertTrue(retry.contains(SummaryPrompts.RETRY_INSTRUCTION), "повторный вызов несёт инструкцию: $retry")
        assertTrue(retry.contains(oversizedSummary), "повторный вызов получает черновик для ужимки")
        assertEquals("короткая сводка", result?.newSummary)
        assertEquals(6, result?.tokens)
        assertEquals(4, result?.removeCount)
    }

    @Test
    fun `merge — повторно oversized сводка — жёсткая обрезка по границе предложений`() = runBlocking {
        // Обе попытки превышают лимит 20 токенов → обрезка по последнему знаковому рубежу
        val stubborn = "Первая часть сводки очень длинная и важная. Вторая часть тоже длинная и важная. Третья."
        val fake = FakeSummarizer { _, _, _ -> SummarizeResult(stubborn, 35) }
        val c = compressor(2, 1, 20, fake)
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val result = c.merge(null, messages)

        assertEquals(2, fake.calls.size)
        val cut = result!!.newSummary
        assertTrue(stubborn.startsWith(cut), "обрезка — префикс черновика")
        assertTrue(cut.length < stubborn.length, "обрезка короче черновика")
        assertTrue(cut.endsWith("."), "обрезка по границе предложения: $cut")
        assertTrue(SessionHistory.estimateTokens(OpenAIMessage(role = "system", content = cut)) <= 20)
        assertEquals(35, result.tokens)
    }

    @Test
    fun `merge — сводка в пределах лимита — повторный вызов не происходит`() = runBlocking {
        val fake = FakeSummarizer { _, _, _ -> SummarizeResult("короткая сводка", 6) }
        val c = compressor(2, 1, 20, fake)
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        val result = c.merge(null, messages)

        assertEquals(1, fake.calls.size)
        assertEquals("короткая сводка", result?.newSummary)
    }

    // --- 7. Контракт исключений ---

    @Test
    fun `merge — исключение суммаризатора пробрасывается вызывающему`() {
        val c = compressor(2, 1, 100, ThrowingSummarizer())
        val messages = turn(1, 10) + turn(2, 10) + turn(3, 10)

        runBlocking {
            assertFailsWith<RuntimeException> { c.merge(null, messages) }
        }
    }

    // --- Маркер mock-LLM (дизайн-решение 15) ---

    @Test
    fun `system-промпт суммаризации содержит маркер «Сводка ранее» и лимит токенов`() {
        val system = SummaryPrompts.systemPrompt(123)

        assertEquals("Сводка ранее", SummaryPrompts.PREVIOUS_SUMMARY_MARKER)
        assertTrue(system.contains(SummaryPrompts.PREVIOUS_SUMMARY_MARKER), "маркер для mock-LLM отсутствует: $system")
        assertTrue(system.contains("123"), "лимит токенов не подставлен в промпт: $system")
    }

    // --- Фейки ---

    /** Фейковый суммаризатор: детерминированный, со счётчиком и записью аргументов вызовов. */
    private class FakeSummarizer(
        private val next: (previous: String?, batch: List<OpenAIMessage>, callNumber: Int) -> SummarizeResult
    ) : HistorySummarizer {
        data class Call(val previous: String?, val batch: List<OpenAIMessage>)

        val calls = mutableListOf<Call>()

        constructor() : this({ _, _, _ -> SummarizeResult("сводка", 7) })

        override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>): SummarizeResult {
            calls.add(Call(previous, batch))
            return next(previous, batch, calls.size)
        }
    }

    /** Фейковый суммаризатор, имитирующий сбой LLM-вызова. */
    private class ThrowingSummarizer(
        private val error: RuntimeException = RuntimeException("сбой суммаризатора")
    ) : HistorySummarizer {
        override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>): SummarizeResult = throw error
    }
}
