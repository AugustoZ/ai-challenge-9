package com.example.aiweb.task

/**
 * Рендер системного блока состояния задачи для seed каждого LLM-запроса
 * (инъекция — задача 5 плана task-state-fsm). Блок добавляется всегда;
 * вариант зависит от состояния:
 *  - активная задача — текущие этап/шаг/ожидаемое действие + правило
 *    объявления перехода блоком ```task-state и таблица легальных переходов;
 *  - пауза — запрет переходов (агент отвечает на уточняющие вопросы);
 *  - состояние ещё не ставилось — однострочная инструкция «задача не начата».
 *
 * Эфемерная system-строка: в Session.messages не пишется (G5), SessionHistory.trim
 * её не режет (инъекция после trim, хвостовым слотом как у предпочтений/памяти).
 */

/** Заголовок активного варианта блока; по нему тесты находят блок в seed. */
const val TASK_STATE_PROMPT_HEADER = "ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ"

/** Однострочный вариант при отсутствии состояния. */
private const val NOT_STARTED_TEXT = "Задача не начата. Приступая, объяви planning."

/** Вариант на паузе: переходы запрещены — пауза в полёте побеждает LLM-переход (G2). */
private const val PAUSED_TEXT = "Задача на паузе. Не объявляй переходы; отвечай на уточняющие вопросы."

/** Таблица легальных переходов для активного варианта блока. */
private const val EDGES_TEXT =
    "переходы: planning→execution→validation (→execution для доработки)→done; " +
        "validation→done; done→planning (новая задача)."

private fun stageName(stage: TaskStage): String = stage.name.lowercase()

/**
 * Строит системный блок состояния задачи для seed запроса.
 *
 * @param state актуальное состояние задачи сессии; null — задача не начата.
 */
fun renderTaskStateBlock(state: TaskState?): String = when {
    state == null -> NOT_STARTED_TEXT
    state.stage == TaskStage.PAUSED -> PAUSED_TEXT
    else -> listOf(
        "$TASK_STATE_PROMPT_HEADER: этап=${stageName(state.stage)}; шаг=${state.step}; " +
            "ожидаемое действие=${state.expectedAction}.",
        "В финальном ответе объяви переход блоком " +
            "```task-state {\"stage\":\"…\",\"step\":\"…\",\"expected_action\":\"…\"}```;",
        EDGES_TEXT
    ).joinToString("\n")
}
