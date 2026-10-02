package com.example.aiweb.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [RagPrompts.buildRagBlock]: заголовок RAG_MARKER, инструкция, нумерованные
 * блоки, суммарный кап maxChars с пер-чанк обрезкой «…».
 * Разметка БЕЗ обратных кавычек/фенсов — устойчива к markdown внутри чанков.
 */
class RagPromptsTest {

    private fun passage(id: Int, content: String, source: String = "docs/файл$id.md", section: String = "Раздел $id") =
        Passage(
            chunkId = "c$id",
            source = source,
            title = "Заголовок $id",
            section = section,
            score = 0.9,
            content = content
        )

    // ------------------------------------------------------------------
    // (в) Маркер, инструкция, нумерация
    // ------------------------------------------------------------------

    @Test
    fun `маркер РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ присутствует и константа точная`() {
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, "текст")), maxChars = 4_000
        )

        assertTrue(block.contains(RagPrompts.RAG_MARKER), "маркер в блоке: $block")
        assertEquals("РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ", RagPrompts.RAG_MARKER, "точная строка (задача 8 копирует её в mock-llm.py)")
        assertEquals(RagPrompts.RAG_MARKER, block.lineSequence().first(), "маркер — первая строка блока")
    }

    @Test
    fun `инструкция про источники присутствует`() {
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, "текст")), maxChars = 4_000
        )

        assertTrue(
            block.contains("Отвечай по приведённым документам"),
            "инструкция в блоке: $block"
        )
        assertTrue(
            block.contains("[файл · раздел]"),
            "формат источников назван: $block"
        )
    }

    @Test
    fun `нумерация блоков с 1 и строки source-раздел`() {
        val block = RagPrompts.buildRagBlock(
            listOf(
                passage(1, "текст первый"),
                passage(2, "текст второй")
            ),
            maxChars = 4_000
        )

        assertTrue(block.contains("[1] source: docs/файл1.md · раздел: Раздел 1"), "блок [1]: $block")
        assertTrue(block.contains("[2] source: docs/файл2.md · раздел: Раздел 2"), "блок [2]: $block")
        assertTrue(block.contains("текст первый"))
        assertTrue(block.contains("текст второй"))
    }

    // ------------------------------------------------------------------
    // (в) Кап maxChars с пер-чанк обрезкой
    // ------------------------------------------------------------------

    @Test
    fun `суммарный кап maxChars соблюдён - пер-чанк обрезка и лишние чанки отбрасываются`() {
        val longText = "С".repeat(900)
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, longText), passage(2, longText), passage(3, longText)),
            maxChars = 700
        )

        assertTrue(block.length <= 700, "блок ${block.length} симв. превышает кап 700")
        assertTrue(block.contains("С".repeat(100)), "первый чанк присутствует (пусть и обрезанный)")
        // Первый чанк (900 симв.) не влезает в бюджет → обрезан с «…»
        assertTrue(block.contains("…"), "пер-чанк обрезка помечается «…»: ${block.takeLast(120)}")
        // Второй и третий чанки отброшены целиком
        assertFalse(block.contains("[2]"), "не влезающие блоки отбрасываются: ${block.length}")
        assertFalse(block.contains("[3]"))
    }

    @Test
    fun `пер-чанк обрезка ставит многоточие в конце текста чанка`() {
        val longText = "Б".repeat(300)
        val block = RagPrompts.buildRagBlock(listOf(passage(1, longText)), maxChars = 200)

        assertTrue(block.length <= 200, "кап соблюдён: ${block.length}")
        assertTrue(block.contains("…"), "обрезанный чанк завершается «…»")
        assertFalse(block.trimEnd().endsWith("Б" + "Б"), "текст не должен закончиться без «…», если был обрезан")
    }

    @Test
    fun `короткие чанки не обрезаются и не получают многоточие`() {
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, "короткий текст")),
            maxChars = 4_000
        )

        assertTrue(block.contains("короткий текст"))
        assertFalse(block.contains("…"), "без обрезки многоточие не нужно: $block")
    }

    // ------------------------------------------------------------------
    // (в) Чанк с markdown-фенсами не ломает структуру
    // ------------------------------------------------------------------

    @Test
    fun `чанк с тройными фенсами не ломает структуру блока`() {
        val fenced = "Код в документе:\n```\nprintln(\"hi\")\n```\nи пояснение после."
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, fenced), passage(2, "второй чанк")),
            maxChars = 4_000
        )

        // Структура цела: маркер первым, оба блока пронумерованы, тексты на месте
        assertEquals(RagPrompts.RAG_MARKER, block.lineSequence().first())
        assertTrue(block.contains("[1] source:"))
        assertTrue(block.contains("[2] source:"))
        assertTrue(block.contains("и пояснение после."))
        assertTrue(block.contains("второй чанк"))
        // Билдер сам НЕ добавляет фенсов (в блоке они только из чанка)
        val builderLines = block.lineSequence().filter { it.startsWith("[") }.toList()
        assertEquals(2, builderLines.size, "ровно два заголовка блоков: $builderLines")
    }

    @Test
    fun `билдер не оборачивает чанки в обратные кавычки`() {
        val block = RagPrompts.buildRagBlock(
            listOf(passage(1, "чистый текст")),
            maxChars = 4_000
        )

        assertFalse(block.contains("```"), "никаких фенсов от билдера: $block")
        assertFalse(block.contains("`"), "никаких обратных кавычек в разметке: $block")
    }

    // ------------------------------------------------------------------
    // Пустой список
    // ------------------------------------------------------------------

    @Test
    fun `пустой список чанков - пустой блок`() {
        val block = RagPrompts.buildRagBlock(emptyList(), maxChars = 4_000)

        assertEquals("", block, "без пассажей инжектить нечего")
    }

    @Test
    fun `абсурдно малый кап не роняет функцию`() {
        // maxChars меньше шапки: возвращается что-то осмысленное, без исключения и без мусора
        val block = RagPrompts.buildRagBlock(listOf(passage(1, "текст")), maxChars = 10)

        assertTrue(block.contains(RagPrompts.RAG_MARKER), "маркер сохраняется даже при крошечном капе: '$block'")
        try {
            RagPrompts.buildRagBlock(listOf(passage(1, "текст")), maxChars = -1)
            // Отрицательный кап не должен бросать — просто вырожденный блок
        } catch (e: Exception) {
            fail("отрицательный maxChars не должен бросать: ${e.message}")
        }
    }
}
