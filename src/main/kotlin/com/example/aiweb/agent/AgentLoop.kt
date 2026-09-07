package com.example.aiweb.agent

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.tools.ToolRegistry
import com.example.aiweb.tools.ToolResult
import com.example.aiweb.tools.ToolTransport

/**
 * Итог агентного прогона: финальный ответ, причина завершения и суммарные токены
 * всех обращений к модели за цикл. Причины: «готово», «лимит шагов», «дедлайн», «зацикливание».
 */
data class AgentRunResult(
    val reply: String,
    val reason: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)

/**
 * Агентный цикл «Фазы 3»: модель и инструменты итерируют до финального ответа.
 *
 * Цикл живёт в корутине вызывающего (suspend, без GlobalScope) — отмена запроса P1
 * подключается отменой Job. События хода работы отдаются в sink [onEvent] (не списком):
 * заготовка под SSE — P1 начнёт стримить тот же поток. Правила цикла:
 *  - падение инструмента не прерывает прогон: модель получает ошибку ролью "tool"
 *    и корректирует действия (правило Фазы 2);
 *  - бюджет шага — [maxTokens], делённый на [AgentLoop.MAX_STEPS] (по образцу режима «Команда»);
 *  - гварды: дедлайн [AgentLoop.AGENT_DEADLINE_MS] до начала каждого шага, лимит шагов,
 *    детект зацикливания по [AgentLoop.LOOP_THRESHOLD] одинаковым вызовам подряд;
 *    по любому гварду — принудительный финал: один вызов клиента без инструментов
 *    с системной заметкой «собери ответ из уже собранного».
 *
 * Системный промт агента принадлежит этому пакету ([SYSTEM_PROMPT]); ReasoningPrompts
 * не затрагивается. В [seed] Routes передаёт обрезанную историю сессии и цель пользователя
 * ([OpenAIMessage] role="user"); system-промт цикл добавляет сам.
 *
 * @param client     обращение к LLM (нативный вызов используется только принудительным финалом)
 * @param transport  способ доставки инструментов — нативный или промт-протокол, по supportsTools
 * @param registry   доступные инструменты; реестр общий на весь прогон
 */
class AgentLoop(
    private val client: LLMClient,
    private val transport: ToolTransport,
    private val registry: ToolRegistry
) {

    /**
     * Прогоняет агентный цикл до финального ответа или срабатывания гварда.
     *
     * @param seed    контекст без system-промта: история сессии + user-сообщение с целью
     * @param maxTokens  «Топливо» из интерфейса; делится на шаги, null — без бюджета
     * @param onEvent sink событий хода работы; вызывается по порядку, suspend — заготовка SSE
     * @param model   модель из запроса (как в остальных режимах); null — модель провайдера
     * @param temperature / topP / stop / thinkingEnabled — параметры из ChatRequest
     * @return финальный ответ, причина завершения и суммарные токены всех обращений
     */
    suspend fun run(
        seed: List<OpenAIMessage>,
        maxTokens: Int?,
        onEvent: suspend (AgentEvent) -> Unit,
        model: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        stop: String? = null,
        thinkingEnabled: Boolean? = null
    ): AgentRunResult {
        val perStepBudget = maxTokens?.let { it / MAX_STEPS }
        val deadlineAt = System.currentTimeMillis() + AGENT_DEADLINE_MS
        val sums = TokenSums()

        var conversation: List<OpenAIMessage> =
            listOf(OpenAIMessage(role = "system", content = SYSTEM_PROMPT)) + seed
        var lastAssistantText = ""
        var lastSignature: String? = null
        var repeatCount = 0
        var step = 0

        /**
         * Принудительный финал по гварду (лимит шагов / зацикливание): один вызов клиента
         * без инструментов, с системной заметкой собрать ответ из уже добытых данных.
         */
        suspend fun forcedFinal(reason: String): AgentRunResult {
            val result = client.ask(
                LLMCall(
                    messages = conversation +
                        OpenAIMessage(role = "system", content = FORCED_FINAL_NOTE),
                    model = model,
                    maxTokens = perStepBudget,
                    temperature = temperature,
                    topP = topP,
                    stop = stop,
                    thinkingEnabled = thinkingEnabled,
                    tools = null
                )
            )
            sums.add(result)
            onEvent(AgentEvent.Final(step, result.reply, reason))
            return AgentRunResult(result.reply, reason, sums.prompt, sums.completion, sums.total)
        }

        while (true) {
            step++

            // Дедлайн проверяется до начала шага: 8 шагов по 60 с провайдера
            // не должны показывать спиннер минутами. Промежуточный текст модели —
            // лучший доступный ответ.
            if (System.currentTimeMillis() > deadlineAt) {
                val reply = lastAssistantText
                onEvent(AgentEvent.Final(step, reply, REASON_DEADLINE))
                return AgentRunResult(reply, REASON_DEADLINE, sums.prompt, sums.completion, sums.total)
            }

            // Лимит шагов: все шаги ушли на вызовы инструментов — их результаты уже
            // в контексте, дальше спрашиваем финальный ответ без инструментов.
            if (step > MAX_STEPS) {
                return forcedFinal(REASON_STEP_LIMIT)
            }

            onEvent(AgentEvent.StepStarted(step))
            val result = transport.ask(
                LLMCall(
                    messages = conversation,
                    model = model,
                    maxTokens = perStepBudget,
                    temperature = temperature,
                    topP = topP,
                    stop = stop,
                    thinkingEnabled = thinkingEnabled
                ),
                client,
                registry
            )
            sums.add(result)

            // Обычный текст без вызовов — модель закончила работу
            if (result.toolCalls.isEmpty()) {
                onEvent(AgentEvent.Final(step, result.reply, REASON_DONE))
                return AgentRunResult(result.reply, REASON_DONE, sums.prompt, sums.completion, sums.total)
            }

            if (result.reply.isNotBlank()) {
                lastAssistantText = result.reply
                onEvent(AgentEvent.AssistantMessage(step, result.reply))
            }
            // Assistant-сообщение обязано нести toolCalls: иначе пары
            // assistant(tool_calls) → role="tool" разорвутся в контексте
            conversation += OpenAIMessage(
                role = "assistant",
                content = result.reply.ifBlank { null },
                toolCalls = result.toolCalls
            )

            // Детект зацикливания: подпись шага — все пары (tool, args) этого ответа;
            // считаем только подряд идущие одинаковые
            val signature = result.toolCalls.joinToString(SIGNATURE_SEPARATOR) { toolCall ->
                (toolCall.function?.name ?: "") + SIGNATURE_SEPARATOR + (toolCall.function?.arguments ?: "")
            }
            repeatCount = if (signature == lastSignature) repeatCount + 1 else 1
            lastSignature = signature

            for (toolCall in result.toolCalls) {
                val name = toolCall.function?.name.orEmpty()
                val args = toolCall.function?.arguments.orEmpty()
                onEvent(AgentEvent.ToolCalled(step, name, args))
                val tool = registry.get(name)
                val outcome = if (tool == null) {
                    ToolResult(ok = false, content = "Неизвестный инструмент: $name")
                } else {
                    runCatching { tool.execute(args) }.fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            ToolResult(ok = false, content = "Инструмент упал: ${error.message}")
                        }
                    )
                }
                onEvent(AgentEvent.ToolResult(step, name, outcome.ok, outcome.content.take(EVENT_CONTENT_LIMIT)))
                // Модель получает полный текст результата; усечение — только для события
                conversation += OpenAIMessage(role = "tool", content = outcome.content, toolCallId = toolCall.id)
            }

            // Третий одинаковый вызов подряд — принудительный финал; вызов исполнен,
            // поэтому контекст остаётся валидным (нет «висячих» assistant tool_calls)
            if (repeatCount >= LOOP_THRESHOLD) {
                return forcedFinal(REASON_LOOP)
            }
        }
    }

    /** Накопитель токенов по всем обращениям к модели; null, пока API не вернул ни одного значения. */
    private class TokenSums {
        var prompt: Int? = null
            private set
        var completion: Int? = null
            private set
        var total: Int? = null
            private set

        fun add(result: ChatResult) {
            prompt = prompt.plusNullable(result.promptTokens)
            completion = completion.plusNullable(result.completionTokens)
            total = total.plusNullable(result.totalTokens)
        }
    }

    companion object {
        /** Максимум шагов цикла с инструментами; дальше — принудительный финал. */
        const val MAX_STEPS = 8

        /** Общий дедлайн прогона, мс (~3 минуты): иначе 8 шагов × таймаут провайдера = 8+ минут спиннера. */
        const val AGENT_DEADLINE_MS = 180_000L

        /** Ограничение содержимого в событии ToolResult; полный текст уходит модели. */
        const val EVENT_CONTENT_LIMIT = 500

        /** Сколько подряд идущих одинаковых вызовов (tool, args) считаются зацикливанием. */
        const val LOOP_THRESHOLD = 3

        /** Системный промт агента: живёт в agent/, ReasoningPrompts не затрагивается. */
        private val SYSTEM_PROMPT = """
            Ты — бортовой агент «Штурмана». Ты самостоятельно разбиваешь задачу на шаги
            и решаешь её, пользуясь доступными инструментами. Правила:
            - сначала составь короткий план: какие данные нужны для ответа и какими инструментами их добыть;
            - вызывай инструмент, когда точные данные (дата, вычисления, веб-страница) важнее догадки;
            - проверяй результат каждого вызова, прежде чем опираться на него в ответе;
            - если инструмент вернул ошибку — меняй подход (другие аргументы, другой инструмент),
              а не повторяй тот же вызов;
            - когда данных достаточно — дай финальный ответ обычным текстом, без вызова инструментов.
        """.trimIndent()

        /** Системная заметка перед принудительным финалом по гварду. */
        private const val FORCED_FINAL_NOTE =
            "Инструменты больше недоступны — дай финальный ответ на основе собранной информации"

        /** Причина завершения: модель дала финальный ответ сама. */
        private const val REASON_DONE = "готово"

        /** Причина завершения: исчерпан лимит шагов. */
        private const val REASON_STEP_LIMIT = "лимит шагов"

        /** Причина завершения: превышен общий дедлайн прогона. */
        private const val REASON_DEADLINE = "дедлайн"

        /** Причина завершения: модель повторяет один и тот же вызов инструмента. */
        private const val REASON_LOOP = "зацикливание"

        /** Разделитель подписи зацикливания; \u0000 не встречается в текстах моделей. */
        private const val SIGNATURE_SEPARATOR = "\u0000"
    }
}

/** Складывает значения с допуском null: результат null, только если оба слагаемых null. */
private fun Int?.plusNullable(other: Int?): Int? =
    if (this == null && other == null) null else (this ?: 0) + (other ?: 0)
