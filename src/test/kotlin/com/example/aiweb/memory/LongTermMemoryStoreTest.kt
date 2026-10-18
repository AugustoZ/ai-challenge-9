package com.example.aiweb.memory

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки долговременной памяти: контракт [MemoryRepository] наследуется из
 * [MemoryRepositoryContractTest] (CRUD, фильтры, капы, персистентность);
 * здесь — специализированные проверки JSON-реализации: валидация источника,
 * русские тексты ошибок, валидация результата update и устойчивость к битому
 * файлу с неизвестными полями.
 */
class LongTermMemoryStoreTest : MemoryRepositoryContractTest() {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    /** Каталог последнего созданного хранилища — для переоткрытия. */
    private lateinit var lastDir: File

    override fun newStore(): LongTermMemoryStore {
        lastDir = Files.createTempDirectory("aiweb-longterm").toFile()
        return create(lastDir)
    }

    override fun reopenStore(): LongTermMemoryStore = create(lastDir)

    private fun newDir(): File = Files.createTempDirectory("aiweb-longterm").toFile()

    private fun create(dir: File): LongTermMemoryStore =
        LongTermMemoryStore(dir.absolutePath, now = { clock.incrementAndGet() })

    @Test
    fun `неизвестный источник отклоняется`() {
        val store = create(newDir())

        val error = assertFailsWith<IllegalArgumentException> { store.add(entry("m1", source = "system")) }
        assertTrue(error.message!!.contains("user"), "Сообщение: ${error.message}")
    }

    @Test
    fun `теги сверх лимитов отклоняются`() {
        val store = create(newDir())

        val tooMany = assertFailsWith<IllegalArgumentException> {
            store.add(entry("m1", tags = List(MemoryLimits.LONG_MAX_TAGS + 1) { "т$it" }))
        }
        assertTrue(tooMany.message!!.contains("тег"), "Сообщение: ${tooMany.message}")

        val tooLong = assertFailsWith<IllegalArgumentException> {
            store.add(entry("m2", tags = listOf("т".repeat(MemoryLimits.LONG_MAX_TAG_LEN + 1))))
        }
        assertTrue(tooLong.message!!.contains("тег"), "Сообщение: ${tooLong.message}")
    }

    @Test
    fun `сообщение о переполнении капа записей — точное`() {
        val store = create(newDir())
        repeat(MemoryLimits.LONG_MAX_ENTRIES) { i -> store.add(entry("m$i")) }

        val error = assertFailsWith<IllegalArgumentException> { store.add(entry("m200")) }
        assertEquals("лимит 200 записей", error.message)
    }

    @Test
    fun `update валидирует результат — портящий transform отклоняется`() {
        val store = create(newDir())
        store.add(entry("m1", content = "ок"))
        val before = assertNotNull(store.get("m1"))

        val error = assertFailsWith<IllegalArgumentException> {
            store.update("m1") { it.copy(content = "т".repeat(MemoryLimits.LONG_MAX_CONTENT + 1)) }
        }
        assertEquals("текст длиннее 2000 символов", error.message)
        assertEquals(before, assertNotNull(store.get("m1")), "Запись не должна измениться")
    }

    @Test
    fun `долговременная память переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = create(dir)
        store.add(entry("m1", kind = "profile", title = "Имя", content = "Август", source = "agent"))
        store.add(entry("m2", kind = "knowledge", title = "Порт", content = "8083", tags = listOf("инфра")))

        val reloaded = create(dir)

        assertEquals(2, reloaded.list().size)
        assertEquals("Август", reloaded.get("m1")?.content)
        assertEquals("agent", reloaded.get("m1")?.source)
        assertEquals(listOf("инфра"), reloaded.get("m2")?.tags)
        assertTrue(File(dir, "memory/longterm.json").isFile, "Файл должен лежать в memory/longterm.json")
        assertFalse(
            File(dir, "memory").listFiles()!!.any { it.name.endsWith(".tmp") },
            "Временный файл должен быть переименован"
        )
    }

    @Test
    fun `json с неизвестными полями читается без падения`() {
        val dir = newDir()
        val memoryDir = File(dir, "memory").apply { mkdirs() }
        File(memoryDir, "longterm.json").writeText(
            """{"entries":[{"id":"m1","kind":"profile","title":"Имя","content":"Август","createdAt":1,"updatedAt":2,"extra":1}],"extraTop":true}"""
        )

        val loaded = assertNotNull(create(dir).get("m1"))

        assertEquals("Имя", loaded.title)
        assertEquals("Август", loaded.content)
    }
}
