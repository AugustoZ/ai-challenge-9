package com.example.aiweb.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки NativeToolProvider: все 4 нативных инструмента (reminder, summarize,
 * search, saveToFile) появляются в реестре через ToolRegistry.augmentedWith —
 * критерий приёмки задачи 4.
 */
class NativeToolProviderTest {

    @Test
    fun `нативные тула появляются в specs реестра через augmentedWith`() {
        val dataDir = Files.createTempDirectory("aiweb-native").toFile().absolutePath

        val registry = ToolRegistry.withBuiltIns().augmentedWith(NativeToolProvider.tools(dataDir, llm = null))
        val names = registry.specs().map { it.function.name }

        assertTrue(names.containsAll(listOf("reminder", "summarize", "search", "saveToFile")), names.toString())
        // встроенные инструменты остаются на месте
        assertTrue(names.containsAll(listOf("current_datetime", "calculator", "http_get")), names.toString())
    }

    @Test
    fun `все четыре тула доступны по имени`() {
        val dataDir = Files.createTempDirectory("aiweb-native").toFile().absolutePath

        val registry = ToolRegistry.withBuiltIns().augmentedWith(NativeToolProvider.tools(dataDir, llm = null))

        assertEquals(ReminderTool.NAME, "reminder")
        assertEquals(SummarizeTool.NAME, "summarize")
        assertEquals(SearchTool.NAME, "search")
        assertEquals(SaveToFileTool.NAME, "saveToFile")
        listOf("reminder", "summarize", "search", "saveToFile").forEach { name ->
            assertTrue(registry.get(name) != null, "инструмент $name не найден в реестре")
        }
    }
}
