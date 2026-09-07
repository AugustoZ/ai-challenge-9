package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Серверная память диалога: выдаёт идентификаторы сессий и копит историю сообщений.
 * История ведётся только у одиночных режимов (default/direct/step_by_step);
 * «Команда» и Prompt-to-Prompt stateless — сессию не создают и в неё не пишут.
 */
interface SessionStore {
    /** Создаёт пустую сессию с новым уникальным идентификатором. */
    fun create(): Session

    /** Возвращает сессию по идентификатору; null, пустая строка или неизвестный id — null. */
    fun get(id: String?): Session?

    /** Дописывает сообщение в историю сессии; неизвестный идентификатор игнорируется. */
    fun append(sessionId: String, message: OpenAIMessage)

    /** Удаляет сессию; вызов для неизвестного идентификатора ошибкой не является (идемпотентно). */
    fun delete(id: String)
}

/**
 * Доменная модель сессии диалога. System-сообщения (промты режимов, бюджет ответа)
 * в истории не живут — они добавляются к запросу в момент его сборки, поэтому
 * здесь хранятся только ходы диалога: user, assistant и role="tool".
 */
@Serializable
data class Session(
    val id: String,
    val messages: List<OpenAIMessage> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
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

        private const val ROLE_USER = "user"
    }

    /** Изменяемое внутреннее состояние сессии; [Session] — его снимок. */
    private class Entry(
        val id: String,
        val createdAt: Long
    ) {
        val messages = mutableListOf<OpenAIMessage>()

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
            trimOverflow(entry.messages)
            entry.updatedAt = now()
        }
    }

    override fun delete(id: String) {
        if (id.isNullOrBlank()) return
        sessions.remove(id.trim())
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

    /**
     * Обрезает историю одной сессии до [MAX_MESSAGES]: лишние старые ходы
     * сбрасываются целиком, срез проходит только перед user-сообщением.
     * Если границ user-сообщений не осталось (вырожденная история), кап
     * нарушается — резать дальше, рискуя разорвать пару
     * assistant(tool_calls) → role="tool", нельзя.
     */
    private fun trimOverflow(messages: MutableList<OpenAIMessage>) {
        while (messages.size > MAX_MESSAGES) {
            // Первое user-сообщение со второй позиции — начало второго хода;
            // всё, что до него, удаляется одним куском
            val nextTurn = messages.subList(1, messages.size).indexOfFirst { it.role == ROLE_USER } + 1
            if (nextTurn == 0) return
            messages.subList(0, nextTurn).clear()
        }
    }

    /** Потокобезопасный снимок записи в неизменяемую [Session]. */
    private fun Entry.snapshot(): Session = synchronized(this) {
        Session(
            id = id,
            messages = messages.toList(),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
