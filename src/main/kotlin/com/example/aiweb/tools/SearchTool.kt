package com.example.aiweb.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Инструмент search: полнотекстовый поиск по файлам в `<dataDir>/exports/`
 * (папка, куда saveToFile кладёт результаты работы агента).
 *
 * Поиск построчный, регистронезависимый (contains); опциональный [SearchArgs.glob]
 * фильтрует файлы по относительному пути (поддерживаются `*` и `?`, `*` не
 * переходит через границы папок). Совпадения возвращаются строками
 * `файл:строка:фрагмент`, максимум [MAX_MATCHES] совпадений.
 *
 * Аргументы приходят от LLM — это ненадёжный ввод: валидируются до
 * использования; любая ошибка возвращается как [ToolResult] с ok = false,
 * исключения наружу не пробрасываются.
 */
class SearchTool(
    /** Каталог данных приложения; поиск ведётся в `<dataDir>/exports/`. */
    private val dataDir: String
) : Tool {

    override val name = NAME

    override val description =
        "Полнотекстовый поиск по файлам в папке exports (регистронезависимо, построчно). " +
            "Возвращает строки файл:строка:фрагмент, максимум 50 совпадений. " +
            "Примеры: найти упоминание порта в сохранённых отчётах."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Что искать (подстрока, без учёта регистра)")
            })
            put("glob", buildJsonObject {
                put("type", "string")
                put("description", "Фильтр файлов по относительному пути, например *.md (опционально)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val query = args.toolString("query")
        if (query.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"query\" (что искать)")
        }
        val glob = args.toolString("glob")?.takeIf { it.isNotBlank() }
        val matcher = glob?.let { globToRegex(it) }

        return try {
            val dir = File(dataDir, EXPORTS_DIR)
            if (!dir.isDirectory) {
                return ToolResult(true, "Совпадений не найдено: папка exports отсутствует")
            }
            val needle = query.lowercase()
            val lines = StringBuilder()
            var matches = 0
            val files = dir.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            for (file in files) {
                if (matches >= MAX_MATCHES) break
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                if (matcher != null && !matcher.matches(relative)) continue
                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    continue // нечитаемый файл пропускаем, поиск не прерываем
                }
                content.lineSequence().withIndex()
                    .takeWhile { matches < MAX_MATCHES }
                    .forEach { (index, line) ->
                        if (line.lowercase().contains(needle)) {
                            matches++
                            lines.append(relative).append(':').append(index + 1).append(':')
                                .append(fragment(line)).append('\n')
                        }
                    }
                if (matches >= MAX_MATCHES) break
            }
            if (matches == 0) {
                return ToolResult(true, "Совпадений не найдено")
            }
            val suffix = if (matches >= MAX_MATCHES) "\n…(показаны первые $MAX_MATCHES совпадений)" else ""
            ToolResult(true, lines.toString().trimEnd('\n') + suffix)
        } catch (e: Exception) {
            ToolResult(false, "Поиск не удался: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Фрагмент строки для вывода: обрезка пробелов и кап длины. */
    private fun fragment(line: String): String {
        val trimmed = line.trim()
        return if (trimmed.length > MAX_FRAGMENT_LENGTH) trimmed.take(MAX_FRAGMENT_LENGTH) + "…" else trimmed
    }

    companion object {
        const val NAME = "search"

        /** Папка поиска в каталоге данных (общая с saveToFile). */
        const val EXPORTS_DIR = "exports"

        /** Кап числа совпадений в ответе. */
        const val MAX_MATCHES = 50

        /** Кап длины одного фрагмента, символов. */
        const val MAX_FRAGMENT_LENGTH = 160

        /**
         * Простой glob в Regex: `*` — любые символы кроме разделителя папок,
         * `?` — один такой символ, остальное — как есть.
         */
        private fun globToRegex(glob: String): Regex = buildString {
            append('^')
            glob.forEach { c ->
                when (c) {
                    '*' -> append("[^/]*")
                    '?' -> append("[^/]")
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }.toRegex(RegexOption.IGNORE_CASE)
    }
}
