package com.example.aiweb.task

/**
 * Этапы конечного автомата состояния задачи «Штурмана».
 *
 * Основной цикл: PLANNING → EXECUTION → VALIDATION → DONE (из DONE новая
 * задача → PLANNING). PAUSED — пауза любого не-PAUSED этапа (включая DONE);
 * приостановленный этап хранится в [TaskState.prevStage].
 */
enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE,
    PAUSED
}
