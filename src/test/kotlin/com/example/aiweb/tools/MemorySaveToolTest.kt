package com.example.aiweb.tools

import com.example.aiweb.memory.LongTermMemoryStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки memory_save: успешное сохранение по видам, ошибки валидации
 * (русские сообщения хранилища) и источник записи "agent".
 */
class MemorySaveToolTest {

    private fun newTool(dir: File = Files.createTempDirectory("aiweb-save").toFile()) =
        MemorySaveTool(LongTermMemoryStore(dir.absolutePath))

    private fun store(dir: File) = LongTermMemoryStore(dir.absolutePath)

    @Test
    fun `сохранение решения возвращает ok с видом и id в сообщении`() = runBlocking {
        val dir = Files.createTempDirectory("aiweb-save").toFile()
        val tool = newTool(dir)

        val result = tool.execute(
            """{"kind": "decision", "title": "Порт бэкенда", "content": "фиксируем порт 8083", "tags": ["deploy", "порты"]}"""
        )

        assertTrue(result.ok)
        assertTrue(result.content.startsWith("Сохранено в долговременную память: [decision] Порт бэкенда (id "))
        val saved = store(dir).list(kind = "decision")
        assertEquals(1, saved.size)
        assertEquals("agent", saved.single().source)
        assertEquals(listOf("deploy", "порты"), saved.single().tags)
    }

    @Test
    fun `неизвестный вид памяти даёт ошибку с сообщением хранилища`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"kind": "secret", "title": "Т", "content": "К"}""")

        assertFalse(result.ok)
        assertEquals(
            "Неизвестный вид памяти 'secret' (допустимы: profile, decision, knowledge)",
            result.content
        )
    }

    @Test
    fun `сохранение без заголовка даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"kind": "knowledge", "content": "текст без заголовка"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("title"))
    }

    @Test
    fun `запись сохраняется в хранилище и видна через list`() = runBlocking {
        val dir = Files.createTempDirectory("aiweb-save").toFile()
        val tool = newTool(dir)

        tool.execute("""{"kind": "profile", "title": "Имя пользователя", "content": "Аугусто"}""")

        val all = store(dir).list()
        assertEquals(1, all.size)
        assertEquals("profile", all.single().kind)
        assertEquals("Аугусто", all.single().content)
        assertFalse(all.single().tags.isNotEmpty())
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("not json")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }
}
