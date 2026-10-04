package com.example.aiweb.config

import com.example.aiweb.model.ContextStrategy
import com.example.aiweb.model.FileConfig
import com.example.aiweb.model.FileProvider
import com.example.aiweb.model.FileMcpServerConfig
import com.example.aiweb.model.FileToolsConfig
import com.example.aiweb.model.MemoryConfig
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Конфигурация одного провайдера LLM.
 *
 * @param type протокол: "openai" — OpenAI-совместимый Chat Completions,
 *             "opencode" — локальный экземпляр OpenCode (opencode serve)
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    /** Явный список моделей; пусто — список запрашивается у API провайдера. */
    val models: List<String> = emptyList(),
    /** id провайдера внутри OpenCode для Model.Ref (type = "opencode"). */
    val upstream: String = "",
    /**
     * Поддержка провайдером нативного function calling; false (по умолчанию) —
     * инструменты вызываются через промт-протокол (fallback), т.к. серверы без
     * флагов tools молча игнорируют их.
     */
    val supportsTools: Boolean = false
) {
    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_OPENCODE = "opencode"
    }
}

/**
 * Рантайм-конфигурация одного MCP-сервера (раздел "mcp" конфиг-файла).
 *
 * [toString] намеренно не печатает значения [env]: секция может носить секреты
 * (ключи API), а конфиг попадает в логи и отладочный вывод. Ключи и число
 * переменных видны, значения скрыты.
 */
data class McpServerConfig(
    /** Уникальное имя сервера в пределах раздела mcp. */
    val name: String,
    /** Команда запуска серверного процесса (stdio-транспорт). */
    val command: String,
    /** Аргументы команды. */
    val args: List<String> = emptyList(),
    /** Переменные окружения процесса; значения исключены из [toString]. */
    val env: Map<String, String> = emptyMap(),
    /** false — сервер не запускается (конфигурация остаётся видимой). */
    val enabled: Boolean = true,
    /** Таймаут одиночного запроса (callTool/listTools), мс. */
    val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    /** Таймаут инициализации (handshake initialize), мс. */
    val initializationTimeoutMs: Long = DEFAULT_INITIALIZATION_TIMEOUT_MS
) {
    override fun toString(): String =
        "McpServerConfig(name=$name, command=$command, args=$args, " +
            "env=<${env.size} keys, values hidden>, enabled=$enabled, " +
            "requestTimeoutMs=$requestTimeoutMs, initializationTimeoutMs=$initializationTimeoutMs)"

    companion object {
        /** Таймаут запроса по умолчанию (30 с). */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** Таймаут инициализации по умолчанию (10 с). */
        const val DEFAULT_INITIALIZATION_TIMEOUT_MS = 10_000L
    }
}

/**
 * Рантайм-конфигурация RAG-поиска (раздел "rag" конфиг-файла).
 *
 * RAG активен только если [url] != null; при отсутствии ключей в разделе
 * значения дополняются дефолтами [DEFAULT_K]/[DEFAULT_TIMEOUT_MS]/
 * [DEFAULT_MAX_BLOCK_CHARS]. Валидация значений выполняется только при
 * непустом url (см. AppConfig.validate).
 *
 * Только конфиг-файл: переменных окружения для RAG-раздела нет.
 */
data class RagConfig(
    /** Адрес RAG-сервиса (POST {url} с телом {"query": ..., "k": ...}); null — RAG выключен. */
    val url: String? = null,
    /** Число извлекаемых чанков (1..20). */
    val k: Int = DEFAULT_K,
    /** Таймаут запроса к RAG-сервису, мс (>= 1000). */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Максимальный суммарный размер инжектируемого блока документов, символы (>= 500). */
    val maxBlockChars: Int = DEFAULT_MAX_BLOCK_CHARS,
    /**
     * Реранкер на стороне RAG-сервиса (второй этап пайплайна): true — сервис
     * переранжирует кандидатов и score приходит на sigmoid-шкале [0, 1].
     */
    val rerank: Boolean = false,
    /**
     * Порог отсечения нерелевантных результатов на финальной шкале score
     * (cosine [-1, 1] при rerank=false, sigmoid [0, 1] при rerank=true);
     * null — фильтрация порогом выключена.
     */
    val threshold: Double? = null,
    /**
     * Число кандидатов до фильтрации/реранкинга (1..20); null — берётся [k].
     * Клэмп sidecar до 50 из конфига недоступен: K_MAX Штурмана = 20.
     */
    val kBefore: Int? = null,
    /** Переписывание поискового запроса через LLM перед извлечением (fail-open). */
    val rewrite: Boolean = false,
    /** Таймаут запроса на переписывание запроса, мс (>= 1000). */
    val rewriteTimeoutMs: Long = DEFAULT_REWRITE_TIMEOUT_MS
) {
    companion object {
        /** Число чанков по умолчанию. */
        const val DEFAULT_K = 5

        /** Максимальное число извлекаемых чанков. */
        const val K_MAX = 20

        /** Таймаут запроса по умолчанию (10 с). */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Бюджет блока документов по умолчанию. */
        const val DEFAULT_MAX_BLOCK_CHARS = 6_000

        /** Таймаут переписывания запроса по умолчанию (8 с). */
        const val DEFAULT_REWRITE_TIMEOUT_MS = 8_000L
    }
}

/**
 * Конфигурация приложения.
 *
 * Значения подставляются по приоритету (от высшего к низшему):
 *  1. Переменные окружения (PORT, AI_API_URL, AI_API_KEY, AI_MODEL, AI_DATA_DIR,
 *     AI_SUPPORTS_TOOLS, CONFIG_FILE)
 *  2. Конфиг-файл в формате JSON (путь задаётся через CONFIG_FILE)
 *  3. Значения по умолчанию
 *
 * Поля apiUrl/apiKey/model файла задают провайдер по умолчанию; массив providers —
 * дополнительные провайдеры (например, локальный экземпляр OpenCode с бесплатными
 * моделями). allowedModels — белый список: из списка моделей провайдера по умолчанию
 * остаются только перечисленные (в заданном порядке).
 */
data class AppConfig(
    val port: Int,
    val default: ProviderConfig,
    val extraProviders: List<ProviderConfig> = emptyList(),
    val allowedModels: List<String> = emptyList(),
    /**
     * Каталог для данных (P1-персистентность сессий); null — только in-memory.
     * Приоритет: env AI_DATA_DIR > config.json > null. В P0 каталог монтируется,
     * но пуст — SessionStore остаётся InMemorySessionStore.
     */
    val dataDir: String? = null,
    /**
     * Раздел инструментов: вкл/выкл встроенных и подключение sandbox-инструментов.
     * null — сегодняшнее поведение (три встроенных инструмента, sandbox выключены).
     * Только конфиг-файл: переменных окружения для инструментов нет.
     */
    val tools: FileToolsConfig? = null,
    /** Настройки памяти агента: стратегия поиска; null — keyword-поиск. Только конфиг-файл. */
    val memory: MemoryConfig? = null,
    /**
     * Сжатие истории диалога: null-поле запроса compressionEnabled трактуется как
     * это значение (приоритет: поле запроса > конфиг). Только конфиг-файл.
     */
    val compressionEnabled: Boolean = false,
    /** Окно последних сообщений, уходящих в запрос «как есть» (≥ 1). */
    val compressionWindowSize: Int = 5,
    /** Порог слияния: сообщений вне окна, после которых выполняется один merge (≥ 1). */
    val compressionMergeEvery: Int = 5,
    /** Бюджет сводки диалога в токенах (≥ 50). */
    val compressionSummaryMaxTokens: Int = 400,
    /**
     * Стратегия формирования контекста из конфиг-файла — нижний уровень
     * приоритета: действует, когда запрос не несёт ни явной стратегии, ни
     * legacy-флага. Только конфиг-файл: переменных окружения нет.
     */
    val contextStrategy: ContextStrategy = ContextStrategy.OFF,
    /**
     * Сырое значение ключа contextStrategy из конфиг-файла — только для
     * fail-fast в [validate]: невалидная строка падает стартом. null — ключ
     * в файле отсутствовал.
     */
    val contextStrategyRaw: String? = null,
    /**
     * MCP-серверы из раздела "mcp" конфиг-файла; пусто — раздел отсутствовал
     * или список пуст. Только конфиг-файл: переменных окружения нет.
     * Значения env в [McpServerConfig] не попадают в toString.
     */
    val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * RAG-поиск по базе знаний: url == null — RAG выключен. Только конфиг-файл:
     * переменных окружения нет.
     */
    val rag: RagConfig = RagConfig()
) {
    val providers: List<ProviderConfig> get() = listOf(default) + extraProviders

    /** Провайдер по идентификатору; неизвестный или пустой id — провайдер по умолчанию. */
    fun provider(id: String?): ProviderConfig =
        providers.firstOrNull { it.id == id?.trim() } ?: default

    companion object {
        private const val DEFAULT_CONFIG_FILE = "config.json"
        private val defaultConfig = AppConfig(
            port = 8080,
            default = ProviderConfig(
                id = DEFAULT_PROVIDER_ID,
                name = "Основной",
                type = ProviderConfig.TYPE_OPENAI,
                apiUrl = "https://api.openai.com/v1/chat/completions",
                apiKey = "ВАШ_API_КЛЮЧ",
                model = "gpt-3.5-turbo"
            ),
            compressionEnabled = false,
            compressionWindowSize = 5,
            compressionMergeEvery = 5,
            compressionSummaryMaxTokens = 400
        )

        const val DEFAULT_PROVIDER_ID = "default"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Загружает конфигурацию, объединяя значения из переменных окружения
         * и конфиг-файла.
         */
        fun fromEnv(): AppConfig {
            // 1. Значения по умолчанию + конфиг-файл
            val configPath = System.getenv("CONFIG_FILE") ?: DEFAULT_CONFIG_FILE
            var config = fromFile(configPath)

            // 2. Переменные окружения имеют высший приоритет
            System.getenv("PORT")?.toIntOrNull()?.let { config = config.copy(port = it) }
            System.getenv("AI_API_URL")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultApiUrl(it) }
            System.getenv("AI_API_KEY")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultApiKey(it) }
            System.getenv("AI_MODEL")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultModel(it) }

            // Каталог данных: env перекрывает config.json
            System.getenv("AI_DATA_DIR")?.takeIf { it.isNotBlank() }
                ?.let { config = config.copy(dataDir = it) }

            // Поддержка нативного function calling: env включает её только у провайдера
            // по умолчанию ("true"/"1", регистр не важен); отключить из env нельзя —
            // приоритет env > config.json.
            System.getenv("AI_SUPPORTS_TOOLS")
                ?.takeIf { it.equals("true", ignoreCase = true) || it == "1" }
                ?.let { config = config.copy(default = config.default.copy(supportsTools = true)) }

            return config.validate()
        }

        /**
         * Загружает конфигурацию только из конфиг-файла (без переменных окружения),
         * начиная со значений по умолчанию. Отдельная точка входа для тестов:
         * System.getenv в JVM подменить нельзя, поэтому файловый уровень
         * проверяется напрямую через эту функцию (в fromEnv вызывается до наложения env).
         */
        internal fun fromFile(path: String): AppConfig {
            var config = defaultConfig
            val fileConfig = loadFile(path)
            if (fileConfig != null) {
                config = config.applyFile(fileConfig)
            }
            return config.validate()
        }

        /**
         * Читает конфиг-файл. Возвращает null, если файл не найден или повреждён.
         */
        private fun loadFile(path: String): FileConfig? {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return null
            }
            return try {
                json.decodeFromString<FileConfig>(file.readText())
            } catch (e: Exception) {
                System.err.println(
                    "Предупреждение: не удалось прочитать конфиг-файл '$path': ${e.message}"
                )
                null
            }
        }

        /** Применяет значения из конфиг-файла к текущей конфигурации. */
        private fun AppConfig.applyFile(fc: FileConfig): AppConfig {
            val defaultProvider = default.copy(
                apiUrl = fc.apiUrl ?: default.apiUrl,
                apiKey = fc.apiKey ?: default.apiKey,
                model = fc.model ?: default.model,
                supportsTools = fc.supportsTools ?: default.supportsTools
            )
            val extras = fc.providers.orEmpty()
                .map { it.toProviderConfig() }
                .filter { it.apiUrl.isNotBlank() && it.id.isNotBlank() }
            return copy(
                port = fc.port ?: port,
                default = defaultProvider,
                extraProviders = extras,
                allowedModels = fc.allowedModels.orEmpty().filter { it.isNotBlank() },
                dataDir = fc.dataDir?.takeIf { it.isNotBlank() } ?: dataDir,
                tools = fc.tools ?: tools,
                memory = fc.memory ?: memory,
                compressionEnabled = fc.compressionEnabled ?: compressionEnabled,
                compressionWindowSize = fc.compressionWindowSize ?: compressionWindowSize,
                compressionMergeEvery = fc.compressionMergeEvery ?: compressionMergeEvery,
                compressionSummaryMaxTokens = fc.compressionSummaryMaxTokens ?: compressionSummaryMaxTokens,
                contextStrategy = ContextStrategy.fromId(fc.contextStrategy) ?: contextStrategy,
                contextStrategyRaw = fc.contextStrategy,
                mcpServers = fc.mcp?.map { it.toMcpServerConfig() } ?: mcpServers,
                rag = fc.rag?.let {
                    RagConfig(
                        url = it.url ?: rag.url,
                        k = it.k ?: rag.k,
                        timeoutMs = it.timeoutMs ?: rag.timeoutMs,
                        maxBlockChars = it.maxBlockChars ?: rag.maxBlockChars,
                        rerank = it.rerank ?: rag.rerank,
                        threshold = it.threshold ?: rag.threshold,
                        kBefore = it.kBefore ?: rag.kBefore,
                        rewrite = it.rewrite ?: rag.rewrite,
                        rewriteTimeoutMs = it.rewriteTimeoutMs ?: rag.rewriteTimeoutMs
                    )
                } ?: rag
            )
        }

        /**
         * Проверяет настройки компрессии; нарушение — падение старта с внятным
         * сообщением (невалидные значения молча превращают компрессию в no-op
         * или в полную потерю контекста, поэтому предпочтительно упасть рано).
         */
        private fun AppConfig.validate(): AppConfig {
            require(compressionWindowSize >= 1) {
                "compressionWindowSize должен быть не меньше 1 (получено: $compressionWindowSize) — " +
                    "окно последних сообщений не может быть пустым"
            }
            require(compressionMergeEvery >= 1) {
                "compressionMergeEvery должен быть не меньше 1 (получено: $compressionMergeEvery) — " +
                    "порог слияния истории не может быть нулевым"
            }
            require(compressionSummaryMaxTokens >= 50) {
                "compressionSummaryMaxTokens должен быть не меньше 50 (получено: $compressionSummaryMaxTokens) — " +
                    "слишком маленький бюджет для сводки диалога"
            }
            require(contextStrategyRaw == null || ContextStrategy.fromId(contextStrategyRaw) != null) {
                "contextStrategy: неизвестная стратегия '$contextStrategyRaw' " +
                    "(допустимо: ${ContextStrategy.values().joinToString(", ") { it.id }})"
            }
            val seenMcpNames = mutableSetOf<String>()
            mcpServers.forEach { server ->
                require(server.name.isNotBlank()) {
                    "mcp: пустое имя сервера — в разделе mcp каждой записи нужно имя"
                }
                require(seenMcpNames.add(server.name)) {
                    "mcp: дублирующееся имя сервера '${server.name}' — имена в разделе mcp должны быть уникальны"
                }
                require(server.command.isNotBlank()) {
                    "mcp: сервер '${server.name}' — пустая команда: укажите команду запуска MCP-сервера"
                }
            }
            // RAG-раздел валидируется только при непустом url: без адреса RAG выключен,
            // параметры с дефолтами не используются.
            if (rag.url != null) {
                require(rag.url!!.isNotBlank()) {
                    "rag: url должен быть непустым — укажите адрес RAG-сервиса (POST {url})"
                }
                require(rag.k in 1..RagConfig.K_MAX) {
                    "rag: k должен быть в диапазоне 1..${RagConfig.K_MAX} (получено: ${rag.k}) — " +
                        "число извлекаемых чанков"
                }
                require(rag.timeoutMs >= 1000) {
                    "rag: timeoutMs должен быть не меньше 1000 (получено: ${rag.timeoutMs}) — " +
                        "таймаут запроса к RAG-сервису"
                }
                require(rag.maxBlockChars >= 500) {
                    "rag: maxBlockChars должен быть не меньше 500 (получено: ${rag.maxBlockChars}) — " +
                        "максимальный размер инжектируемого блока документов"
                }
                rag.threshold?.let { threshold ->
                    require(threshold > 0.0 && threshold <= 1.0) {
                        "rag: порог threshold должен быть больше 0 и не больше 1 (получено: $threshold) — " +
                            "отсечение нерелевантных результатов на шкале score"
                    }
                }
                rag.kBefore?.let { kBefore ->
                    require(kBefore in 1..RagConfig.K_MAX) {
                        "rag: kBefore должен быть в диапазоне 1..${RagConfig.K_MAX} (получено: $kBefore) — " +
                            "число кандидатов до фильтрации/реранкинга"
                    }
                }
                require(rag.rewriteTimeoutMs >= 1000) {
                    "rag: rewriteTimeoutMs должен быть не меньше 1000 (получено: ${rag.rewriteTimeoutMs}) — " +
                        "таймаут переписывания поискового запроса"
                }
            }
            return this
        }

        private fun AppConfig.withDefaultApiUrl(value: String): AppConfig =
            copy(default = default.copy(apiUrl = value))

        private fun AppConfig.withDefaultApiKey(value: String): AppConfig =
            copy(default = default.copy(apiKey = value))

        private fun AppConfig.withDefaultModel(value: String): AppConfig =
            copy(default = default.copy(model = value))
    }
}

/** Разбор MCP-сервера из конфиг-файла. */
private fun FileMcpServerConfig.toMcpServerConfig(): McpServerConfig = McpServerConfig(
    name = name.trim(),
    command = command.trim(),
    args = args.map { it.trim() },
    env = env.toMap(),
    enabled = enabled,
    requestTimeoutMs = requestTimeoutMs ?: McpServerConfig.DEFAULT_REQUEST_TIMEOUT_MS,
    initializationTimeoutMs = initializationTimeoutMs ?: McpServerConfig.DEFAULT_INITIALIZATION_TIMEOUT_MS
)

/** Разбор провайдера из конфиг-файла. */
private fun FileProvider.toProviderConfig(): ProviderConfig = ProviderConfig(
    id = id.trim().ifBlank { "provider" },
    name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: id.trim().ifBlank { "provider" },
    type = type?.trim()?.takeIf { it.isNotEmpty() } ?: ProviderConfig.TYPE_OPENAI,
    apiUrl = apiUrl.trim(),
    apiKey = apiKey.orEmpty(),
    model = model.orEmpty().trim(),
    models = models.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
    upstream = upstream.orEmpty().trim(),
    supportsTools = supportsTools ?: false
)
