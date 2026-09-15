package com.example.aiweb.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Внутренний формат файла долговременной памяти: {"entries":[...]}. */
@Serializable
internal data class LongTermFile(val entries: List<MemoryEntry> = emptyList())

/**
 * Долговременная память (общая для всех сессий): один JSON-файл
 * `<dir>/memory/longterm.json`, полностью читается в память при старте и
 * перезаписывается атомарно после каждой мутации (tmp + rename, как в
 * [com.example.aiweb.session.FileSessionStore]).
 *
 * Реализация порта [MemoryRepository]; на стадии 1 вместо неё появится
 * SQLite-хранилище с тем же контрактом.
 *
 * Лимиты и допустимые значения — в [MemoryLimits]; нарушения выбрасывают
 * [IllegalArgumentException] с понятным русским сообщением. Битый файл
 * читается как пустая память с предупреждением в System.err.
 */
class LongTermMemoryStore(
    /** Каталог данных (AI_DATA_DIR); подкаталог memory создаётся по требованию. */
    private val dir: String,
    /** Источник времени — шов для детерминированных тестов. */
    private val now: () -> Long = System::currentTimeMillis
) : MemoryRepository {

    private val memoryDir = File(dir, MEMORY_DIR)
    private val entries = mutableListOf<MemoryEntry>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        loadAll()
    }

    /**
     * Список записей, свежие по updatedAt первыми; фильтр по виду памяти
     * и/или подстроке (без учёта регистра, ищет в заголовке, тексте и тегах).
     * Значения по умолчанию наследуются от порта [MemoryRepository.list].
     */
    @Synchronized
    override fun list(kind: String?, q: String?): List<MemoryEntry> {
        val query = q?.trim()?.lowercase()
        return entries.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { query.isNullOrEmpty() || matches(it, query) }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    /** Число записей по каждому виду памяти (все три ключа присутствуют всегда). */
    @Synchronized
    override fun counts(): Map<String, Int> =
        MemoryLimits.KINDS.associateWith { kind -> entries.count { it.kind == kind } }

    /** Запись по идентификатору или null. */
    @Synchronized
    override fun get(id: String): MemoryEntry? = entries.firstOrNull { it.id == id }

    /**
     * Добавляет запись; updatedAt проставляется хранилищем. Лимиты, вид памяти
     * и источник проверяются — нарушение выбрасывает [IllegalArgumentException].
     */
    @Synchronized
    override fun add(entry: MemoryEntry): MemoryEntry {
        validate(entry, checkCap = true)
        val stored = entry.copy(updatedAt = now())
        entries.add(stored)
        persist()
        return stored
    }

    /**
     * Применяет [transform] к записи и сохраняет результат (id фиксируется,
     * updatedAt проставляется хранилищем). Результат проверяется на лимиты:
     * портящая transform отклоняется, запись остаётся прежней.
     * Неизвестный id — null.
     */
    @Synchronized
    override fun update(id: String, transform: (MemoryEntry) -> MemoryEntry): MemoryEntry? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(entries[index]).copy(id = id, updatedAt = now())
        validate(updated, checkCap = false)
        entries[index] = updated
        persist()
        return updated
    }

    /** Удаляет запись; true, если запись была (повторный вызов — false). */
    @Synchronized
    override fun delete(id: String): Boolean {
        val removed = entries.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    /** Подстрочный поиск без учёта регистра по заголовку, тексту и тегам. */
    private fun matches(entry: MemoryEntry, query: String): Boolean =
        entry.title.lowercase().contains(query) ||
            entry.content.lowercase().contains(query) ||
            entry.tags.any { it.lowercase().contains(query) }

    /** Проверка лимитов записи; [checkCap] — дополнительно кап общего числа. */
    private fun validate(entry: MemoryEntry, checkCap: Boolean) {
        if (entry.kind !in MemoryLimits.KINDS) {
            throw IllegalArgumentException(
                "Неизвестный вид памяти '${entry.kind}' (допустимы: ${MemoryLimits.KINDS.joinToString(", ")})"
            )
        }
        if (entry.source !in SOURCES) {
            throw IllegalArgumentException("Источник должен быть 'user' или 'agent', получено: '${entry.source}'")
        }
        if (entry.title.length > MemoryLimits.LONG_MAX_TITLE) {
            throw IllegalArgumentException("заголовок длиннее ${MemoryLimits.LONG_MAX_TITLE} символов")
        }
        if (entry.content.length > MemoryLimits.LONG_MAX_CONTENT) {
            throw IllegalArgumentException("текст длиннее ${MemoryLimits.LONG_MAX_CONTENT} символов")
        }
        if (entry.tags.size > MemoryLimits.LONG_MAX_TAGS) {
            throw IllegalArgumentException("тегов не больше ${MemoryLimits.LONG_MAX_TAGS}")
        }
        if (entry.tags.any { it.length > MemoryLimits.LONG_MAX_TAG_LEN }) {
            throw IllegalArgumentException("тег длиннее ${MemoryLimits.LONG_MAX_TAG_LEN} символов")
        }
        if (checkCap && entries.size >= MemoryLimits.LONG_MAX_ENTRIES) {
            throw IllegalArgumentException("лимит ${MemoryLimits.LONG_MAX_ENTRIES} записей")
        }
    }

    /** Читает файл памяти с диска; битый файл пропускается с предупреждением. */
    private fun loadAll() {
        val file = File(memoryDir, FILE_NAME)
        if (!file.isFile) return
        try {
            entries += json.decodeFromString(LongTermFile.serializer(), file.readText()).entries
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось прочитать долговременную память: ${e.message}")
        }
    }

    /** Атомарно пишет весь список на диск (tmp + rename); сбой не роняет запрос. */
    private fun persist() {
        try {
            memoryDir.mkdirs()
            val target = File(memoryDir, FILE_NAME)
            val tmp = File(memoryDir, FILE_NAME + TMP_EXTENSION)
            tmp.writeText(json.encodeToString(LongTermFile.serializer(), LongTermFile(entries.toList())))
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось сохранить долговременную память: ${e.message}")
        }
    }

    companion object {
        /** Подкаталог внутри каталога данных с файлом долговременной памяти. */
        private const val MEMORY_DIR = "memory"

        /** Имя файла долговременной памяти. */
        private const val FILE_NAME = "longterm.json"

        /** Расширение временного файла атомарной записи. */
        private const val TMP_EXTENSION = ".tmp"

        /** Допустимые источники записи. */
        private val SOURCES = listOf("user", "agent")
    }
}
