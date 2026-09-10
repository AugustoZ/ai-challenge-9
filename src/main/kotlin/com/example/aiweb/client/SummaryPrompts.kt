package com.example.aiweb.client

import com.example.aiweb.model.OpenAIMessage

/**
 * Компрессия истории: накопившиеся внеоконные сообщения сжимаются отдельным
 * вызовом LLM в краткую сводку, которая подставляется в контекст как
 * system-сообщение. Паттерн промптов — как у [JudgePrompts]: system-инструкция
 * на русском + собранный user-контекст.
 */
object SummaryPrompts {

    /**
     * Фраза, ОБЯЗАТЕЛЬНО присутствующая в system-промпте суммаризации:
     * mock-LLM (задача 5) по ней распознаёт суммаризационный запрос
     * и возвращает заготовленную сводку (дизайн-решение 15).
     */
    const val PREVIOUS_SUMMARY_MARKER = "Сводка ранее"

    /** Инструкция повторного сжимания, когда первая сводка превысила лимит. */
    const val RETRY_INSTRUCTION = "Сожми жёстче"

    private val SYSTEM_TEMPLATE = """
        Ты — редактор сводки диалога.
        Сводка ранее — краткий конспект прошлой части диалога; передаётся вместе с новыми
        сообщениями, если он уже существует.
        Составь одну обновлённую краткую сводку по переданным сообщениям и, если она есть,
        по сводке ранее.
        Сохрани: факты, принятые решения, имена, числовые значения и незавершённые намерения.
        Не выдумывай ничего, чего нет в переданных данных.
        Ответь одним текстом без markdown-разметки. Максимум %d токенов.
    """.trimIndent()

    /** System-промпт суммаризации с подставленным лимитом токенов. */
    fun systemPrompt(maxTokens: Int): String = SYSTEM_TEMPLATE.format(maxTokens)

    /**
     * Запрос к модели: system-инструкция + сводка ранее (если есть) + батч сообщений.
     */
    fun buildMessages(previous: String?, batch: List<OpenAIMessage>, maxTokens: Int): List<OpenAIMessage> {
        val user = buildString {
            previous?.let {
                append(PREVIOUS_SUMMARY_MARKER)
                append(":\n")
                append(it)
                append("\n\n")
            }
            append("Сообщения диалога:\n")
            batch.forEach { append(renderMessage(it)).append('\n') }
            append("Составь обновлённую сводку.")
        }
        return listOf(
            OpenAIMessage(role = "system", content = systemPrompt(maxTokens)),
            OpenAIMessage(role = "user", content = user)
        )
    }

    /**
     * Повторный запрос, когда первая сводка превысила лимит: черновик +
     * инструкция [RETRY_INSTRUCTION].
     */
    fun buildRetryMessages(
        previous: String?,
        batch: List<OpenAIMessage>,
        firstSummary: String,
        maxTokens: Int
    ): List<OpenAIMessage> {
        val user = buildString {
            previous?.let {
                append(PREVIOUS_SUMMARY_MARKER)
                append(":\n")
                append(it)
                append("\n\n")
            }
            append("Черновик сводки:\n")
            append(firstSummary)
            append('\n')
            append(RETRY_INSTRUCTION)
            append(": новая сводка должна уложиться в $maxTokens токенов, сохрани главное.")
        }
        return listOf(
            OpenAIMessage(role = "system", content = systemPrompt(maxTokens)),
            OpenAIMessage(role = "user", content = user)
        )
    }

    private fun renderMessage(message: OpenAIMessage): String {
        val body = when {
            message.content != null -> message.content
            message.toolCalls != null -> "[вызов инструментов]"
            else -> ""
        }
        return "${message.role}: $body"
    }
}
