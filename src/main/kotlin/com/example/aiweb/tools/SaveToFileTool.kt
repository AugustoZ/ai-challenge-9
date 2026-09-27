package com.example.aiweb.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Инструмент saveToFile: сохраняет текстовый файл в `<dataDir>/exports/`
 * (существующий файл перезаписывается). Так агент оставляет результаты работы
 * на диске: отчёты, заметки, подготовленные данные; их потом находит поиском
 * инструмент search.
 *
 * Запись разрешена ТОЛЬКО внутрь exports: путь обязателен, не пуст, не длиннее
 * капа, не абсолютный и не содержит «..» — гварды в точности по образцу
 * read_file/write_file (SandboxTools). Содержимое обязательно и не длиннее
 * 1 МБ символов. Папка exports при необходимости создаётся. Любая ошибка
 * возвращается как [ToolResult] с понятным текстом — исключения наружу не
 * пробрасываются.
 */
class SaveToFileTool(
    /** Каталог данных приложения; файлы пишутся в `<dataDir>/exports/`. */
    private val dataDir: String
) : Tool {

    override val name = NAME

    override val description =
        "Сохраняет текстовый файл в папку exports; существующий файл перезаписывается. " +
            "Примеры: положить отчёт report.md для поиска и выгрузки. Путь относительный " +
            "внутри exports; абсолютные пути и «..» запрещены."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Относительный путь файла внутри exports, например report.md или out/data.csv")
                put("maxLength", MAX_PATH_LENGTH)
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Полное содержимое файла; существующий файл перезаписывается целиком")
                put("maxLength", MAX_CONTENT_LENGTH)
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("name"))
            add(JsonPrimitive("content"))
        })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val name = args.toolString("name")
        if (name.isNullOrEmpty()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"name\" (относительный путь файла)")
        }
        // Гварды пути — по образцу SandboxTools.kt (read_file/write_file)
        if (name.length > MAX_PATH_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: путь длиннее $MAX_PATH_LENGTH символов")
        }
        if (name.startsWith("/")) {
            return ToolResult(false, "Некорректные аргументы: путь должен быть относительным, без \"..\" — '$name'")
        }
        if (name.contains("..")) {
            return ToolResult(false, "Некорректные аргументы: путь не должен содержать \"..\" — '$name'")
        }
        val content = args.toolString("content")
        if (content.isNullOrEmpty()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"content\" (непустая строка с содержимым файла)")
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: содержимое длиннее $MAX_CONTENT_LENGTH символов (${content.length})")
        }

        val exports = File(dataDir, EXPORTS_DIR)
        return try {
            val target = File(exports, name)
            target.parentFile?.mkdirs()
            target.writeText(content)
            val bytes = content.toByteArray(Charsets.UTF_8).size
            ToolResult(true, "Файл записан: exports/$name ($bytes байт)")
        } catch (e: Exception) {
            ToolResult(false, "Не удалось записать файл: ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        const val NAME = "saveToFile"

        /** Папка записи в каталоге данных (общая с search). */
        const val EXPORTS_DIR = "exports"

        /** Кап длины пути, символов. */
        const val MAX_PATH_LENGTH = 1024

        /** Кап содержимого, символов (1 МБ). */
        const val MAX_CONTENT_LENGTH = 1_000_000
    }
}
