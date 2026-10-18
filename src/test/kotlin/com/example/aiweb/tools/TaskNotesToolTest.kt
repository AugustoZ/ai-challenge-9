package com.example.aiweb.tools

import com.example.aiweb.memory.WorkingMemoryStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки task_notes: добавление с генерацией id, обновление по id,
 * удаление, список, ошибки валидации и недоступность без сессии.
 */
class TaskNotesToolTest {

    private val sessionId = "s-test"

    private fun newTool(dir: File = Files.createTempDirectory("aiweb-notes").toFile(), session: String? = sessionId) =
        TaskNotesTool(WorkingMemoryStore(dir.absolutePath), session)

    private suspend fun add(tool: TaskNotesTool, title: String, content: String): ToolResult =
        tool.execute("""{"action": "add", "title": "$title", "content": "$content"}""")

    /** Вытаскивает id из сообщения «Заметка сохранена: … (id …)». */
    private fun idFrom(message: String): String =
        Regex("\\(id (.+)\\)").find(message)?.groupValues?.get(1) ?: error("нет id в сообщении: $message")

    @Test
    fun `add сохраняет заметку и возвращает id в сообщении`() = runBlocking {
        val tool = newTool()
        val result = add(tool, "Шаг 1", "нашли порт 8083")

        assertTrue(result.ok)
        val id = idFrom(result.content)
        assertTrue(result.content.contains("Шаг 1"))
        val stored = tool.execute("""{"action": "list"}""")
        assertTrue(stored.ok)
        assertTrue(stored.content.contains(id))
        assertTrue(stored.content.contains("- $id | Шаг 1: нашли порт 8083"))
    }

    @Test
    fun `update меняет заголовок и текст, сохраняя id`() = runBlocking {
        val tool = newTool()
        val id = idFrom(add(tool, "Старая", "старый текст").content)

        val updated = tool.execute("""{"action": "update", "id": "$id", "title": "Новая", "content": "новый текст"}""")

        assertTrue(updated.ok)
        assertEquals("Заметка обновлена: Новая", updated.content)
        val list = tool.execute("""{"action": "list"}""")
        assertTrue(list.content.contains("- $id | Новая: новый текст"))
        assertFalse(list.content.contains("Старая"))
    }

    @Test
    fun `update по неизвестному id даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "update", "id": "nope", "title": "X"}""")

        assertFalse(result.ok)
        assertEquals("Заметка с id nope не найдена", result.content)
    }

    @Test
    fun `remove удаляет заметку, повторный remove даёт ошибку`() = runBlocking {
        val tool = newTool()
        val id = idFrom(add(tool, "Временная", "текст").content)

        val removed = tool.execute("""{"action": "remove", "id": "$id"}""")
        assertTrue(removed.ok)
        assertEquals("Заметка удалена", removed.content)

        val again = tool.execute("""{"action": "remove", "id": "$id"}""")
        assertFalse(again.ok)
        assertEquals("Заметка с id $id не найдена", again.content)
    }

    @Test
    fun `list на пустой памяти сообщает о пустоте`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "list"}""")

        assertTrue(result.ok)
        assertEquals("Рабочая память пуста", result.content)
    }

    @Test
    fun `add без заголовка даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "add", "content": "текст без заголовка"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("title"))
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("[1,2]")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }

    @Test
    fun `неизвестное действие даёт ошибку`() = runBlocking {
        val tool = newTool()
        val result = tool.execute("""{"action": "wipe"}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("wipe"))
    }

    @Test
    fun `инструмент без сессии недоступен`() = runBlocking {
        val tool = newTool(session = null)
        val result = tool.execute("""{"action": "list"}""")

        assertFalse(result.ok)
        assertEquals("Рабочая память недоступна: нет сессии", result.content)
    }
}
