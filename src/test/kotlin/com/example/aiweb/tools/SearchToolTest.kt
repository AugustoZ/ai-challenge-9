package com.example.aiweb.tools

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки search: находка по регистронезависимой подстроке с номером строки,
 * фильтр glob, кап 50 совпадений, ошибки и пустые результаты.
 */
class SearchToolTest {

    private fun newTool(dir: File = Files.createTempDirectory("aiweb-search").toFile()) =
        SearchTool(dir.absolutePath)

    /** Готовит exports/ с парой файлов под поиск. */
    private fun plantExports(root: File): File {
        val exports = File(root, SearchTool.EXPORTS_DIR).apply { mkdirs() }
        File(exports, "notes.md").writeText(
            "первая строка\nПорт бэкенда 8083\nтретья строка\n"
        )
        File(exports, "data.txt").writeText("значение порта: 8083\n")
        return exports
    }

    @Test
    fun `находит подстроку без учёта регистра с файлом и номером строки`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-search").toFile()
        plantExports(root)
        val tool = newTool(root)

        val result = tool.execute("""{"query": "порт"}""")

        assertTrue(result.ok, result.content)
        assertTrue(result.content.contains("notes.md:2:Порт бэкенда 8083"), result.content)
        assertTrue(result.content.contains("data.txt:1:значение порта: 8083"), result.content)
    }

    @Test
    fun `glob ограничивает поиск указанными файлами`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-search").toFile()
        plantExports(root)
        val tool = newTool(root)

        val result = tool.execute("""{"query": "порт", "glob": "*.md"}""")

        assertTrue(result.ok, result.content)
        assertTrue(result.content.contains("notes.md:2"), result.content)
        assertFalse(result.content.contains("data.txt"), result.content)
    }

    @Test
    fun `кап совпадений не превышает 50`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-search").toFile()
        val exports = File(root, SearchTool.EXPORTS_DIR).apply { mkdirs() }
        val content = (1..60).joinToString("\n") { "цель $it" } + "\n"
        File(exports, "big.txt").writeText(content)
        val tool = newTool(root)

        val result = tool.execute("""{"query": "цель"}""")

        assertTrue(result.ok, result.content)
        val outLines = result.content.lineSequence().toList()
        assertEquals(SearchTool.MAX_MATCHES, outLines.size - 1) // последняя строка — примечание об обрезке
        assertFalse(result.content.contains("цель 60"), result.content)
        assertTrue(result.content.contains("первые ${SearchTool.MAX_MATCHES} совпадений"), result.content)
    }

    @Test
    fun `пустой запрос даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"query": ""}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("query"))
    }

    @Test
    fun `нет совпадений - ok с текстом об отсутствии`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-search").toFile()
        plantExports(root)
        val tool = newTool(root)

        val result = tool.execute("""{"query": "несуществующее"}""")

        assertTrue(result.ok)
        assertTrue(result.content.contains("не найдено"))
    }

    @Test
    fun `папка exports отсутствует - поиск возвращает пустой результат`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"query": "что-то"}""")

        assertTrue(result.ok)
        assertTrue(result.content.contains("не найдено"))
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("not json")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
