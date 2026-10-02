package com.example.aiweb.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RAG-секция конфиг-файла (RAG-режим, задача 3):
 *  - регрессия: старый конфиг БЕЗ секции rag остаётся валидным, RAG выключен,
 *    параметры — значения по умолчанию;
 *  - частичная секция: недостающие значения дополняются дефолтами;
 *  - невалидные значения (url пуст, k вне 1..20, таймаут < 1000, бюджет < 500)
 *    — падение старта с русским сообщением.
 *
 * Файловая точка входа AppConfig.fromFile: тот же путь loadFile + applyFile,
 * что fromEnv до наложения env (System.getenv в JVM подменить нельзя).
 */
class AppConfigRagTest {

    private val tempDirs = mutableListOf<File>()

    private fun writeConfig(json: String): String {
        val dir = Files.createTempDirectory("aiweb-rag-config-test").toFile()
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
    fun `старый конфиг без секции rag остаётся валидным и дефолты применяются`() {
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

        assertNull(config.rag.url, "без секции rag — RAG выключен (url == null)")
        assertEquals(5, config.rag.k, "k — значение по умолчанию")
        assertEquals(10_000L, config.rag.timeoutMs, "timeoutMs — значение по умолчанию")
        assertEquals(6_000, config.rag.maxBlockChars, "maxBlockChars — значение по умолчанию")
        assertEquals(8080, config.port, "старые поля читаются как раньше")
        assertEquals("old-model", config.default.model, "старые поля читаются как раньше")
    }

    @Test
    fun `частичная секция rag дополняется дефолтами для отсутствующих значений`() {
        val path = writeConfig(
            """
            {
              "rag": {
                "url": "http://localhost:18700/search",
                "k": 3
              }
            }
            """.trimIndent()
        )

        val rag = AppConfig.fromFile(path).rag

        assertEquals("http://localhost:18700/search", rag.url, "явный url применяется из файла")
        assertEquals(3, rag.k, "явный k применяется из файла")
        assertEquals(10_000L, rag.timeoutMs, "отсутствующий timeoutMs — дефолт")
        assertEquals(6_000, rag.maxBlockChars, "отсутствующий maxBlockChars — дефолт")
    }

    @Test
    fun `невалидные значения rag — падение старта с русским сообщением`() {
        val invalidConfigs = mapOf(
            "пустой url" to """{"rag": {"url": ""}}""",
            "k = 0" to """{"rag": {"url": "http://localhost:18700/search", "k": 0}}""",
            "k = 21" to """{"rag": {"url": "http://localhost:18700/search", "k": 21}}""",
            "timeoutMs = 999" to """{"rag": {"url": "http://localhost:18700/search", "timeoutMs": 999}}""",
            "maxBlockChars = 499" to """{"rag": {"url": "http://localhost:18700/search", "maxBlockChars": 499}}"""
        )

        invalidConfigs.forEach { (label, json) ->
            val path = writeConfig(json)
            val error = assertFailsWith<IllegalArgumentException> { AppConfig.fromFile(path) }
            assertTrue(
                "rag" in error.message!!,
                "$label: сообщение должно называть раздел rag: ${error.message}"
            )
            assertTrue(
                "должен" in error.message!!,
                "$label: сообщение должно быть на русском и объяснять ограничение: ${error.message}"
            )
        }
    }

    @Test
    fun `валидные граничные значения rag принимаются`() {
        val path = writeConfig(
            """
            {
              "rag": {
                "url": "http://localhost:18700/search",
                "k": 20,
                "timeoutMs": 1000,
                "maxBlockChars": 500
              }
            }
            """.trimIndent()
        )

        val rag = AppConfig.fromFile(path).rag

        assertEquals(20, rag.k)
        assertEquals(1000L, rag.timeoutMs)
        assertEquals(500, rag.maxBlockChars)
    }
}
