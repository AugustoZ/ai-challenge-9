package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
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
 * Проверки файлового хранилища сессий (P1-персистентность): перезапуск процесса
 * (новый экземпляр читает сессии с диска), идемпотентное удаление с файлом,
 * выселение старейших сессий вместе с их файлами, устойчивость к битым json-файлам
 * и кап сообщений как в памяти.
 */
class FileSessionStoreTest {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    private fun newDir(): File = Files.createTempDirectory("aiweb-sessions").toFile()

    private fun newStore(dir: File = newDir()) =
        FileSessionStore(dir.absolutePath, now = { clock.incrementAndGet() })

    private fun user(text: String) = OpenAIMessage(role = "user", content = text)

    private fun assistant(text: String) = OpenAIMessage(role = "assistant", content = text)

    @Test
    fun `сессия переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос"))
        store.append(session.id, assistant("ответ"))

        val reloaded = newStore(dir)

        val loaded = assertNotNull(reloaded.get(session.id))
        assertEquals(session.createdAt, loaded.createdAt)
        assertEquals(2, loaded.messages.size)
        assertEquals("вопрос", loaded.messages[0].content)
        assertEquals("ответ", loaded.messages[1].content)
    }

    @Test
    fun `delete удаляет сессию вместе с файлом и идемпотентен`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        val file = File(dir, "sessions/${session.id}.json")
        assertTrue(file.exists())

        store.delete(session.id)

        assertNull(store.get(session.id))
        assertFalse(file.exists())
        store.delete(session.id)
        store.delete("нет-такого")
        assertNull(newStore(dir).get(session.id))
    }

    @Test
    fun `clear на пустом хранилище возвращает ноль`() {
        assertEquals(0, newStore().clear())
    }

    @Test
    fun `clear стирает все сессии, их файлы и возвращает их число`() {
        val dir = newDir()
        val store = newStore(dir)
        repeat(3) { store.create() }
        val sessionsDir = File(dir, "sessions")
        assertEquals(3, sessionsDir.listFiles()!!.size)

        assertEquals(3, store.clear())

        assertTrue(store.list().isEmpty())
        assertTrue(sessionsDir.listFiles()!!.isEmpty())
        assertTrue(newStore(dir).list().isEmpty())
    }

    @Test
    fun `выселение старейших сессий удаляет их файлы`() {
        val dir = newDir()
        val store = newStore(dir)
        val sessionsDir = File(dir, "sessions")
        val oldest = store.create()
        repeat(FileSessionStore.MAX_SESSIONS - 1) { store.create() }
        assertEquals(FileSessionStore.MAX_SESSIONS, sessionsDir.listFiles()!!.size)

        store.create()

        assertNull(store.get(oldest.id))
        assertFalse(File(sessionsDir, "${oldest.id}.json").exists())
        assertEquals(FileSessionStore.MAX_SESSIONS, sessionsDir.listFiles()!!.size)
    }

    @Test
    fun `битый json-файл пропускается без падения`() {
        val dir = newDir()
        val store = newStore(dir)
        val good = store.create()
        store.append(good.id, user("вопрос"))
        File(dir, "sessions/битая.json").writeText("{ это не json")

        val reloaded = newStore(dir)

        val loaded = assertNotNull(reloaded.get(good.id))
        assertEquals(1, loaded.messages.size)
    }

    @Test
    fun `выселенная при загрузке сессия удаляется с диска`() {
        val dir = newDir()
        val store = newStore(dir)
        val stale = store.create()
        store.append(stale.id, user("старый ход"))
        repeat(FileSessionStore.MAX_SESSIONS) { newStore(dir).create() }

        val reloaded = newStore(dir)

        assertNull(reloaded.get(stale.id))
        assertFalse(File(dir, "sessions/${stale.id}.json").exists())
    }

    @Test
    fun `кап сообщений — лишние старые ходы сбрасываются от границы user`() {
        val store = newStore()
        val session = store.create()
        repeat(101) { i ->
            store.append(session.id, user("ход $i"))
            store.append(session.id, assistant("ответ $i"))
        }

        val loaded = assertNotNull(store.get(session.id))

        assertEquals(InMemorySessionStore.MAX_MESSAGES, loaded.messages.size)
        assertEquals("user", loaded.messages.first().role)
        assertEquals("ход 1", loaded.messages.first().content)
        assertEquals("ответ 100", loaded.messages.last().content)
    }

    @Test
    fun `list на пустом хранилище — пустой список`() {
        assertTrue(newStore().list().isEmpty())
    }

    @Test
    fun `list отдаёт сессии по updatedAt по убыванию`() {
        val store = newStore()
        val oldest = store.create()
        val middle = store.create()
        val newest = store.create()

        // Обновление старой сессии поднимает её наверх списка
        store.append(oldest.id, user("вопрос"))

        assertEquals(listOf(oldest.id, newest.id, middle.id), store.list().map { it.id })
        assertEquals(1, store.list().first().messages.size)
    }

    @Test
    fun `list отражает создание и удаление сессий`() {
        val store = newStore()
        val first = store.create()
        val second = store.create()

        assertEquals(listOf(second.id, first.id), store.list().map { it.id })

        store.delete(first.id)

        assertEquals(listOf(second.id), store.list().map { it.id })
    }

    @Test
    fun `sessionStoreFor выбирает реализацию по каталогу`() {
        assertTrue(sessionStoreFor(null) is InMemorySessionStore)
        assertTrue(sessionStoreFor("   ") is InMemorySessionStore)

        val dir = newDir()
        assertTrue(sessionStoreFor(dir.absolutePath) is FileSessionStore)
    }
}
