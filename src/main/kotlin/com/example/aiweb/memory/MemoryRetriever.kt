package com.example.aiweb.memory

/**
 * Собирает блок памяти для системного сообщения из двух слоёв:
 *  - долговременная память: профили (всегда) плюс релевантные запросу
 *    решения и знания — отбор делает поисковый движок [MemorySearchEngine]
 *    (сейчас keyword-скоринг, без эмбеддингов);
 *  - рабочая память сессии: все записи текущей задачи.
 *
 * Пустой блок (оба слоя пусты) — null, чтобы системное сообщение не раздувалось.
 */
class MemoryRetriever(
    /** Рабочая память сессии (текущая задача). */
    private val working: WorkingMemoryStore,
    /** Долговременная память — через порт репозитория. */
    private val repository: MemoryRepository,
    /** Поисковый движок релевантных решений/знаний. */
    private val search: MemorySearchEngine
) {

    /**
     * Строит блок памяти для сессии [sessionId] с релевантностью по
     * [userQuery] (null или пустой — без скоринга, только профили).
     * Возвращает null, когда оба слоя пусты.
     */
    fun memoryBlock(sessionId: String, userQuery: String?): String? {
        val longSection = longTermSection(userQuery)
        val workingSection = workingSection(sessionId)
        return when {
            longSection != null && workingSection != null -> longSection + "\n\n" + workingSection
            else -> longSection ?: workingSection
        }
    }

    /** Долговременная секция: профили + топ релевантных решений/знаний. */
    private fun longTermSection(userQuery: String?): String? {
        val lines = mutableListOf<String>(HEADER_LONG)

        // Профили — всегда, независимо от запроса, но не более MAX_PROFILES
        repository.list(KIND_PROFILE)
            .take(MAX_PROFILES)
            .forEach { lines.add(formatEntry(it)) }

        // Решения и знания — через поисковый движок; пустой запрос — без скоринга
        val scored = if (userQuery.isNullOrBlank()) {
            emptyList()
        } else {
            search.search(userQuery, limit = MAX_SCORED)
        }
        scored.forEach { lines.add(formatEntry(it.entry)) }
        return withinBudget(lines, LONG_BUDGET)
    }

    /** Рабочая секция: все записи сессии, свежие первыми. */
    private fun workingSection(sessionId: String): String? {
        val lines = mutableListOf(HEADER_WORKING)
        working.entries(sessionId)
            .sortedByDescending { it.updatedAt }
            .forEach { lines.add("- ${it.title}: ${it.content}") }
        return withinBudget(lines, WORKING_BUDGET)
    }

    /** Строка записи: "- [kind] title (теги: a, b): content", теги — при наличии. */
    private fun formatEntry(entry: MemoryEntry): String {
        val tags = if (entry.tags.isEmpty()) "" else " (теги: ${entry.tags.joinToString(", ")})"
        return "- [${entry.kind}] ${entry.title}$tags: ${entry.content}"
    }

    /**
     * Ужимает секцию в бюджет: целые строки отбрасываются с конца, пока текст
     * (заголовок + строки) не влезет. Если строк не осталось — секция пустая,
     * то есть заголовок тоже опускается.
     */
    private fun withinBudget(lines: List<String>, budget: Int): String? {
        var kept = lines
        while (kept.size > 1 && kept.joinToString("\n").length > budget) {
            kept = kept.dropLast(1)
        }
        return if (kept.size <= 1) null else kept.joinToString("\n")
    }

    companion object {
        /** Заголовок долговременной секции. */
        private const val HEADER_LONG = "Долговременная память:"

        /** Заголовок рабочей секции. */
        private const val HEADER_WORKING = "Рабочая память (текущая задача):"

        /** Бюджет долговременной секции, символов. */
        private const val LONG_BUDGET = 1800

        /** Бюджет рабочей секции, символов. */
        private const val WORKING_BUDGET = 2400

        /** Максимум профилей в блоке. */
        private const val MAX_PROFILES = 10

        /** Максимум релевантных решений/знаний в блоке. */
        private const val MAX_SCORED = 6

        /** Вид памяти «профиль» — в блок попадает без скоринга. */
        private const val KIND_PROFILE = "profile"
    }
}
