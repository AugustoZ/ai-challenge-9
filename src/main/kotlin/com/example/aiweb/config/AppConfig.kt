package com.example.aiweb.config

import com.example.aiweb.model.FileConfig
import com.example.aiweb.model.FileProvider
import com.example.aiweb.model.FileToolsConfig
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
    val compressionSummaryMaxTokens: Int = 400
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
                compressionEnabled = fc.compressionEnabled ?: compressionEnabled,
                compressionWindowSize = fc.compressionWindowSize ?: compressionWindowSize,
                compressionMergeEvery = fc.compressionMergeEvery ?: compressionMergeEvery,
                compressionSummaryMaxTokens = fc.compressionSummaryMaxTokens ?: compressionSummaryMaxTokens
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
