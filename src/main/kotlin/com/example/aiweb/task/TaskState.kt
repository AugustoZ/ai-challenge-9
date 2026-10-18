package com.example.aiweb.task

import kotlinx.serialization.Serializable

/** Максимальная длина текущего шага; длиннее обрезается на входе в автомат. */
const val MAX_STEP_LENGTH = 120

/** Максимальная длина ожидаемого действия; длиннее обрезается на входе в автомат. */
const val MAX_EXPECTED_ACTION_LENGTH = 200

/**
 * Состояние задачи конечного автомата «Штурмана»: текущий этап, шаг внутри
 * этапа и ожидаемое следующее действие. Драйвер переходов — LLM (блок
 * ```task-state``` в финальном ответе) + серверная валидация по таблице
 * рёбер [TaskStateMachine]; нелегальный переход — no-op.
 *
 * [step] и [expectedAction] обрезаются до [MAX_STEP_LENGTH] /
 * [MAX_EXPECTED_ACTION_LENGTH] на входе в автомат ([TaskStateMachine.apply],
 * [withCapsApplied]).
 */
@Serializable
data class TaskState(
    /** Текущий этап задачи. */
    val stage: TaskStage,
    /** Текущий шаг (свободный текст, ≤ [MAX_STEP_LENGTH]). */
    val step: String,
    /** Ожидаемое следующее действие (свободный текст, ≤ [MAX_EXPECTED_ACTION_LENGTH]). */
    val expectedAction: String,
    /**
     * Этап, с которого задача поставлена на паузу; ненулев только на PAUSED.
     * Пишется автоматом ([TaskStateMachine.pause], [TaskStateMachine.apply])
     * и очищается при возобновлении ([TaskStateMachine.resume]).
     */
    val prevStage: TaskStage? = null
) {
    /** Копия состояния с [step] и [expectedAction], обрезанными по капам. */
    fun withCapsApplied(): TaskState =
        copy(
            step = step.take(MAX_STEP_LENGTH),
            expectedAction = expectedAction.take(MAX_EXPECTED_ACTION_LENGTH)
        )
}
