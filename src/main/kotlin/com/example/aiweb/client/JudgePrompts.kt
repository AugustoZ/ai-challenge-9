package com.example.aiweb.client

import com.example.aiweb.model.JudgeReport
import com.example.aiweb.model.OpenAIMessage
import kotlinx.serialization.json.Json

/**
 * Режим «Судья»: готовый ответ модели перепроверяется отдельным вызовом LLM.
 * Судья оценивает корректность фактов, качество ответа, скорость (по приборным
 * замерам) и ресурсоёмкость (потраченные токены против пользы ответа) и возвращает
 * строгий JSON-отчёт, который показывается пользователю под ответом.
 */
object JudgePrompts {

    private val SYSTEM = """
        Ты — строгий судья приёмки ответов ассистента. Тебе дают вопрос пользователя, ответ модели
        и приборные замеры (время ответа, потраченные токены, скорость генерации).
        Проверь ответ по четырём критериям и выставь по каждому балл от 1 до 10 с кратким обоснованием:
        1. facts — корректность фактов: фактические ошибки, выдуманные данные, внутренние противоречия.
           Если факт проверить нечем — так и скажи в комментарии и не завышай балл.
        2. quality — качество: полнота, соответствие вопросу, ясность, структура, соблюдение формата.
        3. speed — скорость: оцени измеренное время и токены/с относительно объёма и сложности ответа.
        4. efficiency — ресурсоёмкость: оправданы ли потраченные токены; была ли «вода» или обрыв темы.
        Затем выстави overall — итоговый балл (округлённое среднее) и verdict — краткий вердикт
        одним предложением. Пиши по-русски.
        Ответь СТРОГО одним JSON-объектом без markdown-разметки и пояснений, по схеме:
        {"facts":{"score":8,"comment":"..."},"quality":{"score":7,"comment":"..."},"speed":{"score":9,"comment":"..."},"efficiency":{"score":6,"comment":"..."},"overall":8,"verdict":"..."}
    """.trimIndent()

    /**
     * Собирает контекст для судьи: вопрос, ответ модели и приборные замеры.
     */
    fun buildMessages(
        question: String,
        answer: String,
        elapsedMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?
    ): List<OpenAIMessage> {
        val metrics = buildString {
            append("- полное время ответа: $elapsedMs мс")
            promptTokens?.let { append("\n- токены запроса: $it") }
            completionTokens?.let { append("\n- токены ответа: $it") }
            totalTokens?.let { append("\n- всего токенов: $it") }
            if (completionTokens != null && completionTokens > 0 && elapsedMs > 0) {
                val rate = completionTokens / (elapsedMs / 1000.0)
                append("\n- средняя скорость генерации: ${"%.1f".format(rate)} токенов/с")
            }
        }

        val user = buildString {
            append("Вопрос пользователя:\n")
            append(question)
            append("\n\nОтвет модели:\n")
            append(answer)
            append("\n\nПриборные замеры:\n")
            append(metrics)
            append("\n\nПроверь ответ и верни JSON-заключение.")
        }

        return listOf(
            OpenAIMessage(role = "system", content = SYSTEM),
            OpenAIMessage(role = "user", content = user)
        )
    }

    /**
     * Разбирает ответ судьи в JudgeReport. Судья может обернуть JSON в блок кода
     * или добавить пояснения — ищем первый сбалансированный JSON-объект в тексте.
     */
    fun parseReport(reply: String): JudgeReport {
        val jsonText = extractJsonObject(reply)
            ?: throw IllegalStateException("Судья не вернул JSON-объект")

        return try {
            json.decodeFromString<JudgeReport>(jsonText)
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось разобрать JSON заключения: ${e.message}")
        }
    }

    /** Первый сбалансированный JSON-объект в тексте (с учётом строк и экранирования). */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
