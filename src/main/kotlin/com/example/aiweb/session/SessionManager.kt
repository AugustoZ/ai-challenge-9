package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
import java.util.concurrent.ConcurrentHashMap


class SessionManager(
    
    private val windowSize: Int = 10
) {

    class Session {
        
        val history = mutableListOf<OpenAIMessage>()
        var summary: String? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Добавляет сообщение пользователя в сессию.
     *
     * @return true, если сообщение принято; false — если это был бы повторный
     *         user подряд (последним ходом уже был user, ответа ассистента ещё нет).
     */
    fun addUserMessage(sessionId: String, content: String): Boolean {
        val session = sessions.computeIfAbsent(sessionId) { Session() }
        val last = session.history.lastOrNull()

        if (last != null && last.role == "user") {
            return false
        }
        session.history.add(OpenAIMessage(role = "user", content = content))
        return true
    }

    /** Добавляет ответ ассистента в сессию (сразу после user). */
    fun addAssistantMessage(sessionId: String, content: String) {
        val session = sessions.computeIfAbsent(sessionId) { Session() }
        session.history.add(OpenAIMessage(role = "assistant", content = content))
    }

    /** Индекс, с которого начинается скользящее окно (всегда с роли user). */
    private fun windowStart(size: Int): Int {
        var start = size - windowSize
        if (start < 0) start = 0
        if ((size - start) % 2 != 0) start++
        return start
    }

    /**
     * Сообщения для отправки модели: последние [windowSize] ходов из истории,
     * а также зафиксированное резюме более старой части как отдельное сообщение.
     * Окно всегда начинается с роли user (чётное число сообщений).
     *
     * @return список сообщений (system-резюме + скользящее окно последних ходов).
     */
    fun messagesForRequest(sessionId: String): List<OpenAIMessage> {
        val session = sessions[sessionId] ?: return emptyList()
        val result = mutableListOf<OpenAIMessage>()

        session.summary?.let {
            result.add(OpenAIMessage(role = "system", content = "Ранее было обсуждено: $it"))
        }

        val history = session.history
        result.addAll(history.subList(windowStart(history.size), history.size))
        return result
    }

    /**
     * Историю, которая «вывалилась» за окно, нужно сжать. Возвращает сообщения,
     * которые следует суммаризовать (ещё не включённые в текущее резюме).
     */
    fun messagesToSummarize(sessionId: String): List<OpenAIMessage> {
        val history = sessions[sessionId]?.history ?: return emptyList()
        val start = windowStart(history.size)
        return if (start > 0) history.subList(0, start) else emptyList()
    }

    /**
     * Сохраняет сжатое резюме для сессии и удаляет из истории сообщения,
     * которые попали в это резюме (сжатие — уменьшение потребления токенов).
     */
    fun setSummary(sessionId: String, summary: String) {
        val session = sessions.computeIfAbsent(sessionId) { Session() }
        val merged = listOfNotNull(session.summary, summary).joinToString(" ")
        session.summary = merged

        // Удаляем из истории все сообщения до начала текущего окна
        val history = session.history
        val start = windowStart(history.size)
        if (start > 0) {
            history.subList(0, start).clear()
        }
    }

    /** Простой идентификатор последнего хода (для проверки внутри API). */
    fun lastRole(sessionId: String): String? =
        sessions[sessionId]?.history?.lastOrNull()?.role

    /** Удаляет сессию (очистка памяти, если понадобится). */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }
}
