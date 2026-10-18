package com.example.aiweb.task

/**
 * Этапы конечного автомата состояния задачи «Штурмана».
 *
 * Основной цикл: PLANNING → PLAN_APPROVED → EXECUTION → VALIDATION → DONE
 * (из DONE новая задача → PLANNING). PLAN_APPROVED — утверждённый
 * пользователем план (диалоговый чекпоинт): EXECUTION достижим только через
 * этот этап. PAUSED — пауза любого не-PAUSED этапа (включая DONE);
 * приостановленный этап хранится в [TaskState.prevStage].
 */
enum class TaskStage {
    PLANNING,
    PLAN_APPROVED,
    EXECUTION,
    VALIDATION,
    DONE,
    PAUSED
}
