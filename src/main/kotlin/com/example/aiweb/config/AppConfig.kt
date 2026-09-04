package com.example.aiweb.config

import com.example.aiweb.model.FileConfig
import com.example.aiweb.model.FileProvider
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
    val upstream: String = ""
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
 *  1. Переменные окружения (PORT, AI_API_URL, AI_API_KEY, AI_MODEL, CONFIG_FILE)
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
    val allowedModels: List<String> = emptyList()
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
            )
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
            // 1. Начинаем с значений по умолчанию
            var config = defaultConfig

            // 2. Применяем значения из конфиг-файла (если он существует)
            val configPath = System.getenv("CONFIG_FILE") ?: DEFAULT_CONFIG_FILE
            val fileConfig = loadFile(configPath)
            if (fileConfig != null) {
                config = config.applyFile(fileConfig)
            }

            // 3. Переменные окружения имеют высший приоритет
            System.getenv("PORT")?.toIntOrNull()?.let { config = config.copy(port = it) }
            System.getenv("AI_API_URL")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultApiUrl(it) }
            System.getenv("AI_API_KEY")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultApiKey(it) }
            System.getenv("AI_MODEL")?.takeIf { it.isNotBlank() }
                ?.let { config = config.withDefaultModel(it) }

            return config
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
                model = fc.model ?: default.model
            )
            val extras = fc.providers.orEmpty()
                .map { it.toProviderConfig() }
                .filter { it.apiUrl.isNotBlank() && it.id.isNotBlank() }
            return copy(
                port = fc.port ?: port,
                default = defaultProvider,
                extraProviders = extras,
                allowedModels = fc.allowedModels.orEmpty().filter { it.isNotBlank() }
            )
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
    upstream = upstream.orEmpty().trim()
)
