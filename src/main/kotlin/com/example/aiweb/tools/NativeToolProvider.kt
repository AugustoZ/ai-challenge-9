package com.example.aiweb.tools

import com.example.aiweb.client.LLMClient

/**
 * Поставщик нативных инструментов агента (задача 4 плана mcp-integration):
 * reminder, summarize, search, saveToFile. Регистрируются поверх реестра из
 * конфига через [ToolRegistry.augmentedWith] — раздел "tools" конфига эти
 * инструменты не расширяют и forConfig не трогают.
 */
object NativeToolProvider {

    /**
     * Константный список нативных инструментов.
     *
     * @param dataDir каталог данных приложения: reminders.json и папка exports
     *        живут рядом с хранилищами памяти; null трактуется как пустая строка
     *        не допускается — на месте вызова dataDir уже разрешён (временный
     *        каталог в P0-режиме)
     * @param llm LLM-клиент для summarize; null — провайдеров нет, суммаризация
     *        отвечает ok = false
     */
    fun tools(dataDir: String, llm: LLMClient?): List<Tool> = listOf(
        ReminderTool(dataDir),
        SummarizeTool(llm),
        SearchTool(dataDir),
        SaveToFileTool(dataDir)
    )
}
