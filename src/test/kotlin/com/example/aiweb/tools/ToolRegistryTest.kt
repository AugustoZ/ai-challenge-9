package com.example.aiweb.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверки реестра: маппинг в спецификации function calling и поиск по имени. */
class ToolRegistryTest {

    private val registry = ToolRegistry.withBuiltIns()

    @Test
    fun `specs формирует спецификации function calling для всех встроенных инструментов`() {
        val specs = registry.specs()
        assertEquals(listOf("current_datetime", "calculator", "http_get"), specs.map { it.function.name })
        specs.forEach { spec ->
            assertEquals("function", spec.type)
            assertNotNull(spec.function.parameters, "Схема параметров у ${spec.function.name} не задана")
        }
    }

    @Test
    fun `имя описание и схема инструмента попадают в спецификацию`() {
        val calculator = registry.specs().first { it.function.name == "calculator" }
        assertTrue(calculator.function.description.contains("выражение"))
        assertTrue(calculator.function.parameters.toString().contains("expression"))
    }

    @Test
    fun `get находит инструмент по имени`() {
        assertEquals(CurrentDatetimeTool.NAME, registry.get("current_datetime")?.name)
    }

    @Test
    fun `get на неизвестное имя возвращает null`() {
        assertNull(registry.get("nope"))
    }

    @Test
    fun `augmentedWith сохраняет базовые инструменты и добавляет новые в конец`() {
        val augmented = registry.augmentedWith(listOf(StubTool("task_notes"), StubTool("memory_save")))

        assertEquals(
            listOf("current_datetime", "calculator", "http_get", "task_notes", "memory_save"),
            augmented.specs().map { it.function.name }
        )
        assertEquals(StubTool("task_notes").description, augmented.get("task_notes")?.description)
    }

    @Test
    fun `augmentedWith пропускает дубликаты имён`() {
        val augmented = registry.augmentedWith(
            listOf(StubTool("calculator"), StubTool("memory_save"))
        )

        val names = augmented.specs().map { it.function.name }
        assertEquals(listOf("current_datetime", "calculator", "http_get", "memory_save"), names)
        // Базовый инструмент не заменён заглушкой
        assertEquals(CalculatorTool().description, augmented.get("calculator")?.description)
    }

    /** Заглушка инструмента для проверок реестра. */
    private class StubTool(override val name: String) : Tool {
        override val description = "заглушка $name"
        override val parametersJsonSchema = kotlinx.serialization.json.buildJsonObject { }
        override suspend fun execute(argsJson: String) = ToolResult(true, "")
    }
}
