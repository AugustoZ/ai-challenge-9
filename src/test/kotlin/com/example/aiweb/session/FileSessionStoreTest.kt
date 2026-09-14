package com.example.aiweb.session

import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.TurnUsage
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
    fun `трейс агента на assistant-сообщении переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("напиши игру змейку и пришли файлом"))
        val trace = listOf(
            AgentEvent.StepStarted(1),
            AgentEvent.ToolCalled(1, "write_file", "{\"path\":\"snake.py\"}"),
            AgentEvent.ToolResult(1, "write_file", ok = true, content = "Файл snake.py записан"),
            AgentEvent.Final(1, "Готово: файл snake.py записан.", "готово")
        )
        store.append(
            session.id,
            OpenAIMessage(role = "assistant", content = "Готово: файл snake.py записан.", events = trace)
        )

        val loaded = assertNotNull(newStore(dir).get(session.id))

        assertEquals(2, loaded.messages.size)
        assertEquals(trace, loaded.messages[1].events)
        assertTrue(loaded.messages[0].events.isEmpty())
    }

    @Test
    fun `трейс пишется с дискриминатором type, пустой не пишется`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос"))
        store.append(
            session.id,
            OpenAIMessage(
                role = "assistant",
                content = "готово",
                events = listOf(AgentEvent.ToolCalled(1, "read_file", "{}"))
            )
        )

        val raw = File(dir, "sessions/${session.id}.json").readText()

        // Контракт фронтенда: {type:"tool_call", step, tool, arguments} — как в SSE
        assertTrue(raw.contains("\"type\":\"tool_call\""), "Файл: $raw")
        // encodeDefaults=false: пустой events user-сообщения в файл не попадает
        assertEquals(1, Regex("\"events\"").findAll(raw).count())
    }

    @Test
    fun `старый json-файл сессии без events читается пустым трейсом`() {
        val dir = newDir()
        val sessionsDir = File(dir, "sessions").apply { mkdirs() }
        File(sessionsDir, "old-1.json").writeText(
            """{"id":"old-1","messages":[{"role":"assistant","content":"старый ответ"}],"createdAt":1,"updatedAt":2}"""
        )

        val loaded = assertNotNull(newStore(dir).get("old-1"))

        assertEquals(1, loaded.messages.size)
        assertTrue(loaded.messages.single().events.isEmpty())
    }

    @Test
    fun `сессия со summary переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос"))
        store.append(session.id, assistant("ответ"))

        store.applyCompressionResult(session.id, "сводка диалога", 1)

        val loaded = assertNotNull(newStore(dir).get(session.id))
        assertEquals("сводка диалога", loaded.summary)
        assertEquals(1, loaded.messages.size)
        assertEquals("ответ", loaded.messages.single().content)
    }

    @Test
    fun `старый json-файл сессии без summary читается с summary null`() {
        val dir = newDir()
        val sessionsDir = File(dir, "sessions").apply { mkdirs() }
        File(sessionsDir, "old-2.json").writeText(
            """{"id":"old-2","messages":[{"role":"user","content":"старый вопрос"}],"createdAt":1,"updatedAt":2}"""
        )

        val loaded = assertNotNull(newStore(dir).get("old-2"))

        assertNull(loaded.summary)
        assertEquals(1, loaded.messages.size)
        assertEquals("старый вопрос", loaded.messages.single().content)
    }

    @Test
    fun `applyCompressionResult атомарен — файл на диске совпадает с памятью`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        repeat(10) { i -> store.append(session.id, user("ход $i")) }

        val updated = assertNotNull(store.applyCompressionResult(session.id, "сводка", 6))
        assertEquals(4, updated.messages.size)
        assertEquals("сводка", updated.summary)

        val clamped = assertNotNull(store.applyCompressionResult(session.id, "жёсткая сводка", 99))
        assertTrue(clamped.messages.isEmpty())

        val reloaded = assertNotNull(newStore(dir).get(session.id))
        assertEquals(clamped, reloaded)
        assertTrue(
            File(dir, "sessions/${session.id}.json").readText().contains("жёсткая сводка"),
            "Файл должен содержать summary"
        )
    }

    @Test
    fun `applyWindow обрезает историю и переживает пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        repeat(4) { i ->
            store.append(session.id, user("ход $i"))
            store.append(session.id, assistant("ответ $i"))
        }

        val updated = assertNotNull(store.applyWindow(session.id, 5))
        assertEquals(5, updated.messages.size)
        assertNull(updated.summary, "Окно не пишет сводку")

        val reloaded = assertNotNull(newStore(dir).get(session.id))
        assertEquals(updated, reloaded, "Подрезка записана на диск атомарно")
        assertEquals(
            listOf("ответ 1", "ход 2", "ответ 2", "ход 3"),
            reloaded.messages.dropLast(1).mapNotNull { it.content }
        )
    }

    @Test
    fun `applyWindow — история не длиннее окна — файл не меняется`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос"))
        store.append(session.id, assistant("ответ"))
        val before = assertNotNull(store.get(session.id))

        val updated = assertNotNull(store.applyWindow(session.id, 5))

        assertEquals(before, updated)
        val reloaded = assertNotNull(newStore(dir).get(session.id))
        assertEquals(before, reloaded)
        assertFalse(
            File(dir, "sessions").listFiles()!!.any { it.name.endsWith(".json.tmp") },
            "Временных файлов после no-op быть не должно"
        )
    }

    @Test
    fun `applyWindow не рвёт пару assistant(tool_calls) и role=tool`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(
            session.id,
            OpenAIMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(OpenAIToolCall(id = "call-1"))
            )
        )
        store.append(session.id, OpenAIMessage(role = "tool", content = "результат", toolCallId = "call-1"))
        store.append(session.id, user("вопрос 2"))
        store.append(session.id, assistant("ответ 2"))

        val updated = assertNotNull(store.applyWindow(session.id, 3))

        // Граница наехала на role="tool" — окно расширено назад до user-сообщения
        assertEquals("user", updated.messages.first().role)
        assertEquals("вопрос 1", updated.messages.first().content)
        updated.messages.forEachIndexed { index, message ->
            if (message.role == "tool") {
                val prev = updated.messages[index - 1]
                assertEquals("assistant", prev.role)
                assertNotNull(prev.toolCalls)
            }
        }

        val reloaded = assertNotNull(newStore(dir).get(session.id))
        assertEquals(updated, reloaded)
    }

    @Test
    fun `applyWindow на неизвестной сессии возвращает null`() {
        assertNull(newStore().applyWindow("нет-такого", 5))
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

    // ── Ветки и факты: персист и back-compat ──

    @Test
    fun `ветки и факты переживают пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.append(session.id, user("вопрос 2"))
        store.setFacts(session.id, listOf(Fact("тема", "змейка")))
        store.applyCompressionResult(session.id, "сводка", 0)
        store.forkTwin(session.id)
        store.append(session.id, assistant("ответ 2"))
        store.activateBranch(session.id, "b1")

        val loaded = assertNotNull(newStore(dir).get(session.id))

        assertEquals("b1", loaded.activeBranchId)
        assertEquals(3, loaded.messages.size)
        assertEquals(listOf(Fact("тема", "змейка")), loaded.facts)
        assertEquals(2, loaded.branches.size)
        assertEquals(2, loaded.branchSeq)

        // Активная ветка и её снимок в branches совпадают (зеркало)
        val b1 = loaded.branches.single { it.branchId == "b1" }
        assertEquals("Ветка 1", b1.label)
        assertEquals(2, b1.forkUserCount)
        assertEquals(loaded.messages, b1.messages)
        assertEquals("сводка", b1.summary)
        assertEquals(listOf(Fact("тема", "змейка")), b1.facts)

        // Прежняя активная сохранена отдельной веткой
        val b2 = loaded.branches.single { it.branchId == "b2" }
        assertEquals(4, b2.messages.size)
        assertEquals(0, b2.forkUserCount)
        assertEquals(2, b2.userCountSinceFork)
    }

    @Test
    fun `старый json-файл сессии без веток и фактов читается с пустыми значениями`() {
        val dir = newDir()
        val sessionsDir = File(dir, "sessions").apply { mkdirs() }
        File(sessionsDir, "old-3.json").writeText(
            """{"id":"old-3","messages":[{"role":"user","content":"старый вопрос"}],"createdAt":1,"updatedAt":2}"""
        )

        val store = newStore(dir)
        val loaded = assertNotNull(store.get("old-3"))

        assertTrue(loaded.branches.isEmpty())
        assertNull(loaded.activeBranchId)
        assertEquals(0, loaded.branchSeq)
        assertTrue(loaded.facts.isEmpty())

        // forkTwin на legacy-сессии работает
        val branch = assertNotNull(store.forkTwin("old-3"))
        assertEquals("b1", branch.branchId)
        assertEquals("Ветка 1", branch.label)
        assertEquals(listOf("старый вопрос"), branch.messages.mapNotNull { it.content })

        // После форка файл перезаписан и содержит ветки; временных файлов не осталось
        val raw = File(sessionsDir, "old-3.json").readText()
        assertTrue(raw.contains("\"branches\""), "Файл: $raw")
        assertTrue(raw.contains("\"branchSeq\":1"), "Файл: $raw")
        assertTrue(raw.contains("Ветка 1"), "Файл: $raw")
        assertFalse(
            sessionsDir.listFiles()!!.any { it.name.endsWith(".json.tmp") },
            "Временный файл должен быть переименован"
        )
        // Форк не переключает активную ветку: активной остаётся исходная история
        val reread = assertNotNull(newStore(dir).get("old-3"))
        assertNull(reread.activeBranchId)
        assertEquals(listOf("b1"), reread.branches.map { it.branchId })
    }

    @Test
    fun `activateBranch без изменений не создаёт дубликат-ветку и переживает пересоздание`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.forkTwin(session.id)

        val activated = assertNotNull(store.activateBranch(session.id, "b1"))

        assertEquals(1, activated.branches.size)
        assertEquals("b1", activated.activeBranchId)
        assertEquals(2, activated.messages.size)
        assertEquals(2, activated.capturedMessages)

        // Отпечаток и активная ветка персистентны — после перечитывания веток по-прежнему одна
        val reloaded = newStore(dir)
        val reread = assertNotNull(reloaded.get(session.id))
        assertEquals(1, reread.branches.size)
        assertEquals("b1", reread.activeBranchId)
        val again = assertNotNull(reloaded.activateBranch(session.id, "b1"))
        assertEquals(1, again.branches.size)
    }

    @Test
    fun `activateBranch при изменениях сворачивает состояние без потерь`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос 1"))
        store.append(session.id, assistant("ответ 1"))
        store.forkTwin(session.id)
        store.append(session.id, user("вопрос 2"))

        // Уход с изменённого состояния — свёртка в новую ветку
        val activated = assertNotNull(store.activateBranch(session.id, "b1"))
        assertEquals(2, activated.branches.size)
        val folded = activated.branches.single { it.branchId == "b2" }
        assertEquals(
            listOf("вопрос 1", "ответ 1", "вопрос 2"),
            folded.messages.mapNotNull { it.content }
        )

        // Обратное переключение к сфолдированному — дубликата нет; всё переживает перечитывание
        val reloaded = newStore(dir)
        val back = assertNotNull(reloaded.activateBranch(session.id, "b2"))
        assertEquals(2, back.branches.size)
        assertEquals("b2", back.activeBranchId)
        assertEquals(
            listOf("вопрос 1", "ответ 1", "вопрос 2"),
            back.messages.mapNotNull { it.content }
        )
    }

    @Test
    fun `показания топлива на assistant-сообщении переживают пересоздание хранилища`() {
        val dir = newDir()
        val store = newStore(dir)
        val session = store.create()
        store.append(session.id, user("вопрос"))
        store.append(
            session.id,
            OpenAIMessage(
                role = "assistant",
                content = "ответ",
                usage = TurnUsage(
                    elapsedMs = 42L,
                    promptTokens = 1,
                    completionTokens = 2,
                    totalTokens = 3,
                    summaryApplied = true,
                    summaryTokens = 7
                )
            )
        )

        // Показания читаются и без перечитывания, и из нового экземпляра (диск)
        for (source in listOfNotNull(store.get(session.id), newStore(dir).get(session.id))) {
            val restored = assertNotNull(
                source.messages.last { it.role == "assistant" }.usage,
                "usage должен пережить персист"
            )
            assertEquals(42L, restored.elapsedMs)
            assertEquals(1, restored.promptTokens)
            assertEquals(2, restored.completionTokens)
            assertEquals(3, restored.totalTokens)
            assertTrue(restored.summaryApplied)
            assertEquals(7, restored.summaryTokens)
        }
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

        // Изменённое состояние свернуть некуда — кап, сессия не меняется
        store.append(session.id, user("вопрос 2"))
        val before = assertNotNull(store.get(session.id))
        assertNull(store.activateBranch(session.id, "b2"))
        assertEquals(before, assertNotNull(store.get(session.id)))
    }
}
