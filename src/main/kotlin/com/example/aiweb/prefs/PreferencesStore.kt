package com.example.aiweb.prefs

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * Хранилище предпочтений пользователей: по одному JSON-файлу на пользователя
 * в `<dir>/preferences/{userId}.json`. Отсутствие файла — дефолтные
 * предпочтения [UserPreferences.DEFAULTS] (ошибкой не является).
 *
 * Устройство повторяет [com.example.aiweb.memory.WorkingMemoryStore]:
 *  - запись атомарная — сперва `{userId}.json.tmp`, затем переименование
 *    (ATOMIC_MOVE, с fallback на обычный move);
 *  - изменяющие операции синхронизированы по хранилищу;
 *  - битый или невалидный файл читается как дефолт с предупреждением
 *    в System.err, а не роняет запрос;
 *  - идентификатор пользователя попадает в путь файла, поэтому проверяется
 *    на path traversal.
 */
class PreferencesStore(
    /** Каталог данных (AI_DATA_DIR); подкаталог preferences создаётся по требованию. */
    private val dir: String
) {

    private val prefsDir = File(dir, PREFS_DIR)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Предпочтения пользователя: сохранённые или [UserPreferences.DEFAULTS],
     * если файла нет (первое обращение) или файл не читается.
     */
    @Synchronized
    fun get(userId: String): UserPreferences {
        val file = fileFor(userId) ?: return UserPreferences.DEFAULTS
        if (!file.isFile) return UserPreferences.DEFAULTS
        return try {
            PreferenceLimits.validate(json.decodeFromString(UserPreferences.serializer(), file.readText()))
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось прочитать предпочтения '${file.name}': ${e.message}")
            UserPreferences.DEFAULTS
        }
    }

    /**
     * Сохраняет предпочтения пользователя. Данные валидируются перед записью:
     * мусор не попадает на диск, нарушение лимитов — [IllegalArgumentException]
     * с русским текстом.
     */
    @Synchronized
    fun save(userId: String, prefs: UserPreferences) {
        requireSafeId(userId)
        val valid = PreferenceLimits.validate(prefs)
        persist(userId, valid)
    }

    /** Атомарно пишет предпочтения на диск (tmp + rename); сбой записи не роняет запрос. */
    private fun persist(userId: String, prefs: UserPreferences) {
        try {
            prefsDir.mkdirs()
            val target = File(prefsDir, userId + EXTENSION)
            val tmp = File(prefsDir, userId + TMP_EXTENSION)
            tmp.writeText(json.encodeToString(UserPreferences.serializer(), prefs))
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
            System.err.println("Предупреждение: не удалось сохранить предпочтения '$userId': ${e.message}")
        }
    }

    /** Файл пользователя; идентификатор не должен вылезать за пределы каталога. */
    private fun fileFor(userId: String): File? {
        if (!isSafeId(userId)) return null
        return File(prefsDir, userId + EXTENSION)
    }

    private fun requireSafeId(userId: String) {
        if (!isSafeId(userId)) {
            throw IllegalArgumentException("Недопустимый идентификатор пользователя: '$userId'")
        }
    }

    companion object {
        /** Подкаталог внутри каталога данных с файлами предпочтений. */
        private const val PREFS_DIR = "preferences"

        /** Расширение файла предпочтений. */
        private const val EXTENSION = ".json"

        /** Расширение временного файла атомарной записи. */
        private const val TMP_EXTENSION = ".json.tmp"

        /** Идентификатор пользователя попадает в имя файла — traversal недопустим. */
        private fun isSafeId(id: String): Boolean =
            id.isNotBlank() &&
                !id.contains('/') &&
                !id.contains('\\') &&
                !id.contains("..") &&
                !id.contains('\u0000')
    }
}
