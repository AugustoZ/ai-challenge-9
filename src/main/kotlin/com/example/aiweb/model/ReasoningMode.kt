package com.example.aiweb.model

/**
 * Способ рассуждения, выбираемый в интерфейсе.
 *
 * @param id строковый идентификатор, который передаётся в ChatRequest.reasoningMode
 */
enum class ReasoningMode(val id: String) {
    /** Без дополнительных модификаций: вопрос уходит в LLM как есть, без режимного system-промта. */
    DEFAULT("default"),

    /** Сугубо сухой и прямолинейный ответ без лишних инструкций. */
    DIRECT("direct"),

    /** Подробное пошаговое решение с обоснованием каждого шага. */
    STEP_BY_STEP("step_by_step"),

    /** Вывод — готовый промт, который передаётся в LLM для решения задачи. */
    PROMPT_TO_PROMPT("prompt_to_prompt"),

    /** Три роли (Архитектор, Инженер, Исследователь) решают задачу, затем саммари сравнения. */
    TEAM("team");

    companion object {
        /**
         * Разбирает значение из запроса. Null, пустое или неизвестное значение
         * трактуется как базовый режим [DEFAULT].
         */
        fun fromId(raw: String?): ReasoningMode =
            values().firstOrNull { it.id == raw?.trim()?.lowercase() } ?: DEFAULT
    }
}
