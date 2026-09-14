package com.example.aiweb.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIRequest
import com.example.aiweb.model.OpenAIFunctionSpec
import com.example.aiweb.model.OpenAIToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты сериализации tool-полей OpenAIRequest.
 * Json-инстанс повторяет конфигурацию AIClient (encodeDefaults = true, без изменений):
 * поэтому проверяется фактический вывод — ключ "tools" присутствует и при отсутствии
 * инструментов (в виде null), а не только при заданных.
 */
class OpenAIRequestSerializationTest {

    /** Точная копия Json-конфигурации AIClient — конфиг менять запрещено. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `запрос с tools сериализует вложенную функцию с параметрами`() {
        val parameters = buildJsonObject {
            put("type", "object")
            put("expression", "string")
        }
        val request = OpenAIRequest(
            model = "test-model",
            messages = listOf(OpenAIMessage(role = "user", content = "привет")),
            tools = listOf(
                OpenAIToolSpec(
                    function = OpenAIFunctionSpec(
                        name = "calculator",
                        description = "Вычисление выражений",
                        parameters = parameters
                    )
                )
            )
        )

        val text = json.encodeToString(request)
        val root = Json.parseToJsonElement(text).jsonObject
        val tools = root["tools"]?.jsonArray
        assertTrue(tools != null && tools.size == 1, "JSON должен содержать непустой массив tools: $text")

        val function = tools.first().jsonObject["function"]?.jsonObject
        assertTrue(function != null, "в описании инструмента должна быть функция: $text")
        assertEquals("calculator", function["name"]?.jsonPrimitive?.content)
        assertTrue(function.containsKey("parameters"), "JSON должен содержать function.parameters: $text")

        // Обратная проверка: тело читается обратно без потерь
        val decoded = json.decodeFromString<OpenAIRequest>(text)
        assertEquals(request, decoded)
    }

    @Test
    fun `запрос без tools сериализуется без передачи списка инструментов`() {
        val request = OpenAIRequest(
            model = "test-model",
            messages = listOf(OpenAIMessage(role = "user", content = "привет"))
        )

        val text = json.encodeToString(request)
        val root = Json.parseToJsonElement(text).jsonObject

        // При encodeDefaults = true ключ присутствует, но значение — null:
        // серверу фактически не передаётся список инструментов
        assertTrue(root.containsKey("tools"), "JSON должен содержать ключ tools: $text")
        assertTrue(root["tools"] is JsonNull, "tools при отсутствии списка должен быть null: $text")
        assertEquals(request, json.decodeFromString<OpenAIRequest>(text))
    }

    @Test
    fun `показания хода usage не уезжают в API-запрос`() {
        // usage=null (после среза в AIClient при сборке запроса) не кодируется вовсе:
        // EncodeDefault NEVER, в отличие от tools без аннотации (явный null в JSON)
        val request = OpenAIRequest(
            model = "test-model",
            messages = listOf(OpenAIMessage(role = "user", content = "привет"))
        )

        val text = json.encodeToString(request)
        val messages = Json.parseToJsonElement(text).jsonObject["messages"]!!.jsonArray
        val first = messages.first().jsonObject

        assertFalse(first.containsKey("usage"), "ключ usage не должен попадать в запрос к API: $text")
    }
}
