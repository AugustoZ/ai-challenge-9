package com.example.aiweb.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Проверка парсера/стриппера блока task-state [TaskStateJson]: последний из
 * нескольких блоков выигрывает, толерантный JSON, наследование отсутствующих
 * полей от текущего состояния, null при любом сбое, стрип всех блоков
 * с сохранением остального текста дословно.
 */
class TaskStateJsonTest {

    private fun currentState(
        stage: TaskStage = TaskStage.PLANNING,
        step: String = "старый шаг",
        action: String = "старое действие"
    ) = TaskState(stage, step, action)

    @Test
    fun `parse - block in long mixed answer is parsed`() {
        val text = """
            Завершаю этап планирования. Сначала разберу требования,
            затем разобью задачу на шаги.

            ```task-state
            {"stage":"planning","step":"разбор требований","expected_action":"разбить на шаги"}
            ```

            Если будут уточняющие вопросы — спрошу.
        """.trimIndent()

        val parsed = TaskStateJson.parse(text, null)!!

        assertEquals(TaskStage.PLANNING, parsed.stage)
        assertEquals("разбор требований", parsed.step)
        assertEquals("разбить на шаги", parsed.expectedAction)
    }

    @Test
    fun `parse - no block, null`() {
        assertNull(TaskStateJson.parse("обычный ответ без блока", null))
        assertNull(TaskStateJson.parse("", null))
        assertNull(TaskStateJson.parse("```json\n{\"stage\":\"planning\"}\n```", null), "чужой тип блока не наш")
    }

    @Test
    fun `parse - broken json or unclosed block, null`() {
        val broken = """
            ```task-state
            {"stage":"planning","step":
            ```
        """.trimIndent()
        assertNull(TaskStateJson.parse(broken, null))

        assertNull(TaskStateJson.parse("```task-state\n{\"stage\":\"planning\"}", null), "забор не закрыт")
        assertNull(TaskStateJson.parse("```task-state\n```\n", null), "пустой блок")
        assertNull(TaskStateJson.parse("```task-state\nэто не json\n```", null), "не-JSON в блоке")
    }

    @Test
    fun `parse - two blocks, last one wins`() {
        val text = """
            ```task-state
            {"stage":"planning","step":"старый шаг","expected_action":"старое действие"}
            ```
            Этап сменился:
            ```task-state
            {"stage":"execution","step":"новый шаг","expected_action":"новое действие"}
            ```
        """.trimIndent()

        val parsed = TaskStateJson.parse(text, null)!!

        assertEquals(TaskStage.EXECUTION, parsed.stage)
        assertEquals("новый шаг", parsed.step)
        assertEquals("новое действие", parsed.expectedAction)
    }

    @Test
    fun `parse - unknown or missing stage, null`() {
        assertNull(TaskStateJson.parse("```task-state\n{\"stage\":\"flying\",\"step\":\"s\"}\n```", null))
        assertNull(TaskStateJson.parse("```task-state\n{\"step\":\"s\",\"expected_action\":\"a\"}\n```", null))
    }

    @Test
    fun `parse - stage is case-insensitive and unquoted keys are lenient`() {
        val parsed = TaskStateJson.parse("""```task-state
            {stage:"VALIDATION",step:"s",expected_action:"a"}
            ```""", null)!!

        assertEquals(TaskStage.VALIDATION, parsed.stage)
    }

    @Test
    fun `parse - missing step and expected_action are inherited from current`() {
        val parsed = TaskStateJson.parse("""```task-state
            {"stage":"planning"}
            ```""", currentState())!!

        assertEquals(TaskStage.PLANNING, parsed.stage)
        assertEquals("старый шаг", parsed.step)
        assertEquals("старое действие", parsed.expectedAction)
    }

    @Test
    fun `parse - missing fields without current, empty strings`() {
        val parsed = TaskStateJson.parse("""```task-state
            {"stage":"execution","step":"есть шаг"}
            ```""", null)!!

        assertEquals(TaskStage.EXECUTION, parsed.stage)
        assertEquals("есть шаг", parsed.step)
        assertEquals("", parsed.expectedAction)
    }

    @Test
    fun `parse - present fields override current`() {
        val parsed = TaskStateJson.parse("""```task-state
            {"stage":"validation","step":"новый шаг","expected_action":"новое действие"}
            ```""", currentState())!!

        assertEquals(TaskStage.VALIDATION, parsed.stage)
        assertEquals("новый шаг", parsed.step)
        assertEquals("новое действие", parsed.expectedAction)
    }

    @Test
    fun `parse - unknown json fields are ignored`() {
        val parsed = TaskStateJson.parse("""```task-state
            {"stage":"planning","step":"s","expected_action":"a","confidence":0.9}
            ```""", null)!!

        assertEquals(TaskStage.PLANNING, parsed.stage)
    }

    @Test
    fun `strip - removes all blocks, rest of the text verbatim`() {
        val text = """
            Текст до блока.
            ```task-state
            {"stage":"planning","step":"s","expected_action":"a"}
            ```
            Средний текст.
            ```task-state
            {"stage":"execution","step":"s2","expected_action":"a2"}
            ```
            Текст после блока.
        """.trimIndent()

        val stripped = TaskStateJson.strip(text)

        assertEquals("Текст до блока.\n\nСредний текст.\n\nТекст после блока.", stripped)
        assertEquals(stripped, TaskStateJson.strip(stripped), "повторный стрип идемпотентен")
    }

    @Test
    fun `strip - text without block is unchanged`() {
        val text = "Просто ответ без блока\nс несколькими строками"

        assertEquals(text, TaskStateJson.strip(text))
    }
}
