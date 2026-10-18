package com.example.aiweb.client

import com.example.aiweb.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Тесты разбора ответа OpenAI-совместимого API ([AIClient.parseOpenAIResponse]).
 * Ключевой сценарий — ответ с tool_calls и content=null: до фиксы он падал
 * с ошибкой «API вернул пустой ответ», хотя это валидный запрос вызова инструмента.
 */
class AIClientParsingTest {

    private fun newClient(): AIClient = AIClient(
        ProviderConfig(
            id = "test",
            name = "Тестовый провайдер",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://localhost:1/v1/chat/completions",
            apiKey = "",
            model = "test-model"
        )
    )

    @Test
    fun `ответ только с tool_calls разбирается без ошибки пустого ответа`() {
        val client = newClient()
        try {
            val body = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": { "name": "current_datetime", "arguments": "{}" }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
                }
            """.trimIndent()

            val result = client.parseOpenAIResponse(body, startedAt = 0L)

            assertEquals("", result.reply)
            assertEquals(1, result.toolCalls.size)
            assertEquals("call_1", result.toolCalls.first().id)
            assertEquals("current_datetime", result.toolCalls.first().function?.name)
            assertEquals("{}", result.toolCalls.first().function?.arguments)
            assertEquals("tool_calls", result.finishReason)
            assertEquals(15, result.totalTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun `обычный ответ с текстом разбирается как раньше`() {
        val client = newClient()
        try {
            val body = """
                {
                  "choices": [
                    {
                      "message": { "role": "assistant", "content": "Привет!" },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()

            val result = client.parseOpenAIResponse(body, startedAt = 0L)

            assertEquals("Привет!", result.reply)
            assertEquals(0, result.toolCalls.size)
            assertEquals("stop", result.finishReason)
        } finally {
            client.close()
        }
    }

    @Test
    fun `пустой список choices даёт ошибку пустого ответа`() {
        val client = newClient()
        try {
            val body = """{"choices": []}"""

            val error = assertFailsWith<IllegalStateException> {
                client.parseOpenAIResponse(body, startedAt = 0L)
            }

            assertEquals(
                "API вернул пустой ответ: модель не вернула текст (проверьте лимит токенов и выбранную модель)",
                error.message
            )
        } finally {
            client.close()
        }
    }
}
