package com.example.aiweb.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки keyword-движка поиска: скоринг по токенам запроса (заголовок 3,
 * теги 2, текст 1), отсечение нерелевантного и профилей, фильтр по виду
 * памяти, лимит выдачи, тай-брейк по updatedAt, пустой запрос — пустой список.
 */
class KeywordMemorySearchTest {

    /** Фейковый репозиторий: отдаёт заготовленные записи как есть (без валидации). */
    private class FakeRepository(private val entries: List<MemoryEntry>) : MemoryRepository {
        override fun list(kind: String?, q: String?): List<MemoryEntry> =
            entries.filter { kind == null || it.kind == kind }

        override fun counts(): Map<String, Int> = emptyMap()
        override fun get(id: String): MemoryEntry? = entries.firstOrNull { it.id == id }
        override fun add(entry: MemoryEntry): MemoryEntry = entry
        override fun update(id: String, transform: (MemoryEntry) -> MemoryEntry): MemoryEntry? = null
        override fun delete(id: String): Boolean = false
    }

    /** Фабрика записи с явным updatedAt — движок читает его напрямую. */
    private fun entry(
        id: String,
        kind: String = "knowledge",
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        updatedAt: Long = 1L
    ) = MemoryEntry(
        id = id,
        kind = kind,
        title = title,
        content = content,
        tags = tags,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    private fun search(entries: List<MemoryEntry>, query: String, kind: String? = null, limit: Int = 6) =
        KeywordMemorySearch(FakeRepository(entries)).search(query, kind = kind, limit = limit)

    @Test
    fun `пустой запрос — пустой результат`() {
        val entries = listOf(entry("k1", title = "Деплой", content = "порт 8083"))

        assertTrue(search(entries, "").isEmpty())
        assertTrue(search(entries, "   ").isEmpty())
    }

    @Test
    fun `запрос без токенов нужной длины — пустой результат`() {
        val entries = listOf(entry("k1", title = "Деплой", content = "порт 8083"))

        assertTrue(search(entries, "ми ок").isEmpty(), "Токены короче 3 символов игнорируются")
    }

    @Test
    fun `релевантное находится, нерелевантное отсекается`() {
        val entries = listOf(
            entry("d1", kind = "decision", title = "Деплой приложения", content = "Для деплоя используем порт 8083"),
            entry("k1", title = "Кулинария", content = "Щи со сметаной")
        )

        val result = search(entries, "деплой порт")

        assertEquals(listOf("d1"), result.map { it.entry.id }, "Нерелевантное знание не возвращается")
        assertTrue(result.single().score > 0)
    }

    @Test
    fun `скоринг учитывает заголовок, теги и текст с разными весами`() {
        val entries = listOf(
            entry("t1", title = "Деплой", content = "деплой по расписанию", updatedAt = 3L),
            entry("t2", title = "Прочее", content = "деплой", tags = listOf("деплой"), updatedAt = 2L),
            entry("t3", title = "Ещё", content = "упомянут деплой вскользь", updatedAt = 1L)
        )

        val result = search(entries, "деплой")

        // Заголовок + текст (3 + 1) > теги + текст (2 + 1) > только текст (1)
        assertEquals(listOf("t1", "t2", "t3"), result.map { it.entry.id })
        assertEquals(4.0, result[0].score)
        assertEquals(3.0, result[1].score)
        assertEquals(1.0, result[2].score)
    }

    @Test
    fun `профили никогда не возвращаются`() {
        val entries = listOf(
            entry("p1", kind = "profile", title = "Деплой любимая тема", content = "всё о деплое"),
            entry("k1", title = "Заметка", content = "деплой по пятницам")
        )

        val result = search(entries, "деплой")

        assertEquals(listOf("k1"), result.map { it.entry.id }, "Профили включаются ретривером отдельно, без скоринга")
    }

    @Test
    fun `фильтр по виду памяти передаётся в репозиторий`() {
        val entries = listOf(
            entry("d1", kind = "decision", title = "Деплой", content = "порт"),
            entry("k1", kind = "knowledge", title = "Деплой", content = "порт")
        )

        assertEquals(listOf("d1"), search(entries, "деплой", kind = "decision").map { it.entry.id })
        assertEquals(listOf("k1"), search(entries, "деплой", kind = "knowledge").map { it.entry.id })
        assertEquals(2, search(entries, "деплой").size)
    }

    @Test
    fun `limit ограничивает выдачу`() {
        val entries = List(5) { i -> entry("k$i", title = "Деплой $i", content = "шаг $i") }

        assertEquals(2, search(entries, "деплой", limit = 2).size)
        assertEquals(5, search(entries, "деплой", limit = 10).size)
    }

    @Test
    fun `при равном скоринге свежие по updatedAt первыми`() {
        val entries = listOf(
            entry("old", title = "Деплой старый", content = "текст", updatedAt = 100L),
            entry("new", title = "Деплой новый", content = "текст", updatedAt = 200L)
        )

        assertEquals(listOf("new", "old"), search(entries, "деплой").map { it.entry.id })
    }

    @Test
    fun `регистр запроса и записи не важен`() {
        val entries = listOf(entry("k1", title = "ДЕПЛОЙ", content = "ПОРТ"))

        assertEquals(listOf("k1"), search(entries, "Деплой").map { it.entry.id })
    }
}
