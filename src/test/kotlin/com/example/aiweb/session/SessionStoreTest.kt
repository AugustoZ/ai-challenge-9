package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки серверной памяти диалога: жизненный цикл сессий, поток идентификаторов,
 * выселение старейших сессий и жёсткий кап сообщений со срезом по границе user-хода.
 */
class SessionStoreTest {

    /** Детерминированное время: каждый вызов добавляет миллисекунду. */
    private val clock = AtomicLong(1_000)

    private fun newStore() = InMemorySessionStore(now = { clock.incrementAndGet() })

    private fun user(text: String) = OpenAIMessage(role = "user", content = text)

    private fun assistant(text: String) = OpenAIMessage(role = "assistant", content = text)

    @Test
    fun `создание сессии — новый id и пустая история`() {
        val store = newStore()

        val session = store.create()

        assertTrue(session.id.isNotBlank())
        assertTrue(session.messages.isEmpty())
        assertEquals(session.createdAt, session.updatedAt)
    }

    @Test
    fun `get — null на пустое и неизвестное значение`() {
        val store = newStore()

        assertNull(store.get(null))
        assertNull(store.get(""))
        assertNull(store.get("   "))
        assertNull(store.get("нет-такого"))
    }

    @Test
    fun `append пишет историю по порядку`() {
        val store = newStore()
        val session = store.create()

        store.append(session.id, user("вопрос"))
        store.append(session.id, assistant("ответ"))

        val loaded = assertNotNull(store.get(session.id))
        assertEquals(2, loaded.messages.size)
        assertEquals("user", loaded.messages[0].role)
        assertEquals("вопрос", loaded.messages[0].content)
        assertEquals("assistant", loaded.messages[1].role)
        assertEquals("ответ", loaded.messages[1].content)
    }

    @Test
    fun `append в неизвестную сессию игнорируется`() {
        val store = newStore()

        store.append("нет-такого", user("вопрос"))

        assertNull(store.get("нет-такого"))
    }

    @Test
    fun `applyCompressionResult устанавливает summary и удаляет первые removeCount сообщений`() {
        val store = newStore()
        val session = store.create()
        repeat(6) { i -> store.append(session.id, user("ход $i")) }

        val updated = assertNotNull(store.applyCompressionResult(session.id, "сводка", 4))

        assertEquals("сводка", updated.summary)
        assertEquals(listOf("ход 4", "ход 5"), updated.messages.mapNotNull { it.content })
        val reread = assertNotNull(store.get(session.id))
        assertEquals(2, reread.messages.size)
        assertEquals("сводка", reread.summary)
    }

    @Test
    fun `applyCompressionResult — removeCount больше размера обрезается до размера`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("единственный ход"))

        val updated = assertNotNull(store.applyCompressionResult(session.id, "сводка", 99))

        assertEquals("сводка", updated.summary)
        assertTrue(updated.messages.isEmpty())
    }

    @Test
    fun `applyCompressionResult на неизвестной сессии возвращает null`() {
        assertNull(newStore().applyCompressionResult("нет-такого", "сводка", 3))
    }

    @Test
    fun `delete удаляет сессию и идемпотентен`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос"))

        store.delete(session.id)

        assertNull(store.get(session.id))
        store.delete(session.id)
        store.delete("нет-такого")
    }

    @Test
    fun `clear на пустом хранилище возвращает ноль`() {
        assertEquals(0, newStore().clear())
    }

    @Test
    fun `clear стирает все сессии и возвращает их число`() {
        val store = newStore()
        repeat(3) { store.create() }

        assertEquals(3, store.clear())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `выселение старейших сессий за пределами MAX_SESSIONS`() {
        val store = newStore()
        val oldest = store.create()
        repeat(InMemorySessionStore.MAX_SESSIONS - 1) { store.create() }
        assertNotNull(store.get(oldest.id))

        val extra = store.create()

        assertNull(store.get(oldest.id))
        assertNotNull(store.get(extra.id))
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
    fun `list отражает создание, дописывание и удаление сессий`() {
        val store = newStore()
        val first = store.create()
        store.append(first.id, user("вопрос"))
        val second = store.create()

        val listed = store.list()

        assertEquals(2, listed.size)
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
        assertEquals(1, listed.last().messages.size)

        store.delete(first.id)

        assertEquals(listOf(second.id), store.list().map { it.id })
    }

    @Test
    fun `кап сообщений не рвёт пару assistant(tool_calls) и role=tool`() {
        val store = newStore()
        val session = store.create()
        repeat(67) { i ->
            store.append(session.id, user("вопрос $i"))
            store.append(
                session.id,
                OpenAIMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(OpenAIToolCall(id = "call-$i"))
                )
            )
            store.append(
                session.id,
                OpenAIMessage(role = "tool", content = "результат $i", toolCallId = "call-$i")
            )
        }

        val loaded = assertNotNull(store.get(session.id))

        // Срез прошёл по границе user-сообщения: история начинается с вопроса
        assertEquals("user", loaded.messages.first().role)
        assertEquals("вопрос 1", loaded.messages.first().content)
        // Каждое role="tool" стоит сразу после своего assistant(tool_calls)
        loaded.messages.forEachIndexed { index, message ->
            if (message.role == "tool") {
                val prev = loaded.messages[index - 1]
                assertEquals("assistant", prev.role)
                assertNotNull(prev.toolCalls)
            }
        }
    }
}
