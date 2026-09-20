package com.example.aiweb.invariants

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Инвариант — непреложное правило для ассистента: id (UUID) и текст.
 *
 * Капы — константы [MAX_TEXT] и [MAX_COUNT]: текст 1..300 символов после trim,
 * не более 20 инвариантов на скоуп. Единственная точка валидации —
 * [normalizeAndValidate]; нарушение бросает [IllegalArgumentException]
 * с русским текстом (в стиле [com.example.aiweb.prefs.PreferenceLimits]),
 * роуты транслируют его в 400.
 */
@Serializable
data class StoredInvariant(val id: String, val text: String) {

    companion object {

        /** Максимум символов в тексте инварианта (после trim). */
        const val MAX_TEXT = 300

        /** Максимум инвариантов на скоуп (глобальный/сессия). */
        const val MAX_COUNT = 20

        /** Создаёт инвариант с новым UUID; текст валидируется и триммится. */
        fun create(text: String): StoredInvariant =
            StoredInvariant(id = UUID.randomUUID().toString(), text = normalizeAndValidate(text))

        /**
         * Trim-обработка и валидация текста инварианта. Возвращает очищенный
         * текст; пустой после trim — [IllegalArgumentException], длина больше
         * [MAX_TEXT] — [IllegalArgumentException] с указанием капа.
         */
        fun normalizeAndValidate(text: String): String {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) {
                "Инвариант: текст не может быть пустым"
            }
            require(trimmed.length <= MAX_TEXT) {
                "Инвариант: текст длиннее $MAX_TEXT символов"
            }
            return trimmed
        }
    }
}
