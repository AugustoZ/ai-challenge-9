package com.example.aiweb.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Проверка серверного авто-FSM [TaskStateHeuristic]: создание задачи по
 * многошаговому намерению (глаголы, перечислители, нумерованный список),
 * негативные случаи (вопрос, один глагол, отказ, пустые строки), продвижение
 * по лестнице «одна ступень за ход», доработка с VALIDATION, no-op на
 * нелегальных шагах, перезапуск из DONE, страж PAUSED и капы длины шага.
 */
class TaskStateHeuristicTest {

    private val h = TaskStateHeuristic

    private fun state(stage: TaskStage, step: String = "шаг", action: String = "действие") =
        TaskState(stage, step, action)

    // --- Создание задачи (null → PLANNING) ---

    @Test
    fun `создание - два глагола в запросе создают PLANNING`() {
        val result = h.infer("Сделай план, выполни и проверь", "Приступаю.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("Сделай план, выполни и проверь", result?.step)
        assertEquals("", result?.expectedAction)
        assertNull(result?.prevStage)
    }

    @Test
    fun `создание - глагол с перечислителем создаёт PLANNING`() {
        val result = h.infer("напиши и затем проверь", "хорошо.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - нумерованный список создаёт PLANNING`() {
        val result = h.infer("1. Собрать 2. Проверить", "принято.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("1. Собрать 2. Проверить", result?.step)
    }

    @Test
    fun `создание - регресс реального пропуска «придумай…после чего проведи» создаёт PLANNING`() {
        val message = "Придумай план для проверки статусной модели задач, критерии приемки, " +
            "реализацию, после чего проведи тесты для сверки успешности критериев приемки"
        val result = h.infer(message, "Вот черновой план работ.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals(message.take(MAX_STEP_LENGTH), result?.step, "шаг переносится из запроса (с капом)")
        assertEquals("", result?.expectedAction)
        assertNull(result?.prevStage)
    }

    @Test
    fun `создание - новые глаголы «разработай…проведи» создают PLANNING`() {
        val result = h.infer("Разработай модуль X, затем проведи нагрузочные тесты", "Принято.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - новый перечислитель «далее» создаёт PLANNING`() {
        val result = h.infer("Опиши архитектуру, далее перечисли риски", "хорошо.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - глагол и слово «план» без перечислителей создают PLANNING`() {
        val message = "Составь план для фотосессии авто в СПб"
        val result = h.infer(message, "Вот черновой план работ.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals(message, result?.step)
        assertEquals("", result?.expectedAction)
    }

    @Test
    fun `создание - глагол и «инструкция» создают PLANNING`() {
        val result = h.infer("Напиши пошаговую инструкцию по настройке сервера", "Приступаю.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - глагол и «чек-лист» создают PLANNING`() {
        val result = h.infer("Подготовь чек-лист выезда команды", "Приступаю.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - глагол и «смета» создают PLANNING`() {
        val result = h.infer("Рассчитай смету ремонта офиса", "Приступаю.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    @Test
    fun `создание - составное слово «бизнес-план» создаёт PLANNING`() {
        val result = h.infer("Сделай бизнес-план кофейни", "Приступаю.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
    }

    // --- Создание: негативные случаи ---

    @Test
    fun `создание - вопрос без намерения null`() {
        assertNull(h.infer("что такое энтропия?", "Энтропия — мера беспорядка.", null))
    }

    @Test
    fun `создание - один глагол без перечислителя null`() {
        assertNull(h.infer("напиши стихотворение", "вот оно.", null))
    }

    @Test
    fun `создание - один глагол «придумай» без перечислителя null`() {
        assertNull(h.infer("Придумай название для проекта", "Конечно.", null))
    }

    @Test
    fun `создание - отказ модели подавляет создание null`() {
        assertNull(h.infer("напиши и затем проверь", "извините, не могу", null))
    }

    @Test
    fun `создание - пустой ответ null`() {
        assertNull(h.infer("сделай и проверь", "   ", null))
    }

    @Test
    fun `создание - пустое сообщение пользователя null`() {
        assertNull(h.infer("", "обычный ответ.", null))
    }

    // --- Продвижение по лестнице ---

    @Test
    fun `продвижение - код-фенс из PLANNING даёт EXECUTION`() {
        val result = h.infer("сделай", "Вот код:\n```\nfun f() {}\n```", state(TaskStage.PLANNING))

        assertEquals(TaskStage.EXECUTION, result?.stage)
        assertEquals("шаг", result?.step, "шаг сохраняется при продвижении")
        assertEquals("действие", result?.expectedAction)
    }

    @Test
    fun `продвижение - отчёт о проверке из EXECUTION даёт VALIDATION`() {
        val result = h.infer("проверь", "проверил, ошибок нет", state(TaskStage.EXECUTION))

        assertEquals(TaskStage.VALIDATION, result?.stage)
    }

    @Test
    fun `продвижение - готово в хвосте из VALIDATION даёт DONE`() {
        val result = h.infer("проверь", "Всё ок. Готово.", state(TaskStage.VALIDATION))

        assertEquals(TaskStage.DONE, result?.stage)
    }

    @Test
    fun `продвижение - готово из EXECUTION даёт только VALIDATION (одна ступень)`() {
        val result = h.infer("сделай", "всё готово", state(TaskStage.EXECUTION))

        assertEquals(TaskStage.VALIDATION, result?.stage, "DONE-цель из EXECUTION — одна ступень за ход")
    }

    @Test
    fun `доработка - вот код из VALIDATION возвращает на EXECUTION`() {
        val result = h.infer("исправь", "вот код с исправлениями", state(TaskStage.VALIDATION))

        assertEquals(TaskStage.EXECUTION, result?.stage)
    }

    // --- No-op ---

    @Test
    fun `no-op - маркер валидации из PLANNING нелегален null`() {
        assertNull(h.infer("сделай", "проверил", state(TaskStage.PLANNING)))
    }

    @Test
    fun `no-op - маркер исполнения из DONE null (липкость)`() {
        assertNull(h.infer("сделай", "```\nкод\n```", state(TaskStage.DONE)))
    }

    @Test
    fun `no-op - нейтральный ответ из EXECUTION null`() {
        assertNull(h.infer("сделай", "продолжаю работу", state(TaskStage.EXECUTION)))
    }

    @Test
    fun `no-op - «планшет» не является планом null`() {
        assertNull(h.infer("Опиши планшет для рисования", "Приступаю.", null))
    }

    @Test
    fun `no-op - один глагол без существительного результата null`() {
        assertNull(h.infer("Объясни, как работает FSM", "Приступаю.", null))
    }

    @Test
    fun `no-op - «план» без глагола намерения null`() {
        assertNull(h.infer("Какой план на завтра?", "Приступаю.", null))
    }

    // --- Перезапуск из DONE ---

    @Test
    fun `перезапуск - новая задача из DONE даёт PLANNING с шагом из запроса`() {
        val result = h.infer(
            "сделай отчёт и проверь",
            "принял.",
            state(TaskStage.DONE, "старый шаг", "старое действие")
        )

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("сделай отчёт и проверь", result?.step)
        assertEquals("", result?.expectedAction)
    }

    // --- Пре-создание на старте хода (ensureForRequest) ---

    @Test
    fun `ensure - два глагола из словаря создают PLANNING с обрезанным шагом`() {
        val long = "Сделай обзор и проверь " + "х".repeat(200)
        val result = h.ensureForRequest(long, null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals(MAX_STEP_LENGTH, result!!.step.length, "шаг обрезается капом")
        assertEquals("", result.expectedAction)
        assertNull(result.prevStage)
    }

    @Test
    fun `ensure - DONE текущий и намерение перезапускает PLANNING`() {
        val result = h.ensureForRequest(
            "сделай отчёт и проверь",
            state(TaskStage.DONE, "старый шаг", "старое действие")
        )

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("сделай отчёт и проверь", result?.step)
        assertEquals("", result?.expectedAction)
    }

    @Test
    fun `ensure - PAUSED текущий не трогаем`() {
        assertNull(
            h.ensureForRequest(
                "сделай и проверь",
                state(TaskStage.PAUSED, "шаг", "действие")
            )
        )
    }

    @Test
    fun `ensure - активный этап EXECUTION не трогаем`() {
        assertNull(h.ensureForRequest("сделай и проверь", state(TaskStage.EXECUTION)))
    }

    @Test
    fun `ensure - глагол и «план» создают PLANNING до вызова модели`() {
        val result = h.ensureForRequest("Составь план поездки и смету", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("Составь план поездки и смету", result?.step)
        assertEquals("", result?.expectedAction)
    }

    @Test
    fun `ensure - привет без намерения null`() {
        assertNull(h.ensureForRequest("Привет", null))
    }

    @Test
    fun `ensure - пустое сообщение null`() {
        assertNull(h.ensureForRequest("   ", null))
        assertNull(h.ensureForRequest(null, null))
    }

    // --- Стражи и капы ---

    @Test
    fun `страж - PAUSED текущий всегда null (ремень и подтяжки)`() {
        assertNull(
            h.infer(
                "сделай и проверь",
                "готово",
                TaskState(TaskStage.PAUSED, "шаг", "действие", TaskStage.EXECUTION)
            )
        )
    }

    @Test
    fun `кап - длинный шаг запроса обрезается до MAX_STEP_LENGTH`() {
        val long = "Сделай " + "х".repeat(200) + " и затем проверь"
        val result = h.infer(long, "принято.", null)

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals(MAX_STEP_LENGTH, result!!.step.length)
    }

    @Test
    fun `создание - ответ с код-фенсом в первом ходу всё равно создаёт PLANNING`() {
        val result = h.infer(
            "Сделай план, выполни и проверь",
            "Вот код:\n```\nfun f() {}\n```",
            null
        )

        assertEquals(TaskStage.PLANNING, result?.stage)
        assertEquals("Сделай план, выполни и проверь", result?.step)
    }
}
