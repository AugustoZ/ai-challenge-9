package com.example.aiweb.rag

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Контракт [RetrievedSource]: DTO, который задача 6 выставит в ChatResponse.
 * Фиксированные свойства:
 *  - ключи camelCase (зеркало frontend/src/api/types.ts);
 *  - НИКАКОГО полного content — только snippet ≤ 200 символов.
 */
class RetrievedSourceTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `сериализация-десериализация симметрична`() {
        val source = RetrievedSource(
            chunkId = "c-1",
            source = "gw-club-docs/faq.md",
            title = "FAQ",
            section = "Порталы",
            score = 0.873,
            snippet = "Краткий фрагмент документа"
        )

        val roundTrip = json.decodeFromString<RetrievedSource>(json.encodeToString(source))

        assertEquals(source, roundTrip)
    }

    @Test
    fun `ключи camelCase - как в контракте фронтенда`() {
        val encoded = json.encodeToString(
            RetrievedSource("c-1", "file.md", "T", "S", 0.5, "сниппет")
        )

        assertTrue(encoded.contains("\"chunkId\""), "ожидается ключ chunkId, получено: $encoded")
        assertTrue(encoded.contains("\"source\""))
        assertTrue(encoded.contains("\"title\""))
        assertTrue(encoded.contains("\"section\""))
        assertTrue(encoded.contains("\"score\""))
        assertTrue(encoded.contains("\"snippet\""))
    }

    @Test
    fun `в DTO нет полного content - только snippet`() {
        // Класс не имеет поля content: сериализация не может его выдать.
        val encoded = json.encodeToString(
            RetrievedSource("c-1", "file.md", "T", "S", 0.5, "сниппет")
        )

        assertFalse(encoded.contains("\"content\""), "полный content не должен попадать в JSON: $encoded")
    }

    @Test
    fun `score - дробное число Double`() {
        val encoded = json.encodeToString(
            RetrievedSource("c-1", "file.md", "T", "S", 1.0, "сниппет")
        )

        assertTrue(encoded.contains("\"score\":1.0"), "score сериализуется как Double: $encoded")
    }
}
