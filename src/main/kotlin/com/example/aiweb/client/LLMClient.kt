package com.example.aiweb.client

import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolSpec

/**
 * Контракт обращения к LLM: шов для подмены клиента фейком в тестах.
 * Реализация по умолчанию — [AIClient] (openai/opencode протоколы).
 */
interface LLMClient {
    /** Отправляет готовый контекст модели и возвращает результат обращения. */
    suspend fun ask(call: LLMCall): ChatResult
}

/** Параметры одного обращения к LLM; null — параметр не задан (берётся из конфигурации провайдера). */
data class LLMCall(
    val messages: List<OpenAIMessage>,
    val model: String? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: String? = null,
    val thinkingEnabled: Boolean? = null,
    val tools: List<OpenAIToolSpec>? = null
)
