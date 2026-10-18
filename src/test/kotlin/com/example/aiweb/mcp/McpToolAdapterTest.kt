package com.example.aiweb.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Проверки McpToolAdapter против живого fake-echo stdio-сервера
 * (src/test/resources/mcp/fake_echo_server.py через venv-python).
 */
class McpToolAdapterTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))
    private val venvPython: Path = repoRoot.resolve("mcp-servers/.venv/bin/python")
    private val echoServer: Path = repoRoot.resolve("src/test/resources/mcp/fake_echo_server.py")

    private var client: McpSyncClient? = null

    private fun buildClient(): McpSyncClient {
        val params = ServerParameters.builder(venvPython.toString())
            .args(echoServer.toString())
            .build()
        val transport = StdioClientTransport(params, JacksonMcpJsonMapperSupplier().get())
        return McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(20))
            .initializationTimeout(Duration.ofSeconds(20))
            .clientInfo(McpSchema.Implementation.builder("mcp-adapter-test", "1.0").build())
            .build()
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(Files.exists(venvPython), "Python venv missing: $venvPython")
        assumeTrue(Files.exists(echoServer), "Echo server missing: $echoServer")
        client = buildClient()
    }

    @AfterEach
    fun tearDown() {
        client?.let { try { it.close() } catch (_: Throwable) {} }
        client = null
    }

    private fun tool(name: String): McpSchema.Tool {
        val tools = client!!.listTools().tools()
        return tools.first { it.name() == name }
    }

    @Test
    fun `adapter name uses mcp server double underscore tool prefix`() {
        val adapter = McpToolAdapter("fake", tool("echo"), client!!)
        assertTrue(adapter.name == "mcp__fake__echo", "expected mcp__fake__echo, got ${adapter.name}")
    }

    @Test
    fun `adapter exposes input schema from server declaration`() {
        val adapter = McpToolAdapter("fake", tool("echo"), client!!)
        assertTrue(adapter.parametersJsonSchema.toString().contains("text"),
            "schema должен описывать параметр text: ${adapter.parametersJsonSchema}")
        assertTrue(adapter.description.isNotBlank())
    }

    @Test
    fun `execute proxies arguments and returns text result`() = runBlocking {
        val adapter = McpToolAdapter("fake", tool("echo"), client!!)
        val result = adapter.execute("""{"text": "hi"}""")
        assertTrue(result.ok, result.content)
        assertTrue(result.content.contains("ECHO:hi"), "expected ECHO:hi, got ${result.content}")
    }

    @Test
    fun `tool error returns readable text result instead of throwing`() = runBlocking {
        val adapter = McpToolAdapter("fake", tool("fail"), client!!)
        val result = adapter.execute("{}")
        assertFalse(result.ok, "fail tool должен отразиться как ok=false")
        assertTrue(result.content.contains("fail tool as requested"),
            "ожидаемый читаемый текст ошибки, got ${result.content}")
        val echo = McpToolAdapter("fake", tool("echo"), client!!)
        val after = echo.execute("""{"text": "again"}""")
        assertTrue(after.ok && after.content.contains("ECHO:again"), "клиент жив после fail: ${after.content}")
    }

    @Test
    fun `non object args produce readable error`() = runBlocking {
        val adapter = McpToolAdapter("fake", tool("echo"), client!!)
        val result = adapter.execute("not json")
        assertFalse(result.ok)
        assertTrue(result.content.isNotBlank())
    }

    @Test
    fun `MAX_RESULT_LENGTH is a positive cap`() {
        assertTrue(McpToolAdapter.MAX_RESULT_LENGTH > 0)
    }
}
