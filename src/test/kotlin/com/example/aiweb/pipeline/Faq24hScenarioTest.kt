package com.example.aiweb.pipeline

import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сценарий faq-24h (задача 12): файл парсится в PipelineScenario и прогоняется
 * движком на stub-тулах всех пяти задействованных инструментов.
 *
 * Gradle-тесты идут из корня репозитория, поэтому файл читается как
 * `config/pipelines/faq-24h.json`.
 */
class Faq24hScenarioTest {

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

    private val scenarioFile = File("config/pipelines/faq-24h.json")

    private val tools = mutableMapOf<String, Stub>()

    private fun stub(name: String, content: String): Stub {
        val s = Stub(name) { ToolResult(true, content) }
        tools[s.name] = s
        return s
    }

    private fun runScenario(): Pair<PipelineRunResult, MutableList<PipelineEvent>> {
        val events = mutableListOf<PipelineEvent>()
        val scenario = Json.decodeFromString<PipelineScenario>(scenarioFile.readText())
        val runner = PipelineRunner(scenario, { tools[it] }, { events.add(it) })
        val result = runBlocking { runner.run() }
        return result to events
    }

    @Test
    fun `faq-24h json parses into PipelineScenario with 5 steps and real tool names`() {
        val scenario = Json.decodeFromString<PipelineScenario>(scenarioFile.readText())

        assertEquals("faq-24h", scenario.name)
        assertTrue(!scenario.description.isNullOrBlank(), "description должно быть коротким, но заполненным")
        assertEquals(5, scenario.steps.size)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), scenario.steps.map { it.id })
        assertEquals(
            listOf(
                "mcp__tg__read_recent",
                "mcp__tg__summary",
                "summarize",
                "saveToFile",
                "mcp__wiki__append_faq_page"
            ),
            scenario.steps.map { it.tool }
        )
        assertTrue(scenario.steps.all { !it.optional }, "в faq-24h нет optional-шагов")
    }

    @Test
    fun `scenario runs green on 5 stub tools and step5 receives step3 content`() {
        val recentJson =
            """{"ok":true,"chat":"tank_dargo_h6_chat","windowHours":24,"count":2,"messages":["[2026-09-26 01:00 UTC] Анна: Как включить турбо?","[2026-09-26 02:00 UTC] Игорь: Дави на кнопку турбо"]}"""
        val summaryMd = "## Сводка чата за последние 24 ч\n\nСообщений: 2 | Текстовых: 2 | Вопросов: 1\n\n### Вопросы (топ)\n\n1. Как включить турбо?"
        val summaryRaw = """{"ok":true,"windowHours":24,"count":2,"text":"$summaryMd"}"""
        val faq = "1. Как включить турбо? — Дави на кнопку турбо. Частота: 3"

        val s3 = stub("summarize", faq)
        val s4 = stub("saveToFile", "Файл записан: exports/faq-24h.md (120 байт)")
        val s5 = stub("mcp__wiki__append_faq_page", """{"ok":true,"created":false,"newSize":120}""")
        stub("mcp__tg__read_recent", recentJson)
        stub("mcp__tg__summary", summaryRaw)

        val (result, events) = runScenario()

        assertTrue(result.succeeded, "сценарий должен завершиться успешно: $result")
        val finished = events.filterIsInstance<PipelineEvent.StepFinished>()
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), finished.map { it.id })
        assertTrue(finished.all { it.status == PipelineStepState.done }, "все 5 шагов должны быть done: $finished")

        // s3: после правки «постоянная память» s3 получает только s2. Целый
        // плейсхолдер резолвится типизированно: результат s2 (валидный JSON)
        // приходит объектом, SummarizeTool сериализует его обратно в текст.
        val s3Args = Json.parseToJsonElement(s3.calls.single()).jsonObject
        assertEquals(Json.parseToJsonElement(summaryRaw), s3Args["text"])
        assertEquals(
            "Выбери 5–10 самых популярных вопросов за сутки и краткие ответы из чата; формат: вопрос, ответ, частота",
            s3Args["instruction"]!!.jsonPrimitive.content
        )

        // s4: точный плейсхолдер → содержимое = результат s3; имя файла статичное.
        val s4Args = Json.parseToJsonElement(s4.calls.single()).jsonObject
        assertEquals("faq-24h.md", s4Args["name"]!!.jsonPrimitive.content)
        assertEquals(faq, s4Args["content"]!!.jsonPrimitive.content)

        // s5: получил контент шага 3 + заголовок секции.
        val s5Args = Json.parseToJsonElement(s5.calls.single()).jsonObject
        assertEquals("Сводка за 24ч", s5Args["section_title"]!!.jsonPrimitive.content)
        assertEquals(faq, s5Args["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty window read_recent and zero questions still succeeds and wiki step gets the message`() {
        stub("mcp__tg__read_recent", "") // пустое окно: ни одного сообщения
        stub("mcp__tg__summary", """{"ok":true,"windowHours":24,"count":0,"text":"— вопросов с '?' за период нет —"}""")
        val s3 = stub("summarize", "0 вопросов за окно")
        stub("saveToFile", "Файл записан: exports/faq-24h.md (18 байт)")
        val s5 = stub("mcp__wiki__append_faq_page", """{"ok":true,"created":false,"newSize":18}""")

        val (result, events) = runScenario()

        assertTrue(result.succeeded, "пустое окно не должно ронять сценарий: $result")
        val finished = events.filterIsInstance<PipelineEvent.StepFinished>()
        assertTrue(finished.all { it.status == PipelineStepState.done }, "все 5 шагов должны быть done: $finished")

        // wiki-шаг получил сообщение summarize-шага как есть.
        val s5Args = Json.parseToJsonElement(s5.calls.single()).jsonObject
        assertEquals("0 вопросов за окно", s5Args["content"]!!.jsonPrimitive.content)
        assertEquals("Сводка за 24ч", s5Args["section_title"]!!.jsonPrimitive.content)
        // s3 = только s2 (правка «постоянная память»): типизированная подстановка
        // целого плейсхолдера отдаёт сводку s2 объектом.
        val s3Args = Json.parseToJsonElement(s3.calls.single()).jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"ok":true,"windowHours":24,"count":0,"text":"— вопросов с '?' за период нет —"}"""),
            s3Args["text"]
        )
    }
}
