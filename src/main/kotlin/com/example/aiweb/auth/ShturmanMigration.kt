package com.example.aiweb.auth

import com.example.aiweb.session.Session
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Идемпотентная миграция «Шумахер» (задача 10 плана персонализации).
 *
 * При старте приложения наследует все данные без владельца пользователю
 * «Шумахер». Запуск ДО построения маршрутов: переписанные файлы сессий и
 * перенос файла памяти должны быть видны сторам, которые создаются в
 * [com.example.aiweb.routes.aiRoutes] при первом чтении с диска.
 *
 * Условие запуска (по плану):
 *  - пользователя с именем «Шумахер» НЕТ, И
 *  - есть legacy-артефакты: сессии с `userId == null` (JSON-файлы каталога
 *    sessions без этого поля) ИЛИ существует общий файл долговременной памяти
 *    `memory/longterm.json` (до-авторизационная раскладка).
 *
 * Что делает:
 *  1. создаёт «Шумахера» с PIN, сгенерированным SecureRandom (4 цифры);
 *     PIN печатается в stdout ОДИН раз с пометкой миграции (формат строки —
 *     [PIN_LINE_FORMAT]; план фиксирует только «один раз, с пометкой», дословной
 *     строки в плане нет);
 *  2. переписывает файлы осиротевших сессий с `userId` Шумахера (атомарно,
 *     tmp + rename, тот же Json-конфиг, что у [com.example.aiweb.session.FileSessionStore]);
 *  3. переносит `memory/longterm.json` в `memory/{userId}/longterm.json`
 *     (atomic move; формат файла идентичен, конвертация не нужна).
 *
 * Идемпотентность по построению: после первого запуска у всех сессий есть
 * владелец, legacy-файл памяти переехал, а «Шумахер» существует -> повторный
 * бут ничего не делает. Это же защищает от ре-миграции после удаления
 * пользователей: каскадное удаление убирает и сессии, и каталог памяти
 * пользователя, поэтому осиротевших артефактов не остаётся.
 *
 * Частичный сбой не роняет старт: предупреждение в System.err (по образцу
 * сторoв), legacy-файл остаётся на месте (данные не теряются, через API
 * они всё равно не видны), созданный пользователь и его PIN сохраняются.
 */
object ShturmanMigration {

    /** Имя пользователя-наследника всех legacy-данных. */
    const val LEGACY_USER_NAME = "Шумахер"

    /**
     * Формат строки с PIN миграции: печатается в stdout ровно один раз —
     * при создании пользователя. %s заменяется на 4-значный PIN.
     */
    const val PIN_LINE_FORMAT: String =
        "МИГРАЦИЯ: создан пользователь «Шумахер» для унаследованных данных. PIN: %s — " +
            "запишите его, это сообщение больше не появится."

    private const val SESSIONS_DIR = "sessions"
    private const val MEMORY_DIR = "memory"
    private const val LONGTERM_FILE = "longterm.json"
    private const val SESSION_EXT = ".json"

    /** Те же настройки, что у сторoв: читает legacy-файлы, пишет совместимый формат. */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val random = SecureRandom()

    /** Случайный 4-значный PIN (каждая цифра 0..9), формат валиден для [UserAccount.validatePin]. */
    fun newPin(): String = buildString {
        repeat(4) { append('0' + random.nextInt(10)) }
    }

    /**
     * Выполняет миграцию, если она нужна; возвращает созданного пользователя
     * или null, если миграция не запускалась (нет dataDir, «Шумахер» уже есть
     * или legacy-артефактов нет).
     *
     * @param dataDir каталог данных приложения (AppConfig.dataDir); null/пусто — P0-режим, миграции нет
     * @param randomPin шов для тестов: генератор PIN
     * @param report шов для тестов: куда печатается строка с PIN (по умолчанию stdout)
     */
    fun run(
        dataDir: String?,
        randomPin: () -> String = ::newPin,
        report: (String) -> Unit = { println(it) }
    ): UserAccount? {
        if (dataDir.isNullOrBlank()) return null

        val users = UserStore(dataDir)
        if (users.existsWithName(LEGACY_USER_NAME)) return null

        val legacySessions = findLegacySessionFiles(File(dataDir, SESSIONS_DIR))
        val legacyMemory = File(File(dataDir, MEMORY_DIR), LONGTERM_FILE).takeIf { it.isFile }
        if (legacySessions.isEmpty() && legacyMemory == null) return null

        // Порядок по плану: сначала пользователь (и сразу PIN в stdout —
        // владелец данных обязан его увидеть), затем перенос данных.
        val pin = randomPin()
        val account = users.create(LEGACY_USER_NAME, pin)
        report(PIN_LINE_FORMAT.format(pin))

        val attributed = legacySessions.sumOf { reassignSession(it, account.id) }
        val memoryMoved = legacyMemory?.let { moveMemoryFile(it, dataDir, account.id) } == true
        if (attributed < legacySessions.size || (legacyMemory != null && !memoryMoved)) {
            System.err.println("Предупреждение: миграция завершена не полностью: приписано сессий $attributed из ${legacySessions.size}, память перенесена: $memoryMoved")
        }
        return account
    }

    /** Файлы сессий без владельца (userId отсутствует/нулевой); битые файлы не считаются legacy. */
    private fun findLegacySessionFiles(sessionsDir: File): List<File> =
        sessionsDir.listFiles { file -> file.isFile && file.name.endsWith(SESSION_EXT) }
            .orEmpty()
            .filter { file -> isUnowned(file) }

    private fun isUnowned(file: File): Boolean =
        try {
            json.decodeFromString(Session.serializer(), file.readText()).userId == null
        } catch (e: Exception) {
            false
        }

    /** Переписывает файл сессии с владельцем [userId] (атомарно); возвращает 1 при успехе. */
    private fun reassignSession(file: File, userId: String): Int {
        try {
            val session = json.decodeFromString(Session.serializer(), file.readText())
            if (session.userId != null) return 0
            val updated = session.copy(userId = userId)
            val tmp = File(file.parentFile, file.name + TMP_EXT)
            tmp.writeText(json.encodeToString(Session.serializer(), updated))
            moveAtomically(tmp.toPath(), file.toPath())
            return 1
        } catch (e: Exception) {
            System.err.println("Предупреждение: не удалось приписать сессию '${file.name}' пользователю: ${e.message}")
            return 0
        }
    }

    /** Переносит legacy-файл памяти в per-user раскладку memory/{userId}/longterm.json. */
    private fun moveMemoryFile(legacy: File, dataDir: String, userId: String): Boolean =
        try {
            val targetDir = File(File(dataDir, MEMORY_DIR), userId)
            targetDir.mkdirs()
            val target = File(targetDir, LONGTERM_FILE)
            moveAtomically(legacy.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            // Целевой файл не перезаписываем: legacy-файл остаётся на месте, данные не теряются.
            System.err.println("Предупреждение: не удалось перенести долговременную память пользователю: ${e.message}")
            false
        }

    /** Atomic move с fallback на обычный переименование (тот же паттерн, что у сторoв). */
    private fun moveAtomically(source: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private const val TMP_EXT = ".tmp"
}
