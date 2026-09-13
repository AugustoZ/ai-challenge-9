package com.example.aiweb.client

import com.example.aiweb.model.Fact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверка промптов экстракции фактов [FactPrompts]: system-промпт обязан содержать
 * маркер «Извлечение фактов» (по нему mock-LLM распознаёт запрос, как «Сводка ранее»
 * у суммаризации), user-контекст несёт текущие факты и новое сообщение пользователя.
 * Парсер [FactPrompts.parseFacts] защищённый: любой сбой → null, исключений нет.
 */
class FactPromptsTest {

    @Test
    fun `buildMessages содержит маркер, текущие факты и новое сообщение`() {
        val messages = FactPrompts.buildMessages(
            listOf(Fact("цель", "тест"), Fact("имя", "Аугусто")),
            "новое сообщение"
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(
            messages[0].content!!.contains(FactPrompts.FACTS_MARKER),
            "system-промпт содержит маркер «Извлечение фактов»"
        )
        assertEquals("user", messages[1].role)
        assertTrue(
            messages[1].content!!.contains("Текущие факты (JSON): "),
            "user-контекст открывается текущими фактами"
        )
        assertTrue(messages[1].content!!.contains("цель"), "ключ текущего факта в user-контексте")
        assertTrue(messages[1].content!!.contains("Аугусто"), "значение текущего факта в user-контексте")
        assertTrue(
            messages[1].content!!.contains("Новое сообщение пользователя: новое сообщение"),
            "новое сообщение пользователя идёт после текущих фактов"
        )
    }

    @Test
    fun `buildMessages без текущих фактов - пустой JSON-массив в user-контексте`() {
        val messages = FactPrompts.buildMessages(emptyList(), "старт диалога")

        assertTrue(
            messages[1].content!!.contains("Текущие факты (JSON): []"),
            "пустой список фактов сериализуется в []"
        )
    }

    @Test
    fun `parseFacts - валидный массив разбирается в List Fact`() {
        val facts = FactPrompts.parseFacts("""[{"key":"цель","value":"тест"}]""")

        assertEquals(listOf(Fact("цель", "тест")), facts)
    }

    @Test
    fun `parseFacts - массив из окружающего текста и markdown-блока`() {
        val facts = FactPrompts.parseFacts("Вот факты:\n```json\n[{\"key\":\"a\",\"value\":\"1\"}]\n```")

        assertEquals(listOf(Fact("a", "1")), facts)
    }

    @Test
    fun `parseFacts - больше 10 фактов обрезается до 10`() {
        val reply = (1..15).joinToString(",", "[", "]") { """{"key":"k$it","value":"v$it"}""" }

        val facts = FactPrompts.parseFacts(reply)!!

        assertEquals(10, facts.size, "лимит 10 фактов")
        assertEquals("k1", facts.first().key, "первые 10 сохраняются")
        assertEquals("k10", facts.last().key)
    }

    @Test
    fun `parseFacts - обрезанный JSON в null`() {
        assertNull(FactPrompts.parseFacts("""[{"key":"a","value":"1""""))
    }

    @Test
    fun `parseFacts - не-JSON мусор в null`() {
        assertNull(FactPrompts.parseFacts("не json вообще"))
        assertNull(FactPrompts.parseFacts("[мусор без JSON]"))
    }

    @Test
    fun `parseFacts - пустой ответ в null`() {
        assertNull(FactPrompts.parseFacts(""))
    }
}
