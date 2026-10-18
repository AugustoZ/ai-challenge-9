package com.example.aiweb.mcp

import com.example.aiweb.config.McpServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Проверки McpServerManager: подключение к fake-echo stdio-серверу,
 * статусы (Connected/Disabled/Failed/Degraded), жизненный цикл (no-zombie
 * после close) и таймаут-деградация.
 */
class McpServerManagerTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))
    private val venvPython: Path = repoRoot.resolve("mcp-servers/.venv/bin/python")
    private val echoServer: Path = repoRoot.resolve("src/test/resources/mcp/fake_echo_server.py")

    private fun echoConfig(): McpServerConfig = McpServerConfig(
        name = "fake",
        command = venvPython.toString(),
        args = listOf(echoServer.toString()),
        enabled = true
    )

    private fun assumeEchoAvailable() {
        assumeTrue(Files.exists(venvPython), "Python venv missing: $venvPython")
        assumeTrue(Files.exists(echoServer), "Echo server missing: $echoServer")
    }

    private fun echoProcesses(): List<ProcessHandle> =
        ProcessHandle.allProcesses()
            .filter { it.info().commandLine().orElse("").contains("fake_echo_server.py") }
            .toList()

    @Test
    fun `enabled echo server becomes connected and exposes tools`() = runBlocking {
        assumeEchoAvailable()
        val manager = McpServerManager(listOf(echoConfig()))
        manager.start()

        assertEquals(McpConnectionStatus.CONNECTED, manager.status("fake"))
        val tools = manager.listTools("fake")
        assertEquals(setOf("echo", "fail"), tools.map { it.name }.toSet())
        val echo = tools.first { it.name == "echo" }
        assertTrue(echo.inputSchema.toString().contains("text"),
            "schema должен описывать text: ${echo.inputSchema}")
        manager.close()
    }

    @Test
    fun `disabled server is marked disabled and process not started`() = runBlocking {
        assumeEchoAvailable()
        val manager = McpServerManager(listOf(echoConfig().copy(enabled = false)))
        manager.start()

        assertEquals(McpConnectionStatus.DISABLED, manager.status("fake"))
        assertTrue(manager.listTools("fake").isEmpty())
        val res = manager.callTool("fake", "echo", mapOf("text" to "x"))
        assertFalse(res.ok, "disabled сервер недоступен")
        manager.close()
    }

    @Test
    fun `bad command degrades to failed and call gives readable error`() = runBlocking {
        val cfg = McpServerConfig(name = "broken", command = "/nonexistent-mcp-cmd-xyz")
        val manager = McpServerManager(listOf(cfg))
        manager.start()

        val st = manager.status("broken")
        assertTrue(st == McpConnectionStatus.FAILED || st == McpConnectionStatus.DEGRADED,
            "ожидался Failed/Degraded, got $st")
        val res = manager.callTool("broken", "echo", mapOf("text" to "x"))
        assertFalse(res.ok, "callTool на упавший сервер возвращает ошибку")
        assertTrue(res.content.contains("broken"), "сообщение называет сервер: ${res.content}")
        manager.close()
    }

    @Test
    fun `close kills the spawned python process (no zombie)`() = runBlocking {
        assumeEchoAvailable()
        val manager = McpServerManager(listOf(echoConfig()))
        manager.start()
        assertEquals(McpConnectionStatus.CONNECTED, manager.status("fake"))
        assertTrue(echoProcesses().isNotEmpty(), "до close процесс python живёт")

        manager.close()

        assertTrue(echoProcesses().isEmpty(), "после close python-процесс закрыт")
    }

    @Test
    fun `tiny request timeout degrades server but app stays alive`() = runBlocking {
        assumeEchoAvailable()
        val cfg = echoConfig().copy(requestTimeoutMs = 1)
        val manager = McpServerManager(listOf(cfg))
        manager.start()

        assertEquals(McpConnectionStatus.DEGRADED, manager.status("fake"))
        val res = manager.callTool("fake", "echo", mapOf("text" to "x"))
        assertFalse(res.ok, "таймаут-сервер недоступен")
        manager.close()
    }

    @Test
    fun `start is idempotent`() = runBlocking {
        assumeEchoAvailable()
        val manager = McpServerManager(listOf(echoConfig()))
        manager.start()
        manager.start()
        assertEquals(McpConnectionStatus.CONNECTED, manager.status("fake"))
        manager.close()
    }

    @Test
    fun `mcpRegistry contains adapter for connected server`() = runBlocking {
        assumeEchoAvailable()
        val manager = McpServerManager(listOf(echoConfig()))
        manager.start()

        val registry = manager.mcpRegistry()
        assertEquals("mcp__fake__echo", registry.get("mcp__fake__echo")?.name)
        assertTrue(registry.specs().any { it.function.name == "mcp__fake__echo" })
        manager.close()
    }
}
