package com.example.aiweb.routes

import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.StreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки кодирования кадров SSE-потока /api/chat/stream: формат
 * `data: <JSON>\n\n`, дискриминатор type, гарантия одной строки
 * (переносы внутри полезной нагрузки экранируются).
 */
class SseFrameTest {

    @Test
    fun `кадр agent оборачивает событие агента`() {
        assertEquals(
            "data: {\"type\":\"agent\",\"event\":{\"type\":\"step_started\",\"step\":1}}\n\n",
            encodeFrame(StreamEvent.Agent(AgentEvent.StepStarted(1)))
        )
    }

    @Test
    fun `кадр final несёт полный ответ чата`() {
        val frame = encodeFrame(
            StreamEvent.Final(ChatResponse(reply = "готово", totalTokens = 42))
        )

        assertTrue(frame.startsWith("data: {\"type\":\"final\",\"response\":"))
        assertTrue(frame.contains("\"reply\":\"готово\""))
        assertTrue(frame.contains("\"totalTokens\":42"))
        assertEquals("\n\n", frame.takeLast(2))
    }

    @Test
    fun `кадр cancelled передаёт requestId`() {
        assertEquals(
            "data: {\"type\":\"cancelled\",\"requestId\":\"req-1\"}\n\n",
            encodeFrame(StreamEvent.Cancelled("req-1"))
        )
    }

    @Test
    fun `кадр error передаёт сообщение об ошибке`() {
        assertEquals(
            "data: {\"type\":\"error\",\"message\":\"сбой\"}\n\n",
            encodeFrame(StreamEvent.Error("сбой"))
        )
    }

    @Test
    fun `JSON в кадре всегда в одну строку`() {
        val frame = encodeFrame(StreamEvent.Status("строка 1\nстрока 2"))

        // Ровно два \n на кадр — завершающие; перенос внутри текста экранирован
        assertEquals(2, frame.count { it == '\n' })
        assertTrue(frame.contains("\\n"))
    }
}
