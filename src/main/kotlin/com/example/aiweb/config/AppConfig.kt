package com.example.aiweb.config

import com.example.aiweb.model.FileConfig
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Конфигурация приложения.
 *
 * Значения подставляются по приоритету (от высшего к низшему):
 *  1. Переменные окружения (PORT, AI_API_URL, AI_API_KEY, AI_MODEL, CONFIG_FILE)
 *  2. Конфиг-файл в формате JSON (путь задаётся через CONFIG_FILE)
 *  3. Значения по умолчанию
 */
data class AppConfig(
    val port: Int,
    val apiUrl: String,
    val apiKey: String,
    val model: String
) {
    companion object {
        private const val DEFAULT_CONFIG_FILE = "config.json"
        private val defaultConfig = AppConfig(
            port = 8080,
            apiUrl = "https://api.openai.com/v1/chat/completions",
            apiKey = "ВАШ_API_КЛЮЧ",
            model = "gpt-3.5-turbo"
        )

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
            System.getenv("AI_API_URL")?.takeIf { it.isNotBlank() }?.let { config = config.copy(apiUrl = it) }
            System.getenv("AI_API_KEY")?.takeIf { it.isNotBlank() }?.let { config = config.copy(apiKey = it) }
            System.getenv("AI_MODEL")?.takeIf { it.isNotBlank() }?.let { config = config.copy(model = it) }

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
            return copy(
                port = fc.port ?: port,
                apiUrl = fc.apiUrl ?: apiUrl,
                apiKey = fc.apiKey ?: apiKey,
                model = fc.model ?: model
            )
        }
    }
}
