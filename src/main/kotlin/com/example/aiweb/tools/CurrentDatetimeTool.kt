package com.example.aiweb.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Инструмент current_datetime: возвращает текущие дату и время (ISO-8601, локальные)
 * с указанием часового пояса. Параметров нет — пустые аргументы допустимы.
 */
class CurrentDatetimeTool : Tool {

    override val name = NAME

    override val description =
        "Возвращает текущие дату и время (ISO-8601, локальные) с указанием часового пояса. " +
            "Параметров не требует."

    /** Инструмент без параметров: пустая схема-объект. */
    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(argsJson: String): ToolResult {
        parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val now = LocalDateTime.now()
        return ToolResult(
            ok = true,
            content = "Текущие дата и время: $now (часовой пояс: ${ZoneId.systemDefault().id})"
        )
    }

    companion object {
        const val NAME = "current_datetime"
    }
}
