package com.example.aiweb.invariants

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Хранилище глобальных инвариантов: один JSON-файл `<dir>/invariants.json`
 * формы `{"invariants": [ {id, text}, … ]}` на весь деплой (в отличие от
 * [com.example.aiweb.prefs.PreferencesStore] — без per-user разбивки).
 * Отсутствие файла — пустой список (ошибкой не является).
 *
 * Устройство повторяет [com.example.aiweb.prefs.PreferencesStore]:
 *  - запись атомарная — сперва `invariants.json.tmp`, затем переименование
 *    (ATOMIC_MOVE, с fallback на обычный move);
 *  - изменяющие операции синхронизированы по хранилищу;
 *  - битый или невалидный файл читается как пустой дефолт с предупреждением
 *    в System.err, а не роняет запрос.
 *
 * Валидация — [StoredInvariant.normalizeAndValidate]: текст триммится,
 * пустой/длиннее капа — [IllegalArgumentException] с русским текстом;
 * точный дубль текста и превышение [StoredInvariant.MAX_COUNT] — тоже
 * [IllegalArgumentException] (роуты транслируют в 400).
 */
class GlobalInvariantStore(
    /** Каталог данных (AI_DATA_DIR); файл создаётся по требованию. */
    private val dir: String
) {

    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, TMP_FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Все инварианты в сохранённом порядке. Отсутствие файла — пустой список;
     * битый файл читается как пустой дефолт с предупреждением, не как ошибка.
     */
    @Synchronized
    fun list(): List<StoredInvariant> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(FileContent.serializer(), file.readText()).invariants
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось прочитать инварианты '${file.name}': ${e.message}")
            emptyList()
        }
    }

    /**
     * Добавляет инвариант. Текст валидируется ([StoredInvariant.normalizeAndValidate]),
     * точный дубль текста (после trim) и превышение [StoredInvariant.MAX_COUNT]
     * отклоняются [IllegalArgumentException] с русским текстом.
     */
    @Synchronized
    fun add(text: String): StoredInvariant {
        val trimmed = StoredInvariant.normalizeAndValidate(text)
        val current = list()
        require(current.none { it.text == trimmed }) {
            "Инвариант: инвариант с таким текстом уже существует"
        }
        require(current.size < StoredInvariant.MAX_COUNT) {
            "Инвариант: нельзя добавить больше ${StoredInvariant.MAX_COUNT}"
        }
        val invariant = StoredInvariant.create(trimmed)
        persist(current + invariant)
        return invariant
    }

    /** Удаляет инвариант по id; true, если он существовал, false — нет (no-op). */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = list()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    /** Атомарно пишет список на диск (tmp + rename); сбой записи не роняет запрос. */
    private fun persist(invariants: List<StoredInvariant>) {
        try {
            File(dir).mkdirs()
            tmp.writeText(json.encodeToString(FileContent.serializer(), FileContent(invariants)))
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось сохранить инварианты: ${e.message}")
        }
    }

    /** Файл на диске: `{"invariants": [ {id, text}, … ]}`. */
    @Serializable
    private data class FileContent(val invariants: List<StoredInvariant> = emptyList())

    companion object {
        /** Имя файла глобальных инвариантов в каталоге данных. */
        private const val FILE_NAME = "invariants.json"

        /** Имя временного файла атомарной записи. */
        private const val TMP_FILE_NAME = "invariants.json.tmp"
    }
}
