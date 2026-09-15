package com.example.aiweb.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки модели предпочтений и валидации [PreferenceLimits]:
 * enum-членство, лимиты символов, зачистка контрольных символов,
 * русские сообщения об ошибках с названием поля.
 */
class UserPreferencesTest {

    @Test
    fun `дефолты соответствуют опроснику`() {
        val defaults = UserPreferences.DEFAULTS

        assertEquals("ты", defaults.addressForm)
        assertEquals("дружелюбный", defaults.tone)
        assertEquals("Русский", defaults.language)
        assertEquals("текст", defaults.answerStructure)
        assertEquals("средне", defaults.length)
        assertEquals("", defaults.avoidTopics)
        assertEquals("", defaults.interests)
    }

    @Test
    fun `неизвестная форма обращения отклоняется с русским текстом`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(UserPreferences.DEFAULTS.copy(addressForm = "эскимо"))
        }
        assertTrue(error.message!!.contains("Форма обращения"), "Сообщение: ${error.message}")
        assertTrue(error.message!!.contains("ты"), "Сообщение должно называть допустимые значения: ${error.message}")
    }

    @Test
    fun `неизвестный тон отклоняется`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(UserPreferences.DEFAULTS.copy(tone = "хамский"))
        }
        assertTrue(error.message!!.contains("Тон"), "Сообщение: ${error.message}")
    }

    @Test
    fun `неизвестный язык отклоняется`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(UserPreferences.DEFAULTS.copy(language = "Французский"))
        }
        assertTrue(error.message!!.contains("Язык"), "Сообщение: ${error.message}")
    }

    @Test
    fun `неизвестная длина отклоняется`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(UserPreferences.DEFAULTS.copy(length = "бесконечно"))
        }
        assertTrue(error.message!!.contains("Длина"), "Сообщение: ${error.message}")
    }

    @Test
    fun `структура ответов длиннее лимита отклоняется`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(
                UserPreferences.DEFAULTS.copy(answerStructure = "х".repeat(PreferenceLimits.STRUCTURE_MAX + 1))
            )
        }
        assertTrue(error.message!!.contains("Структура"), "Сообщение: ${error.message}")
    }

    @Test
    fun `избегаемые темы длиннее лимита отклоняются`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(
                UserPreferences.DEFAULTS.copy(avoidTopics = "т".repeat(PreferenceLimits.AVOID_TOPICS_MAX + 1))
            )
        }
        assertTrue(error.message!!.contains("Избегаемые темы"), "Сообщение: ${error.message}")
    }

    @Test
    fun `интересы длиннее лимита отклоняются`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(
                UserPreferences.DEFAULTS.copy(interests = "x".repeat(PreferenceLimits.INTERESTS_MAX + 1))
            )
        }
        assertTrue(error.message!!.contains("Интересы"), "Сообщение: ${error.message}")
    }

    @Test
    fun `поля на самой границе лимитов проходят`() {
        val edge = UserPreferences(
            addressForm = "гонщик",
            tone = "роботизированный",
            language = "Английский",
            answerStructure = "с".repeat(PreferenceLimits.STRUCTURE_MAX),
            length = "развернуто",
            avoidTopics = "а".repeat(PreferenceLimits.AVOID_TOPICS_MAX),
            interests = "и".repeat(PreferenceLimits.INTERESTS_MAX)
        )

        assertEquals(edge, PreferenceLimits.validate(edge))
    }

    @Test
    fun `форма обращения с контрольным символом отклоняется`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PreferenceLimits.validate(UserPreferences.DEFAULTS.copy(addressForm = "ты\u0000"))
        }
        assertTrue(error.message!!.contains("Форма обращения"), "Сообщение: ${error.message}")
    }

    @Test
    fun `свободные поля очищаются от контрольных символов`() {
        val validated = PreferenceLimits.validate(
            UserPreferences.DEFAULTS.copy(
                avoidTopics = "политика\rрелигия",
                interests = "космос\u0007гонки",
                answerStructure = "списки\u000Cс примерами"
            )
        )

        assertEquals("политика религия", validated.avoidTopics)
        assertEquals("космос гонки", validated.interests)
        assertEquals("списки с примерами", validated.answerStructure)
    }

    @Test
    fun `весь опросник проходит валидацию без изменений`() {
        val full = UserPreferences(
            addressForm = "пилот",
            tone = "панибратский",
            language = "Английский",
            answerStructure = "таблицы",
            length = "кратко",
            avoidTopics = "политика",
            interests = "гонки"
        )

        assertEquals(full, PreferenceLimits.validate(full))
    }
}
