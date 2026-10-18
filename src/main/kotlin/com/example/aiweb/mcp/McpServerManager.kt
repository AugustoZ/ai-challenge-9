package com.example.aiweb.mcp

import com.example.aiweb.config.McpServerConfig
import com.example.aiweb.tools.ToolRegistry
import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Состояние подключения к MCP-серверу:
 * CONNECTED — инициализирован, tools/list получены; DISABLED — enabled=false;
 * DEGRADED — процесс запущен, но handshake/инициализация не завершилась
 * (таймаут) или обрушился рабочий вызов; FAILED — процесс не смог запуститься
 * (битая команда/каталог).
 */
enum class McpConnectionStatus { CONNECTED, DISABLED, DEGRADED, FAILED }

/** Описание MCP-тула для инвентаря/спецификаций: имя, описание, JSON-Schema. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonElement
)

/**
 * Менеджер подключений к MCP-серверам (stdio-процессы) из [McpServerConfig].
 * На каждый сервер поднимается отдельный [McpSyncClient]; менеджер держит
 * статус подключения, отдаёт список тулов и исполняет вызовы по
 * (serverId, toolName, args), а по [close] корректно закрывает клиентов
 * (graceful + принудительно) — дочерних процессов-зомби не остаётся.
 *
 * Идемпотентен: повторный [start] не поднимает серверы второй раз, повторный
 * [close] безопасен. Блокирующие вызовы SDK выполняются только на
 * [Dispatchers.IO].
 */
class McpServerManager(private val serverConfigs: List<McpServerConfig>) : AutoCloseable {

    private class ServerState(val config: McpServerConfig) {
        @Volatile var started = false
        @Volatile var status = McpConnectionStatus.DISABLED
        @Volatile var error: String? = null
        @Volatile var client: McpSyncClient? = null
        @Volatile var tools: List<McpSchema.Tool> = emptyList()
    }

    private val states: LinkedHashMap<String, ServerState> = LinkedHashMap()
    private val lock = Any()
    private val closed = AtomicBoolean(false)

    init {
        serverConfigs.forEach { states[it.name] = ServerState(it) }
    }

    /** Поднимает все enabled-серверы (в Dispatchers.IO); повторный вызов — no-op. */
    suspend fun start() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed.get()) return@withContext
            states.values.forEach { ensureStarted(it) }
        }
    }

    private fun ensureStarted(state: ServerState) {
        if (state.started) return
        state.started = true
        if (!state.config.enabled) {
            state.status = McpConnectionStatus.DISABLED
            return
        }
        try {
            val client = buildClient(state.config)
            state.client = client
            client.initialize()
            val tools = client.listTools().tools()
            state.tools = tools
            state.status = McpConnectionStatus.CONNECTED
            state.error = null
        } catch (e: Exception) {
            state.client?.let { runCatching { it.close() } }
            state.client = null
            val launchFailed = isLaunchFailure(e)
            state.status = if (launchFailed) McpConnectionStatus.FAILED else McpConnectionStatus.DEGRADED
            state.error = safeError(e)
            System.err.println("mcp: сервер '${state.config.name}' не подключился ($state.status): ${state.error}")
        }
    }

    private fun buildClient(config: McpServerConfig): McpSyncClient {
        val mapper = JacksonMcpJsonMapperSupplier().get()
        val effectiveEnv = buildMap {
            System.getenv("PATH")?.let { put("PATH", it) }
            System.getenv("HOME")?.let { put("HOME", it) }
            // Санирование: PYTHONPATH/PYTHONHOME/VIRTUAL_ENV родителя ломают импорты
            // python-детей (venv-серверов) — SDK мерджит окружение процесса, и
            // чужой PYTHONPATH затеняет site-packages venv'а сервера.
            putAll(config.env)
            put("PYTHONUNBUFFERED", "1")
        }
        val params = ServerParameters.builder(config.command)
            .args(config.args)
            .env(effectiveEnv)
            .build()
        val transport = object : StdioClientTransport(params, mapper) {
            // SDK мерджит окружение родителя в ProcessBuilder ребёнка
            // (environment().putAll(env)); PYTHONPATH/PYTHONHOME/VIRTUAL_ENV
            // родителя затеняют site-packages venv'а python-сервера и ломают
            // импорты — вырезаем их после мерджа (явно заданные config.env
            // ключи с этими именами вернутся через putAll ниже по стеку).
            override fun getProcessBuilder(): ProcessBuilder =
                super.getProcessBuilder().apply {
                    environment().keys.removeAll(setOf("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))
                }
        }
        return McpClient.sync(transport)
            .requestTimeout(Duration.ofMillis(config.requestTimeoutMs))
            .initializationTimeout(Duration.ofMillis(config.initializationTimeoutMs))
            .clientInfo(McpSchema.Implementation.builder("shturman", "1.0").build())
            .build()
    }

    /** Статус сервера по имени; неизвестное имя — FAILED. */
    fun status(serverId: String): McpConnectionStatus =
        states[serverId]?.status ?: McpConnectionStatus.FAILED

    /** Статус всех настроенных серверов (по имени). */
    fun statuses(): Map<String, McpConnectionStatus> =
        states.mapValues { it.value.status }

    /** Текущая ошибка сервера (после FAILED/DEGRADED) или null. */
    fun error(serverId: String): String? = states[serverId]?.error

    /** Инвентарь тулов сервера; сервер не подключён — пустой список. */
    fun listTools(serverId: String): List<McpToolDescriptor> {
        val state = states[serverId] ?: return emptyList()
        if (state.client == null) return emptyList()
        return state.tools.map {
            McpToolDescriptor(
                name = it.name(),
                description = it.description() ?: "",
                inputSchema = it.inputSchema().toJsonElement()
            )
        }
    }

    /**
     * Исполняет тул сервера по (serverId, toolName, args). Неподключённый
     * сервер или неизвестный тул — [ToolResult] с ok=false и читаемым текстом;
     * исключение наружу не пробрасывается.
     */
    suspend fun callTool(serverId: String, toolName: String, args: Map<String, Any?>): ToolResult {
        val state = states[serverId] ?: return ToolResult(false, "MCP-сервер '$serverId' не найден")
        val client = state.client
        if (client == null || state.status != McpConnectionStatus.CONNECTED) {
            return ToolResult(false, "MCP-сервер '$serverId' недоступен ($state.status): ${state.error ?: "не подключён"}")
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = McpSchema.CallToolRequest.builder(toolName).arguments(args).build()
                val result = client.callTool(request)
                val text = McpToolAdapter.extractText(result).take(McpToolAdapter.MAX_RESULT_LENGTH)
                if (result.isError() == true) ToolResult(false, text) else ToolResult(true, text)
            } catch (e: Exception) {
                synchronized(lock) {
                    if (states[serverId]?.client == client && states[serverId]?.status == McpConnectionStatus.CONNECTED) {
                        states[serverId]?.status = McpConnectionStatus.DEGRADED
                        states[serverId]?.error = safeError(e)
                    }
                }
                ToolResult(false, "MCP-инструмент mcp__${serverId}__${toolName}: ${safeError(e)}")
            }
        }
    }

    /** Адаптер тула сервера; сервер не подключён или тула нет — null. */
    fun adapter(serverId: String, toolName: String): McpToolAdapter? {
        val state = states[serverId] ?: return null
        val client = state.client ?: return null
        val tool = state.tools.firstOrNull { it.name() == toolName } ?: return null
        return McpToolAdapter(serverId, tool, client)
    }

    /** Адаптеры всех тулов всех подключённых серверов. */
    fun adapters(): List<McpToolAdapter> = buildList {
        for ((serverId, state) in states) {
            val client = state.client ?: continue
            for (tool in state.tools) add(McpToolAdapter(serverId, tool, client))
        }
    }

    /** Реестр с адаптерами всех подключённых серверов (дедупликация имён). */
    fun mcpRegistry(): ToolRegistry {
        val merged = linkedMapOf<String, Tool>()
        adapters().forEach { adapter -> merged[adapter.name] = adapter }
        return ToolRegistry(merged)
    }

    /** Закрывает всех клиентов (graceful + принудительно); повторный вызов безопасен. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        states.values.forEach { state ->
            val client = state.client ?: return@forEach
            runCatching { client.closeGracefully() }
            runCatching { client.close() }
            state.client = null
        }
    }

    /** Регистрирует shutdown-хук JVM, закрывающий клиентов (для производства). */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    private fun isLaunchFailure(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return "cannot run program" in msg || "no such file" in msg ||
            "error=2" in msg || "createprocess" in msg || "error=13" in msg
    }

    private fun safeError(e: Throwable): String {
        val chain = generateSequence(e as Throwable?) { it.cause }.take(4)
            .joinToString(" ← ") { it::class.simpleName + ": " + (it.message?.takeIf { m -> m.isNotBlank() } ?: "") }
        return chain.ifBlank { e::class.simpleName ?: "unknown" }
    }
}
