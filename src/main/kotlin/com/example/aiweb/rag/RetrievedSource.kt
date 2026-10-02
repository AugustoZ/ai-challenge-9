package com.example.aiweb.rag

import kotlinx.serialization.Serializable

/**
 * Один документ, извлечённый RAG-поиском: метаданные чанка + короткий фрагмент.
 *
 * ФИКСИРОВАННЫЙ контракт (задача 4): в ответе пользователю уезжают только
 * метаданные и snippet (≤ [SNIPPET_MAX_CHARS] символов) — ПОЛНЫЙ текст чанка
 * (content) в DTO не попадает, чтобы не раздувать ChatResponse и бортовой журнал.
 *
 * Ключи camelCase — зеркало `frontend/src/api/types.ts` (задача 5/6).
 * Аннотация @EncodeDefault(ALWAYS) будет добавлена полю ChatResponse в задаче 6
 * (серверный Json имеет encodeDefaults=false); сам DTO — просто @Serializable.
 */
@Serializable
data class RetrievedSource(
    /** Идентификатор чанка в индексе (sidecar-поле chunk_id). */
    val chunkId: String,
    /** Путь к файлу-источнику (относительный, как в индексе). */
    val source: String,
    /** Заголовок документа. */
    val title: String,
    /** Раздел документа. */
    val section: String,
    /** Оценка релевантности от поисковика (double). */
    val score: Double,
    /** Короткий фрагмент содержимого чанка (≤ [SNIPPET_MAX_CHARS] символов); НЕ полный content. */
    val snippet: String
) {
    companion object {
        /** Максимальная длина snippet (контракт задачи 4). */
        const val SNIPPET_MAX_CHARS = 200
    }
}

/**
 * Сведение полного [Passage] к ответному DTO [RetrievedSource]: snippet = срез
 * контента до [RetrievedSource.SNIPPET_MAX_CHARS] символов, полный текст чанка
 * в ответ не уходит. Используется в handleAgent при сборке ChatResponse
 * (задача 6 плана rag-mode).
 */
fun Passage.toRetrievedSource(): RetrievedSource =
    RetrievedSource(
        chunkId = chunkId,
        source = source,
        title = title,
        section = section,
        score = score,
        snippet = content.take(RetrievedSource.SNIPPET_MAX_CHARS)
    )
