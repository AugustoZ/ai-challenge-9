package com.example.aiweb.agent

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.client.ReasoningPrompts
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.SessionHistory
import com.example.aiweb.tools.ToolRegistry
import com.example.aiweb.tools.ToolResult
import com.example.aiweb.tools.ToolTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Итог агентного прогона: финальный ответ, причина завершения и суммарные токены
 * всех обращений к модели за цикл. Причины: «готово», «лимит шагов», «дедлайн»,
 * «зацикливание», «ответ обрезан».
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
 *  - лимит ответа шага — [maxTokens] как есть: «Топливо» из интерфейса — это лимит
 *    одного ответа модели, а не всего прогона; число обращений ограничивают
 *    [MAX_STEPS] и дедлайн, а не деление топлива на шаги;
 *  - топливо доносится до модели не только параметром API, но и словами: к системному
 *    промту добавляется указание уложиться в лимит (как в одиночных режимах),
 *    иначе модель не планирует объём, пишет длинно и обрывается по лимиту токенов;
     *  - tool-результат входит в контекст цикла ограниченно ([TOOL_CONTENT_LIMIT] символов,
     *    начало и хвост): страница или дамп на мегабайты иначе переотправлялись бы
     *    целиком на каждом шаге и сжигали токены провайдера;
     *  - объём отправляемого контекста ограничен [MAX_CONTEXT_TOKENS] токенов (оценка
     *    SessionHistory.estimateTokens): срез неразрушающий, при переполнении старые
     *    группы «assistant с вызовами + его tool-результаты» вытесняются целиком,
     *    пары не рвутся, цель пользователя сохраняется всегда;
     *  - гварды: дедлайн [AGENT_DEADLINE_MS] до начала каждого шага, лимит шагов,
     *    детект зацикливания по [LOOP_THRESHOLD] одинаковым вызовам подряд и по
     *    [SAME_TOOL_FAIL_THRESHOLD] падениям одного инструмента подряд (перебор
     *    аргументов), обрыв генерации по лимиту токенов (finishReason == "length") —
     *    текст усечён и не годится в финальный ответ;
     *    первое срабатывание гварда зацикливания — не финал, а кик: системная заметка
     *    [LOOP_KICK_NOTE] и свежие счётчики ([LOOP_KICK_LIMIT] кик за прогон);
     *    повторное срабатывание — принудительный финал;
     *    по прочим гвардам — принудительный финал: один вызов клиента без инструментов
     *    с бюджетом [maxTokens] и системной заметкой
     *    «собери ответ из уже собранного».
 *
 * Системный промт агента принадлежит этому пакету ([SYSTEM_PROMPT]); из ReasoningPrompts
 * берётся только общий для всех режимов текст указания лимита ([ReasoningPrompts.budgetNote]) —
 * сам объект не меняется. В [seed] Routes передаёт обрезанную историю сессии и цель пользователя
 * ([OpenAIMessage] role="user"); system-промт цикл добавляет сам.
 *
 * Контрольная точка прерванного паузой прогона (checkpoint «Продолжить»): при отмене
 * Job (POST /api/cancel с saveContext=true) отмена ловится как [CancellationException],
 * дельта прогона чинится ([repairDelta]) и однократно отдаётся в [run]'s onCancelled —
 * вызывающий сохраняет её в сессию (PausedRun). При возобновлении [run]'s resumeContext
 * подставляется в контекст сразу после [system]+seed — модель продолжает с места
 * остановки, не повторяя уже выполненные вызовы; resumeContext в дельту не входит.
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
     * @param maxTokens  «Топливо» из интерфейса — лимит ответа одного шага; доносится
     *                   до модели и параметром API, и системной инструкцией; null — без бюджета
     * @param onEvent sink событий хода работы; вызывается по порядку, suspend — заготовка SSE
     * @param model   модель из запроса (как в остальных режимах); null — модель провайдера
     * @param temperature / topP / stop / thinkingEnabled — параметры из ChatRequest
     * @param onCancelled вызывается ОДИН раз при отмене прогона (CancellationException)
     *                    с чиненой дельтой прогона (conversation без [system]+seed и без
     *                    [resumeContext]); вызывается под NonCancellable, затем исключение
     *                    перебрасывается — checkpoint паузы сохраняется, запрос всё равно отменяется
     * @param resumeContext сообщения, добавляемые в контекст СРАЗУ после [system]+seed
     *                      (до старта цикла) — продолжение прерванного паузой прогона;
     *                      null/пусто — обычный прогон с нуля
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
        thinkingEnabled: Boolean? = null,
        onCancelled: ((List<OpenAIMessage>) -> Unit)? = null,
        resumeContext: List<OpenAIMessage>? = null
    ): AgentRunResult {
        val deadlineAt = System.currentTimeMillis() + AGENT_DEADLINE_MS
        val sums = TokenSums()

        // Топливо доносится до модели и словами: без предупреждения модель не планирует объём и обрывается по лимиту токенов
        val systemPrompt = ReasoningPrompts.budgetNote(maxTokens)
            ?.let { "$SYSTEM_PROMPT\n\n$it" }
            ?: SYSTEM_PROMPT
        var conversation: List<OpenAIMessage> =
            listOf(OpenAIMessage(role = "system", content = systemPrompt)) + seed
        // resumeContext — контекст ПРОШЛОГО прогона: в дельту onCancelled не входит,
        // поэтому граница дельты фиксируется после его добавления
        if (!resumeContext.isNullOrEmpty()) {
            conversation = conversation + resumeContext
        }
        val deltaStart = conversation.size
        var lastAssistantText = ""
        var lastSignature: String? = null
        var repeatCount = 0
        var lastFailedTool: String? = null
        var failedToolRun = 0
        var loopKicks = 0
        var step = 0

        /**
         * Принудительный финал по гварду (лимит шагов / зацикливание / обрыв по лимиту
         * токенов): один вызов клиента без инструментов, с бюджетом [maxTokens]
         * и системной заметкой собрать ответ из уже добытых данных.
         */
        suspend fun forcedFinal(reason: String): AgentRunResult {
            // Лимит у финала полный (maxTokens, не шаговый) — модель должна знать его, чтобы снова не оборвать ответ
            val forcedNote = ReasoningPrompts.budgetNote(maxTokens)
                ?.let { "$FORCED_FINAL_NOTE $it" }
                ?: FORCED_FINAL_NOTE
            val result = client.ask(
                LLMCall(
                    messages = trimContext(
                        conversation + OpenAIMessage(role = "system", content = forcedNote)
                    ),
                    model = model,
                    maxTokens = maxTokens,
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

        // Отмена прогона (POST /api/cancel → job.cancel()) всплывает из suspend-точек
        // цикла как CancellationException: в catch чиним дельту и однократно отдаём её
        // в onCancelled под NonCancellable (checkpoint паузы), затем перебрасываем —
        // запрос всё равно отменяется, а собранное не теряется.
        try {
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
                        messages = trimContext(conversation),
                        model = model,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        stop = stop,
                        thinkingEnabled = thinkingEnabled
                    ),
                    client,
                    registry
                )
                sums.add(result)

                // Обычный текст без вызовов — модель закончила работу. Но обрыв по лимиту
                // токенов (finishReason == "length") финалом не считается: текст усечён
                // посреди мысли, пользователю уходит принудительный финал с полным бюджетом.
                if (result.toolCalls.isEmpty()) {
                    if (result.finishReason == FINISH_LENGTH) {
                        return forcedFinal(REASON_LENGTH)
                    }
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
                    // Гвард падений: успех сбрасывает серию, падение того же инструмента — наращивает
                    if (outcome.ok) {
                        failedToolRun = 0
                        lastFailedTool = null
                    } else if (name == lastFailedTool) {
                        failedToolRun++
                    } else {
                        failedToolRun = 1
                        lastFailedTool = name
                    }
                    // В контекст цикла результат входит ограниченно (TOOL_CONTENT_LIMIT):
                    // страница или дамп на мегабайты иначе переотправлялись бы на каждом шаге
                    conversation += OpenAIMessage(
                        role = "tool",
                        content = capToolContent(outcome.content),
                        toolCallId = toolCall.id
                    )
                }

                // Гвард зацикливания: первый раз — кик (системная заметка «смени подход», счётчики
                // сброшены, инструменты остаются доступны); повторное срабатывание — принудительный финал.
                // Вызов кикнутого шага уже исполнен — контекст валиден (нет «висячих» tool_calls).
                if (repeatCount >= LOOP_THRESHOLD || failedToolRun >= SAME_TOOL_FAIL_THRESHOLD) {
                    if (loopKicks < LOOP_KICK_LIMIT) {
                        loopKicks++
                        repeatCount = 0
                        lastSignature = null
                        failedToolRun = 0
                        lastFailedTool = null
                        conversation += OpenAIMessage(role = "system", content = LOOP_KICK_NOTE)
                        continue
                    }
                    return forcedFinal(REASON_LOOP)
                }
            }
        } catch (e: CancellationException) {
            val repaired = repairDelta(conversation.drop(deltaStart))
            withContext(NonCancellable) { onCancelled?.invoke(repaired) }
            throw e
        }
    }

    /**
     * Срез разговоров под кап [MAX_CONTEXT_TOKENS]; неразрушающий: полный разговор
     * живёт в цикле, на отправку идут ведущие system-сообщения и группы с хвоста.
     * Группа «assistant с tool_calls + его tool-результаты» неделима — модель
     * не видит «висячих» tool-сообщений. Приоритет при переполнении: свежие шаги,
     * затем цель пользователя (сохраняется всегда, как «последний ход» в
     * SessionHistory.trim), затем старая история по остатку бюджета.
     */
    private fun trimContext(messages: List<OpenAIMessage>): List<OpenAIMessage> {
        val leading = messages.takeWhile { it.role == "system" }
        val rest = messages.drop(leading.size)
        if (rest.isEmpty()) return messages

        val budget = (MAX_CONTEXT_TOKENS - leading.sumOf { SessionHistory.estimateTokens(it) })
            .coerceAtLeast(0)
        val lastUser = rest.indexOfLast { it.role == "user" }

        // Группы от свежих к старым; tool-сообщения приклеиваются к своему assistant
        val groups = ArrayList<IntRange>()
        var end = rest.size
        while (end > 0) {
            var start = end
            while (start > 0 && rest[start - 1].role == "tool") start--
            if (start in 1 until end && rest[start - 1].role == "assistant" && rest[start - 1].toolCalls != null) start--
            if (start == end) start--
            groups.add(start until end)
            end = start
        }

        var used = 0
        val kept = ArrayDeque<List<OpenAIMessage>>()
        fun fits(range: IntRange): Boolean {
            val group = rest.subList(range.first, range.last + 1)
            val cost = group.sumOf { SessionHistory.estimateTokens(it) }
            if (used + cost > budget) return false
            used += cost
            kept.addFirst(group)
            return true
        }

        val questionIdx = groups.indexOfFirst { lastUser in it }
        if (questionIdx < 0) {
            for (range in groups) if (!fits(range)) break
        } else {
            for (i in 0 until questionIdx) if (!fits(groups[i])) break
            val range = groups[questionIdx]
            used += rest.subList(range.first, range.last + 1).sumOf { SessionHistory.estimateTokens(it) }
            kept.addFirst(rest.subList(range.first, range.last + 1))
            for (i in questionIdx + 1 until groups.size) if (!fits(groups[i])) break
        }
        return leading + kept.flatten()
    }

    /**
     * Чинит дельту прерванного паузой прогона: последний assistant с tool_calls может
     * остаться без части результатов (отмена случилась во время исполнения инструментов).
     * Для каждого id вызова без следующего tool-сообщения дописывается синтетический
     * результат [INTERRUPTED_TOOL_NOTE] — пары assistant(tool_calls) → role="tool"
     * остаются валидными для провайдера при возобновлении. Отмена во время
     * transport.ask (новый assistant ещё не добавлен) — дельта уже валидна, ремонт no-op.
     */
    private fun repairDelta(delta: List<OpenAIMessage>): List<OpenAIMessage> {
        val repaired = delta.toMutableList()
        // Проход с конца: в цикле пары строгие (все результаты шага добавлены до
        // следующего transport.ask), «висячим» может быть только последний assistant —
        // для предыдущих проверка сведётся к no-op
        for (index in repaired.indices.reversed()) {
            val message = repaired[index]
            if (message.role != "assistant") continue
            val calls = message.toolCalls ?: continue
            val served = repaired.drop(index + 1)
                .filter { it.role == "tool" }
                .mapNotNull { it.toolCallId }
                .toSet()
            for (call in calls) {
                val id = call.id
                if (id.isNotEmpty() && id !in served) {
                    repaired.add(
                        OpenAIMessage(role = "tool", content = INTERRUPTED_TOOL_NOTE, toolCallId = id)
                    )
                }
            }
        }
        return repaired
    }

    /**
     * Ограничивает текст tool-результата, входящего в контекст цикла: сохраняет
     * начало и хвост, середину заменяет пометкой. Хвост нужен — ошибки и итоги
     * часто в конце (JSON-ответы, статусы выполнения).
     */
    private fun capToolContent(content: String): String {
        if (content.length <= TOOL_CONTENT_LIMIT) return content
        val head = content.take(TOOL_CONTENT_LIMIT - TOOL_TAIL_KEEP)
        val tail = content.takeLast(TOOL_TAIL_KEEP)
        val skipped = content.length - head.length - tail.length
        return "$head\n\n[…результат обрезан: пропущено $skipped символов…]\n\n$tail"
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

        /** Ограничение содержимого в событии ToolResult; в контекст цикла — через [TOOL_CONTENT_LIMIT]. */
        const val EVENT_CONTENT_LIMIT = 500

        /**
         * Кап символов tool-результата, входящего в контекст цикла: страница или дамп
         * на мегабайты иначе переотправлялись бы целиком на каждом шаге и сжигали
         * токены провайдера (кейс: 1,9 млн токенов на один вопрос про пробки).
         */
        const val TOOL_CONTENT_LIMIT = 8_000

        /** Сколько символов хвоста результата сохраняется при обрезке (ошибки и итоги часто в конце). */
        private const val TOOL_TAIL_KEEP = 2_000

        /**
         * Кап оценки отправляемого контекста (токены, символы/2.5): история +
         * накопленные tool-результаты не должны раздувать вызов сверх лимита
         * провайдера; лишнее вытесняется с хвоста перед каждой отправкой.
         */
        const val MAX_CONTEXT_TOKENS = 30_000

        /** Сколько подряд идущих одинаковых вызовов (tool, args) считаются зацикливанием. */
        const val LOOP_THRESHOLD = 3

        /**
         * Сколько падений одного и того же инструмента подряд считаются топтанием
         * на месте: модель меняет аргументы (подписи разные — гвард одинаковых
         * вызовов слеп), но долбит заведомо сломанный путь.
         */
        const val SAME_TOOL_FAIL_THRESHOLD = 3

        /**
         * Максимум киков за прогон: первое срабатывание гварда зацикливания не завершает
         * прогон, а даёт модели ещё один шанс (системная заметка [LOOP_KICK_NOTE] и свежие
         * счётчики); повторное срабатывание — принудительный финал.
         */
        const val LOOP_KICK_LIMIT = 1

        /** Системный промт агента: живёт в agent/, ReasoningPrompts не затрагивается. */
        private val SYSTEM_PROMPT = """
            Ты — бортовой агент «Штурмана»: решаешь задачу шагами с помощью инструментов.
            - Точные данные (дата, вычисления, веб-страницы, файлы) добывай инструментами, а не догадкой.
            - Результат вызова проверяй, прежде чем опираться на него; при ошибке меняй подход, а не повторяй вызов.
            - Когда данных достаточно — дай финальный ответ обычным текстом, без вызовов инструментов.
            - Финальный ответ — готовое решение для пользователя, а не размышления или промежуточный план.
            - Отвечай по-русски; код и JSON из инструментов можно цитировать как есть.
            - По просьбе пользователя записывай файлы инструментом write_file и называй имя файла в ответе.
            - Рабочая память (task_notes) — заметки по текущей задаче: сохраняй шаги и находки свободно.
            - Долговременная память (memory_save) — только по явной просьбе пользователя («запомни», «сохрани»).
        """.trimIndent()

        /**
         * Системная заметка перед принудительным финалом по гварду; при заданном
         * «Топливе» дополняется текстом [ReasoningPrompts.budgetNote] с полным бюджетом.
         */
        private const val FORCED_FINAL_NOTE =
            "Инструменты больше недоступны — дай финальный ответ на основе собранной информации, прямо сейчас: без описания будущих шагов и обещаний что-то сделать потом. Если данных не хватает, дай лучший доступный ответ и честно скажи, чего не хватило. Ответ дай по-русски."

        /**
         * Системная заметка-кик при первом срабатывании гварда зацикливания: требование
         * прекратить повторяющийся путь, сменить подход или сразу дать финальный ответ.
         * Модель получает ещё один шанс до принудительного финала.
         */
        private const val LOOP_KICK_NOTE =
            "Стоп: ты повторяешь одни и те же действия и не продвигаешься (одинаковые вызовы инструментов или повторные падения одного инструмента). Прекрати этот путь. Опираясь на уже полученные результаты, измени подход — или, если данных достаточно, сразу дай финальный ответ пользователю по-русски."

        /** Причина завершения: модель дала финальный ответ сама. */
        private const val REASON_DONE = "готово"

        /** Причина завершения: исчерпан лимит шагов. */
        private const val REASON_STEP_LIMIT = "лимит шагов"

        /** Причина завершения: превышен общий дедлайн прогона. */
        private const val REASON_DEADLINE = "дедлайн"

        /** Причина завершения: модель повторяет один и тот же вызов инструмента. */
        private const val REASON_LOOP = "зацикливание"

        /** Причина завершения: генерация оборвалась по лимиту токенов (finishReason == "length"). */
        private const val REASON_LENGTH = "ответ обрезан"

        /** Причина завершения генерации OpenAI-протокола: ответ усечён лимитом токенов. */
        private const val FINISH_LENGTH = "length"

        /**
         * Синтетический tool-результат, дописываемый в дельту прерванного паузой
         * прогона ([repairDelta]): закрывает висячие tool_calls для провайдера.
         */
        private const val INTERRUPTED_TOOL_NOTE = "Прервано паузой пользователя"

        /** Разделитель подписи зацикливания; \u0000 не встречается в текстах моделей. */
        private const val SIGNATURE_SEPARATOR = "\u0000"
    }
}

/** Складывает значения с допуском null: результат null, только если оба слагаемых null. */
private fun Int?.plusNullable(other: Int?): Int? =
    if (this == null && other == null) null else (this ?: 0) + (other ?: 0)
