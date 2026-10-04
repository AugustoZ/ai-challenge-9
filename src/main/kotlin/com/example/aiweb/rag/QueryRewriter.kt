package com.example.aiweb.rag

import com.example.aiweb.client.LLMClient
import com.example.aiweb.client.LLMCall
import com.example.aiweb.model.OpenAIMessage
import kotlinx.coroutines.withTimeout

/**
 * Контракт LLM-рерайта поискового запроса для полнотекстового поиска.
 * Fail-open: любой сбой (таймаут, сеть, пустой ответ) → возврат исходного запроса,
 * чтобы переписывание никогда не роняло поиск.
 */
interface QueryRewriter {
    /** Переписывает [query]; при любом сбое возвращает исходный [query] как есть. */
    suspend fun rewrite(query: String): String
}

/**
 * Реализация через существующий AI-клиент — тот же шов [LLMClient], что в handleAgent
 * (RunJudge, Routes.kt:2511-2548). Отдельный клиент НЕ создаётся: переиспользуем
 * [LLMClient.ask], как это делает остальной код.
 *
 * Промпт — константная русская инструкция ([REWRITE_SYSTEM_PROMPT]); ответ модели
 * прогоняется через [clean]. Весь вызов обёрнут в `runCatching` + `withTimeout`
 * (fail-open, симметрично [RagService]): таймаут/ошибка → исходный запрос + warn в stderr.
 *
 * @param client существующий AI-клиент (LLMClient).
 * @param timeoutMs таймаут обращения к LLM в миллисекундах. Дефолт [DEFAULT_TIMEOUT_MS];
 *        T9 прокидывает сюда ragConfig.rewriteTimeoutMs.
 */
class LLMQueryRewriter(
    private val client: LLMClient,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : QueryRewriter {

    override suspend fun rewrite(query: String): String {
        val cleaned = runCatching {
            withTimeout(timeoutMs) {
                val result = client.ask(LLMCall(
                    messages = buildMessages(query),
                    temperature = TEMPERATURE,
                    maxTokens = MAX_TOKENS
                ))
                clean(result.reply)
            }
        }
        return cleaned.fold(
            onSuccess = { rewritten ->
                if (rewritten.isBlank()) {
                    System.err.println("Предупреждение: рерайт вернул пустой ответ, оставляю исходный запрос")
                    query
                } else {
                    // Серверный лог пары «оригинал → переписанный»: только в лог,
                    // НИКОГДА в ChatResponse/UI (guardrail плана, T5/T9).
                    System.err.println("Переписанный запрос: \"$query\" → \"$rewritten\"")
                    rewritten
                }
            },
            onFailure = { error ->
                System.err.println("Предупреждение: рерайт запроса не удался: ${error.message}")
                query
            }
        )
    }

    /** system-инструкция + исходный запрос пользователя. */
    private fun buildMessages(query: String): List<OpenAIMessage> =
        listOf(
            OpenAIMessage(role = "system", content = REWRITE_SYSTEM_PROMPT),
            OpenAIMessage(role = "user", content = query)
        )

    /**
     * Пост-обработка ответа модели: берётся первая строка, снимаются внешние кавычки
     * (одинарные/двойные). Пустой результат → пустая строка; случай обрабатывается в
     * [rewrite] возвратом оригинала.
     */
    internal fun clean(raw: String): String {
        val firstLine = raw.split('\n')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.let { stripQuotes(it) }
            ?: ""
        return firstLine
    }

    /** Снимает парные внешние кавычки (или «'»), если они есть. */
    private fun stripQuotes(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    companion object {
        /**
         * Константная русская инструкция рерайта. Точную подстроку
         * «переписываешь поисковый запрос» копирует в себя mock-llm (задача 7,
         * детект rewrite-режима) — формулировку менять нельзя без синхронной правки мока.
         */
        const val REWRITE_SYSTEM_PROMPT =
            "Ты переписываешь поисковый запрос пользователя для полнотекстового поиска по документации. " +
                "Верни ТОЛЬКО переписанный запрос одной строкой, без пояснений."

        /** Дефолтный таймаут рерайта (T9 прокидывает ragConfig.rewriteTimeoutMs = 8000). */
        const val DEFAULT_TIMEOUT_MS = 8_000L

        /** Температура рерайта: 0 — детерминированный ответ. */
        const val TEMPERATURE = 0.0

        /** Небольшой лимит токенов: ответ — одна строка. */
        const val MAX_TOKENS = 100
    }
}
