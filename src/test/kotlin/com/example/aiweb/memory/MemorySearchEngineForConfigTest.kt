package com.example.aiweb.memory

import com.example.aiweb.model.EmbeddingConfig
import com.example.aiweb.model.MemoryConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки фабрики [MemorySearchEngine.forConfig]: null/"keyword" (и пустая
 * строка) дают keyword-движок, неизвестная стратегия падает стартом с точным
 * русским сообщением.
 */
class MemorySearchEngineForConfigTest {

    /** Реальный репозиторий (пустой) — фабрике важен только тип. */
    private val repository =
        LongTermMemoryStore(Files.createTempDirectory("aiweb-forconfig").toFile().absolutePath)

    @Test
    fun `null-конфиг — keyword-движок`() {
        val engine = MemorySearchEngine.forConfig(null, repository)

        assertTrue(engine is KeywordMemorySearch, "Ожидался KeywordMemorySearch, получено: ${engine::class.simpleName}")
    }

    @Test
    fun `search = keyword — keyword-движок, пустая и пробельная строки тоже`() {
        assertTrue(MemorySearchEngine.forConfig(MemoryConfig(search = "keyword"), repository) is KeywordMemorySearch)
        assertTrue(MemorySearchEngine.forConfig(MemoryConfig(search = ""), repository) is KeywordMemorySearch)
        assertTrue(MemorySearchEngine.forConfig(MemoryConfig(search = "   "), repository) is KeywordMemorySearch)
    }

    @Test
    fun `конфиг с разделом embedding без search — keyword-движок`() {
        val config = MemoryConfig(embedding = EmbeddingConfig(url = "http://localhost/embeddings", model = "emb-1"))

        assertTrue(MemorySearchEngine.forConfig(config, repository) is KeywordMemorySearch)
    }

    @Test
    fun `search = fts — ошибка старта с точным сообщением`() {
        val error = assertFailsWith<IllegalArgumentException> {
            MemorySearchEngine.forConfig(MemoryConfig(search = "fts"), repository)
        }

        assertEquals(
            "Стратегия поиска памяти \"fts\" пока не поддерживается. Доступно: \"keyword\". " +
                "Варианты fts/vector/hybrid появятся на стадиях 1–2 (см. openspec/changes/memory-search-ports).",
            error.message
        )
    }
}
