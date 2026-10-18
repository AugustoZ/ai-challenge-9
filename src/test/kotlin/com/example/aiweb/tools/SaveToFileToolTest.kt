package com.example.aiweb.tools

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки saveToFile: запись в exports, гварды пути (обход каталога и
 * абсолютные пути), кап содержимого 1 МБ, создание вложенных папок.
 */
class SaveToFileToolTest {

    private fun newTool(dir: File = Files.createTempDirectory("aiweb-savefile").toFile()) =
        SaveToFileTool(dir.absolutePath)

    @Test
    fun `запись файла создаёт файл в exports с содержимым`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-savefile").toFile()
        val tool = newTool(root)

        val result = tool.execute("""{"name": "notes.md", "content": "строка отчёта"}""")

        assertTrue(result.ok, result.content)
        val written = File(root, "exports/notes.md")
        assertTrue(written.isFile)
        assertEquals("строка отчёта", written.readText())
        assertTrue(result.content.contains("exports/notes.md"))
    }

    @Test
    fun `путь с обходом каталога отклоняется и ничего не пишет`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-savefile").toFile()
        val tool = newTool(root)

        val result = tool.execute("""{"name": "../evil", "content": "не должно попасть на диск"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("\"..\""), result.content)
        assertFalse(File(root, "evil").exists())
        assertFalse(File(root.parentFile, "evil").exists())
    }

    @Test
    fun `абсолютный путь отклоняется`() = runBlocking {
        val tool = newTool()

        val result = tool.execute("""{"name": "/etc/passwd", "content": "не должно попасть на диск"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("относительным"), result.content)
    }

    @Test
    fun `содержимое больше 1 МБ отклоняется`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-savefile").toFile()
        val tool = newTool(root)
        val big = "a".repeat(SaveToFileTool.MAX_CONTENT_LENGTH + 1)

        val result = tool.execute("""{"name": "big.txt", "content": "$big"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("длиннее"), result.content)
        assertFalse(File(root, "exports/big.txt").exists())
    }

    @Test
    fun `вложенный путь создаёт папки внутри exports`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-savefile").toFile()
        val tool = newTool(root)

        val result = tool.execute("""{"name": "reports/day1/report.txt", "content": "данные"}""")

        assertTrue(result.ok, result.content)
        assertEquals("данные", File(root, "exports/reports/day1/report.txt").readText())
    }

    @Test
    fun `повторная запись перезаписывает файл`() = runBlocking {
        val root = Files.createTempDirectory("aiweb-savefile").toFile()
        val tool = newTool(root)

        tool.execute("""{"name": "notes.md", "content": "старое"}""")
        val result = tool.execute("""{"name": "notes.md", "content": "новое"}""")

        assertTrue(result.ok, result.content)
        assertEquals("новое", File(root, "exports/notes.md").readText())
    }

    @Test
    fun `пустое имя файла даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"name": "", "content": "текст"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("name"))
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("not json")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
