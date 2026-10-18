package com.example.aiweb.tools

import com.example.aiweb.model.FileToolsConfig
import com.example.aiweb.model.OpenAIFunctionSpec
import com.example.aiweb.model.OpenAIToolSpec
import com.example.aiweb.model.SandboxToolConfig

/**
 * Реестр инструментов, доступных модели: точка регистрации встроенных
 * и будущих инструментов (например, подключаемых из конфигурации).
 *
 * @param tools инструменты по имени; порядок связанного словаря определяет
 *              порядок в списке спецификаций и в промт-протоколе
 */
class ToolRegistry(
    private val tools: Map<String, Tool>,
    /** Клиент песочницы файлового инструмента (read_file/write_file); null — файловые выключены. */
    private val fileSandboxClient: SandboxClient? = null
) {

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

    /**
     * Клиент песочницы, привязанный к файловому инструменту (read_file или write_file) —
     * маршруту скачивания файлов пользователем. Файловые инструменты выключены — null.
     */
    fun fileClient(): SandboxClient? = fileSandboxClient

    /**
     * Новый реестр с дополнительными инструментами: текущие инструменты (в
     * порядке регистрации) плюс [extra]; инструменты с уже занятым именем
     * пропускаются — дубликатов в реестре не появляется. Параметры реестра
     * (включая клиент файловых инструментов) наследуются.
     */
    fun augmentedWith(extra: List<Tool>): ToolRegistry {
        val merged = linkedMapOf<String, Tool>()
        tools.forEach { (name, tool) -> merged[name] = tool }
        extra.forEach { tool -> if (tool.name !in merged) merged[tool.name] = tool }
        return ToolRegistry(merged, fileSandboxClient)
    }

    companion object {
        /** Реестр со встроенными инструментами: current_datetime, calculator, http_get. */
        fun withBuiltIns(): ToolRegistry = ToolRegistry(
            linkedMapOf(
                CurrentDatetimeTool.NAME to CurrentDatetimeTool(),
                CalculatorTool.NAME to CalculatorTool(),
                HttpGetTool.NAME to HttpGetTool()
            )
        )

        /**
         * Реестр из раздела "tools" конфига — единственная точка сборки реестра
         * для запуска приложения (см. Routes.aiRoutes).
         *
         * Встроенные инструменты (current_datetime, calculator, http_get)
         * регистрируются по логическим флагам; null трактуется как true —
         * отсутствие раздела в конфиге даёт сегодняшнее поведение
         * ([withBuiltIns]). Sandbox-инструменты run_code/read_file/write_file
         * подключаются только при {"enabled": true} в их конфиге. Отключённые
         * в конфиге инструменты не видны модели вообще — они не попадают в specs().
         *
         * SandboxClient переиспользуется: один клиент на один уникальный
         * [SandboxToolConfig] в реестре (таймаут/адрес песочницы — на инструмент).
         * Клиент файловых инструментов (read_file/write_file) дополнительно
         * запоминается для маршрута скачивания файлов пользователем.
         */
        fun forConfig(tools: FileToolsConfig?): ToolRegistry {
            if (tools == null) return withBuiltIns()
            val sandboxClients = mutableMapOf<SandboxToolConfig, SandboxClient>()
            fun sandboxClient(config: SandboxToolConfig): SandboxClient =
                sandboxClients.getOrPut(config) { SandboxClient(config) }

            var fileClient: SandboxClient? = null
            val registered = linkedMapOf<String, Tool>()
            if (tools.currentDatetime != false) {
                registered[CurrentDatetimeTool.NAME] = CurrentDatetimeTool()
            }
            if (tools.calculator != false) {
                registered[CalculatorTool.NAME] = CalculatorTool()
            }
            if (tools.httpGet != false) {
                registered[HttpGetTool.NAME] = HttpGetTool()
            }
            tools.runCode?.takeIf { it.enabled }?.let { config ->
                registered[RunCodeTool.NAME] = RunCodeTool(sandboxClient(config), config.timeoutSeconds)
            }
            tools.readFile?.takeIf { it.enabled }?.let { config ->
                val client = sandboxClient(config)
                registered[ReadFileTool.NAME] = ReadFileTool(client, config.hostDir)
                fileClient = client
            }
            tools.writeFile?.takeIf { it.enabled }?.let { config ->
                val client = sandboxClient(config)
                registered[WriteFileTool.NAME] = WriteFileTool(client, config.hostDir)
                fileClient = client
            }
            return ToolRegistry(registered, fileClient)
        }
    }
}
