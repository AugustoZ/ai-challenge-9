package com.example.aiweb.prefs

/**
 * Чистый рендер системного блока предпочтений (инъекция — Task 12):
 * заголовок + по строке на каждый вопрос опросника + финальная инструкция.
 *
 * Правила:
 *  - свободные поля обрезаются до [RENDER_FIELD_MAX] с многоточием, чтобы
 *    общий бюджет блока гарантированно укладывался в [BUDGET] символов
 *    (валидация допускает до 1000 символов на поле, три поля уже не влезают);
 *  - пустые свободные поля (избегаемые темы, интересы) строку не порождают —
 *    пустых значений в блоке не бывает;
 *  - «длина» выражается текстовой инструкцией с ориентиром в токенах:
 *    бэкенд игнорирует generation-параметры (AIClient.kt), слайдер «Топливо»
 *    остаётся отдельным жёстким капом и здесь не упоминается;
 *  - слово «profile»/«профиль» в блоке не используется (коллизия с видом
 *    долговременной памяти).
 */

/** Общий бюджет блока в символах. */
private const val BUDGET = 2500

/** Кап одного свободного поля при рендере (3 × 700 + фиксированные строки < [BUDGET]). */
private const val RENDER_FIELD_MAX = 700

private const val HEADER = "Предпочтения пользователя"

private const val FOOTER = "Следуй предпочтениям, если это не противоречит правилам системы."

/**
 * Ориентир по длине ответа в токенах: кратко ≈1000, средне ≈3000,
 * развернуто ≈10000 (план опросника). Неизвестное значение — среднее.
 */
private fun tokenGuide(length: String): String = when (length) {
    "кратко" -> "1000"
    "развернуто" -> "10000"
    else -> "3000"
}

/** Значение с многоточием, если не влезает в [max] символов. */
private fun ellipsize(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "…"

/**
 * Строит блок предпочтений для системного сообщения; бюджет — не более
 * [BUDGET] символов при любых валидных предпочтениях.
 */
fun renderPromptBlock(prefs: UserPreferences): String {
    val lines = listOf(
        HEADER,
        "Форма обращения: ${prefs.addressForm}",
        "Тон: ${prefs.tone}",
        "Язык: ${prefs.language}",
        "Структура ответов: ${ellipsize(prefs.answerStructure, PreferenceLimits.STRUCTURE_MAX)}",
        "Длина ответов: ${prefs.length} (ориентир ~${tokenGuide(prefs.length)} токенов)",
        "Избегаемые темы: ${ellipsize(prefs.avoidTopics, RENDER_FIELD_MAX)}".takeIf { prefs.avoidTopics.isNotBlank() },
        "Интересы: ${ellipsize(prefs.interests, RENDER_FIELD_MAX)}".takeIf { prefs.interests.isNotBlank() },
        FOOTER
    ).filterNotNull()

    return lines.joinToString("\n").take(BUDGET)
}
