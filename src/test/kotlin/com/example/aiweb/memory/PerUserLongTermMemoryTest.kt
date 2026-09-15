package com.example.aiweb.memory

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Per-user обёртка долговременной памяти (задача 8): контракт
 * [MemoryRepository] наследуется из [MemoryRepositoryContractTest] — обёртка
 * не должна ломать CRUD/капы/персистентность; здесь — изоляция пользователей,
 * раскладка файлов `memory/{userId}/longterm.json` и защита id от traversal.
 */
class PerUserLongTermMemoryTest : MemoryRepositoryContractTest() {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    /** Каталог последнего созданного хранилища — для переоткрытия. */
    private lateinit var lastDir: File

    override fun newStore(): MemoryRepository {
        lastDir = Files.createTempDirectory("aiweb-peruser-ltm").toFile()
        return create(lastDir).forUser("user-a")
    }

    override fun reopenStore(): MemoryRepository = create(lastDir).forUser("user-a")

    private fun newDir(): File = Files.createTempDirectory("aiweb-peruser-ltm").toFile()

    private fun create(dir: File): PerUserLongTermMemory =
        PerUserLongTermMemory(dir.absolutePath, now = { clock.incrementAndGet() })

    // --- Изоляция двух пользователей ---

    @Test
    fun `два пользователя не видят записей друг друга`() {
        val dir = newDir()
        val wrapper = create(dir)
        val userA = wrapper.forUser("user-a")
        val userB = wrapper.forUser("user-b")

        userA.add(entry("m1", kind = "profile", title = "Имя A", content = "Август"))

        assertTrue(userB.list().isEmpty(), "Список B пуст — записи A не видны")
        assertNull(userB.get("m1"), "Чужая запись по id не видна")
        assertNull(userB.update("m1") { it.copy(title = "взлом") }, "Чужая запись не обновляется")
        assertFalse(userB.delete("m1"), "Чужая запись не удаляется")
        assertTrue(
            userB.counts().values.all { it == 0 },
            "Счётчики B нулевые: ${userB.counts()}"
        )
        assertEquals(1, userA.list().size, "Свои записи на месте")
    }

    @Test
    fun `файлы разложены по memory userId longterm json`() {
        val dir = newDir()
        val wrapper = create(dir)
        wrapper.forUser("user-a").add(entry("m1", kind = "knowledge"))
        wrapper.forUser("user-b").add(entry("m2", kind = "decision"))

        assertTrue(
            File(dir, "memory/user-a/longterm.json").isFile,
            "Файл пользователя A: memory/user-a/longterm.json"
        )
        assertTrue(
            File(dir, "memory/user-b/longterm.json").isFile,
            "Файл пользователя B: memory/user-b/longterm.json"
        )
        assertFalse(File(dir, "memory/longterm.json").isFile, "Общего файла больше не создаётся")

        // Переоткрытие по тому же userId читает свой файл
        val reloaded = create(dir)
        assertEquals("knowledge", reloaded.forUser("user-a").get("m1")?.kind)
        assertEquals("decision", reloaded.forUser("user-b").get("m2")?.kind)
    }

    @Test
    fun `стор пользователя ленивый и кешируется`() {
        val dir = newDir()
        val wrapper = create(dir)

        // До первой мутации файл не создаётся
        wrapper.forUser("user-a").list()
        assertFalse(File(dir, "memory/user-a/longterm.json").isFile, "Чтение не создаёт файл")

        // Тот же userId — тот же экземпляр стора
        assertSame(wrapper.forUser("user-a"), wrapper.forUser("user-a"))

        // Порядок каталогов не влияет: независимые экземпляры
        assertNotEquals(wrapper.forUser("user-a"), wrapper.forUser("user-b"))
    }

    @Test
    fun `недопустимый userId отклоняется`() {
        val wrapper = create(newDir())

        listOf("../evil", "..\\evil", "a/b", "a\\b", "", "..").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("id=«$bad»") { wrapper.forUser(bad) }
        }
    }
}
