package com.example.aiweb.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Инструмент reminder: напоминания с сохранением на диск. Действия: add —
 * добавить (text, опциональный due — строка ISO-8601), list — перечислить
 * активные (выполненные не показываются; просроченные помечаются), done —
 * отметить выполненным по id.
 *
 * Хранилище — JSON-файл `<dataDir>/reminders.json`: чтение-мерж-запись под
 * [Mutex] (инструмент может вызываться параллельно). Планировщика нет: due
 * только хранится, а при list сравнивается с текущим моментом — истёкшие
 * напоминания помечаются «[ПРОСРОЧЕНО]».
 *
 * Аргументы приходят от LLM — это ненадёжный ввод: валидируются до
 * использования; любая ошибка возвращается как [ToolResult] с ok = false,
 * исключения наружу не пробрасываются.
 */
class ReminderTool(
    /** Каталог данных приложения; файл хранилища — `<dataDir>/reminders.json`. */
    private val dataDir: String
) : Tool {

    private val mutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    override val name = NAME

    override val description =
        "Напоминания: add — добавить (text, due — дата ISO-8601), list — активные " +
            "напоминания с пометкой просроченных, done — отметить выполненным по id. " +
            "Например due: 2026-01-31T12:00:00Z."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("add"))
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("done"))
                })
            })
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "Текст напоминания (для add)")
            })
            put("due", buildJsonObject {
                put("type", "string")
                put("description", "Срок в формате ISO-8601, например 2026-01-31T12:00:00Z (для add, опционально)")
            })
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "Идентификатор напоминания (для done)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val action = args.toolString("action")
        if (action.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"action\" (add, list или done)")
        }
        return try {
            when (action) {
                "add" -> add(args)
                "list" -> list()
                "done" -> markDone(args)
                else -> ToolResult(false, "Неизвестное действие \"$action\" (допустимы: add, list, done)")
            }
        } catch (e: Exception) {
            ToolResult(false, "Не удалось обработать напоминание: ${e.message ?: e::class.simpleName}")
        }
    }

    /** add: text обязателен; due при наличии должен разбираться как дата ISO-8601. */
    private suspend fun add(args: JsonObject): ToolResult {
        val text = args.toolString("text")
        if (text.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"text\" (текст напоминания)")
        }
        val due = args.toolString("due")?.takeIf { it.isNotBlank() }
        if (due != null && parseDue(due) == null) {
            return ToolResult(false, "Некорректный параметр \"due\": ожидается дата ISO-8601, " +
                "например 2026-01-31T12:00:00Z — '$due'")
        }
        val id = args.toolString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val entry = ReminderEntry(id = id, text = text, due = due, createdAt = System.currentTimeMillis())
        mutex.withLock {
            // чтение-мерж-запись: повторный add с тем же id заменяет запись
            val all = readAll().filter { it.id != id } + entry
            writeAll(all)
        }
        return ToolResult(true, "Напоминание добавлено (id $id)" + if (due != null) ", срок: $due" else "")
    }

    /** list: активные напоминания; срок в прошлом помечается «[ПРОСРОЧЕНО]». */
    private suspend fun list(): ToolResult {
        val active = mutex.withLock { readAll() }.filter { !it.done }
        if (active.isEmpty()) {
            return ToolResult(true, "Напоминаний нет")
        }
        val now = Instant.now()
        val text = active.joinToString("\n") { entry ->
            val dueInfo = entry.due?.let { due ->
                val overdue = parseDue(due)?.isBefore(now) == true
                " | срок: $due" + if (overdue) " [ПРОСРОЧЕНО]" else ""
            } ?: ""
            "- ${entry.id} | ${entry.text}$dueInfo"
        }
        return ToolResult(true, text)
    }

    /** done: id обязателен; запись помечается done = true и исчезает из list. */
    private suspend fun markDone(args: JsonObject): ToolResult {
        val id = args.toolString("id")
        if (id.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"id\" (идентификатор напоминания)")
        }
        mutex.withLock {
            val all = readAll()
            val index = all.indexOfFirst { it.id == id }
            if (index < 0) {
                return ToolResult(false, "Напоминание с id $id не найдено")
            }
            if (all[index].done) {
                return ToolResult(false, "Напоминание с id $id уже отмечено выполненным")
            }
            val updated = all.toMutableList()
            updated[index] = all[index].copy(done = true)
            writeAll(updated)
        }
        return ToolResult(true, "Напоминание отмечено выполненным (id $id)")
    }

    /** Читает хранилище; файла нет или он повреждён — пустой список (без исключений). */
    private fun readAll(): List<ReminderEntry> {
        val file = storageFile()
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString<List<ReminderEntry>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Записывает хранилище; каталог данных при необходимости создаётся. */
    private fun writeAll(entries: List<ReminderEntry>) {
        val file = storageFile()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(entries))
    }

    private fun storageFile(): File = File(dataDir, FILE_NAME)

    companion object {
        const val NAME = "reminder"

        /** Имя файла хранилища в каталоге данных. */
        const val FILE_NAME = "reminders.json"

        /**
         * Разбор строки ISO-8601 в [Instant]: дата со смещением («…Z», «…+03:00»),
         * затем наивная дата-время (трактуется как UTC), затем только дата
         * (полночь UTC). Не разбирается — null.
         */
        fun parseDue(due: String): Instant? = try {
            OffsetDateTime.parse(due).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(due).toInstant(ZoneOffset.UTC)
            } catch (e2: DateTimeParseException) {
                try {
                    LocalDate.parse(due).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (e3: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

/** Одна запись напоминания в JSON-хранилище. */
@Serializable
private data class ReminderEntry(
    val id: String,
    val text: String,
    /** Срок строкой ISO-8601; null — без срока. */
    val due: String? = null,
    val done: Boolean = false,
    val createdAt: Long = 0L
)
