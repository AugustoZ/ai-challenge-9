package com.example.aiweb.tools

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenAIToolCallFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Способ доставки инструментов модели. Две реализации за одним интерфейсом:
 *  - [NativeToolTransport] — нативный function calling провайдера (supportsTools = true);
 *  - [PromptToolTransport] — промт-протокол для провайдеров без поддержки tools.
 * Выбор реализации — задача агентного цикла (Фаза 3), здесь только транспорт.
 */
interface ToolTransport {
    /** Проводит одно обращение к модели с учётом инструментов реестра. */
    suspend fun ask(call: LLMCall, client: LLMClient, registry: ToolRegistry): ChatResult
}

/**
 * Нативный транспорт: описания инструментов передаются в поле tools запроса
 * (OpenAI function calling); запрошенные вызовы возвращаются в ChatResult.toolCalls.
 */
class NativeToolTransport : ToolTransport {
    override suspend fun ask(call: LLMCall, client: LLMClient, registry: ToolRegistry): ChatResult =
        client.ask(call.copy(tools = registry.specs()))
}

/**
 * Fallback-транспорт для провайдеров без поддержки function calling:
 * инструменты описываются в system-промте, модель запрашивает вызов блоком
 * ```json {"tool": ..., "arguments": {...}}```, который разбирается в
 * OpenAIToolCall. Блок не найден, JSON некорректен или инструмент неизвестен —
 * весь ответ модели трактуется как финальный текст (без вызовов).
 */
class PromptToolTransport : ToolTransport {

    override suspend fun ask(call: LLMCall, client: LLMClient, registry: ToolRegistry): ChatResult {
        val result = client.ask(
            call.copy(
                messages = call.messages + OpenAIMessage(role = "system", content = buildToolPrompt(registry)),
                tools = null
            )
        )
        val parsed = parseToolCall(result.reply, registry) ?: return result
        return ChatResult(
            // Вызов инструмента получен — текст ответа (описание вызова) модели не показывается
            reply = "",
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            totalTokens = result.totalTokens,
            elapsedMs = result.elapsedMs,
            toolCalls = listOf(
                OpenAIToolCall(
                    id = "prompt",
                    type = "function",
                    function = OpenAIToolCallFunction(name = parsed.first, arguments = parsed.second)
                )
            ),
            finishReason = result.finishReason
        )
    }

    /**
     * Промт-протокол: перечисляет инструменты (имя, описание, схема параметров)
     * и правила ответа. Модель либо запрашивает инструмент одним блоком ```json,
     * либо отвечает обычным текстом, если инструмент не нужен.
     */
    private fun buildToolPrompt(registry: ToolRegistry): String = buildString {
        append("Ты ассистент с доступом к инструментам. Доступные инструменты:\n")
        registry.specs().forEachIndexed { index, spec ->
            val function = spec.function
            append("\n${index + 1}. ${function.name} — ${function.description}\n")
            append("   Параметры (JSON-Schema): ${function.parameters ?: "{}"}\n")
        }
        append("\nПравила:\n")
        append("- Если для ответа нужен вызов инструмента — ответь ТОЛЬКО одним блоком кода ```json вида:\n")
        append("```json\n{\"tool\": \"имя_инструмента\", \"arguments\": {\"параметр\": \"значение\"}}\n```\n")
        append("- В таком ответе не должно быть никакого другого текста до или после блока.\n")
        append("- Если инструмент не нужен — ответь обычным текстом, без блока ```json.\n")
    }

    /**
     * Разбирает первый блок ```json в ответе модели.
     *
     * @return пару (имя инструмента, аргументы — компактный JSON-объект строкой)
     *         или null, если блока нет, JSON некорректен, arguments не объект
     *         или имя инструмента неизвестно реестру.
     */
    private fun parseToolCall(reply: String, registry: ToolRegistry): Pair<String, String>? {
        if (reply.isBlank()) return null
        val block = FENCED_JSON.find(reply)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (block.isEmpty()) return null
        val call = try {
            Json.parseToJsonElement(block) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val name = (call["tool"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
        val arguments = call["arguments"] as? JsonObject ?: return null
        if (name.isEmpty() || registry.get(name) == null) return null
        return name to arguments.toString()
    }

    companion object {
        /** Блок кода с пометкой json: ```json ... ``` (регистр метки не важен). */
        private val FENCED_JSON = Regex("```json\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    }
}
