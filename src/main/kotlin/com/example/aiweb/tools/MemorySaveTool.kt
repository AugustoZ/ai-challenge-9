package com.example.aiweb.tools

import com.example.aiweb.memory.LongTermMemoryStore
import com.example.aiweb.memory.MemoryEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Инструмент memory_save: сохраняет факт в долговременную память (общую для
 * всех сессий). Виды записей: profile — профиль пользователя, decision —
 * принятое решение, knowledge — знание. По дисциплине вызывается только по
 * явной просьбе пользователя («запомни», «сохрани»).
 */
class MemorySaveTool(
    /** Долговременная память. */
    private val long: LongTermMemoryStore
) : Tool {

    override val name = NAME

    override val description =
        "Сохраняет факт в долговременную память (виды: profile — профиль пользователя, " +
            "decision — принятое решение, knowledge — знание). " +
            "ИСПОЛЬЗОВАТЬ ТОЛЬКО ПО ЯВНОЙ ПРОСЬБЕ ПОЛЬЗОВАТЕЛЯ («запомни», «сохрани»)."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("kind", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("decision"))
                    add(JsonPrimitive("knowledge"))
                })
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "Короткий заголовок факта")
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Текст факта")
            })
            put("tags", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Теги для поиска (опционально)")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("kind"))
            add(JsonPrimitive("title"))
            add(JsonPrimitive("content"))
        })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val kind = args.toolString("kind")
        if (kind.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"kind\" (profile, decision или knowledge)")
        }
        val title = args.toolString("title")
        if (title.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"title\" (заголовок факта)")
        }
        val content = args.toolString("content")
        if (content.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"content\" (текст факта)")
        }
        val tags = args.tags()
        val now = System.currentTimeMillis()
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = title,
            content = content,
            tags = tags,
            source = "agent",
            createdAt = now,
            updatedAt = now
        )
        return try {
            val stored = long.add(entry)
            ToolResult(true, "Сохранено в долговременную память: [${stored.kind}] ${stored.title} (id ${stored.id})")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Не удалось сохранить запись в долговременную память")
        }
    }

    /** Список тегов из массива строк; отсутствует или не массив — пустой список. */
    private fun kotlinx.serialization.json.JsonObject.tags(): List<String> =
        (this["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    companion object {
        const val NAME = "memory_save"
    }
}
