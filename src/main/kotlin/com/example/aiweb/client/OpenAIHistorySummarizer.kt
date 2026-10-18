package com.example.aiweb.client

import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult

/**
 * Реальный суммаризатор истории: оборачивает [LLMClient] (в продакшене — [AIClient]
 * того же провайдера, что и чат) и сжимает батч сообщений в сводку.
 *
 * Паттерн внечатового вызова — как у режима «Судья» (runJudge): отдельный
 * [LLMCall] с контекстом из [SummaryPrompts.buildMessages]; температура 0.1,
 * связанное рассуждение выключено; бюджет [maxTokens] уходит и в промпт
 * («Максимум N токенов»), и в лимит ответа. Токены вызова берутся из
 * [ChatResult.totalTokens] и попадают в [SummarizeResult.tokens] → поле
 * ChatResponse.summaryTokens.
 *
 * [previous] (существующая сводка или инструкция «сожми жёстче» из задачи 4)
 * включается в промпт buildMessages КАК ЕСТЬ — маркером «Сводка ранее».
 */
class OpenAIHistorySummarizer(
    private val aiClient: LLMClient,
    private val maxTokens: Int = DEFAULT_SUMMARY_MAX_TOKENS
) : HistorySummarizer {

    override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>): SummarizeResult {
        val result = aiClient.ask(
            LLMCall(
                messages = SummaryPrompts.buildMessages(previous, batch, maxTokens),
                temperature = SUMMARIZER_TEMPERATURE,
                maxTokens = maxTokens,
                thinkingEnabled = false
            )
        )
        return SummarizeResult(summary = result.reply, tokens = result.totalTokens)
    }

    companion object {
        /** Бюджет сводки по умолчанию (совпадает с дефолтом конфига). */
        const val DEFAULT_SUMMARY_MAX_TOKENS = 400

        /** Температура сводки — дешёвая и детерминированная, как у судьи. */
        private const val SUMMARIZER_TEMPERATURE = 0.1
    }
}
