package com.example.aiweb.task

/**
 * Серверный авто-FSM (фолбэк, когда модель не объявила блок ```task-state):
 * сигналы чисто лексические и детерминированные, без I/O.
 *
 * Реальный LLM игнорирует промпт с блоком состояния, поэтому [TaskStateJson.parse]
 * возвращает null и задача не создаётся/не двигается (кнопка «Пауза» недоступна).
 * Здесь по маркерам в сообщении пользователя и ответе модели выводится ОДИН
 * законный шаг автомата [TaskStateMachine]:
 * - цель по ответу модели: DONE (хвост) > VALIDATION > EXECUTION;
 * - создание/перезапуск (null/DONE → PLANNING) — только по многошаговому
 *   намерению пользователя и без отказа модели в ответе;
 * - переходы — ровно по таблице рёбер автомата, «одна ступень за ход»
 *   (DONE-цель из EXECUTION даёт VALIDATION, а не прыжок в DONE).
 *
 * Никогда не возвращает этап PAUSED: null означает «no-op, состояние не трогаем».
 */
object TaskStateHeuristic {

    /** Глаголы намерения в сообщении пользователя (стволы без окончаний, регистр не важен).
     * Пополнено по реальным пропускам (см. TaskStateHeuristicTest). */
    private val INTENT_VERBS = setOf(
        "сделай", "напиши", "реализуй", "создай", "составь", "подготовь",
        "проверь", "исправь", "перепиши", "переделай", "добавь", "настрой", "найди",
        "придумай", "продумай", "проведи", "разработай", "спланируй", "опиши",
        "перечисли", "объясни", "рассчитай", "сравни", "проанализируй",
        "протестируй", "организуй", "построй"
    )

    /** Словесные перечислители многошаговости; нумерованный список считается отдельно.
     * Пополнено по реальным пропускам (см. TaskStateHeuristicTest). */
    private val INTENT_ENUMERATORS = setOf(
        "затем", "потом", "после этого", "после чего", "далее", "пошагово"
    )

    /** Пункт нумерованного списка «1. / 2)» — сам является перечислителем. */
    private val NUMBERED_ITEM = Regex("""\d[.)]\s""")

    /** Существительное-результат «план» в точных словоформах (не подстрока:
     * «планшет», «планета» — отдельные токены, не совпадают). */
    private val TASK_NOUN_PLAN = setOf(
        "план", "плана", "плану", "планом", "плане", "планы", "планов", "планам", "планами", "планах"
    )

    /** Префикс токена для «инструкция» (инструкции/инструкцию/инструкцией/инструкций);
     * «инструктаж» не начинается с этого префикса — безопасно. */
    private val TASK_NOUN_PREFIX = setOf("инструкци")

    /** «Чек-лист» — дефис не токенизаторский разделитель, ищем подстрокой. */
    private val TASK_NOUN_CONTAINS = setOf("чек-лист", "чеклист")

    /** Существительное-результат «смета» в точных словоформах
     * («сметана» — отдельный токен, не совпадёт). */
    private val TASK_NOUN_SMETA = setOf(
        "смета", "сметы", "смете", "смету", "сметой", "смет", "сметам", "сметами", "сметах"
    )

    /** Отказ модели — подавляет только создание/перезапуск, продвижение не блокирует. */
    private val REFUSAL_MARKERS = setOf("не могу", "не смогу", "не умею", "я не могу")

    /** Исполнение в ответе: код-фенс или слова «вот код», «реализация». */
    private val EXEC_MARKERS = setOf("```", "вот код", "реализация")

    /** Валидация в ответе: отчёт о проверке/тестах. */
    private val VALIDATION_MARKERS = setOf(
        "проверил", "проверка", "тесты прошли", "все тесты", "ошибок нет", "всё работает"
    )

    /** Завершение — ищется только в хвосте ответа (финальная фраза, не середина). */
    private val DONE_MARKERS = setOf(
        "готово", "выполнено", "завершено", "все шаги выполнены", "задача выполнена", "done"
    )

    /** Размер хвоста ответа, в котором ищутся маркеры DONE. */
    private const val DONE_TAIL_WINDOW = 300

    /**
     * Выводит один законный шаг автомата по лексическим сигналам.
     *
     * @param userMessage сообщение пользователя этого хода (может отсутствовать).
     * @param reply ответ модели БЕЗ блока task-state (стриппнутый чистый текст).
     * @param current текущее состояние задачи.
     * @return предложенное состояние, или null — no-op (создавать/двигать нечего).
     */
    fun infer(userMessage: String?, reply: String, current: TaskState?): TaskState? {
        // 1. Стражи: пустой ответ не несёт сигналов; PAUSED побеждает всё
        //    (маршрутный страж авторитетен, здесь ремень и подтяжки).
        if (reply.isBlank()) return null
        if (current?.stage == TaskStage.PAUSED) return null

        val replyLower = reply.lowercase()

        // 2. Цель по ответу модели: DONE > VALIDATION > EXECUTION.
        val replyTarget = replyTarget(replyLower)

        // 3. Многошаговое намерение пользователя.
        val intent = hasIntent(userMessage)

        // 4. Желаемый целевой этап.
        //    Задача ТОЛЬКО ЗАПРАШИВАЕТСЯ (null/DONE + намерение): создаём/перезапускаем
        //    на PLANNING — это единственная легальная точка входа в лестницу, даже если
        //    модель в первом же ответе уже выложила код/итог (маркеры продвижения из null
        //    нелегальны). Сигналы ответа двигают УЖЕ начатую задачу (current != null).
        val target = when {
            (current == null || current.stage == TaskStage.DONE) && intent &&
                REFUSAL_MARKERS.none { replyLower.contains(it) } -> TaskStage.PLANNING
            replyTarget != null -> replyTarget
            else -> null
        } ?: return null

        // 5. Ровно одна законная ступень автомата; нелегальный шаг — no-op.
        val stage = stepToward(current?.stage, target) ?: return null

        // 6. Создание/перезапуск переносит шаг из запроса пользователя,
        //    продвижение сохраняет шаг/действие текущего состояния.
        return if (stage == TaskStage.PLANNING) {
            TaskState(
                stage = stage,
                step = userMessage?.trim()?.take(MAX_STEP_LENGTH) ?: "",
                expectedAction = ""
            )
        } else {
            TaskState(
                stage = stage,
                step = current?.step ?: "",
                expectedAction = current?.expectedAction ?: ""
            )
        }
    }

    /**
     * Пре-создание задачи на старте хода (до вызова LLM): кнопка «Пауза» должна
     * быть доступна уже во время генерации, а не только после первого ответа.
     * Создание — только из null/DONE и по многошаговому намерению сообщения;
     * PAUSED и текущие этапы (PLANNING/EXECUTION/VALIDATION) не трогаем —
     * продвижение остаётся делом ответа модели (infer в finalizeResponse).
     */
    fun ensureForRequest(userMessage: String?, current: TaskState?): TaskState? {
        if (userMessage.isNullOrBlank()) return null
        if (current?.stage == TaskStage.PAUSED) return null
        if ((current == null || current.stage == TaskStage.DONE) && hasIntent(userMessage)) {
            return TaskState(
                stage = TaskStage.PLANNING,
                step = userMessage.trim().take(MAX_STEP_LENGTH),
                expectedAction = ""
            )
        }
        return null
    }

    /** Целевой этап по ответу модели: DONE (только хвост) > VALIDATION > EXECUTION. */
    private fun replyTarget(replyLower: String): TaskStage? = when {
        DONE_MARKERS.any { replyLower.takeLast(DONE_TAIL_WINDOW).contains(it) } -> TaskStage.DONE
        VALIDATION_MARKERS.any { replyLower.contains(it) } -> TaskStage.VALIDATION
        EXEC_MARKERS.any { replyLower.contains(it) } -> TaskStage.EXECUTION
        else -> null
    }

    /**
     * Многошаговое намерение: ≥2 разных глаголов, или глагол + перечислитель
     * (словесный или пункт нумерованного списка), или список из ≥2 пунктов,
     * или глагол + существительное-результат (план/инструкция/чек-лист/смета —
     * сам артефакт подразумевает многошаговость).
     */
    private fun hasIntent(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        val verbs = INTENT_VERBS.count { lower.contains(it) }
        val numbered = NUMBERED_ITEM.findAll(lower).count()
        val enumerators = INTENT_ENUMERATORS.count { lower.contains(it) } + numbered
        return verbs >= 2 || (verbs >= 1 && enumerators >= 1) || numbered >= 2 ||
            (verbs >= 1 && hasTaskNoun(lower))
    }

    /**
     * Есть ли в сообщении существительное-результат (артефакт). Токенизация по
     * не-буквам обязательна: «бизнес-план» даёт токен «план» (позитив), а
     * «планшет» остаётся одним токеном и не совпадает с точными словоформами.
     */
    private fun hasTaskNoun(lower: String): Boolean {
        val tokens = lower.split(Regex("[^а-яёa-z]+")).filter { it.isNotEmpty() }
        return tokens.any { it in TASK_NOUN_PLAN || it in TASK_NOUN_SMETA } ||
            tokens.any { TASK_NOUN_PREFIX.any { prefix -> it.startsWith(prefix) } } ||
            TASK_NOUN_CONTAINS.any { lower.contains(it) }
    }

    /**
     * Одна законная ступень по таблице рёбер [TaskStateMachine] (зеркало автомата);
     * DONE-цель из EXECUTION даёт VALIDATION — одна ступень за ход. Нелегальная
     * или отсутствующая в таблице комбинация — null.
     */
    private fun stepToward(from: TaskStage?, target: TaskStage): TaskStage? = when (from) {
        null -> if (target == TaskStage.PLANNING) TaskStage.PLANNING else null
        TaskStage.PLANNING -> if (target == TaskStage.EXECUTION) TaskStage.EXECUTION else null
        TaskStage.EXECUTION -> when (target) {
            TaskStage.VALIDATION -> TaskStage.VALIDATION
            TaskStage.DONE -> TaskStage.VALIDATION
            else -> null
        }
        TaskStage.VALIDATION -> when (target) {
            TaskStage.DONE -> TaskStage.DONE
            TaskStage.EXECUTION -> TaskStage.EXECUTION
            else -> null
        }
        TaskStage.DONE -> if (target == TaskStage.PLANNING) TaskStage.PLANNING else null
        TaskStage.PAUSED -> null
    }
}
