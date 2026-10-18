package com.example.aiweb.memory

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки сборщика блока памяти: пустые слои дают null, профиль включается
 * без запроса, релевантность по токенам отбирает только совпавшие записи,
 * секции не вылезают за бюджеты, рабочая память идёт свежими записями вперёд.
 */
class MemoryRetrieverTest {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    /** Свой каталог на тест: junit создаёт новый экземпляр на каждый метод. */
    private val dir = Files.createTempDirectory("aiweb-retriever").toFile()

    private val working = WorkingMemoryStore(dir.absolutePath, now = { clock.incrementAndGet() })
    private val long = LongTermMemoryStore(dir.absolutePath, now = { clock.incrementAndGet() })
    private val retriever = MemoryRetriever(working, long, KeywordMemorySearch(long))

    private fun longEntry(
        id: String,
        kind: String,
        title: String,
        content: String,
        tags: List<String> = emptyList()
    ) = MemoryEntry(
        id = id,
        kind = kind,
        title = title,
        content = content,
        tags = tags,
        createdAt = clock.incrementAndGet(),
        updatedAt = 0L
    )

    private fun workingEntry(id: String, title: String, content: String) =
        WorkingEntry(id = id, title = title, content = content, createdAt = clock.incrementAndGet(), updatedAt = 0L)

    @Test
    fun `оба хранилища пусты — блок не строится`() {
        assertNull(retriever.memoryBlock("s1", "вопрос о деплое"))
        assertNull(retriever.memoryBlock("s1", null))
    }

    @Test
    fun `профиль включается в блок даже без запроса`() {
        long.add(longEntry("p1", "profile", "Имя пользователя", "Август"))

        val block = assertNotNull(retriever.memoryBlock("s1", null))

        assertTrue(block.contains("Долговременная память:"))
        assertTrue(block.contains("- [profile] Имя пользователя: Август"))
        assertFalse(block.contains("Рабочая память"), "Рабочая секция пуста — заголовка быть не должно")

        // Пустой запрос — без скоринга, только профили
        val blank = assertNotNull(retriever.memoryBlock("s1", "   "))
        assertTrue(blank.contains("- [profile] Имя пользователя: Август"))
    }

    @Test
    fun `релевантность — в блок попадает только совпавшее решение`() {
        long.add(longEntry("p1", "profile", "Имя", "Август"))
        long.add(longEntry("d1", "decision", "Деплой приложения", "Для деплоя используем порт 8083"))
        long.add(longEntry("k1", "knowledge", "Кулинария", "Щи со сметаной"))

        val block = assertNotNull(retriever.memoryBlock("s1", "как настроить деплой"))

        assertTrue(block.contains("- [decision] Деплой приложения: Для деплоя используем порт 8083"))
        assertFalse(block.contains("Кулинария"), "Нерелевантное знание не включается")
        assertTrue(block.contains("- [profile] Имя: Август"), "Профиль включается всегда")
    }

    @Test
    fun `строка записи — с тегами и без`() {
        long.add(longEntry("d1", "decision", "Деплой", "порт 8083", tags = listOf("инфра", "релиз")))
        long.add(longEntry("k1", "knowledge", "База", "postgres 16"))

        val block = assertNotNull(retriever.memoryBlock("s1", "деплой база"))

        assertTrue(block.contains("- [decision] Деплой (теги: инфра, релиз): порт 8083"))
        assertTrue(block.contains("- [knowledge] База: postgres 16"), "Без тегов скобок быть не должно")
    }

    @Test
    fun `рабочая память — все записи, свежие первыми`() {
        working.upsert("s1", workingEntry("w1", "Старая задача", "первый шаг"))
        working.upsert("s1", workingEntry("w2", "Новая задача", "второй шаг"))

        val block = assertNotNull(retriever.memoryBlock("s1", null))

        assertTrue(block.contains("Рабочая память (текущая задача):"))
        assertFalse(block.contains("Долговременная память"), "Долговременная секция пуста — заголовка быть не должно")
        val workingPart = block.substringAfter("Рабочая память (текущая задача):")
        assertTrue(
            workingPart.indexOf("- Новая задача") < workingPart.indexOf("- Старая задача"),
            "Свежие записи должны идти первыми"
        )
        assertTrue(block.contains("- Новая задача: второй шаг"))
    }

    @Test
    fun `обе секции присутствуют одновременно и разделены пустой строкой`() {
        long.add(longEntry("p1", "profile", "Имя", "Август"))
        working.upsert("s1", workingEntry("w1", "Задача", "шаг"))

        val block = assertNotNull(retriever.memoryBlock("s1", null))

        val longIndex = block.indexOf("Долговременная память:")
        val workingIndex = block.indexOf("Рабочая память (текущая задача):")
        assertTrue(longIndex in 0 until workingIndex, "Долговременная секция первой")
        assertTrue(block.contains("\n\n"), "Секции разделены пустой строкой")
    }

    @Test
    fun `бюджет — долговременная секция не длиннее 1800 символов`() {
        repeat(10) { i -> long.add(longEntry("k$i", "knowledge", "Знание $i", "деплой " + "х".repeat(300))) }

        val block = assertNotNull(retriever.memoryBlock("s1", "деплой"))

        assertTrue(block.length <= 1800, "Длина блока: ${block.length}")
        assertTrue(block.contains("Долговременная память:"))
        val kept = Regex("- \\[knowledge\\]").findAll(block).count()
        assertTrue(kept in 1..9, "Часть строк должна быть отброшена с конца, осталось: $kept")
    }

    @Test
    fun `бюджет — рабочая секция не длиннее 2400 символов`() {
        repeat(6) { i -> working.upsert("s1", workingEntry("w$i", "Задача $i", "шаг " + "х".repeat(500))) }

        val block = assertNotNull(retriever.memoryBlock("s1", null))

        assertTrue(block.length <= 2400, "Длина блока: ${block.length}")
        assertTrue(block.contains("Рабочая память (текущая задача):"))
        val kept = Regex("^- Задача", RegexOption.MULTILINE).findAll(block).count()
        assertTrue(kept in 1..5, "Часть строк должна быть отброшена с конца, осталось: $kept")
    }

    @Test
    fun `профилей в блоке не больше 10`() {
        repeat(12) { i -> long.add(longEntry("p$i", "profile", "Факт $i", "значение $i")) }

        val block = assertNotNull(retriever.memoryBlock("s1", null))

        assertEquals(10, Regex("- \\[profile\\]").findAll(block).count())
    }

    @Test
    fun `релевантных записей в блоке не больше 6`() {
        repeat(8) { i -> long.add(longEntry("k$i", "knowledge", "Знание $i", "деплой шаг $i")) }

        val block = assertNotNull(retriever.memoryBlock("s1", "деплой"))

        assertEquals(6, Regex("- \\[knowledge\\]").findAll(block).count())
    }
}
