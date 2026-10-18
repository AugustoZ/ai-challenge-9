package com.example.aiweb.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Контракт порта [MemoryRepository]: обязательное поведение любой реализации
 * (JSON-файл сейчас, SQLite на стадии 1). Подклассы поставляют свежее
 * хранилище в чистом каталоге и его «переоткрытие» для проверки
 * персистентности. Капы/валидация здесь — договорные значения контракта
 * долговременной памяти (см. [MemoryLimits]).
 */
abstract class MemoryRepositoryContractTest {

    /** Свежее хранилище в чистом каталоге — создаёт подкласс. */
    protected abstract fun newStore(): MemoryRepository

    /** Хранилище, перечитывающее данные последнего [newStore] (персистентность). */
    protected abstract fun reopenStore(): MemoryRepository

    /** Фабрика записи с дефолтами под контрактные проверки. */
    protected fun entry(
        id: String,
        kind: String = "knowledge",
        title: String = "Тема $id",
        content: String = "Текст $id",
        tags: List<String> = emptyList(),
        source: String = "user"
    ) = MemoryEntry(
        id = id,
        kind = kind,
        title = title,
        content = content,
        tags = tags,
        source = source,
        createdAt = 1L,
        updatedAt = 0L
    )

    @Test
    fun `add, get, update, delete и update неизвестного id`() {
        val store = newStore()
        val added = store.add(entry("m1", kind = "decision", title = "Деплой", content = "порт 8083"))
        assertTrue(added.updatedAt > 0, "updatedAt проставляется хранилищем")
        assertEquals("Деплой", assertNotNull(store.get("m1")).title)

        val updated = assertNotNull(store.update("m1") { it.copy(content = "порт 8088") })
        assertEquals("порт 8088", updated.content)
        assertEquals(1, store.list().size)

        assertNull(store.update("нет-такого") { it.copy(content = "х") }, "Неизвестный id — null")

        assertTrue(store.delete("m1"))
        assertFalse(store.delete("m1"), "Повторное удаление — false")
        assertNull(store.get("m1"))
    }

    @Test
    fun `counts всегда содержит все три вида памяти`() {
        val store = newStore()
        assertEquals(mapOf("profile" to 0, "decision" to 0, "knowledge" to 0), store.counts())

        store.add(entry("p1", kind = "profile"))
        store.add(entry("d1", kind = "decision"))

        assertEquals(mapOf("profile" to 1, "decision" to 1, "knowledge" to 0), store.counts())
    }

    @Test
    fun `list фильтрует по виду памяти и подстроке без учёта регистра`() {
        val store = newStore()
        store.add(entry("p1", kind = "profile"))
        store.add(entry("k1", title = "Заметка", content = "Деплой идёт через порт 8083"))
        store.add(entry("k2", title = "Кулинария", content = "Рецепт борща", tags = listOf("деплой")))

        assertEquals(listOf("p1"), store.list(kind = "profile").map { it.id })
        assertEquals(listOf("k2", "k1"), store.list(q = "ДЕПЛОЙ").map { it.id }, "Регистр не важен, свежие первыми")
        assertEquals(listOf("k1"), store.list(kind = "knowledge", q = "8083").map { it.id })
        assertTrue(store.list(kind = "decision").isEmpty(), "Фильтр по виду памяти")
        assertTrue(store.list(q = "python").isEmpty(), "Несовпадающая подстрока")
        assertEquals(3, store.list().size, "Без фильтров — все записи")
    }

    @Test
    fun `лимиты записи — заголовок, текст, теги`() {
        val store = newStore()

        assertFailsWith<IllegalArgumentException> {
            store.add(entry("m1", title = "т".repeat(MemoryLimits.LONG_MAX_TITLE + 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            store.add(entry("m2", content = "т".repeat(MemoryLimits.LONG_MAX_CONTENT + 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            store.add(entry("m3", tags = List(MemoryLimits.LONG_MAX_TAGS + 1) { "т$it" }))
        }
        assertFailsWith<IllegalArgumentException> {
            store.add(entry("m4", tags = listOf("т".repeat(MemoryLimits.LONG_MAX_TAG_LEN + 1))))
        }

        // Граничные значения валидны
        store.add(entry("ok1", title = "т".repeat(MemoryLimits.LONG_MAX_TITLE), content = "т".repeat(MemoryLimits.LONG_MAX_CONTENT)))
        assertEquals(1, store.list().size, "Портящие записи не сохраняются, граничные — сохраняются")
    }

    @Test
    fun `двести первая запись не влезает — лимит 200 записей`() {
        val store = newStore()
        repeat(MemoryLimits.LONG_MAX_ENTRIES) { i -> store.add(entry("m$i")) }

        assertFailsWith<IllegalArgumentException> { store.add(entry("m200")) }
        assertEquals(MemoryLimits.LONG_MAX_ENTRIES, store.list().size)
    }

    @Test
    fun `неизвестный вид памяти отклоняется`() {
        val store = newStore()

        val error = assertFailsWith<IllegalArgumentException> { store.add(entry("m1", kind = "заметки")) }
        assertTrue(error.message!!.contains("profile"), "Сообщение перечисляет допустимые виды: ${error.message}")
        assertTrue(store.list().isEmpty(), "Отклонённая запись не сохраняется")
    }

    @Test
    fun `персистентность — переоткрытое хранилище читает записи`() {
        val store = newStore()
        store.add(entry("m1", kind = "profile", title = "Имя", content = "Август", source = "agent"))
        store.add(entry("m2", title = "Порт", content = "8083", tags = listOf("инфра")))

        val reloaded = reopenStore()

        assertEquals(2, reloaded.list().size)
        assertEquals("Август", assertNotNull(reloaded.get("m1")).content)
        assertEquals("agent", assertNotNull(reloaded.get("m1")).source)
        assertEquals(listOf("инфра"), assertNotNull(reloaded.get("m2")).tags)
    }
}
