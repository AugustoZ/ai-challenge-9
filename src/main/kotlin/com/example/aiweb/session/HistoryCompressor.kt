package com.example.aiweb.session

import com.example.aiweb.client.SummaryPrompts
import com.example.aiweb.model.OpenAIMessage

/** Результат одного вызова суммаризатора: текст сводки и (если провайдер отдал usage) токены. */
data class SummarizeResult(val summary: String, val tokens: Int?)

/**
 * Шов для суммаризации истории: реализация (задача 6) обёрнёт AIClient,
 * в тестах — детерминированный фейк без сети.
 */
interface HistorySummarizer {
    /**
     * Сжимает [batch] в сводку, дополняя её к существующей [previous] (null — сводки ещё нет).
     *
     * Повторный вызов при превышении лимита [HistoryCompressor] передаёт в [previous]
     * инструкцию [SummaryPrompts.RETRY_INSTRUCTION] и черновик oversized-сводки —
     * реализация должна включить текст [previous] в промпт как есть.
     */
    suspend fun summarize(previous: String?, batch: List<OpenAIMessage>): SummarizeResult
}

/**
 * Итог компрессии: новая сводка и количество сообщений, которое вызывающий
 * обязан снять из начала истории (store-операция задачи 6, контракт T3).
 */
data class MergeResult(val newSummary: String, val removeCount: Int, val tokens: Int?)

/**
 * Чистая логика компрессии истории диалога: скользящее окно последних
 * [windowSize] сообщений + периодическая сводка накопившегося внеоконного.
 *
 * Правила:
 *  - merge срабатывает, когда вне окна накопилось ≥ [mergeEvery] сообщений;
 *  - один merge закрывает ВСЕ накопившиеся внеоконные сообщения одним вызовом
 *    суммаризатора (дизайн-решение 5 — не N вызовов по одному сообщению);
 *  - батч суммаризации предварительно обрезается до [SUMMARY_BATCH_TOKEN_BUDGET]
 *    токенов, резать можно только перед user-сообщением — пара
 *    assistant(tool_calls) → role="tool" не разрывается (дизайн-решение 6);
 *  - сводка длиннее [summaryMaxTokens] токенов получает ОДИН повторный вызов с
 *    инструкцией «сожми жёстче»; при повторном превышении — жёсткая обрезка
 *    текста по границе предложений;
 *  - [merge] НЕ перехватывает исключения суммаризатора: решение о деградации
 *    (продолжить без компрессии, отключить её) принимает вызывающий (задача 6);
 *  - все функции чистые: входные списки не мутируются, «съедение» сообщений из
 *    истории делает store-операция вызывающего.
 */
class HistoryCompressor(
    val windowSize: Int,
    val mergeEvery: Int,
    val summaryMaxTokens: Int,
    private val summarizer: HistorySummarizer,
    private val estimator: (OpenAIMessage) -> Int = SessionHistory::estimateTokens
) {

    companion object {
        /** Бюджет батча суммаризации в оценочных токенах (дизайн-решение: ~12k). */
        const val SUMMARY_BATCH_TOKEN_BUDGET = 12_000

        private const val ROLE_USER = "user"
        private const val ROLE_TOOL = "tool"

        /**
         * Последние [windowSize] сообщений [messages]. Turn-boundary guard: если срез
         * начинается с role="tool" (его assistant(tool_calls) остался за границей),
         * срез расширяется назад до ближайшего user-сообщения — пара не разрывается,
         * и провайдер не увидит осиротевшего tool (паттерн [SessionHistory.trim]).
         *
         * Свободная функция (companion): используется и компрессором, и планировщиком
         * контекста [ContextPlanner] для стратегий WINDOW/FACTS.
         */
        fun sliceWindow(messages: List<OpenAIMessage>, windowSize: Int): List<OpenAIMessage> {
            if (messages.size <= windowSize) return messages.toList()

            var start = messages.size - windowSize
            if (messages[start].role == ROLE_TOOL) {
                val userStart = messages.subList(0, start).indexOfLast { it.role == ROLE_USER }
                if (userStart >= 0) {
                    start = userStart
                } else {
                    // user до среза нет — история дефектная: отбрасываем подвешенный
                    // начало по тому же гварду, что и в SessionHistory.trim
                    while (start < messages.size && messages[start].role != ROLE_USER) start++
                }
            }
            return messages.subList(start, messages.size).toList()
        }
    }

    /** Сколько сообщений находится вне последних [windowSize]. */
    fun beyondWindowCount(messages: List<OpenAIMessage>): Int =
        (messages.size - windowSize).coerceAtLeast(0)

    /**
     * Последние [windowSize] сообщений. Turn-boundary guard: если срез начинается
     * с role="tool" (его assistant(tool_calls) остался за границей), срез
     * расширяется назад до ближайшего user-сообщения — пара не разрывается,
     * и провайдер не увидит осиротевшего tool (паттерн [SessionHistory.trim]).
     */
    fun sliceWindow(messages: List<OpenAIMessage>): List<OpenAIMessage> =
        sliceWindow(messages, windowSize)

    /**
     * View запроса к модели: опциональная сводка как system-сообщение + окно.
     * История сессии при этом не трогается.
     */
    fun planView(summary: String?, messages: List<OpenAIMessage>): List<OpenAIMessage> {
        val window = sliceWindow(messages)
        if (summary.isNullOrBlank()) return window
        return listOf(OpenAIMessage(role = "system", content = summary)) + window
    }

    /**
     * Сжимает накопившееся вне окна в сводку.
     *
     * @return [MergeResult] (removeCount = сколько сообщений снять из начала истории)
     *   или null, если порога [mergeEvery] ещё не накопилось.
     * @throws Exception — исключения суммаризатора пробрасываются вызывающему;
     *   деградация без компрессии — его решение (задача 6).
     */
    suspend fun merge(summary: String?, messages: List<OpenAIMessage>): MergeResult? {
        val beyond = beyondWindowCount(messages)
        if (beyond < mergeEvery) return null

        val batch = preTrim(messages.subList(0, beyond).toList())
        val first = summarizer.summarize(summary, batch)
        if (summaryTokens(first.summary) <= summaryMaxTokens) {
            return MergeResult(first.summary, beyond, first.tokens)
        }

        // Один повторный вызов: инструкция и черновик oversized-сводки уезжают
        // в previous (у интерфейса суммаризатора больше нет слотов).
        val retryPrevious = buildString {
            summary?.let { append(it); append('\n') }
            append(SummaryPrompts.RETRY_INSTRUCTION)
            append(": сводка превысила лимит ($summaryMaxTokens токенов), сожми короче, сохрани главное. Черновик:")
            append('\n')
            append(first.summary)
        }
        val second = summarizer.summarize(retryPrevious, batch)

        val finalSummary =
            if (summaryTokens(second.summary) <= summaryMaxTokens) second.summary
            else hardCut(second.summary)
        return MergeResult(finalSummary, beyond, second.tokens)
    }

    /**
     * Обрезает батч до [SUMMARY_BATCH_TOKEN_BUDGET], отбрасывая старейшие ходы
     * целиком: резать можно только перед user-сообщением, свежий хвост сохраняется
     * всегда (алгоритм [SessionHistory.trim], перенесён на бюджет батча).
     */
    private fun preTrim(batch: List<OpenAIMessage>): List<OpenAIMessage> {
        if (batch.isEmpty()) return emptyList()

        var start = batch.indexOfLast { it.role == ROLE_USER }
        if (start < 0) return emptyList()

        var used = batch.subList(start, batch.size).sumOf { estimator(it) }
        while (start > 0) {
            val prevTurn = batch.subList(0, start).indexOfLast { it.role == ROLE_USER }
            if (prevTurn < 0) break
            val turnCost = batch.subList(prevTurn, start).sumOf { estimator(it) }
            if (used + turnCost > SUMMARY_BATCH_TOKEN_BUDGET) break
            used += turnCost
            start = prevTurn
        }
        while (start < batch.size && batch[start].role != ROLE_USER) start++
        return batch.subList(start, batch.size).toList()
    }

    /** Оценка сводки в токенах единственным допустимым эстиматором. */
    private fun summaryTokens(text: String): Int =
        estimator(OpenAIMessage(role = "system", content = text))

    /**
     * Жёсткая обрезка сводки, повторным вызовом не ужатой до [summaryMaxTokens]:
     * режем текст до лимита символов (limit = maxTokens * 2.5 — обратная сторона
     * делителя эстиматора), откатываясь к ближайшему знаку конца предложения.
     */
    private fun hardCut(text: String): String {
        if (summaryTokens(text) <= summaryMaxTokens) return text
        val limitChars = (summaryMaxTokens * 2.5).toInt()
        if (text.length <= limitChars) return text
        val candidate = text.substring(0, limitChars)
        val boundary = maxOf(
            candidate.lastIndexOf('.'),
            candidate.lastIndexOf('!'),
            candidate.lastIndexOf('?'),
            candidate.lastIndexOf('\n')
        )
        return if (boundary > 0) candidate.substring(0, boundary + 1) else candidate
    }
}
