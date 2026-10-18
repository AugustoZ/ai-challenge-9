package com.example.aiweb.session

import com.example.aiweb.model.ContextStrategy
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage

/** Размер окна истории для стратегии FACTS (дизайн-решение: последние 3 сообщения). */
private const val FACTS_WINDOW = 3

/** Заголовок system-блока фактов (стратегия FACTS). */
private const val FACTS_HEADER = "Известные факты о диалоге:"

/**
 * Чистый планировщик контекста запроса (задача 4): собирает вид истории для
 * модели по стратегии [ContextStrategy]. История сессии никогда не урезается —
 * view только формирует срез запроса; капы поверх view применяет вызывающий
 * ([SessionHistory.trim] — стадия-2 в handleAgent).
 *
 * Стратегии:
 *  - OFF, BRANCHING → [messages] как есть (полное активное состояние);
 *  - WINDOW → [HistoryCompressor.sliceWindow] последних [windowSize] с
 *    turn-boundary guard; сводка игнорируется;
 *  - SUMMARY → планView компрессора ([HistoryCompressor.planView]): сводка
 *    system-сообщением + окно; компрессор null (дефект сборки) — деградация к окну;
 *  - FACTS → system-блок фактов + последние [FACTS_WINDOW] сообщения;
 *    пустые факты — пустой вклад.
 *
 * Сводка и факты взаимно независимы: WINDOW/FACTS сводку не подставляют,
 * OFF/BRANCHING игнорируют и факты.
 */
fun view(
    strategy: ContextStrategy,
    summary: String?,
    messages: List<OpenAIMessage>,
    facts: List<Fact>,
    windowSize: Int,
    compressor: HistoryCompressor?
): List<OpenAIMessage> = when (strategy) {
    ContextStrategy.OFF, ContextStrategy.BRANCHING -> messages
    ContextStrategy.WINDOW -> HistoryCompressor.sliceWindow(messages, windowSize)
    ContextStrategy.SUMMARY ->
        compressor?.planView(summary, messages) ?: HistoryCompressor.sliceWindow(messages, windowSize)
    ContextStrategy.FACTS -> factBlock(facts) + HistoryCompressor.sliceWindow(messages, FACTS_WINDOW)
}

/**
 * System-блок фактов диалога: заголовок [FACTS_HEADER] и строки «- <key>: <value>».
 * Пустой список фактов — пустой вклад (сообщение не создаётся).
 */
fun factBlock(facts: List<Fact>): List<OpenAIMessage> {
    if (facts.isEmpty()) return emptyList()
    val content = buildString {
        append(FACTS_HEADER)
        facts.forEach { fact ->
            append('\n')
            append("- ")
            append(fact.key)
            append(": ")
            append(fact.value)
        }
    }
    return listOf(OpenAIMessage(role = "system", content = content))
}
