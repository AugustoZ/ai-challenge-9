package com.example.aiweb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Одно событие хода работы агента (для блока «Ход работы агента» и будущего SSE). */
@Serializable
sealed class AgentEvent {

    /** Номер шага цикла, начиная с 1. */
    abstract val step: Int

    /** Начало шага: модель обдумывает задачу. */
    @Serializable
    @SerialName("step_started")
    data class StepStarted(override val step: Int) : AgentEvent()

    /** Модель запросила вызов инструмента; arguments — JSON-строка аргументов. */
    @Serializable
    @SerialName("tool_call")
    data class ToolCalled(override val step: Int, val tool: String, val arguments: String) : AgentEvent()

    /** Результат вызова инструмента; content усечён до 500 символов (полный текст уходит модели). */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(override val step: Int, val tool: String, val ok: Boolean, val content: String) : AgentEvent()

    /** Промежуточный текст модели вместе с вызовом инструмента (редкий случай). */
    @Serializable
    @SerialName("assistant_message")
    data class AssistantMessage(override val step: Int, val text: String) : AgentEvent()

    /** Завершение работы: text — итоговый ответ, reason — готово | лимит шагов | дедлайн | зацикливание. */
    @Serializable
    @SerialName("final")
    data class Final(override val step: Int, val text: String, val reason: String) : AgentEvent()
}
