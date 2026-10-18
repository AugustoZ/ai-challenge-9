package com.example.aiweb.tools

import com.example.aiweb.memory.LongTermMemoryStore
import com.example.aiweb.memory.MemoryEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Инструмент memory_recall: ищет по долговременной памяти (профили, решения,
 * знания) по строке запроса; можно уточнить вид записи. Целые слова запроса
 * используются как резервные подзапросы, если весь запрос ничего не нашёл.
 */
class MemoryRecallTool(
    /** Долговременная память. */
    private val long: LongTermMemoryStore
) : Tool {

    override val name = NAME

    override val description =
        "Ищет по долговременной памяти (профили/решения/знания) по строке запроса; " +
            "можно уточнить вид записи (profile, decision или knowledge)."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Строка поиска: слова из заголовка, текста или тегов записи")
            })
            put("kind", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("decision"))
                    add(JsonPrimitive("knowledge"))
                })
                put("description", "Ограничить поиск видом записи (опционально)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val query = args.toolString("query")
        if (query.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"query\" (строка поиска)")
        }
        val kind = args.toolString("kind")?.takeIf { it.isNotBlank() }
        val found = long.list(kind, query).ifEmpty { fallbackByTokens(kind, query) }
        if (found.isEmpty()) {
            return ToolResult(true, "В долговременной памяти ничего не найдено")
        }
        return ToolResult(true, found.joinToString("\n") { formatEntry(it) })
    }

    /**
     * Резервный поиск по отдельным словам запроса (буквы/цифры, длина не менее
     * трёх): по очереди, до первого непустого результата.
     */
    private fun fallbackByTokens(kind: String?, query: String): List<MemoryEntry> {
        val tokens = tokenize(query)
        if (tokens.size < 2) return emptyList()
        for (token in tokens) {
            val found = long.list(kind, token)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Слова запроса из букв и цифр (кириллица и латиница) длиной не менее трёх. */
    private fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        val run = StringBuilder()
        for (char in query.lowercase()) {
            if (char.isLetterOrDigit()) {
                run.append(char)
            } else if (run.isNotEmpty()) {
                if (run.length >= TOKEN_MIN_LENGTH) tokens.add(run.toString())
                run.clear()
            }
        }
        if (run.length >= TOKEN_MIN_LENGTH) tokens.add(run.toString())
        return tokens
    }

    /** Строка записи: "- [kind] title (теги: a, b): content", теги — при наличии. */
    private fun formatEntry(entry: MemoryEntry): String {
        val tags = if (entry.tags.isEmpty()) "" else " (теги: ${entry.tags.joinToString(", ")})"
        return "- [${entry.kind}] ${entry.title}$tags: ${entry.content}"
    }

    companion object {
        const val NAME = "memory_recall"

        /** Минимальная длина слова запроса для резервного поиска. */
        private const val TOKEN_MIN_LENGTH = 3
    }
}
