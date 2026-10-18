package com.example.aiweb.tools

import com.example.aiweb.memory.WorkingEntry
import com.example.aiweb.memory.WorkingMemoryStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Инструмент task_notes: заметки рабочей памяти текущей задачи (сессионный
 * скратчпад) для промежуточных данных — шаги, находки, состояние работы.
 * Действия: add/update/remove/list. Записи живут в пределах сессии и в
 * долговременную память не попадают.
 */
class TaskNotesTool(
    /** Рабочая память сессии. */
    private val working: WorkingMemoryStore,
    /** Идентификатор текущей сессии; null — рабочая память недоступна. */
    private val sessionId: String?
) : Tool {

    override val name = NAME

    override val description =
        "Заметки рабочей памяти текущей задачи (сессионный скратчпад) для промежуточных данных: " +
            "шаги, находки, состояние работы. Действия: add — добавить заметку, " +
            "update — изменить по id, remove — удалить по id, list — перечислить все."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("add"))
                    add(JsonPrimitive("update"))
                    add(JsonPrimitive("remove"))
                    add(JsonPrimitive("list"))
                })
            })
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "Идентификатор заметки для update/remove")
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "Короткий заголовок заметки")
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Текст заметки")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val session = sessionId ?: return ToolResult(false, "Рабочая память недоступна: нет сессии")
        val action = args.toolString("action")
        if (action.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"action\" (add, update, remove или list)")
        }
        return try {
            when (action) {
                "add" -> add(session, args)
                "update" -> update(session, args)
                "remove" -> remove(session, args)
                "list" -> list(session)
                else -> ToolResult(false, "Неизвестное действие \"$action\" (допустимы: add, update, remove, list)")
            }
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Не удалось изменить рабочую память")
        }
    }

    /** add: title и content обязательны; id генерируется, если не передан. */
    private fun add(session: String, args: JsonObject): ToolResult {
        val title = args.toolString("title")
        if (title.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"title\" (заголовок заметки)")
        }
        val content = args.toolString("content")
        if (content.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"content\" (текст заметки)")
        }
        val id = args.toolString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        working.upsert(
            session,
            WorkingEntry(id = id, title = title, content = content, createdAt = System.currentTimeMillis(), updatedAt = 0L)
        )
        return ToolResult(true, "Заметка сохранена: $title (id $id)")
    }

    /** update: id обязателен; незаданные title/content остаются прежними. */
    private fun update(session: String, args: JsonObject): ToolResult {
        val id = args.toolString("id")
        if (id.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"id\" (идентификатор заметки)")
        }
        val existing = working.entries(session).firstOrNull { it.id == id }
            ?: return ToolResult(false, "Заметка с id $id не найдена")
        val title = args.toolString("title")?.takeIf { it.isNotBlank() } ?: existing.title
        val content = args.toolString("content")?.takeIf { it.isNotBlank() } ?: existing.content
        working.upsert(
            session,
            WorkingEntry(id = id, title = title, content = content, createdAt = existing.createdAt, updatedAt = existing.updatedAt)
        )
        return ToolResult(true, "Заметка обновлена: $title")
    }

    /** remove: id обязателен; неизвестный id — ошибка без исключения. */
    private fun remove(session: String, args: JsonObject): ToolResult {
        val id = args.toolString("id")
        if (id.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"id\" (идентификатор заметки)")
        }
        val exists = working.entries(session).any { it.id == id }
        if (!exists) {
            return ToolResult(false, "Заметка с id $id не найдена")
        }
        working.remove(session, id)
        return ToolResult(true, "Заметка удалена")
    }

    /** list: строки "- id | title: content" в порядке добавления. */
    private fun list(session: String): ToolResult {
        val entries = working.entries(session)
        if (entries.isEmpty()) {
            return ToolResult(true, "Рабочая память пуста")
        }
        val text = entries.joinToString("\n") { "- ${it.id} | ${it.title}: ${it.content}" }
        return ToolResult(true, text)
    }

    companion object {
        const val NAME = "task_notes"
    }
}

/** Строковый параметр объекта аргументов: trim; отсутствующий и JSON-null дают null. */
internal fun JsonObject.toolString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
