package com.example.aiweb.tools

import com.example.aiweb.memory.LongTermMemoryStore
import com.example.aiweb.memory.MemoryEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки memory_recall: поиск по подстроке, резервный поиск по отдельным
 * словам запроса, фильтр по виду памяти и пустой результат.
 */
class MemoryRecallToolTest {

    private fun newStore(dir: File = Files.createTempDirectory("aiweb-recall").toFile()) =
        LongTermMemoryStore(dir.absolutePath)

    private fun newTool(store: LongTermMemoryStore) = MemoryRecallTool(store)

    private fun entry(kind: String, title: String, content: String, tags: List<String> = emptyList()) =
        MemoryEntry(
            id = "id-$kind-$title",
            kind = kind,
            title = title,
            content = content,
            tags = tags,
            source = "user",
            createdAt = 1L,
            updatedAt = 1L
        )

    @Test
    fun `находит запись по подстроке запроса`() = runBlocking {
        val store = newStore()
        store.add(entry("decision", "Деплой", "рулим деплоем через docker compose"))
        val tool = newTool(store)

        val result = tool.execute("""{"query": "деплой"}""")

        assertTrue(result.ok)
        assertTrue(result.content.contains("[decision] Деплой"))
        assertTrue(result.content.contains("docker compose"))
    }

    @Test
    fun `резервный поиск по второму слову запроса находит запись`() = runBlocking {
        val store = newStore()
        store.add(entry("knowledge", "Сборка", "фронтенд собирается командой npm run build"))
        val tool = newTool(store)

        // Целиком запрос не совпадает ни с одной подстрокой — сработают слова
        val result = tool.execute("""{"query": "странное фронтенд"}""")

        assertTrue(result.ok)
        assertTrue(result.content.contains("[knowledge] Сборка"))
        assertTrue(result.content.contains("npm run build"))
    }

    @Test
    fun `фильтр по виду памяти оставляет только нужный вид`() = runBlocking {
        val store = newStore()
        store.add(entry("profile", "Порт", "пользователь любит порт 8083"))
        store.add(entry("decision", "Порт", "выбрали порт 8083 для бэкенда"))
        val tool = newTool(store)

        val result = tool.execute("""{"query": "порт", "kind": "profile"}""")

        assertTrue(result.ok)
        assertTrue(result.content.contains("[profile] Порт"))
        assertFalse(result.content.contains("[decision]"))
    }

    @Test
    fun `теги выводятся в строке результата`() = runBlocking {
        val store = newStore()
        store.add(entry("knowledge", "Ключи", "где лежат ключи", tags = listOf("секреты", "ssh")))
        val tool = newTool(store)

        val result = tool.execute("""{"query": "ключи"}""")

        assertTrue(result.ok)
        assertEquals("- [knowledge] Ключи (теги: секреты, ssh): где лежат ключи", result.content)
    }

    @Test
    fun `ничего не найдено — ok с сообщением о пустом результате`() = runBlocking {
        val tool = newTool(newStore())
        val result = tool.execute("""{"query": "несуществующее"}""")

        assertTrue(result.ok)
        assertEquals("В долговременной памяти ничего не найдено", result.content)
    }

    @Test
    fun `аргументы не json объект дают ошибку`() = runBlocking {
        val tool = newTool(newStore())
        val result = tool.execute("[1,2]")

        assertFalse(result.ok)
        assertEquals(ARGS_NOT_OBJECT_ERROR, result.content)
    }

    @Test
    fun `пустой запрос даёт ошибку`() = runBlocking {
        val tool = newTool(newStore())
        val result = tool.execute("""{"query": "   "}""")

        assertFalse(result.ok)
        assertTrue(result.content.contains("query"))
    }
}
