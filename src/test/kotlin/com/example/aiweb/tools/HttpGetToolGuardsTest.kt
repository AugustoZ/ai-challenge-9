package com.example.aiweb.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки гардов http_get: запрещённые схемы, служебные имена хостов и закрытые
 * IP-диапазоны. Все проверки отрабатывают ДО сетевого соединения — тесты не ходят в сеть.
 */
class HttpGetToolGuardsTest {

    private val tool = HttpGetTool()

    private suspend fun fetch(url: String): ToolResult = tool.execute("{\"url\": \"$url\"}")

    @Test
    fun `локальные и приватные адреса запрещены`() = runBlocking {
        listOf(
            "http://127.0.0.1/x",
            "http://localhost/x",
            "http://192.168.1.1/x",
            "http://10.0.0.5/x",
            "http://172.16.0.1/x",
            "http://169.254.169.254/meta",
            "http://100.64.0.1/x",
            "http://[::1]/x"
        ).forEach { url ->
            val result = fetch(url)
            assertFalse(result.ok, "Ожидался запрет для $url, получено: ${result.content}")
            assertTrue(result.content.isNotBlank())
        }
    }

    @Test
    fun `запрещённые схемы не доходят до соединения`() = runBlocking {
        listOf("file:///etc/passwd", "ftp://example.com/doc", "data://text/plain,hi").forEach { url ->
            val result = fetch(url)
            assertFalse(result.ok, "Ожидался запрет для $url, получено: ${result.content}")
        }
    }

    @Test
    fun `служебные имена хостов запрещены`() = runBlocking {
        listOf("http://service.internal/x", "http://printer.local/x").forEach { url ->
            val result = fetch(url)
            assertFalse(result.ok, "Ожидался запрет для $url, получено: ${result.content}")
        }
    }

    @Test
    fun `битые аргументы дают ошибку без исключения`() = runBlocking {
        assertFalse(tool.execute("{}").ok)
        assertFalse(tool.execute("мусор").ok)
    }
}
