package com.example.aiweb.pipeline

import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Движок пайплайна (задача 10): линейный прогон, плейсхолдеры, кап результата,
 * обработка упавших и optional-шагов. Все тулы — stubs на Map, без сети.
 */
class PipelineRunnerTest {

    private class Stub(
        override val name: String,
        private val handler: suspend (String) -> ToolResult
    ) : Tool {
        override val description = "stub"
        override val parametersJsonSchema: JsonElement = JsonObject(emptyMap())
        val calls = mutableListOf<String>()
        override suspend fun execute(argsJson: String): ToolResult {
            calls.add(argsJson)
            return handler(argsJson)
        }
    }

    private val tools = mutableMapOf<String, Stub>()

    private fun tool(name: String, handler: suspend (String) -> ToolResult): Stub {
        val stub = Stub(name, handler)
        tools[stub.name] = stub
        return stub
    }

    private fun lookup(name: String): Tool? = tools[name]

    private fun step(id: String, toolName: String, args: String = "{}", optional: Boolean = false) =
        PipelineStep(id = id, tool = toolName, args = Json.parseToJsonElement(args).jsonObject, optional = optional)

    private fun runWithEvents(vararg steps: PipelineStep): Pair<PipelineRunResult, List<PipelineEvent>> {
        val events = mutableListOf<PipelineEvent>()
        val runner = PipelineRunner(PipelineScenario(name = "s", steps = steps.toList()), ::lookup) { events.add(it) }
        val result = runBlocking { runner.run() }
        return result to events
    }

    @Test
    fun `happy path step2 placeholder receives result of step1`() {
        tool("a") { ToolResult(true, "AAA") }
        val b = tool("b") { ToolResult(true, "ok") }

        val (result, events) = runWithEvents(
            step("s1", "a"),
            step("s2", "b", "{\"x\":\"\${steps.s1.result}\"}")
        )

        assertTrue(result.succeeded, "пайплайн должен завершиться успешно: $result")
        val x = Json.parseToJsonElement(b.calls.single()).jsonObject["x"]!!.jsonPrimitive.content
        assertEquals("AAA", x)

        val statuses = events.filterIsInstance<PipelineEvent.StepFinished>().map { it.status }
        assertEquals(listOf(PipelineStepState.done, PipelineStepState.done), statuses)
    }

    @Test
    fun `valid json result is substituted structurally not as string`() {
        tool("a") { ToolResult(true, "{\"num\":5}") }
        val b = tool("b") { ToolResult(true, "ok") }

        val (result, _) = runWithEvents(
            step("s1", "a"),
            step("s2", "b", "{\"x\":\"\${steps.s1.result}\"}")
        )

        assertTrue(result.succeeded)
        val x = Json.parseToJsonElement(b.calls.single()).jsonObject["x"]
        assertTrue(x is JsonObject, "валидный JSON результата должен вставляться как объект: ${b.calls.single()}")
        assertEquals(5, (x as JsonObject)["num"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `placeholder inside larger string is interpolated`() {
        tool("a") { ToolResult(true, "AAA") }
        val b = tool("b") { ToolResult(true, "ok") }

        val (result, _) = runWithEvents(
            step("s1", "a"),
            step("s2", "b", "{\"x\":\"prefix \${steps.s1.result} suffix\"}")
        )

        assertTrue(result.succeeded)
        val x = Json.parseToJsonElement(b.calls.single()).jsonObject["x"]!!.jsonPrimitive.content
        assertEquals("prefix AAA suffix", x)
    }

    @Test
    fun `unknown step reference fails pipeline with readable text`() {
        val (result, events) = runWithEvents(
            step("s1", "mtool", "{\"x\":\"\${steps.nope.result}\"}")
        )

        assertFalse(result.succeeded)
        assertTrue(result.error!!.contains("nope"), "сообщение должно упоминать неизвестный шаг: ${result.error}")
        val failed = events.filterIsInstance<PipelineEvent.StepFinished>().single()
        assertEquals(PipelineStepState.failed, failed.status)
        assertTrue(failed.error!!.contains("nope"), "ошибка шага должна упоминать ссылку: ${failed.error}")
    }

    @Test
    fun `missing tool fails pipeline with readable text`() {
        val (result, events) = runWithEvents(step("s1", "absent"))

        assertFalse(result.succeeded)
        assertTrue(result.error!!.contains("absent"), "сообщение должно содержать имя тула: ${result.error}")
        val failed = events.filterIsInstance<PipelineEvent.StepFinished>().single()
        assertEquals(PipelineStepState.failed, failed.status)
    }

    @Test
    fun `failed step stops pipeline with its error message`() {
        tool("boom") { ToolResult(false, "шаг рухнул") }
        val later = tool("later") { ToolResult(true, "ok") }

        val (result, events) = runWithEvents(
            step("s1", "boom"),
            step("s2", "later")
        )

        assertFalse(result.succeeded)
        assertEquals("шаг рухнул", result.error)
        assertTrue(later.calls.isEmpty(), "шаг после упавшего не должен запускаться")
        val failed = events.filterIsInstance<PipelineEvent.StepFinished>().first()
        assertEquals(PipelineStepState.failed, failed.status)
        assertEquals("шаг рухнул", failed.error)
    }

    @Test
    fun `optional failed step is marked failed and pipeline continues`() {
        tool("boom") { ToolResult(false, "не критично") }
        val later = tool("later") { ToolResult(true, "ok") }

        val (result, events) = runWithEvents(
            step("s1", "boom", optional = true),
            step("s2", "later")
        )

        assertTrue(result.succeeded, "optional-шаг не должен останавливать пайплайн: $result")
        assertTrue(later.calls.isNotEmpty(), "optional-шаг после упавшего должен выполняться")
        val statuses = events.filterIsInstance<PipelineEvent.StepFinished>().map { it.status }
        assertEquals(listOf(PipelineStepState.failed, PipelineStepState.done), statuses)
    }

    @Test
    fun `result is capped at 20000 chars`() {
        tool("a") { ToolResult(true, "x".repeat(30_000)) }
        val b = tool("b") { ToolResult(true, "ok") }

        val (result, _) = runWithEvents(
            step("s1", "a"),
            step("s2", "b", "{\"x\":\"\${steps.s1.result}\"}")
        )

        assertTrue(result.succeeded)
        val x = Json.parseToJsonElement(b.calls.single()).jsonObject["x"]!!.jsonPrimitive.content
        assertEquals(PipelineRunner.MAX_RESULT_LENGTH, x.length)
    }

    @Test
    fun `result preview in event is capped at 500 chars`() {
        tool("a") { ToolResult(true, "y".repeat(600)) }
        val (_, events) = runWithEvents(step("s1", "a"))

        val preview = events.filterIsInstance<PipelineEvent.StepFinished>().single().resultPreview
        assertEquals(PipelineRunner.RESULT_PREVIEW_LIMIT, preview!!.length)
    }
}
