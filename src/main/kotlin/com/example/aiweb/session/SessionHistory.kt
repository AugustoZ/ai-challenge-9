package com.example.aiweb.session

import com.example.aiweb.model.OpenAIMessage
import kotlin.math.ceil

/**
 * Подготовка истории сессии к отправке в модель: обрезка старых ходов, чтобы
 * история + новый запрос + «Топливо» (maxTokens) влезли в окно модели.
 * Частая точка 400-х от API — слишком длинный контекст.
 */
object SessionHistory {

    /** Оценка объёма истории в токенах до вычета резерва под новый запрос. */
    const val MAX_HISTORY_TOKENS = 24_000

    private const val ROLE_USER = "user"

    /**
     * Грубая оценка токенов сообщения. Русский текст у моделей класса DeepSeek
     * занимает ~2–3 символа на токен, поэтому привычный chars/4 систематически
     * занижает оценку; берём запасной делитель 2.5.
     */
    fun estimateTokens(message: OpenAIMessage): Int =
        ceil((message.content?.length ?: 0) / 2.5).toInt()

    /**
     * Обрезает историю так, чтобы её оценка уложилась в бюджет
     * [MAX_HISTORY_TOKENS] - [reserveTokens] (резерв — «Топливо» и новый запрос).
     *
     * Правила:
     *  - история набирется от свежих ходов к старым, ход добавляется только целиком;
     *  - резать можно только перед user-сообщением: пара assistant(tool_calls) →
     *    role="tool" всегда остаётся внутри хода, который её породил;
     *  - последний ход (от свежей user-подсказки до конца истории) сохраняется
     *    всегда, даже если он один выбивается из бюджета.
     *
     * История без единого user-сообщения не сохраняется — нечего ставить в начало
     * среза без риска оторвать role="tool" от её assistant(tool_calls).
     */
    fun trim(history: List<OpenAIMessage>, reserveTokens: Int): List<OpenAIMessage> {
        if (history.isEmpty()) return emptyList()

        val budget = (MAX_HISTORY_TOKENS - reserveTokens).coerceAtLeast(0)

        // Хвост с последнего user-сообщения — обязательный минимум диалога
        var start = history.indexOfLast { it.role == ROLE_USER }
        if (start < 0) return emptyList()

        var used = history.subList(start, history.size).sumOf { estimateTokens(it) }

        // Добавляем старые ходы целиком, пока суммарная оценка влезает в бюджет
        while (start > 0) {
            val prevTurn = history.subList(0, start).indexOfLast { it.role == ROLE_USER }
            if (prevTurn < 0) break
            val turnCost = history.subList(prevTurn, start).sumOf { estimateTokens(it) }
            if (used + turnCost > budget) break
            used += turnCost
            start = prevTurn
        }

        // Гвард: срез обязан начинаться с user-сообщения — иначе можно оторвать
        // role="tool" от её assistant(tool_calls). По построению start уже указывает
        // на user; цикл страхует от разрыва пар при будущих правках алгоритма.
        while (start < history.size && history[start].role != ROLE_USER) start++

        return history.subList(start, history.size).toList()
    }
}
