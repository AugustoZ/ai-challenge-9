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
import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolRegistry
import com.example.aiweb.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки агентного цикла на фейковом LLMClient с очередью заготовленных ответов:
 * счастливый путь, падение инструмента, зацикливание, лимит шагов, интеграция
 * с промт-транспортом и контрольная точка прерванного паузой прогона
 * (onCancelled + resumeContext). Реальных обращений к LLM нет.
 */
class AgentLoopTest {

    /** Уникальная метка resumeContext: по ней дельта отмены проверяется на отсутствие checkpoint. */
    private val resumeMarker = "resume-marker-system-note"

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
        // «Топливо» — лимит ответа шага: доходит в API-параметр как есть, без деления
        assertEquals(800, fake.calls.first().maxTokens)
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
            (1..6).map { toolCallResult(CalculatorTool.NAME, args) } + textResult(forcedReply)
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        assertEquals("зацикливание", outcome.reason)
        assertEquals(forcedReply, outcome.reply)
        assertEquals(
            AgentEvent.Final(6, forcedReply, "зацикливание"),
            events.last()
        )
        // 6 одинаковых вызовов (кик после третьего, повтор после кика), затем финал без инструментов
        assertEquals(7, fake.calls.size)
        assertNull(fake.calls.last().tools)
        assertTrue(fake.calls.last().messages.last().content.orEmpty().contains("Инструменты больше недоступны"))
        assertEquals(6, events.count { it is AgentEvent.ToolResult && it.ok })
        // Первый вызов после кика: инструменты ещё доступны, заметка — последнее сообщение
        val afterKick = fake.calls[3]
        assertTrue(afterKick.tools != null)
        assertEquals("system", afterKick.messages.last().role)
        assertTrue(afterKick.messages.last().content.orEmpty().contains("Стоп: ты повторяешь"))
        // Кик ровно один: до него заметки нет, после — ровно одна в каждом вызове
        fake.calls.forEachIndexed { idx, call ->
            val count = call.messages.count { it.content.orEmpty().contains("Стоп: ты повторяешь") }
            assertEquals(if (idx < 3) 0 else 1, count, "вызов №${idx + 1}: заметок кика $count")
        }
    }

    @Test
    fun `кик после зацикливания даёт модели шанс исправиться`() = runBlocking {
        val args = "{\"expression\":\"1+1\"}"
        val fixedReply = "Вот итог по собранным данным."
        val fake = FakeLLMClient(
            (1..3).map { toolCallResult(CalculatorTool.NAME, args) } + textResult(fixedReply)
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = { events.add(it) })

        // Кик после третьего одинакового вызова: заметка вместо финала — модель
        // отвечает текстом на шаге 4, принудительного финала нет
        assertEquals("готово", outcome.reason)
        assertEquals(fixedReply, outcome.reply)
        assertEquals(4, fake.calls.size)
        assertTrue(fake.calls.last().tools != null)
        assertEquals("system", fake.calls[3].messages.last().role)
        assertTrue(fake.calls[3].messages.last().content.orEmpty().contains("Стоп: ты повторяешь"))
        assertEquals(AgentEvent.Final(4, fixedReply, "готово"), events.last())
    }

    @Test
    fun `кик после падений инструмента даёт шанс сменить путь`() = runBlocking {
        val flaky = object : Tool {
            override val name = "flaky"
            override val description = "Всегда падает"
            override val parametersJsonSchema: JsonElement = buildJsonObject { }
            override suspend fun execute(argsJson: String) = ToolResult(ok = false, content = "упс")
        }
        val fake = FakeLLMClient(
            (1..3).map { toolCallResult("flaky", "{\"q\":\"$it\"}") } +
                textResult("Сменил подход, вот результат.")
        )

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry(linkedMapOf("flaky" to flaky)))
            .run(seed(), maxTokens = null, onEvent = {})

        // Три падения одного инструмента — кик, а не финал: модель сменила путь
        // и ответила сама
        assertEquals("готово", outcome.reason)
        assertEquals("Сменил подход, вот результат.", outcome.reply)
        assertEquals(4, fake.calls.size)
        assertTrue(fake.calls[3].tools != null)
        assertEquals("system", fake.calls[3].messages.last().role)
        assertTrue(fake.calls[3].messages.last().content.orEmpty().contains("Стоп: ты повторяешь"))
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
    fun `топливо передаётся в шаг как есть без деления`() = runBlocking {
        val fake = FakeLLMClient(
            listOf(
                toolCallResult(CalculatorTool.NAME, "{\"expression\":\"2+2\"}"),
                textResult("Ответ: 4")
            )
        )

        AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = 1500, onEvent = {})

        // Прежний код делил топливо на MAX_STEPS с нижней границей (1500/8 → 1024);
        // теперь лимит ответа шага равен установленному «Топливу»
        assertEquals(1500, fake.calls.first().maxTokens)
    }

    @Test
    fun `топливо доносится до модели в системном промте`() = runBlocking {
        val fake = FakeLLMClient(listOf(textResult("Ответ: 4")))

        AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = 8192, onEvent = {})

        // Модель предупреждена о лимите словами, как в одиночных режимах, —
        // иначе она не планирует объём ответа; лимит тот же, что в параметре API
        val system = fake.calls.first().messages.first()
        assertEquals("system", system.role)
        assertTrue(system.content.orEmpty().contains("бортовой агент"))
        assertTrue(system.content.orEmpty().contains("уложи ответ примерно в 8192 токенов"))
    }

    @Test
    fun `без топлива системный промт без указания бюджета`() = runBlocking {
        val fake = FakeLLMClient(listOf(textResult("Ответ: 4")))

        AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed(), maxTokens = null, onEvent = {})

        assertTrue(!fake.calls.first().messages.first().content.orEmpty().contains("токенов"))
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
        // Полный бюджет доносится и словами — финал не должен оборваться снова
        assertTrue(fake.calls.last().messages.last().content.orEmpty().contains("2048 токенов"))
    }

    @Test
    fun `огромный результат инструмента обрезается в контексте цикла`() = runBlocking {
        val huge = "Х".repeat(AgentLoop.TOOL_CONTENT_LIMIT + 10_000)
        val hugeTool = object : Tool {
            override val name = "huge"
            override val description = "Возвращает огромный текст"
            override val parametersJsonSchema: JsonElement = buildJsonObject { }
            override suspend fun execute(argsJson: String) = ToolResult(ok = true, content = huge)
        }
        val fake = FakeLLMClient(
            listOf(
                toolCallResult("huge", "{}"),
                textResult("Готово")
            )
        )

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry(linkedMapOf("huge" to hugeTool)))
            .run(seed(), maxTokens = null, onEvent = {})

        // В контекст ушёл кап (начало + пометка + хвост), а не 50 тысяч символов
        val toolMessage = fake.calls[1].messages.last { it.role == "tool" }
        val capped = toolMessage.content.orEmpty()
        assertTrue(capped.length < AgentLoop.TOOL_CONTENT_LIMIT + 1_000, "контекст: ${capped.length} символов")
        assertTrue(capped.contains("результат обрезан"))
        assertTrue(capped.endsWith("Х".repeat(2_000)), "хвост результата сохранён")
        assertEquals("Готово", outcome.reply)
    }

    @Test
    fun `три падения одного инструмента подряд режутся гвардом`() = runBlocking {
        val flaky = object : Tool {
            override val name = "flaky"
            override val description = "Всегда падает"
            override val parametersJsonSchema: JsonElement = buildJsonObject { }
            override suspend fun execute(argsJson: String) = ToolResult(ok = false, content = "упс")
        }
        val fake = FakeLLMClient(
            (1..6).map { toolCallResult("flaky", "{\"q\":\"$it\"}") } + textResult("Собрал что смог")
        )

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry(linkedMapOf("flaky" to flaky)))
            .run(seed(), maxTokens = null, onEvent = {})

        // Аргументы у вызовов разные — гвард одинаковых подписей молчит;
        // серию падений одного инструмента ловит отдельный гвард
        assertEquals("зацикливание", outcome.reason)
        // 6 падений: кик после третьего, повтор после кика, затем принудительный финал
        assertEquals(7, fake.calls.size)
        assertNull(fake.calls.last().tools)
        assertTrue(fake.calls.last().messages.last().content.orEmpty().contains("Инструменты больше недоступны"))
        // Первый вызов после кика: заметка — последнее сообщение, инструменты ещё доступны
        assertTrue(fake.calls[3].tools != null)
        assertTrue(fake.calls[3].messages.last().content.orEmpty().contains("Стоп: ты повторяешь"))
    }

    @Test
    fun `контекст отправки режется до 30 тысяч токенов`() = runBlocking {
        val oldTurn = "Х".repeat(30_000) // ~12 000 токенов старой истории
        val fatTool = object : Tool {
            override val name = "fat"
            override val description = "Возвращает результат на капе"
            override val parametersJsonSchema: JsonElement = buildJsonObject { }
            override suspend fun execute(argsJson: String) =
                ToolResult(ok = true, content = "X".repeat(AgentLoop.TOOL_CONTENT_LIMIT))
        }
        val fake = FakeLLMClient((1..8).map { toolCallResult("fat", "{\"n\":$it}") } + textResult("Готово"))
        val seed = listOf(
            OpenAIMessage(role = "user", content = oldTurn),
            OpenAIMessage(role = "assistant", content = "Прежний ответ"),
            OpenAIMessage(role = "user", content = "Цель")
        )

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry(linkedMapOf("fat" to fatTool)))
            .run(seed, maxTokens = null, onEvent = {})

        assertEquals("Готово", outcome.reply)
        for ((idx, call) in fake.calls.withIndex()) {
            val est = call.messages.sumOf { ceil((it.content?.length ?: 0) / 2.5).toInt() }
            assertTrue(est <= AgentLoop.MAX_CONTEXT_TOKENS + 500, "вызов №${idx + 1}: перелив $est")
            call.messages.forEachIndexed { i, m ->
                if (m.role == "tool") {
                    val prev = call.messages[i - 1]
                    assertTrue(prev.role == "assistant" && prev.toolCalls != null, "вызов №${idx + 1}: разрыв пары на $i")
                }
            }
        }
        // Старый тяжёлый ход вытеснен целиком, цель пользователя сохранена
        val last = fake.calls.last().messages
        assertTrue(last.none { it.content == oldTurn }, "старый ход должен вытесниться")
        assertTrue(last.any { it.content == "Цель" }, "цель пользователя сохранена")
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

    // ── Контрольная точка прерванного паузой прогона: onCancelled / resumeContext ──

    /** Ответ модели с ДВУМЯ вызовами инструментов: обрыв между их результатами оставляет висячий tool_call. */
    private fun twoToolCallsResult() = ChatResult(
        reply = "",
        promptTokens = 10,
        completionTokens = 3,
        totalTokens = 13,
        elapsedMs = 0L,
        toolCalls = listOf(
            OpenAIToolCall(
                id = "call-a",
                type = "function",
                function = OpenAIToolCallFunction(CalculatorTool.NAME, "{\"expression\":\"2+2\"}")
            ),
            OpenAIToolCall(
                id = "call-b",
                type = "function",
                function = OpenAIToolCallFunction(CalculatorTool.NAME, "{\"expression\":\"3+3\"}")
            )
        )
    )

    @Test
    fun `отмена прогона отдаёт чиненую дельту в onCancelled, resumeContext в дельту не входит`() = runBlocking {
        val fake = FakeLLMClient(listOf(twoToolCallsResult()))
        val loopJob = Job()
        var callbackCount = 0
        var delta: List<OpenAIMessage>? = null
        val runner = launch(loopJob) {
            try {
                AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns()).run(
                    seed(),
                    maxTokens = null,
                    onEvent = { event ->
                        // Обрыв между tool-результатами: «3+3» — второй вызов, его
                        // результат ещё не в контексте — ремонт обязан дописать его
                        if (event is AgentEvent.ToolCalled && event.arguments == "{\"expression\":\"3+3\"}") {
                            loopJob.cancel()
                        }
                        yield()
                    },
                    onCancelled = { repaired ->
                        callbackCount++
                        delta = repaired
                    },
                    resumeContext = listOf(OpenAIMessage(role = "system", content = resumeMarker))
                )
            } catch (_: CancellationException) {
                // ожидаемое завершение отменённого прогона
            }
        }
        runner.join()

        assertEquals(1, callbackCount, "onCancelled вызывается ровно один раз")
        val repaired = assertNotNull(delta)
        // Дельта — сообщения прогона: assistant с двумя вызовами, выполненный tool
        // и синтетический результат для висячего вызова
        assertEquals(3, repaired.size)
        assertEquals("assistant", repaired[0].role)
        assertEquals(listOf("call-a", "call-b"), repaired[0].toolCalls?.map { it.id })
        assertEquals("tool", repaired[1].role)
        assertEquals("call-a", repaired[1].toolCallId)
        assertEquals("4", repaired[1].content, "выполненная пара сохраняется как есть")
        assertEquals("tool", repaired[2].role)
        assertEquals("call-b", repaired[2].toolCallId)
        assertEquals("Прервано паузой пользователя", repaired[2].content)
        assertTrue(
            repaired.none { it.content == resumeMarker },
            "resumeContext не входит в дельту отмены"
        )
    }

    @Test
    fun `отмена во время вызова модели — дельта пустая, ремонт no-op`() = runBlocking {
        val loopJob = Job()
        val fake = object : LLMClient {
            override suspend fun ask(call: LLMCall): ChatResult {
                loopJob.cancel()
                yield()
                error("недостижимо после отмены")
            }
        }
        var callbackCount = 0
        var delta: List<OpenAIMessage>? = null
        val runner = launch(loopJob) {
            try {
                AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns()).run(
                    seed(),
                    maxTokens = null,
                    onEvent = {},
                    onCancelled = { repaired ->
                        callbackCount++
                        delta = repaired
                    }
                )
            } catch (_: CancellationException) {
                // ожидаемое завершение отменённого прогона
            }
        }
        runner.join()

        // Новый assistant ещё не добавлен — дельта валидна без ремонта
        assertEquals(1, callbackCount)
        assertEquals(emptyList(), assertNotNull(delta))
    }

    @Test
    fun `resumeContext добавляется сразу после seed-сообщений`() = runBlocking {
        val fake = FakeLLMClient(listOf(textResult("Продолжаю с места остановки")))
        val resume = listOf(
            OpenAIMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(OpenAIToolCall(id = "call-1"))
            ),
            OpenAIMessage(role = "tool", content = "4", toolCallId = "call-1"),
            OpenAIMessage(role = "system", content = "Продолжай с места остановки")
        )
        val seed = listOf(
            OpenAIMessage(role = "user", content = "старая история"),
            OpenAIMessage(role = "user", content = "задача")
        )

        val outcome = AgentLoop(fake, NativeToolTransport(), ToolRegistry.withBuiltIns())
            .run(seed, maxTokens = null, onEvent = {}, resumeContext = resume)

        assertEquals("Продолжаю с места остановки", outcome.reply)
        val messages = fake.calls.single().messages
        assertEquals("system", messages.first().role, "system-промт — первым")
        // Порядок: system-промт → seed → resumeContext (без перестановок)
        assertEquals(seed, messages.drop(1).take(seed.size))
        assertEquals(resume, messages.drop(1 + seed.size).take(resume.size))
        assertEquals("system", messages.last().role, "заметка возобновления — последним сообщением")
    }
}
