package com.example.aiweb.rag

/**
 * Полные данные одного чанка: content + метаданные (в отличие от
 * [RetrievedSource] — БЕЗ ограничений на длину текста).
 * Нужен только для сборки промт-блока; в ChatResponse не попадает.
 */
data class Passage(
    /** Идентификатор чанка в индексе. */
    val chunkId: String,
    /** Путь к файлу-источнику. */
    val source: String,
    /** Заголовок документа. */
    val title: String,
    /** Раздел документа. */
    val section: String,
    /** Оценка релевантности. */
    val score: Double,
    /** ПОЛНЫЙ текст чанка. */
    val content: String
)

/**
 * Сборка RAG-блока для system-промта (маркер-константа по образцу JudgePrompts).
 *
     * Точную строку [RAG_MARKER] копирует в себя mock-llm.py (задача 8) —
     * менять формулировку маркера нельзя без синхронной правки мока; текст
     * [INSTRUCTION] моком не парсится и может меняться независимо.
 */
object RagPrompts {

    /** Маркер начала RAG-блока в system-сообщении. */
    const val RAG_MARKER = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"

    /**
     * Инструкция модели: отвечать строго по приведённым документам,
     * не выводить собственные блоки «Источники:»/«Цитаты:» (их добавляет
     * бэкенд) и честно говорить «не знаю», если документов недостаточно.
     */
    private const val INSTRUCTION =
        "Отвечай строго по приведённым документам, опираясь только на их содержание; " +
        "в тексте ответа допускай нумерованные ссылки вида [1]. " +
        "Не выводи собственные разделы «Источники:» и «Цитаты:» — такие блоки добавляет " +
        "система после твоего ответа, поэтому не дублируй и не имитируй их. " +
        "Если документов недостаточно для точного ответа, честно скажи об этом " +
        "(например: «Не знаю — в приведённых документах об этом нет данных»), " +
        "а не домысливай факты."

    /** Многоточие, которым помечается пер-чанк обрезка. */
    private const val ELLIPSIS = "…"

    /** Минимальный осмысленный остаток текста чанка при обрезке (символы). */
    private const val MIN_CHUNK_FLOOR = 20

    /**
     * Собирает блок документов для инжекта в system-сообщение:
     *
     * ```
     * РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ
     * <INSTRUCTION: отвечай строго по приведённым документам, без собственных
     *  разделов «Источники:»/«Цитаты:», честное «не знаю» при недостатке данных>
     *
     * [1] source: docs/faq.md · раздел: Порталы
     * текст чанка...
     *
     * [2] source: ...
     * ```
     *
     * Суммарный размер блока ограничен [maxChars]: чанки добавляются по очереди,
     * не влезающий текст обрезается с «…», совсем не помещающиеся блоки — отбрасываются.
     * Разметка БЕЗ обратных кавычек/фенсов: чанки могут содержать markdown
     * (в т.ч. ```-фенсы), и обрамление фенсами ломало бы их интерпретацию.
     *
     * @return собранный блок; пустая строка, если [passages] пуст — инжектить нечего.
     */
    fun buildRagBlock(passages: List<Passage>, maxChars: Int): String {
        if (passages.isEmpty()) return ""

        val header = "$RAG_MARKER\n$INSTRUCTION"
        val sb = StringBuilder(header)
        var used = header.length

        for ((index, passage) in passages.withIndex()) {
            val prefix = "\n\n[${index + 1}] source: ${passage.source} · раздел: ${passage.section}\n"
            val budget = maxChars - used - prefix.length
            if (budget < MIN_CHUNK_FLOOR) break // на следующий чанк места нет — блок завершён

            val text = if (passage.content.length <= budget) {
                passage.content
            } else {
                passage.content.take(budget - ELLIPSIS.length).trimEnd() + ELLIPSIS
            }
            sb.append(prefix).append(text)
            used += prefix.length + text.length
        }

        return sb.toString()
    }
}
