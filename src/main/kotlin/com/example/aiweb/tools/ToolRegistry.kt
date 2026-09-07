package com.example.aiweb.tools

import com.example.aiweb.model.OpenAIFunctionSpec
import com.example.aiweb.model.OpenAIToolSpec

/**
 * Реестр инструментов, доступных модели: точка регистрации встроенных
 * и будущих инструментов (например, подключаемых из конфигурации).
 *
 * @param tools инструменты по имени; порядок связанного словаря определяет
 *              порядок в списке спецификаций и в промт-протоколе
 */
class ToolRegistry(private val tools: Map<String, Tool>) {

    /** Описания инструментов для поля tools запроса (нативный function calling). */
    fun specs(): List<OpenAIToolSpec> = tools.values.map { tool ->
        OpenAIToolSpec(
            type = "function",
            function = OpenAIFunctionSpec(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersJsonSchema
            )
        )
    }

    /** Инструмент по имени; неизвестное имя — null. */
    fun get(name: String): Tool? = tools[name]

    companion object {
        /** Реестр со встроенными инструментами: current_datetime, calculator, http_get. */
        fun withBuiltIns(): ToolRegistry = ToolRegistry(
            linkedMapOf(
                CurrentDatetimeTool.NAME to CurrentDatetimeTool(),
                CalculatorTool.NAME to CalculatorTool(),
                HttpGetTool.NAME to HttpGetTool()
            )
        )
    }
}
