package com.example.aiweb.session

import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.task.TaskState
import com.example.aiweb.task.TaskStage
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
    fun `applyWindow обрезает хранилище до последних windowSize сообщений`() {
        val store = newStore()
        val session = store.create()
        repeat(3) { i ->
            store.append(session.id, user("ход $i"))
            store.append(session.id, assistant("ответ $i"))
        }
        store.append(session.id, user("висячий вопрос"))

        val updated = assertNotNull(store.applyWindow(session.id, 5))

        // На момент вызова 7 сообщений: за окном ход 0 и ответ 0
        assertEquals(5, updated.messages.size)
        assertEquals(
            listOf("ход 1", "ответ 1", "ход 2", "ответ 2"),
            updated.messages.dropLast(1).mapNotNull { it.content }
        )
        val reread = assertNotNull(store.get(session.id))
        assertEquals(5, reread.messages.size)
        assertTrue(reread.messages.none { it.content == "ход 0" }, "за окном отброшено безвозвратно")
        assertTrue(reread.messages.none { it.content == "ответ 0" }, "за окном отброшено безвозвратно")
    }

    @Test
    fun `applyWindow — история не длиннее окна — no-op`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("ход 1"))
        store.append(session.id, assistant("ответ 1"))
        val before = assertNotNull(store.get(session.id))

        val updated = assertNotNull(store.applyWindow(session.id, 5))
        val sameSize = assertNotNull(store.applyWindow(session.id, 2))

        assertEquals(before, updated, "история короче окна — сессия не изменилась")
        assertEquals(before, sameSize, "история равна окну — сессия не изменилась")
    }

    @Test
    fun `applyWindow не рвёт пару assistant(tool_calls) и role=tool`() {
        val store = newStore()
        val session = store.create()
        // 8 сообщений: окно 3 начинается ровно с role="tool" (индекс 5) —
        // guard обязан расширить окно назад до user-сообщения (индекс 2)
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.append(session.id, user("вопрос 2"))
        store.append(session.id, assistant("ответ 2"))
        store.append(
            session.id,
            OpenAIMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(OpenAIToolCall(id = "call-1"))
            )
        )
        store.append(session.id, OpenAIMessage(role = "tool", content = "результат", toolCallId = "call-1"))
        store.append(session.id, user("вопрос 3"))
        store.append(session.id, assistant("ответ 3"))

        val updated = assertNotNull(store.applyWindow(session.id, 3))

        // Граница наехала на tool — окно расширено назад до user-сообщения
        assertEquals("user", updated.messages.first().role)
        assertEquals("вопрос 2", updated.messages.first().content)
        assertEquals(6, updated.messages.size, "окно расширилось назад, а не разорвало пару")
        updated.messages.forEachIndexed { index, message ->
            if (message.role == "tool") {
                val prev = updated.messages[index - 1]
                assertEquals("assistant", prev.role)
                assertNotNull(prev.toolCalls)
            }
        }
    }

    @Test
    fun `applyWindow не трогает summary, факты и ветки`() {
        val store = newStore()
        val session = store.create()
        repeat(3) { i ->
            store.append(session.id, user("ход $i"))
            store.append(session.id, assistant("ответ $i"))
        }
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))
        store.applyCompressionResult(session.id, "сводка", 0)
        store.forkTwin(session.id)

        val updated = assertNotNull(store.applyWindow(session.id, 2))

        assertEquals("сводка", updated.summary, "Окно не пишет и не стирает сводку")
        assertEquals(listOf(Fact("тема", "змейка")), updated.facts)
        assertEquals(1, updated.branches.size)
        assertEquals(2, updated.messages.size)
    }

    @Test
    fun `applyWindow на неизвестной сессии возвращает null`() {
        assertNull(newStore().applyWindow("нет-такого", 5))
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

    // ── Ветки и факты: forkTwin / activateBranch / setFacts ──

    @Test
    fun `forkTwin создаёт близнеца с копией активного состояния и не меняет активную`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.append(session.id, user("вопрос 2"))

        val branch = assertNotNull(store.forkTwin(session.id))

        assertEquals("b1", branch.branchId)
        assertEquals("Ветка 1", branch.label)
        assertEquals(2, branch.forkUserCount)
        assertEquals(0, branch.userCountSinceFork)
        assertEquals(
            listOf("вопрос 1", "ответ 1", "вопрос 2"),
            branch.messages.mapNotNull { it.content }
        )

        // Активное состояние не изменилось; близнец виден в сессии
        val active = assertNotNull(store.get(session.id))
        assertEquals(3, active.messages.size)
        assertEquals(listOf("b1"), active.branches.map { it.branchId })
        assertEquals(1, active.branchSeq)
        assertNull(active.activeBranchId)

        // Дописали в активную — близнец остался прежним
        store.append(session.id, assistant("ответ 2"))
        val reread = assertNotNull(store.get(session.id))
        assertEquals(4, reread.messages.size)
        assertEquals(3, reread.branches.single().messages.size)
        assertEquals(0, reread.branches.single().userCountSinceFork)
    }

    @Test
    fun `forkTwin копирует summary и facts активного состояния`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))
        store.applyCompressionResult(session.id, "сводка", 0)

        val branch = assertNotNull(store.forkTwin(session.id))

        assertEquals("сводка", branch.summary)
        assertEquals(listOf(Fact("тема", "змейка")), branch.facts)
    }

    @Test
    fun `кап веток — после MAX_BRANCHES форков следующий возвращает null`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос"))

        repeat(InMemorySessionStore.MAX_BRANCHES) { i ->
            val branch = assertNotNull(store.forkTwin(session.id), "Форк ${i + 1} должен удаться")
            assertEquals("b${i + 1}", branch.branchId)
        }

        assertNull(store.forkTwin(session.id))
        val active = assertNotNull(store.get(session.id))
        assertEquals(InMemorySessionStore.MAX_BRANCHES, active.branches.size)
        assertEquals(InMemorySessionStore.MAX_BRANCHES, active.branchSeq)
    }

    @Test
    fun `кап веток — активация изменённого состояния при полном наборе возвращает null без мутации`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос"))
        repeat(InMemorySessionStore.MAX_BRANCHES) { store.forkTwin(session.id) }
        // Кап не мешает переключить неизменённое состояние — сворачивать нечего
        assertNotNull(store.activateBranch(session.id, "b1"))
        // Изменённое состояние свернуть некуда — активация отклоняется без мутации
        store.append(session.id, user("дополнение"))
        val before = assertNotNull(store.get(session.id))

        assertNull(store.activateBranch(session.id, "b2"))

        assertEquals(before, assertNotNull(store.get(session.id)))
    }

    @Test
    fun `activateBranch свопает активное состояние с веткой без потерь`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.append(session.id, user("вопрос 2"))
        store.forkTwin(session.id)
        store.append(session.id, assistant("ответ 2"))

        val activated = assertNotNull(store.activateBranch(session.id, "b1"))

        // Активным стал снимок близнеца (3 хода)
        assertEquals(3, activated.messages.size)
        assertEquals(
            listOf("вопрос 1", "ответ 1", "вопрос 2"),
            activated.messages.mapNotNull { it.content }
        )
        assertEquals("b1", activated.activeBranchId)
        // Прежняя активная ушла в branches со своими 4 ходами
        val folded = activated.branches.single { it.branchId == "b2" }
        assertEquals(4, folded.messages.size)
        assertEquals(
            listOf("вопрос 1", "ответ 1", "вопрос 2", "ответ 2"),
            folded.messages.mapNotNull { it.content }
        )
        // Счётчики прежней активной: исходная история — происхождение пустая сессия
        assertEquals(0, folded.forkUserCount)
        assertEquals(2, folded.userCountSinceFork)
        // Снимок близнеца в списке веток не изменился
        val twin = activated.branches.single { it.branchId == "b1" }
        assertEquals(3, twin.messages.size)
        assertEquals(0, twin.userCountSinceFork)
        assertEquals(2, twin.forkUserCount)
        // В хранилище то же состояние
        val reread = assertNotNull(store.get(session.id))
        assertEquals(activated, reread)
        // Потерь нет: 3 хода в активной + 4 в ветке; пары tool не рвутся.
        // Снимок активной ветки — зеркало активного состояния, в уникальный счёт не входит
        assertEquals(
            7,
            reread.messages.size +
                reread.branches
                    .filter { it.branchId != reread.activeBranchId }
                    .sumOf { it.messages.size }
        )
        reread.messages.forEachIndexed { index, message ->
            if (message.role == "tool") {
                assertEquals("assistant", reread.messages[index - 1].role)
            }
        }
    }

    @Test
    fun `activateBranch свопает messages, summary и facts со снимком ветки`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.append(session.id, user("вопрос 2"))
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))
        store.applyCompressionResult(session.id, "сводка до форка", 0)
        store.forkTwin(session.id)

        // Активное состояние уходит от снимка близнеца
        store.append(session.id, assistant("ответ 2"))
        store.setFacts(session.id, listOf(Fact("тема", "крестики")))
        store.applyCompressionResult(session.id, "сводка после форка", 0)

        val activated = assertNotNull(store.activateBranch(session.id, "b1"))

        // Активное = снимок близнеца
        assertEquals("сводка до форка", activated.summary)
        assertEquals(listOf(Fact("тема", "змейка")), activated.facts)
        assertEquals(3, activated.messages.size)
        // Прежнее активное — в новой ветке со своими summary и facts
        val folded = activated.branches.single { it.branchId == "b2" }
        assertEquals("сводка после форка", folded.summary)
        assertEquals(listOf(Fact("тема", "крестики")), folded.facts)
        assertEquals(4, folded.messages.size)
    }

    @Test
    fun `activateBranch неизвестной ветки возвращает null и не меняет сессию`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.forkTwin(session.id)
        val before = assertNotNull(store.get(session.id))

        assertNull(store.activateBranch(session.id, "b999"))
        assertNull(store.activateBranch(session.id, ""))

        assertEquals(before, assertNotNull(store.get(session.id)))
    }

    @Test
    fun `activateBranch без изменений не создаёт дубликат-ветку`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.forkTwin(session.id)

        val activated = assertNotNull(store.activateBranch(session.id, "b1"))

        // Состояние не менялось с форка — сворачивать нечего, ветка одна
        assertEquals(1, activated.branches.size)
        assertEquals("b1", activated.activeBranchId)
        assertEquals(1, activated.branchSeq)
        assertEquals(2, activated.messages.size)
        assertEquals(
            listOf("вопрос 1", "ответ 1"),
            activated.messages.mapNotNull { it.content }
        )
        // Отпечаток захвата совпал с активным состоянием
        assertEquals(2, activated.capturedMessages)
        // Повторное переключение туда-обратно веток не добавляет
        val back = assertNotNull(store.activateBranch(session.id, "b1"))
        assertEquals(1, back.branches.size)
    }

    @Test
    fun `activateBranch при изменениях сворачивает состояние, обратное переключение — нет`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.forkTwin(session.id)
        store.append(session.id, user("вопрос 2"))

        // Уход с изменённого состояния — свёртка без потерь
        val activated = assertNotNull(store.activateBranch(session.id, "b1"))
        assertEquals(2, activated.branches.size)
        assertEquals("b1", activated.activeBranchId)
        val folded = activated.branches.single { it.branchId == "b2" }
        assertEquals(listOf("вопрос 1", "ответ 1", "вопрос 2"), folded.messages.mapNotNull { it.content })
        assertEquals(2, folded.userCountSinceFork)

        // Обратное переключение к сфолдированному — дубликата нет
        val back = assertNotNull(store.activateBranch(session.id, "b2"))
        assertEquals(2, back.branches.size)
        assertEquals("b2", back.activeBranchId)
        assertEquals(listOf("вопрос 1", "ответ 1", "вопрос 2"), back.messages.mapNotNull { it.content })

        // И снова к близнецу — по-прежнему две ветки
        val twin = assertNotNull(store.activateBranch(session.id, "b1"))
        assertEquals(2, twin.branches.size)
        assertEquals(2, twin.messages.size)
    }

    @Test
    fun `расхождение только по фактам сворачивается при переключении`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.forkTwin(session.id)
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))

        val activated = assertNotNull(store.activateBranch(session.id, "b1"))

        assertEquals(2, activated.branches.size)
        val folded = activated.branches.single { it.branchId == "b2" }
        assertEquals(listOf(Fact("тема", "змейка")), folded.facts)
        assertEquals(emptyList(), activated.facts)
    }

    @Test
    fun `кап веток блокирует только свёртку изменённого состояния`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        repeat(8) { store.forkTwin(session.id) }

        // Неизменённое состояние при капе переключается — сворачивать нечего
        val unchanged = assertNotNull(store.activateBranch(session.id, "b1"))
        assertEquals(8, unchanged.branches.size)
        assertEquals("b1", unchanged.activeBranchId)

        // Изменённое состояние свернуть некуда — кап
        store.append(session.id, user("вопрос 2"))
        val before = assertNotNull(store.get(session.id))
        assertNull(store.activateBranch(session.id, "b2"))
        assertEquals(before, assertNotNull(store.get(session.id)))
    }

    @Test
    fun `activateBranch на неизвестной сессии возвращает null`() {
        assertNull(newStore().activateBranch("нет-такого", "b1"))
    }

    @Test
    fun `activateBranch уже активной ветки не меняет состояние`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.forkTwin(session.id)
        store.append(session.id, assistant("ответ 1"))
        store.activateBranch(session.id, "b1")

        val before = assertNotNull(store.get(session.id))
        val again = assertNotNull(store.activateBranch(session.id, "b1"))

        assertEquals(before, again)
        assertEquals(2, again.branches.size)
    }

    @Test
    fun `setFacts заменяет список фактов активного состояния`() {
        val store = newStore()
        val session = store.create()
        store.append(session.id, user("вопрос"))

        val updated = assertNotNull(
            store.setFacts(session.id, listOf(Fact("тема", "змейка"), Fact("язык", "python")))
        )

        assertEquals(listOf(Fact("тема", "змейка"), Fact("язык", "python")), updated.facts)
        val reread = assertNotNull(store.get(session.id))
        assertEquals(2, reread.facts.size)

        // Повторный вызов заменяет список, а не дополняет
        store.setFacts(session.id, listOf(Fact("тема", "морской бой")))
        val reread2 = assertNotNull(store.get(session.id))
        assertEquals(listOf(Fact("тема", "морской бой")), reread2.facts)
    }

    @Test
    fun `setFacts пустым списком сбрасывает факты и на неизвестной сессии возвращает null`() {
        val store = newStore()
        val session = store.create()
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))

        val cleared = assertNotNull(store.setFacts(session.id, emptyList()))
        assertTrue(cleared.facts.isEmpty())

        assertNull(store.setFacts("нет-такого", listOf(Fact("тема", "змейка"))))
    }

    // ── Per-user скоупинг: create(userId), listForUser, deleteAllForUser ──

    @Test
    fun `create с userId хранит владельца, create без userId — сессия без владельца`() {
        val store = newStore()

        val owned = store.create(userId = "u1")
        val anonymous = store.create()

        assertEquals("u1", assertNotNull(store.get(owned.id)).userId)
        assertNull(assertNotNull(store.get(anonymous.id)).userId)
    }

    @Test
    fun `listForUser фильтрует сессии строго по владельцу`() {
        val store = newStore()
        val first = store.create(userId = "u1")
        store.create(userId = "u2")
        val third = store.create(userId = "u1")

        val owned = store.listForUser("u1")

        assertEquals(listOf(third.id, first.id), owned.map { it.id }, "сортировка как в list(): updatedAt по убыванию")
        assertEquals(1, store.listForUser("u2").size)
        assertTrue(store.listForUser("нет-такого").isEmpty())
    }

    @Test
    fun `listForUser не отдаёт сессии без владельца`() {
        val store = newStore()
        store.create()

        assertTrue(store.listForUser("u1").isEmpty())
    }

    @Test
    fun `deleteAllForUser удаляет только сессии своего владельца`() {
        val store = newStore()
        store.create(userId = "u1")
        val foreign = store.create(userId = "u2")
        store.create(userId = "u1")
        val legacy = store.create()

        val deleted = store.deleteAllForUser("u1")

        assertEquals(2, deleted)
        assertTrue(store.listForUser("u1").isEmpty())
        assertEquals(1, store.listForUser("u2").size)
        assertNotNull(store.get(foreign.id))
        // Сессии без userId (legacy) чужими руками не удаляются
        assertNotNull(store.get(legacy.id))
    }

    @Test
    fun `deleteAllForUser на пустом и неизвестном пользователе возвращает ноль`() {
        val store = newStore()

        assertEquals(0, store.deleteAllForUser("нет-такого"))
    }

    private fun taskState(step: String = "шаг", stage: TaskStage = TaskStage.PLANNING) =
        TaskState(stage = stage, step = step, expectedAction = "действие")

    @Test
    fun `taskState round-trip — create → updateTaskState → getTaskState → snapshot содержит состояние`() {
        val store = newStore()
        val session = store.create()
        val state = taskState("шаг 1")

        assertNull(store.getTaskState(session.id))
        assertNull(store.get(session.id)?.taskState)

        val updated = assertNotNull(store.updateTaskState(session.id, state))
        assertEquals(state, updated.taskState)
        assertEquals(state, store.getTaskState(session.id))
        assertEquals(state, assertNotNull(store.get(session.id)).taskState)
        assertEquals(state, store.list().single().taskState)
    }

    @Test
    fun `updateTaskState null сбрасывает состояние`() {
        val store = newStore()
        val session = store.create()
        store.updateTaskState(session.id, taskState())

        val cleared = assertNotNull(store.updateTaskState(session.id, null))

        assertNull(cleared.taskState)
        assertNull(store.getTaskState(session.id))
    }

    @Test
    fun `updateTaskState и getTaskState по неизвестному sessionId — no-op без исключения`() {
        val store = newStore()

        assertNull(store.updateTaskState("нет-такого", taskState()))
        assertNull(store.getTaskState("нет-такого"))
        assertTrue(store.list().isEmpty())
    }
}
