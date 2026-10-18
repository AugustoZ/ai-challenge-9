package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки триммирования истории: свежие ходы сохраняются целиком, срез проходит
 * только перед user-сообщением, запас под «Топливо» ужимает историю, а пара
 * assistant(tool_calls) → role="tool" никогда не разрывается.
 */
class SessionHistoryTest {

    private fun user(text: String) = OpenAIMessage(role = "user", content = text)

    private fun assistant(text: String) = OpenAIMessage(role = "assistant", content = text)

    /** Ход диалога: вопрос и ответ по [chars] символов (оценка — ceil(chars / 2.5) токенов на сообщение). */
    private fun turn(index: Int, chars: Int): List<OpenAIMessage> = listOf(
        user("вопрос $index ".padEnd(chars, 'х')),
        assistant("ответ $index ".padEnd(chars, 'х'))
    )

    /** Ход с обращением к инструменту: user → assistant(tool_calls) → role="tool". */
    private fun toolTurn(index: Int, chars: Int): List<OpenAIMessage> = listOf(
        user("вопрос $index ".padEnd(chars, 'х')),
        OpenAIMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(OpenAIToolCall(id = "call-$index"))
        ),
        OpenAIMessage(role = "tool", content = "результат $index", toolCallId = "call-$index")
    )

    @Test
    fun `оценка токенов — ceil от деления длины на 2_5, пустой контент — ноль`() {
        assertEquals(10, SessionHistory.estimateTokens(user("х".repeat(25))))
        assertEquals(20, SessionHistory.estimateTokens(user("х".repeat(50))))
        assertEquals(0, SessionHistory.estimateTokens(OpenAIMessage(role = "assistant", content = null)))
    }

    @Test
    fun `пустая история — пустой результат`() {
        assertTrue(SessionHistory.trim(emptyList(), 0).isEmpty())
    }

    @Test
    fun `в пределах бюджета сохраняется вся история`() {
        val history = turn(1, 50) + turn(2, 50) + turn(3, 50)

        val trimmed = SessionHistory.trim(history, 0)

        assertEquals(history, trimmed)
    }

    @Test
    fun `обрезка проходит по границе user-сообщения`() {
        val history = turn(1, 50) + turn(2, 50) + turn(3, 50)

        // Бюджет 60 токенов: последний ход (40) влезает, предыдущий уже нет
        val trimmed = SessionHistory.trim(history, SessionHistory.MAX_HISTORY_TOKENS - 60)

        assertEquals(listOf(history[4], history[5]), trimmed)
        assertEquals("user", trimmed.first().role)
    }

    @Test
    fun `запас под Топливо ужимает историю`() {
        val history = turn(1, 50) + turn(2, 50) + turn(3, 50)

        val full = SessionHistory.trim(history, 0)
        val tight = SessionHistory.trim(history, SessionHistory.MAX_HISTORY_TOKENS - 100)

        assertEquals(6, full.size)
        // Бюджет 100: последний ход (40) и предыдущий (40) влезают, третий уже нет
        assertEquals(4, tight.size)
        assertEquals("user", tight.first().role)
    }

    @Test
    fun `минимум — свежий ход целиком, даже при нулевом бюджете`() {
        val history = turn(1, 50) + turn(2, 50)

        val trimmed = SessionHistory.trim(history, SessionHistory.MAX_HISTORY_TOKENS)

        assertEquals(listOf(history[2], history[3]), trimmed)
    }

    @Test
    fun `пара assistant(tool_calls) и role=tool не разрывается при обрезке`() {
        val history = toolTurn(1, 150) + turn(2, 50) + turn(3, 50)

        // Бюджет 100: два свежих хода влезают (40 + 40), ход с инструментами (60) уже нет
        val trimmed = SessionHistory.trim(history, SessionHistory.MAX_HISTORY_TOKENS - 100)

        assertEquals(history.subList(3, history.size), trimmed)
        assertEquals("user", trimmed.first().role)
        // Пара ушла из истории целиком — ни осиротевшего role="tool", ни повисшего вызова
        assertTrue(trimmed.none { it.role == "tool" })
        assertTrue(trimmed.none { it.toolCalls != null })
    }

    @Test
    fun `пара с инструментами сохраняется вместе, когда влезает в бюджет`() {
        val history = toolTurn(1, 150) + turn(2, 50)

        // Бюджет 105: ход с инструментами (60+0+5=65) + последний ход (40) — ровно в бюджет
        val trimmed = SessionHistory.trim(history, SessionHistory.MAX_HISTORY_TOKENS - 105)

        assertEquals(history, trimmed)
    }

    @Test
    fun `подвешенная пара tool-сообщений без user-начала отбрасывается целиком`() {
        val orphan = listOf(
            OpenAIMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(OpenAIToolCall(id = "call-0"))
            ),
            OpenAIMessage(role = "tool", content = "результат", toolCallId = "call-0")
        )
        val history = orphan + turn(1, 50)

        val trimmed = SessionHistory.trim(history, 0)

        // Срез начинается с user-сообщения: подвешенный ход не оставлен частично
        assertEquals(history.drop(2), trimmed)
    }

    @Test
    fun `история без user-сообщений не сохраняется`() {
        val history = listOf(
            OpenAIMessage(
                role = "assistant",
                content = "ответ",
                toolCalls = listOf(OpenAIToolCall(id = "call-0"))
            ),
            OpenAIMessage(role = "tool", content = "результат", toolCallId = "call-0")
        )

        assertTrue(SessionHistory.trim(history, 0).isEmpty())
    }
}
