package com.example.aiweb.invariants

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки глобального хранилища инвариантов на временном каталоге:
 * roundtrip add/list/remove и пересоздание хранилища, капы (дубль, 21-й,
 * 301 символ, whitespace) и устойчивость к заранее созданному битому файлу
 * (читается как пустой, add перезаписывает его валидным JSON).
 */
class GlobalInvariantStoreTest {

    private fun newDir(): File = Files.createTempDirectory("aiweb-invariants").toFile()

    @Test
    fun `add list remove roundtrip и пересоздание хранилища`() {
        val dir = newDir()
        val store = GlobalInvariantStore(dir.absolutePath)

        assertTrue(store.list().isEmpty())
        val a = store.add("  Не удаляй данные без подтверждения  ")
        val b = store.add("Будь осторожен с деньгами")

        assertEquals(2, store.list().size)
        assertEquals("Не удаляй данные без подтверждения", store.list()[0].text)
        assertEquals("Будь осторожен с деньгами", store.list()[1].text)
        assertTrue(a.id.isNotBlank() && b.id.isNotBlank() && a.id != b.id)

        assertTrue(store.remove(a.id))
        assertFalse(store.remove("нет-такого-id"))
        assertEquals(1, store.list().size)
        assertEquals(b.id, store.list()[0].id)

        val reloaded = GlobalInvariantStore(dir.absolutePath)
        assertEquals(listOf(b), reloaded.list())
    }

    @Test
    fun `точный дубль текста отклоняется`() {
        val store = GlobalInvariantStore(newDir().absolutePath)
        store.add("Не удаляй данные")

        assertFailsWith<IllegalArgumentException> { store.add("Не удаляй данные") }
        // trim важен: дубль с краями тоже отклоняется
        assertFailsWith<IllegalArgumentException> { store.add("  Не удаляй данные ") }
        assertEquals(1, store.list().size)
    }

    @Test
    fun `двадцать первый инвариант отклоняется`() {
        val store = GlobalInvariantStore(newDir().absolutePath)
        repeat(StoredInvariant.MAX_COUNT) { store.add("Инвариант №$it") }

        assertFailsWith<IllegalArgumentException> { store.add("Один лишней") }
        assertEquals(StoredInvariant.MAX_COUNT, store.list().size)
    }

    @Test
    fun `триста первый символ отклоняется`() {
        val store = GlobalInvariantStore(newDir().absolutePath)

        assertFailsWith<IllegalArgumentException> { store.add("а".repeat(StoredInvariant.MAX_TEXT + 1)) }
        val atCap = store.add("б".repeat(StoredInvariant.MAX_TEXT))
        assertEquals(StoredInvariant.MAX_TEXT, atCap.text.length)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `пустой и whitespace-только текст отклоняется`() {
        val store = GlobalInvariantStore(newDir().absolutePath)

        assertFailsWith<IllegalArgumentException> { store.add("") }
        assertFailsWith<IllegalArgumentException> { store.add("   \t\n ") }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `заранее созданный битый файл читается как пустой и перезаписывается`() {
        val dir = newDir()
        File(dir, "invariants.json").writeText("{broken json!!")
        val store = GlobalInvariantStore(dir.absolutePath)

        assertTrue(store.list().isEmpty())
        val added = store.add("Выживший инвариант")

        assertEquals(listOf(added), store.list())
        val file = File(dir, "invariants.json")
        val array = (Json.parseToJsonElement(file.readText()) as JsonObject)["invariants"]!!.jsonArray
        assertEquals(listOf("Выживший инвариант"), array.map { (it as JsonObject)["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun `атомарная запись — валидный файл на месте и нет остатков tmp`() {
        val dir = newDir()
        val store = GlobalInvariantStore(dir.absolutePath)
        store.add("Первый")
        store.add("Второй")

        val file = File(dir, "invariants.json")
        assertTrue(file.isFile)
        val array = (Json.parseToJsonElement(file.readText()) as JsonObject)["invariants"]!!.jsonArray
        assertEquals(2, array.size)
        assertEquals("Первый", (array[0] as JsonObject)["text"]!!.jsonPrimitive.content)
        assertTrue(!File(dir, "invariants.json.tmp").exists())
    }
}
