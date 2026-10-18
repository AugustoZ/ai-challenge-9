package com.example.aiweb.memory

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки рабочей памяти: upsert без дубликатов, капы с русскими сообщениями,
 * персистентность (новый экземпляр читает файлы), удаление и полная очистка,
 * выселение чужих файлов через pruneOrphans и устойчивость к json
 * с неизвестными полями.
 */
class WorkingMemoryStoreTest {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    private fun newDir(): File = Files.createTempDirectory("aiweb-working").toFile()

    private fun newStore(dir: File = newDir()) =
        WorkingMemoryStore(dir.absolutePath, now = { clock.incrementAndGet() })

    private fun entry(id: String, title: String = "Тема $id", content: String = "Текст $id") =
        WorkingEntry(id = id, title = title, content = content, createdAt = clock.incrementAndGet(), updatedAt = 0L)

    @Test
    fun `upsert добавляет запись, затем обновляет её по id без дубликата`() {
        val store = newStore()
        val first = store.upsert("s1", entry("e1", "Первая", "текст"))
        assertEquals(1, first.size)

        val second = store.upsert("s1", entry("e1", "Обновлённая", "новый текст"))

        assertEquals(1, second.size)
        assertEquals("Обновлённая", second.single().title)
        assertEquals("новый текст", second.single().content)
        assertTrue(second.single().updatedAt > first.single().updatedAt, "updatedAt должен измениться")
        assertEquals(first.single().createdAt, second.single().createdAt, "createdAt при замене сохраняется")
    }

    @Test
    fun `двадцать пятая запись не влезает — лимит 24 записи`() {
        val store = newStore()
        repeat(MemoryLimits.WORKING_MAX_ENTRIES) { i -> store.upsert("s1", entry("e$i")) }

        val error = assertFailsWith<IllegalArgumentException> { store.upsert("s1", entry("e24")) }
        assertEquals("лимит 24 записи", error.message)

        // Замена существующей записи под капом разрешена — числа не растут
        assertEquals(MemoryLimits.WORKING_MAX_ENTRIES, store.upsert("s1", entry("e0", "Обновлено", "текст")).size)
    }

    @Test
    fun `заголовок длиннее 120 символов отклоняется`() {
        val store = newStore()

        val error = assertFailsWith<IllegalArgumentException> {
            store.upsert("s1", entry("e1", title = "т".repeat(MemoryLimits.WORKING_MAX_TITLE + 1)))
        }
        assertEquals("заголовок длиннее 120 символов", error.message)
    }

    @Test
    fun `текст длиннее 4000 символов отклоняется`() {
        val store = newStore()

        val error = assertFailsWith<IllegalArgumentException> {
            store.upsert("s1", entry("e1", content = "т".repeat(MemoryLimits.WORKING_MAX_CONTENT + 1)))
        }
        assertEquals("текст длиннее 4000 символов", error.message)
    }

    @Test
    fun `рабочая память переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        store.upsert("s1", entry("e1", "Задача", "сделать тесты"))
        store.upsert("s1", entry("e2", "Заметка", "порт 8083"))

        val reloaded = newStore(dir)
        val loaded = reloaded.entries("s1")

        assertEquals(2, loaded.size)
        assertEquals("Задача", loaded.first { it.id == "e1" }.title)
        assertEquals("сделать тесты", loaded.first { it.id == "e1" }.content)
        assertTrue(File(dir, "working/s1.json").isFile, "Файл должен лежать в working/ по id сессии")
        assertFalse(
            File(dir, "working").listFiles()!!.any { it.name.endsWith(".tmp") },
            "Временный файл должен быть переименован"
        )
    }

    @Test
    fun `remove удаляет запись, clear стирает файл целиком`() {
        val dir = newDir()
        val store = newStore(dir)
        store.upsert("s1", entry("e1"))
        store.upsert("s1", entry("e2"))

        val afterRemove = store.remove("s1", "e1")

        assertEquals(listOf("e2"), afterRemove.map { it.id })
        assertEquals(listOf("e2"), newStore(dir).entries("s1").map { it.id })
        // Неизвестный id — без ошибки, список без изменений
        assertEquals(listOf("e2"), store.remove("s1", "нет-такого").map { it.id })

        store.clear("s1")

        assertFalse(File(dir, "working/s1.json").exists())
        assertTrue(store.entries("s1").isEmpty())
        // Повторная очистка отсутствующего файла — идемпотентна
        store.clear("s1")
    }

    @Test
    fun `pruneOrphans удаляет чужие файлы и оставляет живые сессии`() {
        val dir = newDir()
        val store = newStore(dir)
        store.upsert("s1", entry("e1"))
        val workingDir = File(dir, "working")
        File(workingDir, "dead-session.json").writeText("""{"entries":[]}""")

        store.pruneOrphans(setOf("s1"))

        assertFalse(File(workingDir, "dead-session.json").exists())
        assertTrue(File(workingDir, "s1.json").isFile)
        assertEquals(1, newStore(dir).entries("s1").size)
    }

    @Test
    fun `entries на отсутствующем файле — пустой список`() {
        assertTrue(newStore().entries("нет-такого").isEmpty())
    }

    @Test
    fun `json с неизвестными полями читается без падения`() {
        val dir = newDir()
        val workingDir = File(dir, "working").apply { mkdirs() }
        File(workingDir, "s1.json").writeText(
            """{"entries":[{"id":"e1","title":"Тема","content":"Текст","createdAt":1,"updatedAt":2,"extra":42}],"extraTop":true}"""
        )

        val loaded = newStore(dir).entries("s1")

        assertEquals(1, loaded.size)
        assertEquals("Тема", loaded.single().title)
        assertEquals("Текст", loaded.single().content)
    }
}
