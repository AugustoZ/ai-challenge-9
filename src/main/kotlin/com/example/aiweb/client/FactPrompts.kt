package com.example.aiweb.client

import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Промпты стратегии «Факты»: отдельный внечатовой вызов LLM извлекает и обновляет
 * KV-факты диалога (цели, ограничения, предпочтения, решения, договорённости).
 * Паттерн — как у [SummaryPrompts]: system-инструкция на русском + собранный
 * user-контекст; парсер — как у [JudgePrompts] (локальный Json, поиск JSON в тексте).
 */
object FactPrompts {

    /**
     * Фраза, ОБЯЗАТЕЛЬНО присутствующая в system-промпте экстракции фактов:
     * mock-LLM (задача 5) по ней распознаёт запрос на извлечение фактов
     * и возвращает детерминированный KV-JSON (по образцу маркера «Сводка ранее»).
     */
    const val FACTS_MARKER = "Извлечение фактов"

    /** Максимум фактов в памяти диалога (и в промпте, и в разобранном ответе). */
    const val MAX_FACTS = 10

    /** Максимальная длина значения факта (инструкция модели, в парсере не проверяется). */
    const val MAX_VALUE_LENGTH = 200

    private val SYSTEM = """
        Ты — редактор памяти диалога.
        Извлечение фактов — твоя единственная задача: по текущим фактам и новому сообщению
        пользователя составь обновлённый список KV-фактов диалога: цели, ограничения,
        предпочтения, решения, договорённости.
        Прежние факты, всё ещё актуальные, сохраняй как есть; устаревшие и противоречащие
        новым данным — убирай; новые сведения — добавляй как отдельные факты.
        Значение факта — не длиннее %d символов. Максимум %d фактов.
        Не выдумывай ничего, чего нет в переданных данных.
        Ответь СТРОГО одним JSON-массивом без markdown-разметки и пояснений, по схеме:
        [{"key":"...","value":"..."}]
    """.trimIndent().format(MAX_VALUE_LENGTH, MAX_FACTS)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Запрос к модели: system-инструкция с маркером [FACTS_MARKER] +
     * user-контекст «Текущие факты (JSON): …\nНовое сообщение пользователя: …».
     */
    fun buildMessages(currentFacts: List<Fact>, userMessage: String): List<OpenAIMessage> {
        val user = buildString {
            append("Текущие факты (JSON): ")
            append(json.encodeToString(currentFacts))
            append('\n')
            append("Новое сообщение пользователя: ")
            append(userMessage)
        }
        return listOf(
            OpenAIMessage(role = "system", content = SYSTEM),
            OpenAIMessage(role = "user", content = user)
        )
    }

    /**
     * Разбирает ответ модели в список фактов. Модель может обернуть массив в блок кода
     * или добавить пояснения — ищем первый сбалансированный JSON-массив в тексте.
     * Защищённый парсер: ЛЮБОЙ сбой (нет массива, битый JSON, не факты) → null,
     * исключение наружу не выходит — вызывающий сохраняет прежние факты.
     * Массив обрезается до [MAX_FACTS].
     */
    fun parseFacts(reply: String): List<Fact>? {
        return runCatching {
            val jsonText = extractJsonArray(reply) ?: error("JSON-массив не найден")
            json.decodeFromString<List<Fact>>(jsonText).take(MAX_FACTS)
        }.getOrNull()
    }

    /** Первый сбалансированный JSON-массив в тексте (с учётом строк и экранирования). */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
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
                !inString && ch == '[' -> depth++
                !inString && ch == ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
