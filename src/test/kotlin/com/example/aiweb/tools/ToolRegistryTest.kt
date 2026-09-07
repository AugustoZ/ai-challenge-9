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
}
