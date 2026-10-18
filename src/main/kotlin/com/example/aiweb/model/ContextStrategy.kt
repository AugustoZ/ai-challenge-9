package com.example.aiweb.model

/**
 * Стратегия формирования контекста запроса.
 *
 * @param id строковый идентификатор, который передаётся в ChatRequest.contextStrategy
 */
enum class ContextStrategy(val id: String) {
    /** Контекст не формируется: история и сводка в запрос не попадают. */
    OFF("off"),

    /** Окно последних сообщений уезжает в запрос «как есть». */
    WINDOW("window"),

    /** Сводка диалога подставляется в контекст (legacy-поведение компрессии). */
    SUMMARY("summary"),

    /** В контекст — список выделенных фактов. */
    FACTS("facts"),

    /** Разветвление диалога: снимки-ветки с переключением. */
    BRANCHING("branching");

    companion object {
        /** Разбирает id стратегии; null или неизвестное значение — null. */
        fun fromId(id: String?): ContextStrategy? =
            values().firstOrNull { it.id == id?.trim()?.lowercase() }

        /**
         * Разрешает стратегию по приоритету (от высшего к низшему):
         *  1. явное поле запроса [explicit] — валидный id выигрывает всегда;
         *     невалидный id — null (вызывающий отвечает 400);
         *  2. legacy-флаг [legacyCompression] — true → [SUMMARY], false → [OFF];
         *  3. значение из конфига [configDefault].
         */
        fun resolve(
            explicit: String?,
            legacyCompression: Boolean?,
            configDefault: ContextStrategy
        ): ContextStrategy? {
            if (explicit != null) {
                return fromId(explicit)
            }
            if (legacyCompression != null) {
                return if (legacyCompression) SUMMARY else OFF
            }
            return configDefault
        }
    }
}
