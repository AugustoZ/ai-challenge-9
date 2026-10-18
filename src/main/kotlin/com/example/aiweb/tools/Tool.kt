package com.example.aiweb.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Инструмент, доступный модели: описание для function calling (имя, назначение,
 * JSON-Schema параметров) и исполнение по строке аргументов в формате JSON.
 *
 * Аргументы приходят от LLM — это ненадёжный ввод: каждая реализация обязана
 * валидировать их до использования и никогда не бросать исключение наружу.
 * Любая ошибка возвращается как [ToolResult] с ok = false и понятным текстом.
 */
interface Tool {
    /** Уникальное имя инструмента (передаётся в OpenAI function calling). */
    val name: String

    /** Человекочитаемое описание назначения инструмента (для модели). */
    val description: String

    /** JSON-Schema параметров инструмента в виде произвольного JSON. */
    val parametersJsonSchema: JsonElement

    /** Выполняет инструмент по JSON-строке аргументов; исключений наружу не бросает. */
    suspend fun execute(argsJson: String): ToolResult
}

/** Результат исполнения инструмента: признак успеха и текст для модели (роль "tool"). */
data class ToolResult(
    /** true — инструмент отработал успешно; false — ошибка, текст описывает причину. */
    val ok: Boolean,
    /** Текст результата или ошибки — уходит модели как содержимое сообщения role="tool". */
    val content: String
)

/** Единый текст ошибки: строка аргументов не разбирается в JSON-объект. */
internal const val ARGS_NOT_OBJECT_ERROR = "Аргументы не являются JSON-объектом"

/**
 * Базовая валидация аргументов инструмента: строка должна разбираться в JSON-объект.
 * Пустая строка трактуется как пустой объект {} (инструменты без обязательных параметров).
 *
 * @return разобранный [JsonObject] или null — тогда инструмент возвращает
 *         [ToolResult] с текстом [ARGS_NOT_OBJECT_ERROR].
 */
internal fun parseArgsObject(argsJson: String): JsonObject? = try {
    Json.parseToJsonElement(argsJson.trim().ifEmpty { "{}" }) as? JsonObject
} catch (e: Exception) {
    null
}
