package com.example.aiweb.mcp

import com.example.aiweb.tools.ARGS_NOT_OBJECT_ERROR
import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import com.example.aiweb.tools.parseArgsObject
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Адаптер MCP-тула подключённого сервера к интерфейсу [Tool] проекта:
 * имя `mcp__<serverId>__<toolName>`, JSON-Schema из объявления тула и
 * исполнение через синхронный [McpSyncClient] на [Dispatchers.IO].
 *
 * Ошибка тула (isError=true) возвращается как [ToolResult] с ok=false и
 * читаемым текстом из content — исключение наружу не пробрасывается
 * (контракт [Tool.execute]).
 */
class McpToolAdapter(
    private val serverId: String,
    private val tool: McpSchema.Tool,
    private val client: McpSyncClient
) : Tool {

    override val name: String = "mcp__${serverId}__${tool.name()}"

    override val description: String = tool.description()?.takeIf { it.isNotBlank() }
        ?: "MCP-инструмент $serverId/${tool.name()}"

    override val parametersJsonSchema: JsonElement = tool.inputSchema().toJsonElement()

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        return withContext(Dispatchers.IO) {
            try {
                val request = McpSchema.CallToolRequest.builder(tool.name())
                    .arguments(args.toJvmValue() as Map<String, Any?>)
                    .build()
                val result = client.callTool(request)
                val text = extractText(result).take(MAX_RESULT_LENGTH)
                if (result.isError() == true) ToolResult(false, text) else ToolResult(true, text)
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown"
                ToolResult(false, "MCP-инструмент $name: $detail")
            }
        }
    }

    companion object {
        /** Кап текста результата (20 000 символов) — вне агентского цикла свой лимит. */
        const val MAX_RESULT_LENGTH = 20_000

        /** Собирает текст из content (только [McpSchema.TextContent]). */
        fun extractText(result: McpSchema.CallToolResult): String =
            result.content().filterIsInstance<McpSchema.TextContent>()
                .joinToString("\n") { it.text() ?: "" }
    }
}

/** Преобразует произвольный вход (Map/List/примитивы из десериализации JSON) в [JsonElement]. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { for ((k, v) in this@toJsonElement) put(k.toString(), v.toJsonElement()) }
    is Iterable<*> -> buildJsonArray { for (v in this@toJsonElement) add(v.toJsonElement()) }
    is Array<*> -> buildJsonArray { for (v in this@toJsonElement) add(v.toJsonElement()) }
    else -> JsonPrimitive(toString())
}

/** Преобразует [JsonElement] аргументов в Map/List/примитивы для [McpSchema.CallToolRequest]. */
internal fun JsonElement.toJvmValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content
    else content.toLongOrNull() ?: content.toDoubleOrNull() ?: content.toBooleanStrictOrNull() ?: content
    is JsonObject -> LinkedHashMap<String, Any?>(size).also { m ->
        for ((k, v) in this) m[k] = v.toJvmValue()
    }
    is JsonArray -> map { it.toJvmValue() }
}
