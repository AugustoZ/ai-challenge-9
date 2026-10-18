package com.example.aiweb.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки блока предпочтений для системного сообщения: все 7 строк,
 * бюджет ≤2500 символов, обрезка свободных полей при рендере,
 * отсутствие пустых строк для незаполненных полей.
 */
class PromptBlockTest {

    @Test
    fun `дефолтный блок содержит форму обращения и тон`() {
        val block = renderPromptBlock(UserPreferences.DEFAULTS)

        assertTrue(block.contains("Предпочтения пользователя"), "Блок: $block")
        assertTrue(block.contains("Форма обращения: ты"), "Блок: $block")
        assertTrue(block.contains("Тон: дружелюбный"), "Блок: $block")
        assertTrue(block.contains("Язык: Русский"), "Блок: $block")
        assertTrue(block.contains("Структура ответов: текст"), "Блок: $block")
        assertTrue(block.contains("Длина ответов: средне"), "Блок: $block")
        assertTrue(block.contains("~3000 токенов"), "Блок: $block")
    }

    @Test
    fun `финальная инструкция присутствует`() {
        val block = renderPromptBlock(UserPreferences.DEFAULTS)

        assertTrue(
            block.contains("Следуй предпочтениям, если это не противоречит правилам системы."),
            "Блок: $block"
        )
    }

    @Test
    fun `заполненный опросник отражается всеми строками`() {
        val block = renderPromptBlock(
            UserPreferences(
                addressForm = "гонщик",
                tone = "роботизированный",
                language = "Английский",
                answerStructure = "таблицы",
                length = "развернуто",
                avoidTopics = "политика",
                interests = "гонки"
            )
        )

        assertTrue(block.contains("Форма обращения: гонщик"), "Блок: $block")
        assertTrue(block.contains("Тон: роботизированный"), "Блок: $block")
        assertTrue(block.contains("Язык: Английский"), "Блок: $block")
        assertTrue(block.contains("Структура ответов: таблицы"), "Блок: $block")
        assertTrue(block.contains("Длина ответов: развернуто"), "Блок: $block")
        assertTrue(block.contains("~10000 токенов"), "Блок: $block")
        assertTrue(block.contains("Избегаемые темы: политика"), "Блок: $block")
        assertTrue(block.contains("Интересы: гонки"), "Блок: $block")
    }

    @Test
    fun `кратко соответствует ориентиру в 1000 токенов`() {
        val block = renderPromptBlock(UserPreferences.DEFAULTS.copy(length = "кратко"))

        assertTrue(block.contains("~1000 токенов"), "Блок: $block")
    }

    @Test
    fun `пустые свободные поля не порождают строк`() {
        val block = renderPromptBlock(UserPreferences.DEFAULTS)

        assertFalse(block.contains("Избегаемые темы"), "Пустая тема не должна рендериться: $block")
        assertFalse(block.contains("Интересы"), "Пустые интересы не должны рендериться: $block")
    }

    @Test
    fun `свободные поля длиннее бюджета рендера обрезаются`() {
        val block = renderPromptBlock(
            UserPreferences.DEFAULTS.copy(
                avoidTopics = "т".repeat(PreferenceLimits.AVOID_TOPICS_MAX),
                interests = "и".repeat(PreferenceLimits.INTERESTS_MAX),
                answerStructure = "с".repeat(PreferenceLimits.STRUCTURE_MAX)
            )
        )

        assertTrue(block.contains("…"), "Обрезка должна быть видимой: $block")
        assertFalse(block.contains("т".repeat(PreferenceLimits.AVOID_TOPICS_MAX)), "Поле не обрезано")
        assertTrue(block.length <= 2500, "Бюджет превышен: ${block.length}")
    }

    @Test
    fun `блок всегда укладывается в бюджет 2500 символов`() {
        val variants = listOf(
            UserPreferences.DEFAULTS,
            UserPreferences(
                addressForm = "пилот",
                tone = "панибратский",
                language = "Английский",
                answerStructure = "и".repeat(PreferenceLimits.STRUCTURE_MAX),
                length = "развернуто",
                avoidTopics = "а".repeat(PreferenceLimits.AVOID_TOPICS_MAX),
                interests = "и".repeat(PreferenceLimits.INTERESTS_MAX)
            )
        )

        variants.forEach { prefs ->
            val block = renderPromptBlock(prefs)
            assertTrue(block.length <= 2500, "Бюджет превышен: ${block.length} для $prefs")
        }
    }

    @Test
    fun `в блоке нет слова profile`() {
        val block = renderPromptBlock(UserPreferences.DEFAULTS)

        assertFalse(block.lowercase().contains("profile"), "Блок: $block")
        assertFalse(block.contains("профил"), "Блок: $block")
    }

    @Test
    fun `максимум свободных полей обрезается до одинаковой длины`() {
        val block = renderPromptBlock(
            UserPreferences.DEFAULTS.copy(
                avoidTopics = "т".repeat(PreferenceLimits.AVOID_TOPICS_MAX)
            )
        )

        val line = block.lineSequence().first { it.startsWith("Избегаемые темы: ") }
        assertEquals("Избегаемые темы: " + "т".repeat(700) + "…", line)
    }
}
