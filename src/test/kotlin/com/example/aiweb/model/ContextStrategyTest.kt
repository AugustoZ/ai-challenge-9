package com.example.aiweb.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Стратегия формирования контекста: разбор id (fromId) и приоритет
 * разрешения (resolve): явное поле запроса > legacy-флаг compressionEnabled
 * > значение из конфига. Плюс DTO-контракт: echo-поля контекста обязаны
 * присутствовать в JSON-ответе при дефолтах (encodeDefaults=false сервера).
 */
class ContextStrategyTest {

    // --- fromId ---

    @Test
    fun `fromId — каждый валидный id разобран`() {
        assertEquals(ContextStrategy.OFF, ContextStrategy.fromId("off"))
        assertEquals(ContextStrategy.WINDOW, ContextStrategy.fromId("window"))
        assertEquals(ContextStrategy.SUMMARY, ContextStrategy.fromId("summary"))
        assertEquals(ContextStrategy.FACTS, ContextStrategy.fromId("facts"))
        assertEquals(ContextStrategy.BRANCHING, ContextStrategy.fromId("branching"))
    }

    @Test
    fun `fromId — регистр и пробелы не важны`() {
        assertEquals(ContextStrategy.WINDOW, ContextStrategy.fromId("WINDOW"))
        assertEquals(ContextStrategy.SUMMARY, ContextStrategy.fromId(" summary "))
    }

    @Test
    fun `fromId — неизвестный id даёт null`() {
        assertNull(ContextStrategy.fromId("sliding"))
        assertNull(ContextStrategy.fromId("bogus"))
        assertNull(ContextStrategy.fromId(""))
    }

    // --- resolve: приоритет явное поле > legacy-флаг > конфиг ---

    @Test
    fun `resolve — валидный явный id выигрывает над legacy-флагом`() {
        assertEquals(ContextStrategy.WINDOW, ContextStrategy.resolve("window", null, ContextStrategy.OFF))
        assertEquals(ContextStrategy.BRANCHING, ContextStrategy.resolve("branching", true, ContextStrategy.OFF))
        assertEquals(ContextStrategy.FACTS, ContextStrategy.resolve("facts", false, ContextStrategy.WINDOW))
    }

    @Test
    fun `resolve — невалидный явный id даёт null (вызывающий отвечает 400)`() {

        assertNull(ContextStrategy.resolve("bogus", true, ContextStrategy.OFF))
        assertNull(ContextStrategy.resolve("sliding", null, ContextStrategy.WINDOW))
    }

    @Test
    fun `resolve — без явной стратегии legacy-флаг решает`() {
        assertEquals(ContextStrategy.SUMMARY, ContextStrategy.resolve(null, true, ContextStrategy.OFF))
        assertEquals(ContextStrategy.OFF, ContextStrategy.resolve(null, false, ContextStrategy.WINDOW))
    }

    @Test
    fun `resolve — без явной стратегии и legacy-флага действует конфиг`() {
        assertEquals(ContextStrategy.WINDOW, ContextStrategy.resolve(null, null, ContextStrategy.WINDOW))
        assertEquals(ContextStrategy.OFF, ContextStrategy.resolve(null, null, ContextStrategy.OFF))
    }

    // --- DTO-контракт: echo-поля и запрос ---

    /** Json-инстанс повторяет серверную конфигурацию ContentNegotiation (encodeDefaults=false). */
    private val serverJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `ChatRequest без новых полей декодируется с null`() {
        val request = serverJson.decodeFromString<ChatRequest>(
            """{"message":"привет"}"""
        )

        assertNull(request.contextStrategy, "отсутствие поля — разрешение по приоритету")
        assertNull(request.branchId, "отсутствие поля — без ветки")
    }

    @Test
    fun `ChatRequest с новыми полями декодирует значения`() {
        val request = serverJson.decodeFromString<ChatRequest>(
            """{"message":"привет","contextStrategy":"facts","branchId":"b1"}"""
        )

        assertEquals("facts", request.contextStrategy)
        assertEquals("b1", request.branchId)
    }

    @Test
    fun `ChatResponse с дефолтами содержит ключи contextStrategy и branchId`() {
        val text = serverJson.encodeToString(
            ChatResponse(reply = "ответ", totalTokens = 42)
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertTrue(
            text.contains("\"contextStrategy\":null"),
            "ключ contextStrategy обязан присутствовать всегда (encodeDefaults=false сервера): $text"
        )
        assertTrue(
            text.contains("\"branchId\":null"),
            "ключ branchId обязан присутствовать всегда (encodeDefaults=false сервера): $text"
        )
        assertTrue(root.containsKey("contextStrategy"), "ключ contextStrategy в JSON-ответе: $text")
        assertEquals("null", root["contextStrategy"].toString(), "contextStrategy null по умолчанию: $text")
        assertTrue(root.containsKey("branchId"), "ключ branchId в JSON-ответе: $text")
        assertEquals("null", root["branchId"].toString(), "branchId null по умолчанию: $text")
        assertEquals("42", root["totalTokens"]?.toString() ?: "", "старые поля не изменились")
    }

    @Test
    fun `ChatResponse с разрешённой стратегией эхоит её id`() {
        val text = serverJson.encodeToString(
            ChatResponse(reply = "ответ", contextStrategy = "window", branchId = "b2")
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals("window", root["contextStrategy"]?.toString()?.trim('"'))
        assertEquals("b2", root["branchId"]?.toString()?.trim('"'))
    }
}
