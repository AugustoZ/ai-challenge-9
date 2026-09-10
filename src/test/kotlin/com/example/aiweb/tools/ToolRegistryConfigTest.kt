package com.example.aiweb.tools

import com.example.aiweb.model.FileToolsConfig
import com.example.aiweb.model.SandboxToolConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки сборки реестра из раздела "tools" конфига: null даёт сегодняшнее
 * поведение (три встроенных), логические флаги исключают инструменты из
 * спецификаций, sandbox-инструменты подключаются только по enabled = true.
 */
class ToolRegistryConfigTest {

    @Test
    fun `null конфиг даёт три встроенных инструмента`() {
        val registry = ToolRegistry.forConfig(null)
        assertEquals(
            listOf("current_datetime", "calculator", "http_get"),
            registry.specs().map { it.function.name }
        )
    }

    @Test
    fun `выключенные встроенные исключаются из спецификаций`() {
        val registry = ToolRegistry.forConfig(FileToolsConfig(currentDatetime = false, httpGet = false))
        assertEquals(listOf("calculator"), registry.specs().map { it.function.name })
    }

    @Test
    fun `null флаги трактовка как включённые`() {
        val registry = ToolRegistry.forConfig(FileToolsConfig())
        assertEquals(
            listOf("current_datetime", "calculator", "http_get"),
            registry.specs().map { it.function.name }
        )
    }

    @Test
    fun `включенный run_code регистрируется с корректной спецификацией`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(runCode = SandboxToolConfig(enabled = true, timeoutSeconds = 45))
        )
        val spec = registry.specs().first { it.function.name == "run_code" }
        assertTrue(spec.function.description.contains("sandbox"), "В описании должна упоминаться песочница")
        assertTrue(spec.function.parameters.toString().contains("code"), "В схеме должен быть параметр code")
        assertEquals("run_code", registry.get("run_code")?.name)
    }

    @Test
    fun `включенный read_file регистрируется`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(readFile = SandboxToolConfig(enabled = true))
        )
        assertTrue(registry.specs().any { it.function.name == "read_file" })
        assertNotNull(registry.get("read_file"))
    }

    @Test
    fun `включенный write_file регистрируется`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(writeFile = SandboxToolConfig(enabled = true))
        )
        assertTrue(registry.specs().any { it.function.name == "write_file" })
        assertNotNull(registry.get("write_file"))
    }

    @Test
    fun `hostDir доносится до описаний файловых инструментов`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(
                readFile = SandboxToolConfig(enabled = true, hostDir = "/Users/example/Downloads"),
                writeFile = SandboxToolConfig(enabled = true, hostDir = "/Users/example/Downloads")
            )
        )
        for (name in listOf("read_file", "write_file")) {
            val description = registry.specs().first { it.function.name == name }.function.description
            assertTrue(description.contains("host-files"), "$name: должно упоминаться расположение папки хоста")
            assertTrue(description.contains("/Users/example/Downloads"), "$name: должен быть виден путь хоста")
        }
    }

    @Test
    fun `без hostDir описания без упоминания папки хоста`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(
                readFile = SandboxToolConfig(enabled = true),
                writeFile = SandboxToolConfig(enabled = true)
            )
        )
        for (name in listOf("read_file", "write_file")) {
            val description = registry.specs().first { it.function.name == name }.function.description
            assertTrue(!description.contains("host-files"), "$name: без hostDir упоминания быть не должно")
        }
    }

    @Test
    fun `fileClient привязан к файловому инструменту и null без него`() {
        assertNull(
            ToolRegistry.forConfig(FileToolsConfig()).fileClient(),
            "Без файловых инструментов клиента песочницы быть не должно"
        )
        assertNotNull(
            ToolRegistry.forConfig(
                FileToolsConfig(readFile = SandboxToolConfig(enabled = true))
            ).fileClient()
        )
        assertNotNull(
            ToolRegistry.forConfig(
                FileToolsConfig(writeFile = SandboxToolConfig(enabled = true))
            ).fileClient()
        )
    }

    @Test
    fun `выключенные sandbox-инструменты не видны модели`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(
                runCode = SandboxToolConfig(enabled = false),
                readFile = SandboxToolConfig(enabled = false)
            )
        )
        assertEquals(
            listOf("current_datetime", "calculator", "http_get"),
            registry.specs().map { it.function.name }
        )
    }

    @Test
    fun `все инструменты выключены пустой список спецификаций`() {
        val registry = ToolRegistry.forConfig(
            FileToolsConfig(currentDatetime = false, calculator = false, httpGet = false)
        )
        assertEquals(emptyList(), registry.specs().map { it.function.name })
    }
}
