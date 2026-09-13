package com.example.aiweb.session

import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Файловая реализация [SessionStore] (P1-персистентность): сессии живут в
 * `<dir>/sessions/<id>.json` и переживают перезапуск процесса.
 *
 * Ограничения повторяют [InMemorySessionStore]: не более [MAX_SESSIONS] сессий
 * (лишние выселяются по старости — вместе с файлами на диске) и жёсткий кап
 * [InMemorySessionStore.MAX_MESSAGES] сообщений со срезом по границе user-хода.
 *
 * Устройство:
 *  - при старте все файлы читаются сразу; битые/нечитаемые пропускаются
 *    с предупреждением в System.err (по образцу AppConfig.loadFile);
 *  - запись атомарная: сперва `<id>.json.tmp`, затем переименование
 *    (ATOMIC_MOVE, с fallback на обычный move) — обрыв процесса не оставляет
 *    в файле половину JSON;
 *  - идентификатор сессии всегда генерируется здесь (UUID), поэтому путь
 *    файла никогда не строится из клиентского ввода;
 *  - изменяющие операции синхронизированы по хранилищу, а [Session]
 *    неизменяема — читатели всегда получают согласованный снимок.
 */
class FileSessionStore(
    /** Каталог данных (AI_DATA_DIR); подкаталог sessions создаётся при необходимости. */
    private val dir: String,
    /** Источник времени — шов для детерминированных тестов выселения. */
    private val now: () -> Long = System::currentTimeMillis
) : SessionStore {

    private val sessionsDir = File(dir, SESSIONS_DIR)
    private val sessions = ConcurrentHashMap<String, Session>()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        loadAll()
    }

    @Synchronized
    override fun create(): Session {
        val timestamp = now()
        val session = Session(
            id = UUID.randomUUID().toString(),
            createdAt = timestamp,
            updatedAt = timestamp
        )
        sessions[session.id] = session
        persist(session)
        evictOldest()
        return session
    }

    override fun get(id: String?): Session? {
        if (id.isNullOrBlank()) return null
        return sessions[id.trim()]
    }

    @Synchronized
    override fun append(sessionId: String, message: OpenAIMessage) {
        val current = sessions[sessionId] ?: return
        val messages = current.messages.toMutableList()
        messages.add(message)
        trimSessionHistory(messages)
        val updated = current.copy(messages = messages, updatedAt = now())
        sessions[sessionId] = updated
        persist(updated)
    }

    @Synchronized
    override fun applyCompressionResult(sessionId: String, newSummary: String?, removeCount: Int): Session? {
        val current = sessions[sessionId] ?: return null
        val messages = current.messages.drop(removeCount.coerceIn(0, current.messages.size))
        val updated = current.copy(messages = messages, summary = newSummary, updatedAt = now())
        sessions[sessionId] = updated
        persist(updated)
        return updated
    }

    @Synchronized
    override fun forkTwin(sessionId: String): Branch? {
        val current = sessions[sessionId] ?: return null
        if (current.branches.size >= InMemorySessionStore.MAX_BRANCHES) return null
        val seq = current.branchSeq + 1
        val branch = Branch(
            branchId = "b$seq",
            label = "Ветка $seq",
            forkUserCount = current.messages.count { it.role == ROLE_USER },
            userCountSinceFork = 0,
            messages = current.messages,
            summary = current.summary,
            facts = current.facts,
            createdAt = now()
        )
        val updated = current.copy(
            branches = current.branches + branch,
            branchSeq = seq,
            updatedAt = now()
        )
        sessions[sessionId] = updated
        persist(updated)
        return branch
    }

    @Synchronized
    override fun activateBranch(sessionId: String, branchId: String): Session? {
        val current = sessions[sessionId] ?: return null
        val branch = current.branches.firstOrNull { it.branchId == branchId } ?: return null
        if (branchId == current.activeBranchId) return current
        if (current.branches.size >= InMemorySessionStore.MAX_BRANCHES) return null

        // Провенанс активного состояния: ветка, из которой оно было развёрнуто
        // (для исходной истории — пустая сессия, провенанс нулевой)
        val activeOriginFork = current.activeBranchId
            ?.let { activeId -> current.branches.firstOrNull { it.branchId == activeId }?.forkUserCount }
            ?: 0
        val activeUserCount = current.messages.count { it.role == ROLE_USER }
        val seq = current.branchSeq + 1

        // Прежнее активное состояние сохраняется как новая ветка — своп без потерь
        val updated = current.copy(
            messages = branch.messages,
            summary = branch.summary,
            facts = branch.facts,
            branches = current.branches + Branch(
                branchId = "b$seq",
                label = "Ветка $seq",
                forkUserCount = activeOriginFork,
                userCountSinceFork = activeUserCount - activeOriginFork,
                messages = current.messages,
                summary = current.summary,
                facts = current.facts,
                createdAt = now()
            ),
            branchSeq = seq,
            activeBranchId = branchId,
            updatedAt = now()
        )
        sessions[sessionId] = updated
        persist(updated)
        return updated
    }

    @Synchronized
    override fun setFacts(sessionId: String, facts: List<Fact>): Session? {
        val current = sessions[sessionId] ?: return null
        val updated = current.copy(facts = facts.toList(), updatedAt = now())
        sessions[sessionId] = updated
        persist(updated)
        return updated
    }

    @Synchronized
    override fun list(): List<Session> =
        sessions.values.sortedByDescending { it.updatedAt }

    @Synchronized
    override fun delete(id: String) {
        if (id.isNullOrBlank()) return
        val trimmed = id.trim()
        sessions.remove(trimmed)
        deleteFile(trimmed)
    }

    @Synchronized
    override fun clear(): Int {
        val count = sessions.size
        sessions.keys.forEach { deleteFile(it) }
        sessions.clear()
        return count
    }

    /**
     * Читает все файлы сессий с диска: свежие (по updatedAt) — первыми,
     * излишки сверх [MAX_SESSIONS] выселяются вместе с файлами, битые
     * файлы пропускаются с предупреждением.
     */
    private fun loadAll() {
        val files = sessionsDir
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?: emptyArray()

        val loaded = mutableListOf<Session>()
        for (file in files) {
            try {
                loaded += json.decodeFromString(Session.serializer(), file.readText())
            } catch (e: Exception) {
                System.err.println("Предупреждение: не удалось прочитать файл сессии '${file.name}': ${e.message}")
            }
        }

        loaded.sortByDescending { it.updatedAt }
        loaded.take(MAX_SESSIONS).forEach { sessions[it.id] = it }
        loaded.drop(MAX_SESSIONS).forEach { deleteFile(it.id) }
    }

    /** Выселяет старейшие по updatedAt сессии при превышении лимита, удаляя их файлы. */
    private fun evictOldest() {
        val excess = sessions.size - MAX_SESSIONS
        if (excess <= 0) return
        sessions.values
            .sortedBy { it.updatedAt }
            .take(excess)
            .forEach { stale ->
                sessions.remove(stale.id)
                deleteFile(stale.id)
            }
    }

    /** Атомарно пишет сессию на диск (tmp + rename); сбой записи не роняет запрос. */
    private fun persist(session: Session) {
        try {
            sessionsDir.mkdirs()
            val target = File(sessionsDir, session.id + EXTENSION)
            val tmp = File(sessionsDir, session.id + TMP_EXTENSION)
            tmp.writeText(json.encodeToString(Session.serializer(), session))
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось сохранить сессию '${session.id}': ${e.message}")
        }
    }

    /** Удаляет файл сессии; отсутствие файла ошибкой не является (идемпотентно). */
    private fun deleteFile(id: String) {
        runCatching { File(sessionsDir, id + EXTENSION).delete() }
    }

    companion object {
        /** Подкаталог внутри каталога данных, где живут файлы сессий. */
        private const val SESSIONS_DIR = "sessions"

        /** Расширение файла сессии. */
        private const val EXTENSION = ".json"

        /** Расширение временного файла атомарной записи. */
        private const val TMP_EXTENSION = ".json.tmp"

        /** Максимальное число живых сессий — как в [InMemorySessionStore]. */
        val MAX_SESSIONS = InMemorySessionStore.MAX_SESSIONS
    }
}
