package com.example.aiweb.memory

/**
 * Keyword-движок поиска долговременной памяти: детерминированный подстрочный
 * скоринг по токенам запроса, без эмбеддингов. Реализация [MemorySearchEngine]
 * на стадии 0; FTS5/векторные/гибридные движки появятся на стадиях 1–2.
 */
class KeywordMemorySearch(
    /** Хранилище-источник кандидатов (долговременная память). */
    private val repository: MemoryRepository
) : MemorySearchEngine {

    /**
     * Релевантные записи: профили исключаются всегда (ретривер включает их
     * отдельно, без скоринга), нулевой скоринг отбрасывается; сортировка —
     * по убыванию score, при равенстве — свежие по updatedAt первыми.
     * Пустой запрос (или запрос без токенов) — пустой список.
     */
    override fun search(query: String, kind: String?, limit: Int): List<ScoredMemoryEntry> {
        if (query.isBlank()) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return repository.list(kind)
            .filter { it.kind != KIND_EXCLUDED }
            .map { entry -> ScoredMemoryEntry(entry, score(entry, tokens).toDouble()) }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<ScoredMemoryEntry> { it.score }
                    .thenByDescending { it.entry.updatedAt }
            )
            .take(limit)
    }

    /**
     * Токены запроса: подстроки из букв и цифр (кириллица и латиница)
     * длиной не менее [TOKEN_MIN_LENGTH]; запрос предварительно в нижний регистр.
     */
    private fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        val run = StringBuilder()
        for (char in query.lowercase()) {
            if (char.isLetterOrDigit()) {
                run.append(char)
            } else if (run.isNotEmpty()) {
                if (run.length >= TOKEN_MIN_LENGTH) tokens.add(run.toString())
                run.clear()
            }
        }
        if (run.length >= TOKEN_MIN_LENGTH) tokens.add(run.toString())
        return tokens
    }

    /**
     * Скоринг записи по токенам: 3 за токен в заголовке, 2 — в тегах,
     * 1 — в тексте (совпадения подстрочные, без учёта регистра).
     */
    private fun score(entry: MemoryEntry, tokens: List<String>): Int {
        val title = entry.title.lowercase()
        val content = entry.content.lowercase()
        val tags = entry.tags.map { it.lowercase() }
        var value = 0
        for (token in tokens) {
            if (title.contains(token)) value += 3
            if (tags.any { it.contains(token) }) value += 2
            if (content.contains(token)) value += 1
        }
        return value
    }

    companion object {
        /** Минимальная длина токена запроса. */
        private const val TOKEN_MIN_LENGTH = 3

        /** Вид памяти «профиль» — движком не скорится никогда. */
        private const val KIND_EXCLUDED = "profile"
    }
}
