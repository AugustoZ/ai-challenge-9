package com.example.aiweb.config

import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Проверки файлового уровня конфигурации (Фаза 5): dataDir, supportsTools
 * провайдера по умолчанию и дополнительных провайдеров, валидность
 * config.example.json, настройки компрессии истории.
 *
 * Переменные окружения в JVM подменить нельзя (System.getenv неизменяем),
 * поэтому проверяется файловая точка входа AppConfig.fromFile — тот же путь
 * loadFile + applyFile, что fromEnv проходит до наложения env.
 */
class AppConfigTest {

    private val tempDirs = mutableListOf<File>()

    private fun writeConfig(json: String): String {
        val dir = Files.createTempDirectory("aiweb-config-test").toFile()
        tempDirs += dir
        val file = File(dir, "config.json")
        file.writeText(json)
        return file.absolutePath
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `конфиг-файл — dataDir и supportsTools применяются`() {
        val path = writeConfig(
            """
            {
              "dataDir": "/tmp/aiweb-test-data",
              "supportsTools": true,
              "providers": [
                {
                  "id": "opencode",
                  "apiUrl": "https://opencode.example/v1",
                  "supportsTools": true
                }
              ]
            }
            """.trimIndent()
        )

        val config = AppConfig.fromFile(path)

        assertEquals("/tmp/aiweb-test-data", config.dataDir)
        assertTrue(config.default.supportsTools, "supportsTools файла должен примениться к провайдеру по умолчанию")
        val extra = config.extraProviders.single()
        assertEquals("opencode", extra.id)
        assertTrue(extra.supportsTools, "supportsTools дополнительного провайдера должен примениться")
    }

    @Test
    fun `конфиг-файл без новых полей — in-memory и fallback-инструменты`() {
        val path = writeConfig(
            """
            {
              "port": 9090,
              "providers": [
                {
                  "id": "extra",
                  "apiUrl": "https://extra.example/v1"
                }
              ]
            }
            """.trimIndent()
        )

        val config = AppConfig.fromFile(path)

        assertNull(config.dataDir, "без dataDir конфигурация остаётся in-memory")
        assertEquals(9090, config.port)
        assertFalse(config.default.supportsTools, "без supportsTools — fallback: промт-протокол инструментов")
        assertFalse(config.extraProviders.single().supportsTools)
    }

    @Test
    fun `поставляемый пример конфига остаётся валидным для парсера`() {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "config.example.json")
            if (candidate.exists()) {
                val config = AppConfig.fromFile(candidate.absolutePath)
                assertEquals(8080, config.port)
                assertEquals("/app/data", config.dataDir)
                assertFalse(config.default.supportsTools)
                assertEquals(1, config.extraProviders.size)
                assertEquals("opencode", config.extraProviders.single().id)
                return
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("config.example.json не найден относительно рабочей директории теста")
    }

    // --- Компрессия истории: конфиг ---

    @Test
    fun `дефолты компрессии — выключено, окно 5, порог 5, бюджет 400`() {
        val config = AppConfig.fromFile("несуществующий-конфиг.json")

        assertFalse(config.compressionEnabled, "компрессия выключена по умолчанию")
        assertEquals(5, config.compressionWindowSize)
        assertEquals(5, config.compressionMergeEvery)
        assertEquals(400, config.compressionSummaryMaxTokens)
    }

    @Test
    fun `старый JSON без полей компрессии парсится с дефолтами`() {
        val path = writeConfig(
            """
            {
              "port": 8080,
              "apiUrl": "https://old.example/v1",
              "model": "old-model"
            }
            """.trimIndent()
        )

        val config = AppConfig.fromFile(path)

        assertFalse(config.compressionEnabled, "отсутствие поля — компрессия выключена")
        assertEquals(5, config.compressionWindowSize)
        assertEquals(5, config.compressionMergeEvery)
        assertEquals(400, config.compressionSummaryMaxTokens)
        assertEquals("old-model", config.default.model, "старые поля читаются как раньше")
    }

    @Test
    fun `конфиг-файл — настройки компрессии применяются`() {
        val path = writeConfig(
            """
            {
              "compressionEnabled": true,
              "compressionWindowSize": 8,
              "compressionMergeEvery": 6,
              "compressionSummaryMaxTokens": 300
            }
            """.trimIndent()
        )

        val config = AppConfig.fromFile(path)

        assertTrue(config.compressionEnabled)
        assertEquals(8, config.compressionWindowSize)
        assertEquals(6, config.compressionMergeEvery)
        assertEquals(300, config.compressionSummaryMaxTokens)
    }

    @Test
    fun `compressionWindowSize меньше 1 — падение старта с сообщением про окно`() {
        val path = writeConfig("""{"compressionWindowSize": 0}""")

        val error = assertFailsWith<IllegalArgumentException> { AppConfig.fromFile(path) }
        assertTrue(
            "окно" in error.message!!,
            "сообщение должно объяснять проблему с окном: ${error.message}"
        )
    }

    @Test
    fun `compressionMergeEvery меньше 1 — падение старта`() {
        val path = writeConfig("""{"compressionMergeEvery": 0}""")

        val error = assertFailsWith<IllegalArgumentException> { AppConfig.fromFile(path) }
        assertTrue(
            "порог" in error.message!!,
            "сообщение должно объяснять проблему с порогом: ${error.message}"
        )
    }

    @Test
    fun `compressionSummaryMaxTokens меньше 50 — падение старта`() {
        val path = writeConfig("""{"compressionSummaryMaxTokens": 49}""")

        val error = assertFailsWith<IllegalArgumentException> { AppConfig.fromFile(path) }
        assertTrue(
            "сводки" in error.message!!,
            "сообщение должно объяснять проблему с бюджетом сводки: ${error.message}"
        )
    }

    @Test
    fun `валидные граничные значения компрессии принимаются`() {
        val path = writeConfig(
            """
            {
              "compressionEnabled": true,
              "compressionWindowSize": 1,
              "compressionMergeEvery": 1,
              "compressionSummaryMaxTokens": 50
            }
            """.trimIndent()
        )

        val config = AppConfig.fromFile(path)

        assertEquals(1, config.compressionWindowSize)
        assertEquals(1, config.compressionMergeEvery)
        assertEquals(50, config.compressionSummaryMaxTokens)
    }

    // --- Компрессия истории: DTO-контракт запроса/ответа ---

    /** Json-инстанс повторяет серверную конфигурацию ContentNegotiation (encodeDefaults=false). */
    private val serverJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `ChatRequest без compressionEnabled декодируется с null`() {
        val request = serverJson.decodeFromString<ChatRequest>(
            """{"message":"привет","sessionId":"s1","totalTokens":1}"""
        )

        assertNull(request.compressionEnabled, "отсутствие поля — трактуется как «взять из конфига»")
        assertEquals("привет", request.message)
    }

    @Test
    fun `ChatRequest с compressionEnabled декодирует значение`() {
        val request = serverJson.decodeFromString<ChatRequest>(
            """{"message":"привет","compressionEnabled":true}"""
        )

        assertEquals(true, request.compressionEnabled)
    }

    @Test
    fun `ChatResponse с дефолтами содержит ключи summaryTokens и summaryApplied`() {
        val text = serverJson.encodeToString(
            ChatResponse(reply = "ответ", totalTokens = 42)
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertTrue(
            root.containsKey("summaryTokens"),
            "ключ summaryTokens обязан присутствовать (encodeDefaults=false сервера): $text"
        )
        assertTrue(root["summaryTokens"].toString() == "null", "summaryTokens null по умолчанию: $text")
        assertTrue(root.containsKey("summaryApplied"), "ключ summaryApplied обязан присутствовать: $text")
        assertEquals("false", root["summaryApplied"]?.jsonPrimitive?.content)
        assertEquals("42", root["totalTokens"]?.jsonPrimitive?.content, "старые поля не изменились")
    }

    @Test
    fun `ChatResponse с метриками компрессии сериализует значения`() {
        val text = serverJson.encodeToString(
            ChatResponse(reply = "ответ", totalTokens = 42, summaryTokens = 17, summaryApplied = true)
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals("17", root["summaryTokens"]?.jsonPrimitive?.content)
        assertEquals("true", root["summaryApplied"]?.jsonPrimitive?.content)
    }
}
