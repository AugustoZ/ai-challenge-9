package com.example.aiweb.agent

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenAIToolCallFunction
import com.example.aiweb.tools.CalculatorTool
import com.example.aiweb.tools.NativeToolTransport
import com.example.aiweb.tools.PromptToolTransport
import com.example.aiweb.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки агентного цикла на фейковом LLMClient с очередью заготовленных ответов:
 * счастливый путь, падение инструмента, зацикливание, лимит шагов и интеграция
 * с промт-транспортом. Реальных обращений к LLM нет.
 */
class AgentLoopTest {

    /**
     * Фейковый LLMClient (паттерн client/FakeLLMClientTest): записывает вызовы и
     * отдаёт ответы из очереди по порядку; исчерпание очереди — ошибка теста.
     */
    private class FakeLLMClient(private val canned: List<ChatResult>) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned.getOrNull(calls.size - 1)
                ?: error("Фейк: очередь ответов исчерпана на вызове №${calls.size}")
        }
    }

    /** Заготовленный ответ с одним вызовом инструмента. */
    private fun toolCallResult(
        tool: String,
        args: String,
        prompt: Int = 10,
        completion: Int = 3
    ) = ChatResult(
        reply = "",
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = prompt + completion,
        elapsedMs = 0L,
        toolCalls = listOf(
            OpenAIToolCall(
                id = "call-1",
                type = "function",
                function = OpenAIToolCallFunction(name = tool, arguments = args)
            )
        )
    )

    /** Заготовленный ответ обычным текстом (финальный ответ модели). */
    private fun textResult(
        text: String,
        prompt: Int = 7,
        completion: Int = 2,
        finishReason: String? = null
    ) = ChatResult(
        reply = text,
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = prompt + completion,
        elapsedMs = 0L,
        finishReason = finishReason
    )

    private fun seed() = listOf(OpenAIMessage(role = "user", content = "задача"))

    @Test
    fun `инструмент затем финальный ответ события идут по порядку`() = runBlocking {
        val fake = FakeLLMClient(
            listOf(
                toolCallResult(CalculatorTool.NAME, "{\"expression\":\"2+2\"}", prompt = 10, completion = 3),
                textResult("Ответ: 4", prompt = 7, completion = 2)
            )
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = 800, onEvent = { events.add(it) })

        assertEquals(
            listOf(
                AgentEvent.StepStarted(1),
                AgentEvent.ToolCalled(1, "calculator", "{\"expression\":\"2+2\"}"),
                AgentEvent.ToolResult(1, "calculator", ok = true, content = "4"),
                AgentEvent.StepStarted(2),
                AgentEvent.Final(2, "Ответ: 4", "готово")
            ),
            events
        )
        assertEquals("Ответ: 4", outcome.reply)
        assertEquals("готово", outcome.reason)
        assertEquals(17, outcome.promptTokens)
        assertEquals(5, outcome.completionTokens)
        assertEquals(22, outcome.totalTokens)
        // Бюджет шага: 800 / MAX_STEPS = 100, но нижняя граница удерживает 1024 на шаг
        assertEquals(1024, fake.calls.first().maxTokens)
        // Второй вызов получил пару assistant(tool_calls) → role="tool" в контексте
        val followUp = fake.calls[1].messages
        val assistant = followUp[followUp.size - 2]
        assertEquals("assistant", assistant.role)
        assertEquals(1, assistant.toolCalls?.size)
        assertEquals("calculator", assistant.toolCalls?.single()?.function?.name)
        val toolReply = followUp.last()
        assertEquals("tool", toolReply.role)
        assertEquals("4", toolReply.content)
        assertEquals("call-1", toolReply.toolCallId)
    }

    @Test
    fun `неизвестный инструмент не роняет цикл`() = runBlocking {
        val fake = FakeLLMClient(
            listOf(
                toolCallResult("nope", "{}"),
                textResult("Готово: инструмент не понадобился.")
            )
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        assertEquals(
            listOf(
                AgentEvent.StepStarted(1),
                AgentEvent.ToolCalled(1, "nope", "{}"),
                AgentEvent.ToolResult(1, "nope", ok = false, content = "Неизвестный инструмент: nope"),
                AgentEvent.StepStarted(2),
                AgentEvent.Final(2, "Готово: инструмент не понадобился.", "готово")
            ),
            events
        )
        assertEquals("Готово: инструмент не понадобился.", outcome.reply)
        assertEquals("готово", outcome.reason)
        // Ошибка ушла модели ролью "tool", цикл продолжился вторым вызовом
        assertEquals(2, fake.calls.size)
        assertEquals("Неизвестный инструмент: nope", fake.calls[1].messages.last().content)
    }

    @Test
    fun `три одинаковых вызова подряд включают принудительный финал`() = runBlocking {
        val args = "{\"expression\":\"1+1\"}"
        val forcedReply = "Задача не решается: вызовы повторяются без прогресса."
        val fake = FakeLLMClient(
            listOf(
                toolCallResult(CalculatorTool.NAME, args),
                toolCallResult(CalculatorTool.NAME, args),
                toolCallResult(CalculatorTool.NAME, args),
                textResult(forcedReply)
            )
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        assertEquals("зацикливание", outcome.reason)
        assertEquals(forcedReply, outcome.reply)
        assertEquals(
            AgentEvent.Final(3, forcedReply, "зацикливание"),
            events.last()
        )
        // Три шага с одинаковыми вызовами, затем финал без инструментов
        assertEquals(4, fake.calls.size)
        assertNull(fake.calls.last().tools)
        assertTrue(fake.calls.last().messages.last().content.orEmpty().contains("Инструменты больше недоступны"))
        // Третий повтор всё же исполнен — «висячих» assistant tool_calls в контексте нет
        assertEquals(3, events.count { it is AgentEvent.ToolResult && it.ok })
    }

    @Test
    fun `исчерпание лимита шагов даёт финал без инструментов`() = runBlocking {
        val forcedReply = "Итог по собранным данным."
        val fake = FakeLLMClient(
            (1..AgentLoop.MAX_STEPS).map { i -> toolCallResult(CalculatorTool.NAME, "{\"expression\":\"$i*$i\"}") } +
                textResult(forcedReply)
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        assertEquals("лимит шагов", outcome.reason)
        assertEquals(forcedReply, outcome.reply)
        assertEquals(
            buildList {
                repeat(AgentLoop.MAX_STEPS) { idx ->
                    val step = idx + 1
                    add(AgentEvent.StepStarted(step))
                    add(AgentEvent.ToolCalled(step, "calculator", "{\"expression\":\"$step*$step\"}"))
                    add(AgentEvent.ToolResult(step, "calculator", ok = true, content = "${step * step}"))
                }
                add(AgentEvent.Final(AgentLoop.MAX_STEPS + 1, forcedReply, "лимит шагов"))
            },
            events
        )
        // MAX_STEPS вызовов с инструментами + один принудительный финал
        assertEquals(AgentLoop.MAX_STEPS + 1, fake.calls.size)
        assertNull(fake.calls.last().tools)
    }

    @Test
    fun `бюджет шага не опускается ниже нижней границы`() = runBlocking {
        val fake = FakeLLMClient(
            listOf(
                toolCallResult(CalculatorTool.NAME, "{\"expression\":\"2+2\"}"),
                textResult("Ответ: 4")
            )
        )

        AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = 1024, onEvent = {})

        // 1024 / MAX_STEPS = 128, но нижняя граница удерживает полный 1024 на шаг
        assertEquals(1024, fake.calls.first().maxTokens)
    }

    @Test
    fun `обрыв по лимиту токенов включает принудительный финал`() = runBlocking {
        val forcedReply = "Готовое решение из уже проделанных шагов."
        val fake = FakeLLMClient(
            listOf(
                textResult("Сначала рассмотрим постановку задачи и начнём", finishReason = "length"),
                textResult(forcedReply)
            )
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = 2048, onEvent = { events.add(it) })

        assertEquals("ответ обрезан", outcome.reason)
        assertEquals(forcedReply, outcome.reply)
        assertEquals(
            listOf(
                AgentEvent.StepStarted(1),
                AgentEvent.Final(1, forcedReply, "ответ обрезан")
            ),
            events
        )
        // Усечённый шаг, затем финал без инструментов с ПОЛНЫМ бюджетом «Топлива»
        assertEquals(2, fake.calls.size)
        assertNull(fake.calls.last().tools)
        assertEquals(2048, fake.calls.last().maxTokens)
        assertTrue(fake.calls.last().messages.last().content.orEmpty().contains("Инструменты больше недоступны"))
    }

    @Test
    fun `промт-транспорт проходит цикл end-to-end`() = runBlocking {
        val fenced =
            "```json\n{\"tool\": \"calculator\", \"arguments\": {\"expression\": \"2*3\"}}\n```"
        val fake = FakeLLMClient(
            listOf(
                textResult(fenced, prompt = 5, completion = 4),
                textResult("Ответ: 6", prompt = 6, completion = 1)
            )
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, PromptToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        assertEquals(
            listOf(
                AgentEvent.StepStarted(1),
                AgentEvent.ToolCalled(1, "calculator", "{\"expression\":\"2*3\"}"),
                AgentEvent.ToolResult(1, "calculator", ok = true, content = "6"),
                AgentEvent.StepStarted(2),
                AgentEvent.Final(2, "Ответ: 6", "готово")
            ),
            events
        )
        assertEquals("Ответ: 6", outcome.reply)
        assertEquals("готово", outcome.reason)
        assertEquals(11, outcome.promptTokens)
        assertEquals(5, outcome.completionTokens)
        // Промт-транспорт добавил описание инструментов system-сообщением
        assertTrue(fake.calls.first().messages.last().content.orEmpty().contains("calculator"))
    }
}
