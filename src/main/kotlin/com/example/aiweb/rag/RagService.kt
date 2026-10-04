package com.example.aiweb.rag

/**
 * Результат [RagService.retrieve] — обёртка статуса, различающая два исхода:
 * - [RetrieveResult.Ok] — sidecar ответил (список может быть ПУСТЫМ: всё ниже
 *   порога threshold — это «пусто», а не «недоступен»);
 * - [RetrieveResult.Unavailable] — RAG недоступен (сеть, таймаут, HTTP != 2xx,
 *   кривой JSON); [RetrieveResult.Unavailable.reason] — причина для статуса/лога.
 *
 * Fail-open семантика сохраняется: срыв НЕ роняет основной ответ — вызывающий
 * код распаковывает [RetrieveResult] в список (пустой при Unavailable).
 */
sealed interface RetrieveResult {
    /** Sidecar ответил: [passages] может быть пуст (всё ниже порога отсечения). */
    data class Ok(val passages: List<Passage>) : RetrieveResult

    /** RAG недоступен; [reason] — человекочитаемая причина сбоя (для warn-лога/статуса). */
    data class Unavailable(val reason: String) : RetrieveResult
}

/**
 * Надстройка над [RagClient] с fail-open семантикой (по образцу runJudge,
 * Routes.kt:2476-2515): недоступный RAG НЕ роняет основной ответ —
 * ошибки гасятся в [RetrieveResult.Unavailable] с warn-логом
 * (не тихое проглатывание: причина видна и в результате, и в stderr).
 */
class RagService(private val client: RagClient) {

    /**
     * Ищет релевантные чанки; любая ошибка поиска (сеть, таймаут, HTTP, разбор)
     * превращается в [RetrieveResult.Unavailable] с предупреждением «RAG недоступен».
     * Успешный ответ sidecar — [RetrieveResult.Ok]; пустой список внутри Ok значит
     * «ниже порога», а не «недоступен». Пассажи — [Passage] с ПОЛНЫМ content —
     * обрезка до snippet выполняется при сведении к ответному DTO
     * ([Passage.toRetrievedSource]).
     *
     * Стадия 2 (T9): параметры kBefore/threshold/rerank прокидываются в [RagClient].
     * Деградация режима: при активном rerank и ответе sidecar 400 «Реранкер не
     * настроен» выполняется ОДИН повторный поиск БЕЗ реранка + русский warn
     * (это деградация режима, НЕ общий retry — retry-механик в [RagClient] нет).
     */
    suspend fun retrieve(
        query: String,
        k: Int,
        kBefore: Int? = null,
        threshold: Double? = null,
        rerank: Boolean = false
    ): RetrieveResult =
        runCatching { client.search(query, k, kBefore, threshold, rerank) }.fold(
            onSuccess = { RetrieveResult.Ok(it) },
            onFailure = { error ->
                if (rerank && error.message?.contains(RERANK_NOT_CONFIGURED) == true) {
                    // Реранкер не поднят: деградация режима — повторяем поиск без него.
                    System.err.println("Предупреждение: Реранкер не настроен, повторяю поиск без реранкера")
                    retrieveWithoutRerank(query, k, kBefore, threshold)
                } else {
                    System.err.println("Предупреждение: RAG недоступен: ${error.message}")
                    RetrieveResult.Unavailable(error.message ?: "неизвестная ошибка")
                }
            }
        )

    private suspend fun retrieveWithoutRerank(query: String, k: Int, kBefore: Int?, threshold: Double?): RetrieveResult =
        runCatching { client.search(query, k, kBefore, threshold, rerank = false) }.fold(
            onSuccess = { RetrieveResult.Ok(it) },
            onFailure = { retryError ->
                System.err.println("Предупреждение: RAG недоступен: ${retryError.message}")
                RetrieveResult.Unavailable(retryError.message ?: "неизвестная ошибка")
            }
        )

    companion object {
        /** Маркер в сообщении ошибки sidecar (400): реранкер не поднят. */
        const val RERANK_NOT_CONFIGURED = "Реранкер не настроен"
    }
}
