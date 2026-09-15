package com.example.aiweb.auth

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Хранилище пользователей: по одному JSON-файлу на пользователя в
 * `<dir>/users/{userId}.json`. Файлы переживают перезапуск процесса; ПИН
 * хранится только как SHA-256(salt + pin) — см. [UserAccount].
 *
 * Устройство повторяет [com.example.aiweb.memory.WorkingMemoryStore]:
 *  - запись атомарная — сперва `{userId}.json.tmp`, затем переименование
 *    (ATOMIC_MOVE, с fallback на обычный move);
 *  - изменяющие операции синхронизированы по хранилищу, читатели получают
 *    согласованный снимок списка;
 *  - битый файл пропускается с предупреждением в System.err, а не роняет чтение;
 *  - идентификатор пользователя попадает в путь файла, поэтому проверяется на
 *    path traversal.
 *
 * Дубликаты имён РАЗРЕШЕНЫ — пользователей различают идентификатор и ПИН.
 * Каскадное удаление остальных сущностей пользователя (сессии, память,
 * предпочтения, токены) — задача маршрутов, не этого класса.
 */
class UserStore(
    /** Каталог данных (AI_DATA_DIR); подкаталог users создаётся по требованию. */
    private val dir: String,
    /** Источник времени — шов для детерминированных тестов. */
    private val now: () -> Long = System::currentTimeMillis
) {

    private val usersDir = File(dir, USERS_DIR)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Создаёт пользователя: валидирует имя и ПИН (русские сообщения с названием
     * поля), генерирует id и соль, хеширует ПИН и сразу пишет файл на диск.
     */
    @Synchronized
    fun create(name: String, pin: String): UserAccount {
        val cleanName = UserAccount.validateName(name)
        UserAccount.validatePin(pin)
        val salt = UserAccount.newSalt()
        val account = UserAccount(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            pinHash = UserAccount.hashPin(pin, salt),
            salt = salt,
            createdAt = now()
        )
        persist(account)
        return account
    }

    /** Пользователь по идентификатору; неизвестный или небезопасный id — null. */
    fun findById(userId: String): UserAccount? {
        val file = fileFor(userId) ?: return null
        return readUser(file)
    }

    /**
     * Все пользователи в порядке создания (по createdAt, для одинакового
     * времени — по id). Битые файлы пропускаются.
     */
    fun list(): List<UserAccount> =
        usersDir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .mapNotNull { readUser(it) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** Есть ли пользователь с таким именем (после обрезки); дубликаты имён разрешены. */
    fun existsWithName(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        return list().any { it.name == clean }
    }

    /** Удаляет файл пользователя; неизвестный или небезопасный id — false. */
    @Synchronized
    fun delete(userId: String): Boolean {
        val file = fileFor(userId) ?: return false
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Читает файл пользователя; отсутствующий или битый — null с предупреждением. */
    private fun readUser(file: File): UserAccount? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(UserAccount.serializer(), file.readText())
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось прочитать пользователя '${file.name}': ${e.message}")
            null
        }
    }

    /** Атомарно пишет пользователя на диск (tmp + rename); сбой не роняет запрос. */
    private fun persist(account: UserAccount) {
        try {
            usersDir.mkdirs()
            val target = File(usersDir, account.id + EXTENSION)
            val tmp = File(usersDir, account.id + TMP_EXTENSION)
            tmp.writeText(json.encodeToString(UserAccount.serializer(), account))
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
            throw IllegalStateException("Не удалось сохранить пользователя '${account.name}': ${e.message}", e)
        }
    }

    /** Путь файла пользователя; идентификатор не должен вылезать за пределы каталога. */
    private fun fileFor(userId: String): File? {
        if (!isSafeId(userId)) return null
        return File(usersDir, userId + EXTENSION)
    }

    companion object {
        /** Подкаталог внутри каталога данных с файлами пользователей. */
        const val USERS_DIR = "users"

        /** Расширение файла пользователя. */
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
