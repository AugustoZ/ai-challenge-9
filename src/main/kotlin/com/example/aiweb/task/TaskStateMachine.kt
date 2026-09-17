package com.example.aiweb.task

/**
 * Конечный автомат состояния задачи: чистый класс без побочных эффектов —
 * на вход текущее и предложенное состояние, на выход новое состояние или
 * null (no-op: вызывающий не меняет сохранённое состояние).
 *
 * Таблица рёбер (текущий этап → допустимые следующие):
 * - null → PLANNING
 * - PLANNING → EXECUTION
 * - EXECUTION → VALIDATION
 * - VALIDATION → EXECUTION, DONE
 * - DONE → PLANNING
 * - любой не-PAUSED этап (включая DONE) → PAUSED (пауза, текущий этап
 *   запоминается в prevStage)
 * - PAUSED → prevStage (только через [resume]; прямой прыжок из PAUSED
 *   через [apply] нелегален)
 *
 * Остаться на том же этапе легально: этап не меняется, но step/
 * expectedAction обновляются. Всё прочее — нелегально → null.
 */
class TaskStateMachine {

    private val edges: Map<TaskStage, Set<TaskStage>> = mapOf(
        TaskStage.PLANNING to setOf(TaskStage.EXECUTION),
        TaskStage.EXECUTION to setOf(TaskStage.VALIDATION),
        TaskStage.VALIDATION to setOf(TaskStage.EXECUTION, TaskStage.DONE),
        TaskStage.DONE to setOf(TaskStage.PLANNING)
    )

    /**
     * Применяет предложенный моделью переход.
     *
     * @return новое состояние, или null — если переход нелегальный
     *         (включая паузу из null-состояния), тогда вызывающий
     *         сохраняет текущее (no-op).
     *
     * step/expectedAction обрезаются по капам на входе в автомат;
     * prevStage управляет автомат: на паузе пишется текущий этап, при
     * возобновлении очищается.
     */
    fun apply(current: TaskState?, proposed: TaskState): TaskState? {
        val from = current?.stage

        if (proposed.stage == TaskStage.PAUSED) {
            if (from == null || from == TaskStage.PAUSED) return null
            return proposed.withCapsApplied().copy(prevStage = from)
        }

        val legal = when (from) {
            null -> proposed.stage == TaskStage.PLANNING
            TaskStage.PAUSED -> false
            else -> from == proposed.stage || proposed.stage in edges[from].orEmpty()
        }
        if (!legal) return null

        return proposed.withCapsApplied().copy(prevStage = null)
    }

    /**
     * Пауза: любой не-PAUSED этап (включая DONE) → PAUSED, этап
     * запоминается в prevStage; step/expectedAction сохраняются.
     *
     * @return состояние на паузе, или null — если состояния нет (null)
     *         или задача уже на паузе (no-op).
     */
    fun pause(current: TaskState?): TaskState? {
        if (current == null || current.stage == TaskStage.PAUSED) return null
        return current.copy(stage = TaskStage.PAUSED, prevStage = current.stage)
    }

    /**
     * Продолжение: PAUSED → этап из prevStage, prevStage очищается.
     *
     * @return восстановленное состояние, или null — если задача не на
     *         паузе (null или любой другой этап) или prevStage не задан.
     */
    fun resume(current: TaskState?): TaskState? {
        if (current?.stage != TaskStage.PAUSED) return null
        val prev = current.prevStage ?: return null
        return current.copy(stage = prev, prevStage = null)
    }
}
