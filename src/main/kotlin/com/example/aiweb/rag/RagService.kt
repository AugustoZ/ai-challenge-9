package com.example.aiweb.rag

/**
 * Надстройка над [RagClient] с fail-open семантикой (по образцу runJudge,
 * Routes.kt:2476-2515): недоступный RAG НЕ роняет основной ответ —
 * вместо документов возвращается пустой список, а срыв пишется в warn-лог
 * (не тихое проглатывание: причина видна в stderr).
 */
class RagService(private val client: RagClient) {

    /**
     * Ищет релевантные чанки; любая ошибка поиска (сеть, таймаут, HTTP, разбор)
     * превращается в emptyList с предупреждением «RAG недоступен».
     * Возвращает [Passage] с ПОЛНЫМ content — обрезка до snippet выполняется
     * при сведении к ответному DTO ([Passage.toRetrievedSource]).
     */
    suspend fun retrieve(query: String, k: Int): List<Passage> =
        runCatching { client.search(query, k) }.fold(
            onSuccess = { it },
            onFailure = { error ->
                System.err.println("Предупреждение: RAG недоступен: ${error.message}")
                emptyList()
            }
        )
}
