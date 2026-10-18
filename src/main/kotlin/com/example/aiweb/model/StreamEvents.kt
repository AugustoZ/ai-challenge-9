package com.example.aiweb.model

import com.example.aiweb.task.TaskState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Кадр SSE-потока /api/chat/stream. */
@Serializable
sealed class StreamEvent {

    /** Обёртка события агента; payload — то же событие, что в ChatResponse.events. */
    @Serializable
    @SerialName("agent")
    data class Agent(val event: AgentEvent) : StreamEvent()

    /** Текстовый статус этапа обработки («Судья проверяет ответ…»). */
    @Serializable
    @SerialName("status")
    data class Status(
        val text: String,
        /** Состояние задачи на момент старта хода; приходит только в раннем кадре
         * «задача заведена» — кнопка «Пауза» активна уже во время генерации.
         * null/absent — состояние не менялось. */
        val taskState: TaskState? = null,
        /** id сессии для только что созданной задачи; приходит в раннем кадре
         * «задача заведена» при первом ходе, когда клиент ещё не знает id.
         * null/absent — id не менялся. */
        val sessionId: String? = null
    ) : StreamEvent()

    /** Полный ответ: тот же ChatResponse, что вернул бы /api/chat (events уже внутри). */
    @Serializable
    @SerialName("final")
    data class Final(val response: ChatResponse) : StreamEvent()

    /** Прогон отменён по /api/cancel. */
    @Serializable
    @SerialName("cancelled")
    data class Cancelled(val requestId: String) : StreamEvent()

    /** Ошибка обработки (аналог 500 в стриме). */
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : StreamEvent()
}
