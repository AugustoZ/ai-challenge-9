package com.example.aiweb.session

import com.example.aiweb.model.ContextStrategy
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки чистого планировщика контекста [ContextPlanner]: вид запроса для каждой
 * стратегии (window — окно последних сообщений с turn-boundary guard, facts —
 * system-блок фактов + окно 3, summary — планView компрессора, off/branching —
 * сообщения как есть) и неизменность исходной истории (view — чистая функция).
 */
class ContextPlannerTest {

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

    /** Есть ли в срезе tool-сообщение без предшествующего assistant(tool_calls)? */
    private fun hasOrphanTool(slice: List<OpenAIMessage>): Boolean = slice.indices.any { i ->
        slice[i].role == "tool" &&
            slice.subList(0, i).none { it.role == "assistant" && it.toolCalls != null }
    }

    private fun compressor() = HistoryCompressor(
        windowSize = 5,
        mergeEvery = 100,
        summaryMaxTokens = 400,
        summarizer = FakeSummarizer()
    )

    /** Минимальный фейк суммаризатора — планировщик вызывает только planView, merge не нужен. */
    private class FakeSummarizer : HistorySummarizer {
        override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>) =
            SummarizeResult("сводка", 7)
    }

    /** 9 сообщений: ход 1, ход 2, ход 3 с инструментом, ход 4. */
    private fun nineMessages(): List<OpenAIMessage> =
        turn(1, 20) + turn(2, 20) + toolTurn(3, 20) + turn(4, 20)

    // --- Свободная функция sliceWindow ---

    @Test
    fun `sliceWindow — ровно последние windowSize, исходный список не мутирован`() {
        val messages = nineMessages()
        val snapshot = messages.toList()

        val slice = HistoryCompressor.sliceWindow(messages, 5)

        assertEquals(5, slice.size)
        assertEquals(snapshot.subList(4, 9), slice, "окно — последние 5 сообщений")
        assertEquals(snapshot, messages, "исходный список не изменился")
        assertEquals(9, messages.size)
    }

    @Test
    fun `sliceWindow — короткая история возвращается целиком`() {
        val messages = turn(1, 20) + turn(2, 20)

        val slice = HistoryCompressor.sliceWindow(messages, 5)

        assertEquals(messages, slice)
        assertFalse(slice === messages, "возвращается копия, а не тот же список")
    }

    @Test
    fun `sliceWindow — окно наезжает на tool — расширяется назад до user, без сироты`() {
        // 9 сообщений; окно 3 начинается ровно с role="tool" (индекс 6) —
        // guard обязан расширить срез назад до user("вопрос 3")
        val messages = nineMessages()

        val slice = HistoryCompressor.sliceWindow(messages, 3)

        assertEquals("user", slice.first().role, "срез начинается с user, а не с tool")
        assertTrue(slice.first().content!!.startsWith("вопрос 3"), "срез расширен до начала хода 3")
        assertEquals(5, slice.size, "ход 3 целиком (3 сообщения) + ход 4")
        assertFalse(hasOrphanTool(slice), "пару assistant(tool_calls) → tool рвать нельзя")
    }

    // --- view: WINDOW ---

    @Test
    fun `view WINDOW — последние windowSize сообщений, история не тронута`() {
        val c = compressor()
        val messages = nineMessages()
        val snapshot = messages.toList()

        val view = view(ContextStrategy.WINDOW, summary = null, messages, facts = emptyList(), windowSize = 5, compressor = c)

        assertEquals(HistoryCompressor.sliceWindow(messages, 5), view)
        assertFalse(hasOrphanTool(view))
        assertEquals(snapshot, messages, "исходная история не изменена")
    }

    // --- view: FACTS ---

    @Test
    fun `view FACTS — system-блок фактов плюс последние 3 сообщения`() {
        val c = compressor()
        val messages = turn(1, 20) + turn(2, 20) + turn(3, 20)
        val facts = listOf(Fact("цель", "проверить блок фактов"), Fact("ограничение", "не резать историю"))

        val view = view(ContextStrategy.FACTS, summary = null, messages, facts, windowSize = 5, compressor = c)

        assertEquals(1 + 3, view.size, "блок фактов + окно 3")
        val factsBlock = view.first()
        assertEquals("system", factsBlock.role)
        assertTrue(
            factsBlock.content!!.startsWith("Известные факты о диалоге:"),
            "блок фактов начинается с заголовка: ${factsBlock.content}"
        )
        assertTrue(factsBlock.content!!.contains("- цель: проверить блок фактов"), "ключ-значение факта в блоке")
        assertTrue(factsBlock.content!!.contains("- ограничение: не резать историю"), "второй факт в блоке")

        val tail = view.drop(1)
        assertEquals(
            messages.subList(3, 6).map { it.content },
            tail.map { it.content },
            "после блока фактов — последние 3 сообщения"
        )
        assertFalse(hasOrphanTool(tail))
    }

    @Test
    fun `view FACTS — окно 3 наезжает на tool — guard расширяет окно назад до user`() {
        val c = compressor()
        val messages = nineMessages()

        val view = view(ContextStrategy.FACTS, summary = null, messages, facts = listOf(Fact("цель", "х")), windowSize = 5, compressor = c)

        assertEquals(1 + 5, view.size, "блок фактов + ход 3 целиком + ход 4")
        assertEquals("system", view.first().role)
        assertEquals("user", view[1].role, "после блока фактов окно начинается с user, а не с tool")
        assertFalse(hasOrphanTool(view))
    }

    @Test
    fun `view FACTS — пустые факты — без блока, только окно 3`() {
        val c = compressor()
        val messages = turn(1, 20) + turn(2, 20) + turn(3, 20)

        val view = view(ContextStrategy.FACTS, summary = "есть сводка", messages, facts = emptyList(), windowSize = 5, compressor = c)

        assertEquals(3, view.size, "пустые факты — пустой вклад, только окно 3")
        assertEquals(messages.subList(3, 6), view)
        assertTrue(view.none { it.role == "system" }, "сводка при FACTS игнорируется, блока нет")
    }

    // --- view: SUMMARY ---

    @Test
    fun `view SUMMARY — планView компрессора - сводка system плюс окно`() {
        val c = compressor()
        val messages = nineMessages()

        val view = view(ContextStrategy.SUMMARY, summary = "старая сводка", messages, facts = emptyList(), windowSize = 5, compressor = c)

        assertEquals(c.planView("старая сводка", messages), view, "SUMMARY — ровно планView компрессора")
        assertEquals("system", view.first().role)
        assertEquals("старая сводка", view.first().content)
        assertEquals(1 + 5, view.size)
    }

    @Test
    fun `view SUMMARY — без компрессора — деградация к окну без сводки`() {
        val messages = nineMessages()

        val view = view(ContextStrategy.SUMMARY, summary = "старая сводка", messages, facts = emptyList(), windowSize = 5, compressor = null)

        assertEquals(HistoryCompressor.sliceWindow(messages, 5), view)
        assertTrue(view.none { it.role == "system" && it.content == "старая сводка" })
    }

    // --- view: OFF / BRANCHING ---

    @Test
    fun `view OFF — сообщения как есть, сводка и факты игнорируются`() {
        val c = compressor()
        val messages = nineMessages()

        val view = view(ContextStrategy.OFF, summary = "сводка", messages, facts = listOf(Fact("цель", "х")), windowSize = 5, compressor = c)

        assertEquals(messages, view, "OFF — полное активное состояние, капы применяет вызывающий")
    }

    @Test
    fun `view BRANCHING — сообщения как есть`() {
        val c = compressor()
        val messages = nineMessages()

        val view = view(ContextStrategy.BRANCHING, summary = null, messages, facts = emptyList(), windowSize = 5, compressor = c)

        assertEquals(messages, view)
    }
}
