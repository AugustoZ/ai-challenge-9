package com.example.aiweb.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки reminder: жизненный цикл add → list → done, пометка просроченных
 * при list, ошибки валидации аргументов и валидность JSON-хранилища.
 */
class ReminderToolTest {

    private fun newTool(dir: File = Files.createTempDirectory("aiweb-reminder").toFile()) =
        ReminderTool(dir.absolutePath)

    @Test
    fun `жизненный цикл add list done`() = runBlocking {
        val dir = Files.createTempDirectory("aiweb-reminder").toFile()
        val tool = newTool(dir)

        val added = tool.execute("""{"action": "add", "text": "проверить прошивку", "due": "2099-01-01T12:00:00Z"}""")
        assertTrue(added.ok, added.content)
        assertTrue(added.content.contains("id "))

        val listed = tool.execute("""{"action": "list"}""")
        assertTrue(listed.ok)
        assertTrue(listed.content.contains("проверить прошивку"), listed.content)
        assertFalse(listed.content.contains("ПРОСРОЧЕНО"))

        // Хранилище — валидный JSON, id берём оттуда
        val storage = File(dir, ReminderTool.FILE_NAME)
        assertTrue(storage.isFile)
        val raw = storage.readText()
        val id = Json.parseToJsonElement(raw).jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content

        val done = tool.execute("""{"action": "done", "id": "$id"}""")
        assertTrue(done.ok, done.content)

        val after = tool.execute("""{"action": "list"}""")
        assertTrue(after.ok)
        assertFalse(after.content.contains("проверить прошивку"), after.content)
    }

    @Test
    fun `list помечает просроченные напоминания`() = runBlocking {
        val tool = newTool()

        val added = tool.execute("""{"action": "add", "text": "старое дело", "due": "2020-01-01T00:00:00Z"}""")
        assertTrue(added.ok, added.content)

        val listed = tool.execute("""{"action": "list"}""")
        assertTrue(listed.ok)
        assertTrue(listed.content.contains("старое дело"))
        assertTrue(listed.content.contains("ПРОСРОЧЕНО"), listed.content)
    }

    @Test
    fun `add без текста даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "add", "due": "2099-01-01T00:00:00Z"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("text"))
    }

    @Test
    fun `add с некорректной датой due даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "add", "text": "дело", "due": "завтра утром"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("due"))
    }

    @Test
    fun `done с неизвестным id даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "done", "id": "нет-такого"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("не найдено"))
    }

    @Test
    fun `неизвестное действие даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "snooze"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("Неизвестное действие"))
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("not json")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
