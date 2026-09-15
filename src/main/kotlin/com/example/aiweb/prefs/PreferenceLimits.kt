package com.example.aiweb.prefs

/**
 * Жёсткие лимиты и словари предпочтений (по образцу [com.example.aiweb.memory.MemoryLimits]):
 * капы длин свободных полей и допустимые значения фиксированных вопросов опросника.
 * Единая точка правды — валидация PUT /api/preferences, хранилище и фронтенд-зеркало
 * сверяются с ней.
 */
object PreferenceLimits {

    /** Максимум символов в свободном описании структуры ответов («иное»). */
    const val STRUCTURE_MAX = 255

    /** Максимум символов в избегаемых темах. */
    const val AVOID_TOPICS_MAX = 1000

    /** Максимум символов в интересах. */
    const val INTERESTS_MAX = 1000

    /** Допустимые формы обращения. */
    val ADDRESS_FORMS = listOf("ты", "вы", "пилот", "гонщик")

    /** Допустимые тоны общения. */
    val TONES = listOf("формальный", "дружелюбный", "панибратский", "роботизированный")

    /** Допустимые языки ответов модели. */
    val LANGUAGES = listOf("Русский", "Английский")

    /** Допустимые варианты длины ответов. */
    val LENGTHS = listOf("кратко", "средне", "развернуто")

    /** Предустановленные структуры ответов; всё остальное — свободное описание. */
    val STRUCTURE_PRESETS = listOf("таблицы", "текст", "стихи")

    /**
     * Валидирует и очищает предпочтения (серверная линия обороны от мусора
     * в промпте). Возвращает sanitized-копию; при нарушении бросает
     * [IllegalArgumentException] с русским текстом, называющим поле.
     *
     * Правила:
     *  - фиксированные поля — точное членство в словарях (без очистки:
     *    «ты\u0000» членом словаря не является и отклоняется);
     *  - свободные поля ([answerStructure] вне пресетов, [avoidTopics],
     *    [interests]) — контрольные символы заменяются пробелом и текст
     *    триммится (перенос строки не должен ломать однострочный рендер
     *    блока предпочтений), затем проверяется длина.
     */
    fun validate(prefs: UserPreferences): UserPreferences {
        require(prefs.addressForm in ADDRESS_FORMS) {
            "Форма обращения: допустимые значения — ${ADDRESS_FORMS.joinToString(", ")}"
        }
        require(prefs.tone in TONES) {
            "Тон: допустимые значения — ${TONES.joinToString(", ")}"
        }
        require(prefs.language in LANGUAGES) {
            "Язык: допустимые значения — ${LANGUAGES.joinToString(", ")}"
        }
        require(prefs.length in LENGTHS) {
            "Длина ответов: допустимые значения — ${LENGTHS.joinToString(", ")}"
        }

        val structure = if (prefs.answerStructure in STRUCTURE_PRESETS) {
            prefs.answerStructure
        } else {
            sanitize(prefs.answerStructure)
        }
        require(structure.isNotEmpty()) {
            "Структура ответов: допустимые значения — ${STRUCTURE_PRESETS.joinToString(", ")}, " +
                "либо описание до $STRUCTURE_MAX символов"
        }
        require(structure.length <= STRUCTURE_MAX) {
            "Структура ответов: длиннее $STRUCTURE_MAX символов"
        }

        val avoidTopics = sanitize(prefs.avoidTopics)
        require(avoidTopics.length <= AVOID_TOPICS_MAX) {
            "Избегаемые темы: длиннее $AVOID_TOPICS_MAX символов"
        }

        val interests = sanitize(prefs.interests)
        require(interests.length <= INTERESTS_MAX) {
            "Интересы: длиннее $INTERESTS_MAX символов"
        }

        return prefs.copy(
            answerStructure = structure,
            avoidTopics = avoidTopics,
            interests = interests
        )
    }

    /** Контрольные символы → пробел (перенос строки не должен ломать рендер), лишние края срезаются. */
    private fun sanitize(text: String): String =
        buildString(text.length) {
            for (ch in text) append(if (Character.isISOControl(ch)) ' ' else ch)
        }.trim()
}
