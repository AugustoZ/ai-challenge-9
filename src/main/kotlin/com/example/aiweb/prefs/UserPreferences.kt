@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.aiweb.prefs

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Предпочтения пользователя («Предпочтения», НЕ «profile» — имя занято видом
 * долговременной памяти): ответы на опросник из 7 вопросов, применяемые
 * серверно к каждому LLM-запросу.
 *
 * Поля — строки, а не enum'ы: поле [answerStructure] допускает произвольный
 * текст («иное»), а остальные проверяются на членство в словарях
 * [PreferenceLimits] при валидации PUT-запроса и при записи в хранилище.
 *
 * Значения соответствуют вариантам визарда на фронтенде (Task 13) и
 * передаются по wire как есть — см. frontend/src/api/types.ts.
 *
 * Все поля имеют значения по умолчанию, поэтому каждый помечен
 * @EncodeDefault(ALWAYS): серверный Json кодирует с encodeDefaults=false —
 * без аннотации GET дефолтных предпочтений отвечал бы пустым объектом {}.
 */
@Serializable
data class UserPreferences(
    /** Форма обращения: «ты» | «вы» | «пилот» | «гонщик». */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val addressForm: String = DEFAULT_ADDRESS_FORM,
    /** Тон общения: «формальный» | «дружелюбный» | «панибратский» | «роботизированный». */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val tone: String = DEFAULT_TONE,
    /** Язык ответов модели: «Русский» | «Английский» (интерфейс остаётся русским). */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val language: String = DEFAULT_LANGUAGE,
    /** Структура ответов: «таблицы» | «текст» | «стихи» | произвольное описание ≤255 символов. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val answerStructure: String = DEFAULT_STRUCTURE,
    /** Длина ответов: «кратко» | «средне» | «развернуто» — текстовая инструкция модели. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val length: String = DEFAULT_LENGTH,
    /** Избегаемые темы — свободный текст ≤1000 символов. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val avoidTopics: String = "",
    /** Интересы пользователя — свободный текст ≤1000 символов. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val interests: String = ""
) {
    companion object {
        /** Значения по умолчанию (прерванный визард / пользователь без настроек). */
        const val DEFAULT_ADDRESS_FORM = "ты"
        const val DEFAULT_TONE = "дружелюбный"
        const val DEFAULT_LANGUAGE = "Русский"
        const val DEFAULT_STRUCTURE = "текст"
        const val DEFAULT_LENGTH = "средне"

        /** Готовый дефолтный набор: ты / дружелюбный / Русский / текст / средне / "" / "". */
        val DEFAULTS = UserPreferences()
    }
}
