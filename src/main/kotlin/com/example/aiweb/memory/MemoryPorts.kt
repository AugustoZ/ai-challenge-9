package com.example.aiweb.memory

import com.example.aiweb.model.MemoryConfig

/**
 * CRUD-контракт долговременной памяти: вызывающий код зависит от порта,
 * а не от реализации. Реализации: JSON-файл (сейчас), SQLite (стадия 1).
 */
interface MemoryRepository {
    /**
     * Список записей, свежие по updatedAt первыми; фильтр по виду памяти
     * и/или подстроке (без учёта регистра).
     */
    fun list(kind: String? = null, q: String? = null): List<MemoryEntry>

    /** Число записей по каждому виду памяти (все три ключа присутствуют всегда). */
    fun counts(): Map<String, Int>

    /** Запись по идентификатору или null. */
    fun get(id: String): MemoryEntry?

    /** Добавляет запись; updatedAt проставляется хранилищем. */
    fun add(entry: MemoryEntry): MemoryEntry

    /**
     * Применяет [transform] к записи и сохраняет результат; результат
     * валидируется. Неизвестный id — null.
     */
    fun update(id: String, transform: (MemoryEntry) -> MemoryEntry): MemoryEntry?

    /** Удаляет запись; true, если запись была (повторный вызов — false). */
    fun delete(id: String): Boolean
}

/** Запись с оценкой релевантности — результат поискового движка памяти. */
data class ScoredMemoryEntry(val entry: MemoryEntry, val score: Double)

/**
 * Поисковый движок памяти: keyword (сейчас) → FTS5 → вектор → гибрид
 * (стадии 1–2). Ретривер потребляет движок через порт, не зная стратегии.
 */
interface MemorySearchEngine {
    /**
     * Релевантные записи запроса: только ненулевой скоринг, лучшие первыми
     * (при равенстве — свежие по updatedAt). Профили не возвращаются никогда —
     * ретривер включает их отдельно, без скоринга. Пустой запрос — пустой список.
     */
    fun search(query: String, kind: String? = null, limit: Int = 6): List<ScoredMemoryEntry>

    companion object {
        /** Фабрика из конфига: null/"keyword" — KeywordMemorySearch; иное — ошибка старта. */
        fun forConfig(memory: MemoryConfig?, repository: MemoryRepository): MemorySearchEngine {
            val strategy = memory?.search?.takeIf { it.isNotBlank() } ?: "keyword"
            if (strategy == "keyword") return KeywordMemorySearch(repository)
            throw IllegalArgumentException(
                "Стратегия поиска памяти \"$strategy\" пока не поддерживается. Доступно: \"keyword\". " +
                    "Варианты fts/vector/hybrid появятся на стадиях 1–2 (см. openspec/changes/memory-search-ports)."
            )
        }
    }
}

/**
 * Поставщик эмбеддингов (стадия 2): OpenAI-совместимый /embeddings;
 * прод-реализация появится позже — порт фиксирует контракт заранее.
 */
interface EmbeddingProvider {
    fun embed(text: String): FloatArray
}
