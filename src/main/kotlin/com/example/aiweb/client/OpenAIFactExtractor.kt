package com.example.aiweb.client

import com.example.aiweb.model.Fact

/**
 * Шов экстракции фактов: отдельный внечатовой вызов LLM обновляет KV-факты
 * диалога по новому сообщению пользователя. null = сбой (не найден/не разобран
 * JSON) — вызывающий сохраняет прежние факты; транспортные исключения НЕ
 * глотаются — пробрасываются наверх (решение о деградации — за вызывающим,
 * паттерн HistoryCompressor).
 */
interface FactExtractor {

    /**
     * Возвращает обновлённый список фактов (максимум [FactPrompts.MAX_FACTS])
     * или null, если ответ модели не удалось разобрать.
     */
    suspend fun extract(current: List<Fact>, userMessage: String): List<Fact>?
}

/**
 * Реальный экстрактор фактов: оборачивает [LLMClient] (в продакшене — [AIClient]
 * того же провайдера, что и чат). Паттерн внечатового вызова — как у
 * [OpenAIHistorySummarizer] и runJudge: отдельный [LLMCall] с контекстом из
 * [FactPrompts.buildMessages], температура 0.1, рассуждение выключено.
 * Модель отдельно не выбирается — используется модель провайдера из конфига.
 */
class OpenAIFactExtractor(
    private val aiClient: LLMClient
) : FactExtractor {

    override suspend fun extract(current: List<Fact>, userMessage: String): List<Fact>? {
        val result = aiClient.ask(
            LLMCall(
                messages = FactPrompts.buildMessages(current, userMessage),
                temperature = EXTRACTOR_TEMPERATURE,
                maxTokens = FACTS_MAX_TOKENS,
                thinkingEnabled = false
            )
        )
        return FactPrompts.parseFacts(result.reply)
    }

    companion object {
        /** Бюджет ответа экстракции фактов. */
        const val FACTS_MAX_TOKENS = 300

        /** Температура экстракции — детерминированная, как у судьи и сводки. */
        private const val EXTRACTOR_TEMPERATURE = 0.1
    }
}
