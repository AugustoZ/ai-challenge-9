package com.example.aiweb.tools

import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.model.OpenAIMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Инструмент summarize: краткая сводка переданного текста через LLM.
 *
 * Обращение идёт через существующий шов [LLMClient.ask] — клиент инжектится
 * в конструктор (null — суммаризация недоступна). Системный промт просит
 * краткую сводку; опциональное указание instruction уточняет задачу. Любой
 * сбой (клиент не задан, недоступен, пустой ответ) возвращается как
 * [ToolResult] с ok = false и понятным текстом — исключения наружу не
 * пробрасываются (кроме отмены корутины).
 */
class SummarizeTool(
    /** LLM-клиент провайдера; null — суммаризация недоступна (ok = false). */
    private val llm: LLMClient?
) : Tool {

    override val name = NAME

    override val description =
        "Делает краткую сводку переданного текста через языковую модель. Примеры: сжать длинный текст, " +
            "выделить главное, подготовить резюме обсуждения."

    override val parametersJsonSchema: JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "Текст для сводки")
            })
            put("instruction", buildJsonObject {
                put("type", "string")
                put("description", "Уточнение, что выделить в сводке (опционально)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("text")) })
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = parseArgsObject(argsJson) ?: return ToolResult(false, ARGS_NOT_OBJECT_ERROR)
        // Пайплайн резолвит целый плейсхолдер `${steps.X.result}` типизированно: если
        // результат шага — валидный JSON, аргумент приходит объектом, а не строкой
        // (PipelineRunner.resolveString). Терпим это: сериализуем обратно в текст.
        val text = args.toolString("text")
            ?: (args["text"] as? JsonObject)?.toString()
            ?: (args["text"] as? JsonArray)?.toString()
        if (text.isNullOrEmpty()) {
            return ToolResult(false, "Не задан параметр \"text\" (текст для сводки)")
        }
        val instruction = args.toolString("instruction")?.takeIf { it.isNotBlank() }
        val client = llm ?: return ToolResult(false, "Суммаризация недоступна: LLM-клиент не подключён")

        val messages = listOf(
            OpenAIMessage(role = "system", content = systemPrompt(instruction)),
            OpenAIMessage(role = "user", content = text)
        )
        return try {
            val result = client.ask(LLMCall(messages = messages))
            val reply = result.reply.trim()
            if (reply.isEmpty()) {
                ToolResult(false, "Суммаризация не удалась: модель вернула пустой ответ")
            } else {
                ToolResult(true, reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Суммаризация не удалась: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Системный промт суммаризации; уточнение пользователя добавляется в конец. */
    private fun systemPrompt(instruction: String?): String = buildString {
        append("Сделай краткую сводку переданного текста: выдели главное, сохрани ключевые факты и цифры.")
        instruction?.let { append(" Указание: $it") }
    }

    companion object {
        const val NAME = "summarize"
    }
}
