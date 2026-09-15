package com.example.aiweb.auth

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки хранилища пользователей: создание с валидацией имени и ПИНа,
 * персистентность (новый экземпляр читает файлы, ПИН не хранится открыто),
 * список, дубликаты имён, удаление и защита от path traversal.
 */
class UserStoreTest {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    private fun newDir(): File = Files.createTempDirectory("aiweb-users").toFile()

    private fun newStore(dir: File = newDir()) =
        UserStore(dir.absolutePath, now = { clock.incrementAndGet() })

    @Test
    fun `create обрезает имя, солирует и хеширует ПИН, проставляет время`() {
        val store = newStore()

        val user = store.create("  Пилот Тест  ", "0407")

        assertEquals("Пилот Тест", user.name)
        assertTrue(user.id.isNotBlank(), "id должен генерироваться хранилищем")
        assertNotEquals("0407", user.pinHash, "ПИН не должен храниться открыто")
        assertEquals(32, user.salt.length, "Соль — 16 байт в hex")
        assertTrue(user.salt.all { it.isDigit() || it in 'a'..'f' }, "Соль — hex-строка")
        assertEquals(64, user.pinHash.length, "SHA-256 — 64 hex-символа")
        assertEquals(1_001L, user.createdAt, "createdAt берётся из источника времени")
        assertTrue(user.pinMatches("0407"), "Верный ПИН распознаётся")
        assertFalse(user.pinMatches("0408"), "Неверный ПИН отвергается")
        assertFalse(user.pinMatches(""), "Пустой ПИН не совпадает ни с чем")
    }

    @Test
    fun `хранилище переживает пересоздание — ПИН не хранится открыто в файле`() {
        val dir = newDir()
        val store = newStore(dir)
        val user = store.create("ТестЮзер", "1234")

        val file = File(dir, "users/${user.id}.json")
        assertTrue(file.isFile, "Файл пользователя должен лежать в users/ по id")
        val text = file.readText()
        assertTrue(text.contains("\"salt\""), "В файле должна быть соль")
        assertTrue(text.contains("\"pinHash\""), "В файле должен быть хеш ПИНа")
        assertFalse(
            Regex("\"pin\"\\s*:").containsMatchIn(text),
            "Открытого ПИНа в файле быть не должно"
        )
        assertFalse(
            File(dir, "users").listFiles()!!.any { it.name.endsWith(".tmp") },
            "Временный файл должен быть переименован"
        )

        val reloaded = newStore(dir)
        val found = reloaded.findById(user.id)

        assertEquals("ТестЮзер", found?.name)
        assertTrue(found?.pinMatches("1234") == true, "ПИН проверяется после перезагрузки")
        assertFalse(found?.pinMatches("9999") == true, "Чужой ПИН не проходит")
    }

    @Test
    fun `list возвращает пользователей в порядке создания`() {
        val store = newStore()
        store.create("Шумахер", "1111")
        store.create("Пилот", "2222")
        store.create("Гонщик", "3333")

        val names = store.list().map { it.name }

        assertEquals(listOf("Шумахер", "Пилот", "Гонщик"), names)
    }

    @Test
    fun `existsWithName учитывает trim, дубликаты имён разрешены`() {
        val store = newStore()
        val first = store.create("Пилот", "1111")
        val second = store.create("Пилот", "2222")

        assertNotEquals(first.id, second.id, "Одинаковые имена различаются ПИНом")
        assertEquals(2, store.list().size)
        assertTrue(store.existsWithName("Пилот"))
        assertTrue(store.existsWithName("  Пилот  "), "Имя сравнивается после обрезки")
        assertFalse(store.existsWithName("Шумахер"))
        assertFalse(store.existsWithName("   "), "Пустое имя существовать не может")
    }

    @Test
    fun `findById неизвестного или небезопасного идентификатора — null`() {
        val store = newStore()
        store.create("Пилот", "1111")

        assertNull(store.findById("нет-такого"))
        assertNull(store.findById(""), "Пустой id — не путь к файлу")
        assertNull(store.findById("../escape"), "Path traversal закрыт")
        assertNull(store.findById("a/b"), "Слэш в id недопустим")
        assertNull(store.findById("a\\b"), "Обратный слэш в id недопустим")
        assertNull(store.findById(".."), "Точки в id недопустимы")
    }

    @Test
    fun `delete удаляет файл пользователя, повторное удаление и чужой id — false`() {
        val dir = newDir()
        val store = newStore(dir)
        val user = store.create("Пилот", "1111")

        assertTrue(store.delete(user.id))
        assertNull(store.findById(user.id))
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete(user.id), "Повторное удаление — false")
        assertFalse(store.delete("../escape"), "Небезопасный id не удаляет ничего")
    }

    @Test
    fun `битый файл пользователя пропускается, а не роняет чтение`() {
        val dir = newDir()
        val store = newStore(dir)
        val user = store.create("Пилот", "1111")
        File(dir, "users/broken.json").writeText("{это не json")

        val users = store.list()

        assertEquals(1, users.size)
        assertEquals(user.id, users.single().id)
        assertNull(store.findById("broken"))
    }

    @Test
    fun `ПИН из 0, 3, 5 цифр и с буквами отклоняется с русским сообщением`() {
        val store = newStore()

        for (bad in listOf("12a5", "123", "12345", "", "abcd", " 1234")) {
            val error = assertFailsWith<IllegalArgumentException>("ПИН '$bad' должен быть отклонён") {
                store.create("Пилот", bad)
            }
            assertTrue(
                error.message!!.contains("ПИН") && error.message!!.contains("4 цифр"),
                "Сообщение должно называть поле и правило: ${error.message}"
            )
        }
        // Пользователь при неудаче не создаётся
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `имя длиннее 25 символов, пустое и с управляющими символами отклоняется`() {
        val store = newStore()

        val tooLong = assertFailsWith<IllegalArgumentException> {
            store.create("A".repeat(26), "1234")
        }
        assertTrue(
            tooLong.message!!.contains("Имя") && tooLong.message!!.contains("25"),
            "Сообщение должно называть поле и лимит: ${tooLong.message}"
        )

        val empty = assertFailsWith<IllegalArgumentException> { store.create("   ", "1234") }
        assertTrue(empty.message!!.contains("Имя"), "Сообщение должно называть поле: ${empty.message}")

        val withControl = assertFailsWith<IllegalArgumentException> {
            store.create("Имя\nс переносом", "1234")
        }
        assertTrue(
            withControl.message!!.contains("Имя"),
            "Сообщение должно называть поле: ${withControl.message}"
        )
        // Пользователь при неудаче не создаётся
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `имя с недопустимыми символами (эмодзи) отклоняется`() {
        val store = newStore()

        val error = assertFailsWith<IllegalArgumentException> { store.create("Пилот🚀", "1234") }

        assertTrue(error.message!!.contains("Имя"), "Сообщение должно называть поле: ${error.message}")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `допустимые спецсимволы в имени проходят`() {
        val store = newStore()

        val user = store.create("Пилот-1 (гонщик #7)!", "1234")

        assertEquals("Пилот-1 (гонщик #7)!", user.name)
    }
}
