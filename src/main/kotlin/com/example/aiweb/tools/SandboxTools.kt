package com.example.aiweb.tools

import com.example.aiweb.model.SandboxToolConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Разобранный ответ sandbox-сервиса: одна структура на все эндпоинты
 * (POST /run, POST /read и POST /write) — сервер возвращает подмножество полей,
 * неизвестные игнорируются (ignoreUnknownKeys).
 *
 * Формы ответа:
 *  - /run успех: ok=true, exitCode, stdout, stderr, durationMs;
 *  - /read успех: ok=true, content, truncated;
 *  - /write успех: ok=true, path, bytes;
 *  - отказ сервиса (код не выполнился, таймаут, файл не прочитан,
 *    недоступность сервиса или HTTP-ошибка): ok=false, error — человекочитаемый текст.
 */
@Serializable
data class SandboxReply(
    val ok: Boolean = false,
    /** Код завершения процесса (только /run). */
    @SerialName("exitCode") val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    /** Длительность исполнения кода в sandbox, мс (только /run). */
    @SerialName("durationMs") val durationMs: Long? = null,
    /** Содержимое файла (только /read). */
    val content: String? = null,
    /** Признак обрезки содержимого по лимиту сервиса (только /read). */
    val truncated: Boolean = false,
    /** Размер записанного файла в байтах (только /write). */
    val bytes: Long? = null,
    /** Человекочитаемый текст отказа (ok=false). */
    val error: String? = null
)

/**
 * HTTP-клиент sandbox-сервиса инструментов: отправляет JSON-запросы на
 * {url}/run и {url}/read и разбирает ответы в [SandboxReply].
 *
 * Таймаут на стороне оркестратора: timeoutSeconds из конфига (зажим 1..60)
 * плюс запас на сетевые накладные расходы — Ktor HttpTimeout. Любой сбой
 * связи (недоступен, таймаут, HTTP-ошибка, битый JSON) не бросает исключений:
 * возвращается SandboxReply(ok=false, error = «Sandbox недоступен…») — цикл
 * агента никогда не падает из-за песочницы (правило Фазы 3).
 *
 * @param config параметры инструмента из раздела "tools" конфига (url, timeoutSeconds)
 */
open class SandboxClient(private val config: SandboxToolConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Единственный переиспользуемый клиент (lazy): CIO, без автогенерации ошибок
     * (expectSuccess = false — статус проверяем вручную), таймауты = таймаут
     * инструмента + запас на перенос и обработку ответа.
     */
    private val client by lazy {
        val timeoutMs = (config.timeoutSeconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT) + GRACE_SECONDS) * 1000L
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }

    /**
     * Шов для тестов: отправляет [body] на {url}[path] и разбирает ответ.
     * Подменяется фальшивой реализацией в тестах инструментов (без реального HTTP).
     *
     * @param path путь эндпоинта ("/run" или "/read")
     * @param body готовая JSON-строка запроса
     * @return разобранный ответ; ok=false с текстом «Sandbox недоступен…» при
     *         любом сбое связи или не-200 ответе
     */
    open internal suspend fun post(path: String, body: String): SandboxReply {
        val response = try {
            client.post(config.url.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            return SandboxReply(ok = false, error = "Sandbox недоступен: ${e.message ?: e::class.simpleName}")
        }
        if (!response.status.isSuccess()) {
            return SandboxReply(ok = false, error = "Sandbox недоступен: сервер вернул HTTP ${response.status.value}")
        }
        return try {
            json.decodeFromString<SandboxReply>(response.bodyAsText())
        } catch (e: Exception) {
            SandboxReply(ok = false, error = "Sandbox недоступен: не удалось разобрать ответ")
        }
    }

    companion object {
        /** Минимальный таймаут исполнения, сек. */
        const val MIN_TIMEOUT = 1

        /** Максимальный таймаут исполнения, сек. */
        const val MAX_TIMEOUT = 60

        /** Запас на сетевые накладные расходы поверх таймаута исполнения, сек. */
        const val GRACE_SECONDS = 5
    }
}

/**
 * Инструмент run_code: исполняет Python-код в изолированном sandbox-контейнере
 * (без сети, CWD — рабочая папка) через HTTP-сервис песочницы.
 *
 * Аргументы приходят от LLM — это ненадёжный ввод: перед любым HTTP-вызовом
 * валидируются по схеме (код непустой и не длиннее капа, язык — только python).
 * Любая ошибка (валидация, недоступность песочницы, отказ сервиса) возвращается
 * как [ToolResult] с понятным текстом — исключения наружу не пробрасываются.
 *
 * Таймаут исполнения [timeoutSeconds] (зажим 1..60) передаётся в теле запроса —
 * сервис убивает процесс по истечении; HTTP-таймаут клиента выше на запас.
 */
class RunCodeTool(
    private val client: SandboxClient,
    private val timeoutSeconds: Int
) : Tool {

    override val name = NAME

    override val description =
        "Выполняет Python-код в изолированном sandbox-контейнере (без сети, CWD — рабочая папка). " +
            "Возвращает stdout/stderr и код завершения. Примеры: посчитать что-то скриптом, " +
            "распарсить данные, выполнить вычисление, которое не берёт калькулятор. " +
            "Файлы читайте инструментом read_file — песочница видит только рабочую папку."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("code", buildJsonObject {
                put("type", "string")
                put("description", "Исходный код на Python для исполнения, например print(2 + 2)")
                put("maxLength", MAX_CODE_LENGTH)
            })
            put("language", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add(JsonPrimitive(LANGUAGE_PYTHON)) })
                put("description", "Язык кода; сейчас поддерживается только python (по умолчанию)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("code")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val code = (args["code"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        if (code.isNullOrBlank()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"code\" (непустая строка с Python-кодом)")
        }
        if (code.length > MAX_CODE_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: код длиннее $MAX_CODE_LENGTH символов (${code.length})")
        }
        val language = (args["language"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: LANGUAGE_PYTHON
        if (language != LANGUAGE_PYTHON) {
            return ToolResult(false, "Некорректные аргументы: язык '$language' не поддерживается — доступен только python")
        }

        val body = buildJsonObject {
            put("language", language)
            put("code", code)
            put("timeoutSeconds", timeoutSeconds.coerceIn(SandboxClient.MIN_TIMEOUT, SandboxClient.MAX_TIMEOUT))
        }.toString()

        val reply = try {
            client.post(PATH_RUN, body)
        } catch (e: Exception) {
            return ToolResult(false, "Sandbox недоступен: ${e.message ?: e::class.simpleName}")
        }
        if (!reply.ok) {
            return ToolResult(false, reply.error ?: "Sandbox недоступен: сервис вернул отказ без описания")
        }
        return if (reply.exitCode == 0) {
            val content = buildString {
                append(reply.stdout.orEmpty())
                if (!reply.stderr.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(reply.stderr)
                }
            }
            ToolResult(ok = true, content = content)
        } else {
            ToolResult(
                ok = true,
                content = "Процесс завершился с кодом ${reply.exitCode ?: "неизвестен"}\n" +
                    "stdout: ${reply.stdout.orEmpty()}\n" +
                    "stderr: ${reply.stderr.orEmpty()}"
            )
        }
    }

    companion object {
        const val NAME = "run_code"

        /** Язык исполнения, единственный поддерживаемый. */
        const val LANGUAGE_PYTHON = "python"

        /** Кап длины кода, символов. */
        const val MAX_CODE_LENGTH = 65_536

        /** Путь эндпоинта исполнения кода. */
        private const val PATH_RUN = "/run"
    }
}

/**
 * Инструмент read_file: читает файл из рабочей папки через sandbox-контейнер.
 *
 * Защита в глубину (сервис проверяет путь повторно): путь обязателен, не пуст,
 * не длиннее капа, не абсолютный и не содержит «..». Любая ошибка возвращается
 * как [ToolResult] с понятным текстом — исключения наружу не пробрасываются.
 */
class ReadFileTool(private val client: SandboxClient) : Tool {

    override val name = NAME

    override val description =
        "Читает текстовый файл из рабочей папки через sandbox-контейнер и возвращает содержимое " +
            "(при превышении лимита сервиса файл обрезается). Примеры: посмотреть report.txt, " +
            "заглянуть в результат работы run_code. Относительные пути в пределах рабочей папки; " +
            "абсолютные пути и «..» запрещены."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Относительный путь файла в рабочей папке, например report.txt или out/data.csv")
                put("maxLength", MAX_PATH_LENGTH)
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val path = (args["path"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        if (path.isNullOrEmpty()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"path\" (относительный путь файла)")
        }
        if (path.length > MAX_PATH_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: путь длиннее $MAX_PATH_LENGTH символов")
        }
        if (path.startsWith("/")) {
            return ToolResult(false, "Некорректные аргументы: путь должен быть относительным, без \"..\" — '$path'")
        }
        if (path.contains("..")) {
            return ToolResult(false, "Некорректные аргументы: путь не должен содержать \"..\" — '$path'")
        }

        val body = buildJsonObject {
            put("path", path)
        }.toString()

        val reply = try {
            client.post(PATH_READ, body)
        } catch (e: Exception) {
            return ToolResult(false, "Sandbox недоступен: ${e.message ?: e::class.simpleName}")
        }
        if (!reply.ok) {
            return ToolResult(false, reply.error ?: "Sandbox недоступен: сервис вернул отказ без описания")
        }
        return ToolResult(
            ok = true,
            content = reply.content.orEmpty() + if (reply.truncated) "\n…(обрезано)" else ""
        )
    }

    companion object {
        const val NAME = "read_file"

        /** Кап длины пути, символов. */
        const val MAX_PATH_LENGTH = 1024

        /** Путь эндпоинта чтения файла. */
        private const val PATH_READ = "/read"
    }
}

/**
 * Инструмент write_file: записывает файл в рабочую папку через sandbox-контейнер
 * (перезапись разрешена). Так агент доставляет пользователю готовые файлы —
 * например, исходный код игры: пользователь скачивает их по ссылке.
 *
 * Защита в глубину (сервис проверяет путь повторно): путь обязателен, не пуст,
 * не длиннее капа, не абсолютный и не содержит «..»; содержимое обязательно,
 * не пустое и не длиннее капа сервиса (2 МиБ байт, проверяет сервер). Любая
 * ошибка возвращается как [ToolResult] с понятным текстом — исключения наружу
 * не пробрасываются.
 */
class WriteFileTool(private val client: SandboxClient) : Tool {

    override val name = NAME

    override val description =
        "Записывает текстовый файл в рабочую папку через sandbox-контейнер; существующий файл " +
            "перезаписывается. Примеры: сохранить исходный код игры snake.py, положить отчёт " +
            "report.txt для пользователя. Относительные пути в пределах рабочей папки; " +
            "абсолютные пути и «..» запрещены."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Относительный путь файла в рабочей папке, например snake.py или out/report.txt")
                put("maxLength", ReadFileTool.MAX_PATH_LENGTH)
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Полное содержимое файла; существующий файл перезаписывается целиком")
                put("maxLength", MAX_CONTENT_LENGTH)
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("content"))
        })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        val path = (args["path"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        if (path.isNullOrEmpty()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"path\" (относительный путь файла)")
        }
        if (path.length > ReadFileTool.MAX_PATH_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: путь длиннее ${ReadFileTool.MAX_PATH_LENGTH} символов")
        }
        if (path.startsWith("/")) {
            return ToolResult(false, "Некорректные аргументы: путь должен быть относительным, без \"..\" — '$path'")
        }
        if (path.contains("..")) {
            return ToolResult(false, "Некорректные аргументы: путь не должен содержать \"..\" — '$path'")
        }
        val content = (args["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        if (content.isNullOrEmpty()) {
            return ToolResult(false, "Некорректные аргументы: не задан параметр \"content\" (непустая строка с содержимым файла)")
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            return ToolResult(false, "Некорректные аргументы: содержимое длиннее $MAX_CONTENT_LENGTH символов (${content.length})")
        }

        val body = buildJsonObject {
            put("path", path)
            put("content", content)
        }.toString()

        val reply = try {
            client.post(PATH_WRITE, body)
        } catch (e: Exception) {
            return ToolResult(false, "Sandbox недоступен: ${e.message ?: e::class.simpleName}")
        }
        if (!reply.ok) {
            return ToolResult(false, reply.error ?: "Sandbox недоступен: сервис вернул отказ без описания")
        }
        val size = reply.bytes
        return ToolResult(
            ok = true,
            content = if (size != null) "Файл $path записан (${formatKib(size)})" else "Файл $path записан"
        )
    }

    /** Размер в КиБ с одной десятичной и запятой: 12,3 КиБ. */
    private fun formatKib(bytes: Long): String =
        "%.1f".format(bytes / 1024.0).replace('.', ',') + " КиБ"

    companion object {
        const val NAME = "write_file"

        /** Кап длины содержимого, символов (сервис дополнительно капает файл в 2 МиБ байт). */
        const val MAX_CONTENT_LENGTH = 2_000_000

        /** Путь эндпоинта записи файла. */
        private const val PATH_WRITE = "/write"
    }
}
