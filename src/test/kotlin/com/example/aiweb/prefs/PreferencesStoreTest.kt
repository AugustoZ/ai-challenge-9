package com.example.aiweb.prefs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки хранилища предпочтений: раскладка файлов preferences/{userId}.json,
 * атомарная запись (tmp + rename), дефолты при отсутствии/битом файле,
 * защита от path traversal, валидация при записи.
 */
class PreferencesStoreTest {

    private fun newDir(): File = Files.createTempDirectory("aiweb-prefs").toFile()

    private fun create(dir: File): PreferencesStore = PreferencesStore(dir.absolutePath)

    @Test
    fun `отсутствующий файл — дефолтные предпочтения`() {
        val store = create(newDir())

        val prefs = store.get("unknown-user")

        assertEquals(UserPreferences.DEFAULTS, prefs)
    }

    @Test
    fun `сохранённые предпочтения читаются обратно`() {
        val dir = newDir()
        val store = create(dir)
        val prefs = UserPreferences(
            addressForm = "пилот",
            tone = "панибратский",
            language = "Английский",
            answerStructure = "таблицы",
            length = "развернуто",
            avoidTopics = "политика",
            interests = "гонки"
        )

        store.save("u1", prefs)

        assertEquals(prefs, store.get("u1"))
        assertTrue(
            File(dir, "preferences/u1.json").isFile,
            "Файл должен лежать в preferences/{userId}.json"
        )
    }

    @Test
    fun `хранилище переживает пересоздание инстанса`() {
        val dir = newDir()
        create(dir).save(
            "u2",
            UserPreferences.DEFAULTS.copy(addressForm = "гонщик", tone = "роботизированный")
        )

        val reloaded = create(dir).get("u2")

        assertEquals("гонщик", reloaded.addressForm)
        assertEquals("роботизированный", reloaded.tone)
        assertEquals("средне", reloaded.length)
    }

    @Test
    fun `временный файл переименовывается после записи`() {
        val dir = newDir()
        val store = create(dir)

        store.save("u3", UserPreferences.DEFAULTS)

        val files = File(dir, "preferences").listFiles()!!.map { it.name }
        assertTrue(files.contains("u3.json"), "Файлы: $files")
        assertFalse(
            files.any { it.endsWith(".tmp") },
            "Временный файл должен быть переименован: $files"
        )
    }

    @Test
    fun `битый файл читается как дефолт без исключения`() {
        val dir = newDir()
        val prefsDir = File(dir, "preferences").apply { mkdirs() }
        File(prefsDir, "broken.json").writeText("""{"addressForm": НЕВАЛИДНЫЙ JSON""")

        val prefs = create(dir).get("broken")

        assertEquals(UserPreferences.DEFAULTS, prefs)
    }

    @Test
    fun `json с невалидными значениями читается как дефолт`() {
        val dir = newDir()
        val prefsDir = File(dir, "preferences").apply { mkdirs() }
        File(prefsDir, "junk.json").writeText(
            """{"addressForm":"эскимо","tone":"хамский","language":"Русский","answerStructure":"текст","length":"кратко","avoidTopics":"","interests":""}"""
        )

        assertEquals(UserPreferences.DEFAULTS, create(dir).get("junk"))
    }

    @Test
    fun `json с неизвестными полями читается без падения`() {
        val dir = newDir()
        val prefsDir = File(dir, "preferences").apply { mkdirs() }
        File(prefsDir, "legacy.json").writeText(
            """{"addressForm":"пилот","tone":"формальный","language":"Английский","answerStructure":"таблицы","length":"кратко","avoidTopics":"шум","interests":"космос","extra":42}"""
        )

        val prefs = create(dir).get("legacy")

        assertEquals("пилот", prefs.addressForm)
        assertEquals("шум", prefs.avoidTopics)
    }

    @Test
    fun `невалидные предпочтения при записи отклоняются`() {
        val store = create(newDir())

        val error = assertFailsWith<IllegalArgumentException> {
            store.save("u4", UserPreferences.DEFAULTS.copy(interests = "x".repeat(PreferenceLimits.INTERESTS_MAX + 1)))
        }
        assertTrue(error.message!!.contains("Интересы"), "Сообщение: ${error.message}")
    }

    @Test
    fun `небезопасный идентификатор отклоняется при записи`() {
        val store = create(newDir())

        assertFailsWith<IllegalArgumentException> { store.save("../evil", UserPreferences.DEFAULTS) }
        assertFailsWith<IllegalArgumentException> { store.save("a/b", UserPreferences.DEFAULTS) }
        assertFailsWith<IllegalArgumentException> { store.save("", UserPreferences.DEFAULTS) }
    }

    @Test
    fun `небезопасный идентификатор при чтении — дефолт без исключения`() {
        val store = create(newDir())

        assertEquals(UserPreferences.DEFAULTS, store.get("../evil"))
    }
}
