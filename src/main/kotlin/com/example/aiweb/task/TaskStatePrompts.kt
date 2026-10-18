package com.example.aiweb.task

/**
 * Рендер системного блока состояния задачи для seed каждого LLM-запроса
 * (инъекция — задача 5 плана task-state-fsm). Блок добавляется всегда;
 * вариант зависит от состояния:
 *  - активная задача — текущие этап/шаг/ожидаемое действие + правило
 *    объявления перехода блоком ```task-state, таблица легальных переходов
 *    и чекпоинт-дисциплина (кроме DONE — задача завершена);
 *  - пауза — запрет переходов (агент отвечает на уточняющие вопросы);
 *  - состояние ещё не ставилось — однострочная инструкция «задача не начата».
 *
 * При непустом [lastRejectedTransition] (задача 6 плана task-lifecycle-gates)
 * к любому варианту добавляется строка об отклонённом переходе: объяснить
 * причину и не повторять попытку.
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
    "переходы: planning→plan_approved→execution→validation (→execution для доработки)→done; " +
        "validation→done; done→planning (новая задача)."

/**
 * Чекпоинт-дисциплина активного варианта (кроме DONE): после каждой итерации —
 * вопрос пользователю, при вариативности — варианты; следующий этап только по
 * явному ответу (задача 6 плана task-lifecycle-gates).
 */
private const val CHECKPOINT_TEXT =
    "После завершения каждой итерации/шага ОБЯЗАТЕЛЬНО заверши ответ вопросом " +
        "к пользователю: продолжить ли (и в каком порядке/варианте), ожидая его ответа; " +
        "при вариативности решения — предложи варианты и жди выбор пользователем через поле " +
        "запроса. Не переходи к следующему этапу до явного ответа. Один ответ — не более " +
        "одного перехода. Такт PLAN_APPROVED — только объявление утверждения плана и вопрос " +
        "«Приступаю к реализации?», без работы в том же ответе."

/** Строка об отклонённом переходе (задача 6 плана task-lifecycle-gates). */
private fun rejectedTransitionText(lastRejectedTransition: String): String =
    "ПОСЛЕДНИЙ ПРЕДЛОЖЕННЫЙ ПЕРЕХОД ОТКЛОНЁН: $lastRejectedTransition. " +
        "Объясни пользователю, почему переход недопустим, и что нужно сделать вместо этого. " +
        "Не повторяй ту же попытку в том же ответе."

private fun stageName(stage: TaskStage): String = stage.name.lowercase()

/**
 * Строит системный блок состояния задачи для seed запроса.
 *
 * @param state актуальное состояние задачи сессии; null — задача не начата.
 * @param lastRejectedTransition описание отклонённого на прошлом ходу
 *        перехода (задача 6 плана task-lifecycle-gates); null/пусто — строка
 *        об отказе не добавляется.
 */
fun renderTaskStateBlock(state: TaskState?, lastRejectedTransition: String? = null): String {
    val base = when {
        state == null -> NOT_STARTED_TEXT
        state.stage == TaskStage.PAUSED -> PAUSED_TEXT
        else -> listOf(
            "$TASK_STATE_PROMPT_HEADER: этап=${stageName(state.stage)}; шаг=${state.step}; " +
                "ожидаемое действие=${state.expectedAction}.",
            "В финальном ответе объяви переход блоком " +
                "```task-state {\"stage\":\"…\",\"step\":\"…\",\"expected_action\":\"…\"}```;",
            EDGES_TEXT,
            if (state.stage == TaskStage.DONE) null else CHECKPOINT_TEXT
        ).filterNotNull().joinToString("\n")
    }
    return if (lastRejectedTransition.isNullOrBlank()) {
        base
    } else {
        base + "\n" + rejectedTransitionText(lastRejectedTransition)
    }
}
