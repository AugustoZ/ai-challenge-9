package com.example.aiweb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Кадр SSE-потока /api/chat/stream. */
@Serializable
sealed class StreamEvent {

    /** Обёртка события агента; payload — то же событие, что в ChatResponse.events. */
    @Serializable
    @SerialName("agent")
    data class Agent(val event: AgentEvent) : StreamEvent()

    /** Текстовый статус этапа для не-агентных режимов («Судья проверяет ответ…»). */
    @Serializable
    @SerialName("status")
    data class Status(val text: String) : StreamEvent()

    /** Checkpoint: авто-форк создал близнец-ветку (стратегия branching); поток НЕ завершает. */
    @Serializable
    @SerialName("checkpoint")
    data class Checkpoint(
        val branchId: String,
        val label: String,
        val forkUserCount: Int
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
