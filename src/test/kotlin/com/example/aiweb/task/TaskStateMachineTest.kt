package com.example.aiweb.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Проверка конечного автомата [TaskStateMachine]: все легальные рёбра
 * графа, no-op на нелегальных, пауза/продолжение, same-stage-переходы
 * и капы длин step/expectedAction на входе в автомат.
 */
class TaskStateMachineTest {

    private val fsm = TaskStateMachine()

    private fun state(
        stage: TaskStage,
        step: String = "шаг",
        action: String = "действие",
        prevStage: TaskStage? = null
    ) = TaskState(stage, step, action, prevStage)

    @Test
    fun `legal chain - null to planning to execution to validation to done to planning`() {
        var current: TaskState? = null

        current = fsm.apply(current, state(TaskStage.PLANNING, "анализ целей", "написать план"))!!
        assertEquals(TaskStage.PLANNING, current.stage)

        current = fsm.apply(current, state(TaskStage.EXECUTION, "сборка", "запустить сборку"))!!
        assertEquals(TaskStage.EXECUTION, current.stage)

        current = fsm.apply(current, state(TaskStage.VALIDATION, "тесты", "починить падение"))!!
        assertEquals(TaskStage.VALIDATION, current.stage)

        current = fsm.apply(current, state(TaskStage.DONE, "готово", "отчитаться"))!!
        assertEquals(TaskStage.DONE, current.stage)

        current = fsm.apply(current, state(TaskStage.PLANNING, "новая задача", "анализ"))!!
        assertEquals(TaskStage.PLANNING, current.stage)
    }

    @Test
    fun `validation - back to execution and forward to done`() {
        val validation = state(TaskStage.VALIDATION)

        assertEquals(TaskStage.EXECUTION, fsm.apply(validation, state(TaskStage.EXECUTION, "ещё код"))?.stage)
        assertEquals(TaskStage.DONE, fsm.apply(validation, state(TaskStage.DONE))?.stage)
    }

    @Test
    fun `illegal - done to execution, planning to done, null to execution, paused to execution directly`() {
        assertNull(fsm.apply(state(TaskStage.DONE), state(TaskStage.EXECUTION)))
        assertNull(fsm.apply(state(TaskStage.PLANNING), state(TaskStage.DONE)))
        assertNull(fsm.apply(null, state(TaskStage.EXECUTION)))
        assertNull(
            fsm.apply(
                state(TaskStage.PAUSED, prevStage = TaskStage.EXECUTION),
                state(TaskStage.EXECUTION)
            ),
            "прямой прыжок из PAUSED через apply нелегален — только resume()"
        )
    }

    @Test
    fun `illegal - planning to validation and execution to planning`() {
        assertNull(fsm.apply(state(TaskStage.PLANNING), state(TaskStage.VALIDATION)))
        assertNull(fsm.apply(state(TaskStage.EXECUTION), state(TaskStage.PLANNING)))
    }

    @Test
    fun `pause - from any non-paused stage including done, prevStage is remembered`() {
        for (stage in listOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.DONE)) {
            val paused = fsm.pause(state(stage, "шаг", "действие"))!!

            assertEquals(TaskStage.PAUSED, paused.stage, "stage: $stage")
            assertEquals(stage, paused.prevStage, "prevStage: $stage")
            assertEquals("шаг", paused.step, "step сохраняется на паузе: $stage")
            assertEquals("действие", paused.expectedAction, "expectedAction сохраняется на паузе: $stage")
        }
    }

    @Test
    fun `pause - from null or already-paused state, no-op`() {
        assertNull(fsm.pause(null))
        assertNull(fsm.pause(state(TaskStage.PAUSED, prevStage = TaskStage.PLANNING)))
    }

    @Test
    fun `pause via apply - automaton writes prevStage itself`() {
        val paused = fsm.apply(
            state(TaskStage.EXECUTION, "пишу код"),
            state(TaskStage.PAUSED, "шаг на паузе")
        )!!

        assertEquals(TaskStage.PAUSED, paused.stage)
        assertEquals(TaskStage.EXECUTION, paused.prevStage)
    }

    @Test
    fun `resume - from paused to prevStage, prevStage is cleared`() {
        val paused = fsm.pause(state(TaskStage.VALIDATION, "прогоняю тесты", "чиню падение"))!!

        val resumed = fsm.resume(paused)!!

        assertEquals(TaskStage.VALIDATION, resumed.stage)
        assertNull(resumed.prevStage)
        assertEquals("прогоняю тесты", resumed.step, "шаг восстанавливается без повторных объяснений")
        assertEquals("чиню падение", resumed.expectedAction)
    }

    @Test
    fun `resume - from null or non-paused state, no-op`() {
        assertNull(fsm.resume(null))
        assertNull(fsm.resume(state(TaskStage.EXECUTION)))
    }

    @Test
    fun `resume - with empty prevStage, no-op`() {
        assertNull(fsm.resume(state(TaskStage.PAUSED, prevStage = null)))
    }

    @Test
    fun `resume then normal transition - prevStage stays cleared`() {
        val resumed = fsm.resume(fsm.pause(state(TaskStage.VALIDATION))!!)!!

        val next = fsm.apply(resumed, state(TaskStage.EXECUTION, "продолжаю", "дописываю код"))!!

        assertEquals(TaskStage.EXECUTION, next.stage)
        assertNull(next.prevStage)
    }

    @Test
    fun `same stage - step and expectedAction update, stage stays`() {
        val planning = state(TaskStage.PLANNING, "старый шаг", "старое действие")

        val next = fsm.apply(planning, state(TaskStage.PLANNING, "новый шаг", "новое действие"))!!

        assertEquals(TaskStage.PLANNING, next.stage)
        assertEquals("новый шаг", next.step)
        assertEquals("новое действие", next.expectedAction)
    }

    @Test
    fun `caps - long step and expectedAction are trimmed on entry`() {
        val result = fsm.apply(
            null,
            state(TaskStage.PLANNING, "ш".repeat(150), "д".repeat(300))
        )!!

        assertEquals(MAX_STEP_LENGTH, result.step.length)
        assertEquals(MAX_EXPECTED_ACTION_LENGTH, result.expectedAction.length)
    }

    @Test
    fun `caps - pause via apply trims too`() {
        val paused = fsm.apply(
            state(TaskStage.DONE, "ш".repeat(150), "д".repeat(300)),
            state(TaskStage.PAUSED, "п".repeat(200), "о".repeat(500))
        )!!

        assertEquals(MAX_STEP_LENGTH, paused.step.length)
        assertEquals(MAX_EXPECTED_ACTION_LENGTH, paused.expectedAction.length)
        assertEquals(TaskStage.DONE, paused.prevStage)
    }
}
