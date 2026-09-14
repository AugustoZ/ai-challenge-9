package com.example.aiweb.session

import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Серверная память диалога: выдаёт идентификаторы сессий и копит историю сообщений.
 * Сессия создаётся на каждый запрос; user/assistant пишутся после ответа через
 * finalizeResponse.
 */
interface SessionStore {
    /** Создаёт пустую сессию с новым уникальным идентификатором. */
    fun create(): Session

    /** Возвращает сессию по идентификатору; null, пустая строка или неизвестный id — null. */
    fun get(id: String?): Session?

    /** Дописывает сообщение в историю сессии; неизвестный идентификатор игнорируется. */
    fun append(sessionId: String, message: OpenAIMessage)

    /**
     * Атомарно применяет результат компрессии истории: устанавливает сводку
     * [newSummary] (null — сбрасывает) и удаляет первые [removeCount] сообщений
     * (consume-семантика: слитые в сводку ходы из истории исчезают).
     *
     * [removeCount] обрезается до размера истории: запрос удалить больше сообщений,
     * чем есть, ошибкой не является — удаляется всё. Возвращает обновлённый
     * снимок сессии; null — сессия не существует.
     */
    fun applyCompressionResult(sessionId: String, newSummary: String?, removeCount: Int): Session?

    /**
     * Деструктивная подрезка хранилища под окно (стратегия «Окно»): в истории
     * остаются только последние [windowSize] сообщений, всё за окном отбрасывается
     * безвозвратно. Turn-boundary guard тот же, что у view-окна
     * ([HistoryCompressor.sliceWindow]): если срез начинается с role="tool" (его
     * assistant(tool_calls) остался за границей), окно расширяется назад до
     * ближайшего user-сообщения — пара assistant(tool_calls) → role="tool"
     * не разрывается. История короче окна — no-op. Сводка ([Session.summary]),
     * факты и ветки операцией не затрагиваются («Окно» не пишет сводку).
     *
     * Возвращает обновлённый снимок сессии; null — сессия не существует.
     */
    fun applyWindow(sessionId: String, windowSize: Int): Session?

    /**
     * Создаёт близнец-ветку: снимок активного состояния сессии (messages, summary,
     * facts) в новую ветку с id «b<seq>» и label «Ветка <seq>» (нумерация сквозная,
     * из [Session.branchSeq]). Активное состояние не меняется; у близнеца
     * [Branch.userCountSinceFork] = 0.
     *
     * Возвращает созданную ветку; null — сессия не существует или достигнут кап
     * [InMemorySessionStore.MAX_BRANCHES] веток (форк пропускается).
     */
    fun forkTwin(sessionId: String): Branch?

    /**
     * Переключает активное состояние сессии на снимок ветки [branchId]: содержимое
     * ветки (messages, summary, facts) становится активным. Прежнее активное
     * состояние сохраняется как новая ветка «b<seq>»/«Ветка <seq>» ТОЛЬКО если оно
     * изменилось с момента последнего захвата в ветку (форк, свёртка или активация):
     * неизменённое состояние уже хранится снимком ветки, и дубликат плодить нельзя.
     * Счётчики сворачиваемого состояния переносятся в его новую запись
     * ([Branch.userCountSinceFork] = user-сообщения активной минус провенанс её
     * происхождения). [Session.activeBranchId] переключается на [branchId].
     *
     * Возвращает обновлённую сессию; null — сессия или ветка не существует
     * (без мутации), либо изменившееся состояние некуда свернуть — достигнут кап
     * [InMemorySessionStore.MAX_BRANCHES] веток. Повторная активация уже активной
     * ветки состояние не меняет.
     */
    fun activateBranch(sessionId: String, branchId: String): Session?

    /**
     * Заменяет список фактов активного состояния сессии. Возвращает обновлённую
     * сессию; null — сессия не существует.
     */
    fun setFacts(sessionId: String, facts: List<Fact>): Session?

    /** Снимок всех сессий, отсортированных по updatedAt по убыванию. */
    fun list(): List<Session>

    /** Удаляет сессию; вызов для неизвестного идентификатора ошибкой не является (идемпотентно). */
    fun delete(id: String)

    /** Стирает все сессии; возвращает число удалённых. */
    fun clear(): Int
}

/**
 * Активное состояние изменилось с момента последнего захвата в ветку:
 * число сообщений, summary или факты не совпадают с отпечатком [Session].
 * Расхождение — единственный повод сворачивать состояние в новую ветку
 * при переключении; неизменённое уже хранится снимком ветки.
 */
internal fun Session.activeDiverged(): Boolean =
    messages.size != capturedMessages ||
        summary != capturedSummary ||
        facts != capturedFacts

/**
 * Доменная модель сессии диалога. System-сообщения (промт агента, бюджет ответа)
 * в истории не живут — они добавляются к запросу в момент его сборки, поэтому
 * здесь хранятся только ходы диалога: user, assistant и role="tool".
 */
@Serializable
data class Session(
    val id: String,
    val messages: List<OpenAIMessage> = emptyList(),
    /** Rolling-summary более старой истории (компрессия); null — сжатие ещё не выполнялось. */
    val summary: String? = null,
    /** Близнец-ветки сессии — плоский набор снимков; у legacy-сессий пусто. */
    val branches: List<Branch> = emptyList(),
    /** Идентификатор активной ветки; null — активна исходная история (legacy-сессии). */
    val activeBranchId: String? = null,
    /** Порядковый номер последней созданной ветки; из него растут id «b<seq>». */
    val branchSeq: Int = 0,
    /** Выделенные факты диалога (стратегия «Факты») активного состояния. */
    val facts: List<Fact> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Отпечаток активного состояния на момент последнего захвата в ветку
     * (форк, свёртка при переключении или активация): число сообщений, summary
     * и факты. Переключение ветки сворачивает прежнее активное состояние в новую
     * ветку только при расхождении с отпечатком — иначе снимок уже хранится
     * в ветке, и дубликат не создаётся. У legacy-сессий нулевой — первое же
     * переключение сворачивает состояние (прежнее «своп без потерь» сохранено).
     */
    val capturedMessages: Int = 0,
    val capturedSummary: String? = null,
    val capturedFacts: List<Fact> = emptyList()
)

/**
 * Близнец-ветка сессии: снимок состояния (messages, summary, facts) на момент
 * создания. Ветки плоские, без дерева; id «b1», «b2», …, label «Ветка 1», …
 *
 * [forkUserCount] — провенанс содержимого: число user-сообщений в состоянии,
 * из которого создана эта запись ветки. [userCountSinceFork] — user-сообщения,
 * добавленные в ветку после её создания; для активной ветки счётчик выводится:
 * user-сообщения активной минус [forkUserCount] активной ветки (для исходной
 * истории — минус ноль). Запись ветки, развёрнутой в активное состояние,
 * хранит её снимок на момент активации («зеркало»).
 */
@Serializable
data class Branch(
    val branchId: String,
    val label: String,
    val forkUserCount: Int,
    val userCountSinceFork: Int,
    val messages: List<OpenAIMessage>,
    val summary: String? = null,
    val facts: List<Fact> = emptyList(),
    val createdAt: Long
)

/**
 * Потокобезопасная реализация [SessionStore] в памяти процесса.
 *
 * Ограничения:
 *  - не более [MAX_SESSIONS] сессий: лишние выселяются по старости (updatedAt);
 *  - не более [MAX_MESSAGES] сообщений в сессии: лишние старые ходы сбрасываются
 *    целиком, от границы user-сообщения — пара assistant(tool_calls) → role="tool"
 *    не разрывается.
 *
 * Реализация рассчитана на P0; P1-персистентность = новая реализация интерфейса
 * без правки маршрутов.
 */
class InMemorySessionStore(
    /** Источник времени — шов для детерминированных тестов выселения. */
    private val now: () -> Long = System::currentTimeMillis
) : SessionStore {

    companion object {
        /** Максимальное число живых сессий; лишние выселяются по старости (updatedAt). */
        const val MAX_SESSIONS = 50

        /** Жёсткий кап сообщений одной сессии. */
        const val MAX_MESSAGES = 200

        /** Кап числа веток одной сессии; форк сверх капа пропускается (null). */
        const val MAX_BRANCHES = 8
    }

    /** Изменяемое внутреннее состояние сессии; [Session] — его снимок. */
    private class Entry(
        val id: String,
        val createdAt: Long
    ) {
        val messages = mutableListOf<OpenAIMessage>()

        @Volatile
        var summary: String? = null

        @Volatile
        var branches: List<Branch> = emptyList()

        @Volatile
        var activeBranchId: String? = null

        @Volatile
        var branchSeq: Int = 0

        @Volatile
        var facts: List<Fact> = emptyList()

        /** Отпечаток захвата активного состояния (см. [Session.capturedMessages]). */
        @Volatile
        var capturedMessages: Int = 0

        @Volatile
        var capturedSummary: String? = null

        @Volatile
        var capturedFacts: List<Fact> = emptyList()

        @Volatile
        var updatedAt: Long = createdAt
    }

    private val sessions = ConcurrentHashMap<String, Entry>()

    override fun create(): Session {
        val entry = Entry(id = UUID.randomUUID().toString(), createdAt = now())
        sessions[entry.id] = entry
        evictOldest()
        return entry.snapshot()
    }

    override fun get(id: String?): Session? {
        if (id.isNullOrBlank()) return null
        return sessions[id.trim()]?.snapshot()
    }

    override fun append(sessionId: String, message: OpenAIMessage) {
        val entry = sessions[sessionId] ?: return
        synchronized(entry) {
            entry.messages.add(message)
            trimSessionHistory(entry.messages)
            entry.updatedAt = now()
        }
    }

    override fun applyCompressionResult(sessionId: String, newSummary: String?, removeCount: Int): Session? {
        val entry = sessions[sessionId] ?: return null
        return synchronized(entry) {
            entry.messages.subList(0, removeCount.coerceIn(0, entry.messages.size)).clear()
            entry.summary = newSummary
            entry.updatedAt = now()
            entry.snapshot()
        }
    }

    override fun applyWindow(sessionId: String, windowSize: Int): Session? {
        val entry = sessions[sessionId] ?: return null
        return synchronized(entry) {
            val before = entry.messages.size
            truncateToWindow(entry.messages, windowSize)
            if (entry.messages.size != before) entry.updatedAt = now()
            entry.snapshot()
        }
    }

    override fun forkTwin(sessionId: String): Branch? {
        val entry = sessions[sessionId] ?: return null
        return synchronized(entry) {
            if (entry.branches.size >= MAX_BRANCHES) return@synchronized null
            val seq = entry.branchSeq + 1
            val branch = Branch(
                branchId = "b$seq",
                label = "Ветка $seq",
                forkUserCount = entry.messages.count { it.role == ROLE_USER },
                userCountSinceFork = 0,
                messages = entry.messages.toList(),
                summary = entry.summary,
                facts = entry.facts.toList(),
                createdAt = now()
            )
            entry.branches = entry.branches + branch
            entry.branchSeq = seq
            // Форк захватывает активное состояние снимком — отпечаток совпадает
            entry.capturedMessages = entry.messages.size
            entry.capturedSummary = entry.summary
            entry.capturedFacts = entry.facts.toList()
            entry.updatedAt = now()
            branch
        }
    }

    override fun activateBranch(sessionId: String, branchId: String): Session? {
        val entry = sessions[sessionId] ?: return null
        return synchronized(entry) {
            val branch = entry.branches.firstOrNull { it.branchId == branchId }
                ?: return@synchronized null
            if (branchId == entry.activeBranchId) return@synchronized entry.snapshot()

            // Свёртка прежнего состояния нужна только при расхождении с отпечатком:
            // неизменённое уже хранится снимком ветки, дубликат плодить нельзя.
            val diverged = entry.messages.size != entry.capturedMessages ||
                entry.summary != entry.capturedSummary ||
                entry.facts != entry.capturedFacts
            if (diverged && entry.branches.size >= MAX_BRANCHES) return@synchronized null

            // Провенанс активного состояния: ветка, из которой оно было развёрнуто
            // (для исходной истории — пустая сессия, провенанс нулевой)
            val activeOriginFork = entry.activeBranchId
                ?.let { activeId -> entry.branches.firstOrNull { it.branchId == activeId }?.forkUserCount }
                ?: 0
            val activeUserCount = entry.messages.count { it.role == ROLE_USER }
            val seq = entry.branchSeq + 1

            // Прежнее активное состояние сохраняется как новая ветка — своп без потерь
            if (diverged) {
                val folded = Branch(
                    branchId = "b$seq",
                    label = "Ветка $seq",
                    forkUserCount = activeOriginFork,
                    userCountSinceFork = activeUserCount - activeOriginFork,
                    messages = entry.messages.toList(),
                    summary = entry.summary,
                    facts = entry.facts.toList(),
                    createdAt = now()
                )
                entry.branches = entry.branches + folded
                entry.branchSeq = seq
            }
            // Активным становится снимок выбранной ветки; он захвачен самой веткой
            entry.messages.clear()
            entry.messages.addAll(branch.messages)
            entry.summary = branch.summary
            entry.facts = branch.facts
            entry.activeBranchId = branchId
            entry.capturedMessages = branch.messages.size
            entry.capturedSummary = branch.summary
            entry.capturedFacts = branch.facts.toList()
            entry.updatedAt = now()
            entry.snapshot()
        }
    }

    override fun setFacts(sessionId: String, facts: List<Fact>): Session? {
        val entry = sessions[sessionId] ?: return null
        return synchronized(entry) {
            entry.facts = facts.toList()
            entry.updatedAt = now()
            entry.snapshot()
        }
    }

    override fun list(): List<Session> =
        sessions.values.map { it.snapshot() }.sortedByDescending { it.updatedAt }

    override fun delete(id: String) {
        if (id.isNullOrBlank()) return
        sessions.remove(id.trim())
    }

    override fun clear(): Int {
        val count = sessions.size
        sessions.clear()
        return count
    }

    /** Выселяет старейшие по updatedAt сессии при превышении лимита [MAX_SESSIONS]. */
    private fun evictOldest() {
        val excess = sessions.size - MAX_SESSIONS
        if (excess <= 0) return
        sessions.entries
            .sortedBy { it.value.updatedAt }
            .take(excess)
            .forEach { sessions.remove(it.key) }
    }

    /** Потокобезопасный снимок записи в неизменяемую [Session]. */
    private fun Entry.snapshot(): Session = synchronized(this) {
        Session(
            id = id,
            messages = messages.toList(),
            summary = summary,
            branches = branches,
            activeBranchId = activeBranchId,
            branchSeq = branchSeq,
            facts = facts,
            createdAt = createdAt,
            updatedAt = updatedAt,
            capturedMessages = capturedMessages,
            capturedSummary = capturedSummary,
            capturedFacts = capturedFacts
        )
    }
}

/**
 * Выбирает реализацию [SessionStore] в точке сборки маршрутов: без каталога данных
 * (null/пусто/пробелы) — память процесса [InMemorySessionStore] (P0-поведение),
 * с каталогом — файловое хранилище [FileSessionStore] (P1-персистентность,
 * `<dataDir>/sessions/<id>.json`). Маршруты при замене реализации не меняются.
 */
fun sessionStoreFor(dataDir: String?): SessionStore =
    if (dataDir.isNullOrBlank()) InMemorySessionStore() else FileSessionStore(dataDir)

/**
 * Обрезает историю одной сессии до [InMemorySessionStore.MAX_MESSAGES]: лишние
 * старые ходы сбрасываются целиком, срез проходит только перед user-сообщением.
 * Если границ user-сообщений не осталось (вырожденная история), кап нарушается —
 * резать дальше, рискуя разорвать пару assistant(tool_calls) → role="tool", нельзя.
 *
 * Общая логика [InMemorySessionStore] и [FileSessionStore].
 */
internal fun trimSessionHistory(messages: MutableList<OpenAIMessage>) {
    while (messages.size > InMemorySessionStore.MAX_MESSAGES) {
        // Первое user-сообщение со второй позиции — начало второго хода;
        // всё, что до него, удаляется одним куском
        val nextTurn = messages.subList(1, messages.size).indexOfFirst { it.role == ROLE_USER } + 1
        if (nextTurn == 0) return
        messages.subList(0, nextTurn).clear()
    }
}

/**
 * Деструктивная подрезка истории одной сессии до последних [windowSize]
 * сообщений (стратегия «Окно», store-операция [SessionStore.applyWindow]):
 * всё за окном отбрасывается безвозвратно. Граница среза считается тем же
 * turn-boundary guard, что у view-окна ([HistoryCompressor.sliceWindow]):
 * окно не начинается с role="tool", чей assistant(tool_calls) остался за
 * границей, — такое окно расширяется назад до ближайшего user-сообщения.
 * История короче окна (или равна) — no-op. Сводка/факты/ветки не трогаются.
 *
 * Общая логика [InMemorySessionStore] и [FileSessionStore].
 */
internal fun truncateToWindow(messages: MutableList<OpenAIMessage>, windowSize: Int) {
    val kept = HistoryCompressor.sliceWindow(messages, windowSize.coerceAtLeast(1))
    val cut = messages.size - kept.size
    if (cut > 0) messages.subList(0, cut).clear()
}

internal const val ROLE_USER = "user"
