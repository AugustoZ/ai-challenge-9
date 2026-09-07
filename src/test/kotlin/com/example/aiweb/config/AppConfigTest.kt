package com.example.aiweb.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Проверки файлового уровня конфигурации (Фаза 5): dataDir, supportsTools
 * провайдера по умолчанию и дополнительных провайдеров, валидность
 * config.example.json.
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
}
