package com.example.aiweb.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Внутренний формат файла рабочей памяти сессии: {"entries":[...]}. */
@Serializable
internal data class WorkingFile(val entries: List<WorkingEntry> = emptyList())

/**
 * Рабочая память сессии (текущая задача): короткие заметки, по одному
 * JSON-файлу на сессию в `<dir>/working/<sessionId>.json`. Файлы переживают
 * перезапуск процесса; читаются лениво, по требованию.
 *
 * Устройство повторяет [com.example.aiweb.session.FileSessionStore]:
 *  - запись атомарная — сперва `<sessionId>.json.tmp`, затем переименование
 *    (ATOMIC_MOVE, с fallback на обычный move);
 *  - изменяющие операции синхронизированы по хранилищу, читатели получают
 *    согласованный снимок списка;
 *  - битый файл читается как пустая память с предупреждением в System.err,
 *    а не роняет запрос;
 *  - идентификатор сессии попадает в путь файла, поэтому проверяется на
 *    path traversal.
 */
class WorkingMemoryStore(
    /** Каталог данных (AI_DATA_DIR); подкаталог working создаётся по требованию. */
    private val dir: String,
    /** Источник времени — шов для детерминированных тестов. */
    private val now: () -> Long = System::currentTimeMillis
) {

    private val workingDir = File(dir, WORKING_DIR)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Возвращает записи сессии в порядке их добавления; отсутствующий файл —
     * пустой список (ошибкой не является).
     */
    @Synchronized
    fun entries(sessionId: String): List<WorkingEntry> {
        val file = fileFor(sessionId) ?: return emptyList()
        return readEntries(file)
    }

    /**
     * Вставляет запись или заменяет существующую с тем же id (без дубликата).
     * updatedAt всегда проставляется хранилищем, createdAt при замене
     * сохраняется исходный. Превышение капов — [IllegalArgumentException].
     */
    @Synchronized
    fun upsert(sessionId: String, entry: WorkingEntry): List<WorkingEntry> {
        requireSafeId(sessionId)
        if (entry.title.length > MemoryLimits.WORKING_MAX_TITLE) {
            throw IllegalArgumentException("заголовок длиннее ${MemoryLimits.WORKING_MAX_TITLE} символов")
        }
        if (entry.content.length > MemoryLimits.WORKING_MAX_CONTENT) {
            throw IllegalArgumentException("текст длиннее ${MemoryLimits.WORKING_MAX_CONTENT} символов")
        }
        val existing = readEntries(fileFor(sessionId))
        val index = existing.indexOfFirst { it.id == entry.id }
        if (index < 0 && existing.size >= MemoryLimits.WORKING_MAX_ENTRIES) {
            throw IllegalArgumentException("лимит ${MemoryLimits.WORKING_MAX_ENTRIES} записи")
        }
        val stored = WorkingEntry(
            id = entry.id,
            title = entry.title,
            content = entry.content,
            createdAt = if (index >= 0) existing[index].createdAt else entry.createdAt,
            updatedAt = now()
        )
        val updated = existing.toMutableList().also {
            if (index >= 0) it[index] = stored else it.add(stored)
        }
        persist(sessionId, WorkingFile(updated))
        return updated
    }

    /**
     * Удаляет запись по id (неизвестный id — без ошибки) и возвращает остаток.
     */
    @Synchronized
    fun remove(sessionId: String, entryId: String): List<WorkingEntry> {
        requireSafeId(sessionId)
        val existing = readEntries(fileFor(sessionId))
        val updated = existing.filterNot { it.id == entryId }
        if (updated.size != existing.size) persist(sessionId, WorkingFile(updated))
        return updated
    }

    /** Стирает всю рабочую память сессии: удаляет файл, если он есть. */
    @Synchronized
    fun clear(sessionId: String) {
        val file = fileFor(sessionId) ?: return
        runCatching { file.delete() }
    }

    /**
     * Удаляет файлы рабочих сессий, которых нет среди [existingSessionIds]
     * (например, после выселения сессии из файлового хранилища).
     */
    @Synchronized
    fun pruneOrphans(existingSessionIds: Set<String>) {
        val files = workingDir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) } ?: return
        for (file in files) {
            if (file.name.removeSuffix(EXTENSION) !in existingSessionIds) {
                runCatching { file.delete() }
            }
        }
    }

    /** Читает файл памяти; отсутствующий или битый файл — пустой список. */
    private fun readEntries(file: File?): List<WorkingEntry> {
        if (file == null || !file.isFile) return emptyList()
        return try {
            json.decodeFromString(WorkingFile.serializer(), file.readText()).entries
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось прочитать рабочую память '${file.name}': ${e.message}")
            emptyList()
        }
    }

    /** Атомарно пишет память сессии на диск (tmp + rename); сбой не роняет запрос. */
    private fun persist(sessionId: String, data: WorkingFile) {
        try {
            workingDir.mkdirs()
            val target = File(workingDir, sessionId + EXTENSION)
            val tmp = File(workingDir, sessionId + TMP_EXTENSION)
            tmp.writeText(json.encodeToString(WorkingFile.serializer(), data))
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
            System.err.println("Предупреждение: не удалось сохранить рабочую память '$sessionId': ${e.message}")
        }
    }

    /** Путь файла сессии; идентификатор не должен вылезать за пределы каталога. */
    private fun fileFor(sessionId: String): File? {
        if (!isSafeId(sessionId)) return null
        return File(workingDir, sessionId + EXTENSION)
    }

    private fun requireSafeId(sessionId: String) {
        if (!isSafeId(sessionId)) {
            throw IllegalArgumentException("Недопустимый идентификатор сессии: '$sessionId'")
        }
    }

    companion object {
        /** Подкаталог внутри каталога данных с файлами рабочих сессий. */
        private const val WORKING_DIR = "working"

        /** Расширение файла рабочей памяти. */
        private const val EXTENSION = ".json"

        /** Расширение временного файла атомарной записи. */
        private const val TMP_EXTENSION = ".json.tmp"

        /** Идентификатор сессии попадает в имя файла — traversal недопустим. */
        private fun isSafeId(id: String): Boolean =
            id.isNotBlank() &&
                !id.contains('/') &&
                !id.contains('\\') &&
                !id.contains("..") &&
                !id.contains('\u0000')
    }
}
