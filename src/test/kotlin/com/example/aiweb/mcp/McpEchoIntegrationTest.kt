package com.example.aiweb.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Интеграционный тест живого Java MCP-клиента (SDK 2.0.1) против fake-echo
 * stdio-сервера из src/test/resources/mcp/fake_echo_server.py.
 *
 * Скипается (assumeTrue), если venv-python или echo-скрипт недоступны.
 * Использует ТОЛЬКО test-ресурсы: в src/main ничего не добавлял.
 */
class McpEchoIntegrationTest {

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
            .clientInfo(McpSchema.Implementation.builder("mcp-echo-test", "1.0").build())
            .build()
    }

    private fun callText(name: String, args: Map<String, Any>): String {
        val req = McpSchema.CallToolRequest.builder(name).arguments(args).build()
        val res = client!!.callTool(req)
        return res.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }
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

    @Test
    fun listTools_hasEchoAndFail() {
        val names = client!!.listTools().tools().map { it.name() }
        assertTrue(names.containsAll(listOf("echo", "fail")), "expected echo+fail, got $names")
    }

    @Test
    fun echo_returnsEchoPrefixedText() {
        val text = callText("echo", mapOf("text" to "hi"))
        assertTrue(text.contains("ECHO:hi"), "expected ECHO:hi, got $text")
    }

    @Test
    fun fail_isErrorWithReadableMessage_thenEchoStillWorks() {
        val req = McpSchema.CallToolRequest.builder("fail").arguments(emptyMap()).build()
        val res = client!!.callTool(req)
        val failText = res.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }
        assertTrue(res.isError(), "fail tool should set isError=true")
        assertTrue(failText.contains("fail tool as requested"), "expected readable fail message, got $failText")
        val echo2 = callText("echo", mapOf("text" to "after"))
        assertTrue(echo2.contains("ECHO:after"), "echo after fail failed, got $echo2")
    }
}
