package com.example.aiweb.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Парсер/стриппер блока состояния задачи в ответе модели: fenced-блок
 * ```task-state с JSON-объектом
 * `{"stage":"planning","step":"...","expected_action":"..."}`.
 *
 * Паттерн — как у JudgePrompts (краткие строки): локальный lenient Json,
 * защищённый парсинг — ЛЮБОЙ сбой (нет блока, битый JSON, неизвестный
 * stage) → null, исключение наружу не выходит.
 */
object TaskStateJson {

    private const val MARKER = "```task-state"

    private const val FENCE = "```"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Схема JSON-блока; неизвестные поля игнорируются. */
    @Serializable
    private data class TaskStateDto(
        val stage: String? = null,
        val step: String? = null,
        val expected_action: String? = null
    )

    /**
     * Разбирает ПОСЛЕДНИЙ блок task-state в тексте (несколько блоков —
     * последний выигрывает) в предложенное состояние.
     *
     * @param text текст финального ответа модели.
     * @param current текущее состояние задачи: отсутствующие в блоке
     *        step/expected_action наследуются от него; без текущего —
     *        пустые строки.
     * @return предложенное состояние, или null — если блока нет, JSON
     *         битый или stage отсутствует/неизвестен.
     */
    fun parse(text: String, current: TaskState?): TaskState? {
        val block = lastBlock(text) ?: return null
        val dto = runCatching {
            json.decodeFromString<TaskStateDto>(block)
        }.getOrNull() ?: return null

        val stage = dto.stage?.trim()?.uppercase()
            ?.let { name -> TaskStage.values().firstOrNull { it.name == name } }
            ?: return null

        return TaskState(
            stage = stage,
            step = dto.step ?: current?.step ?: "",
            expectedAction = dto.expected_action ?: current?.expectedAction ?: ""
        )
    }

    /**
     * Удаляет ВСЕ блоки task-state из текста, сохраняя остальной текст
     * дословно — история диалога и судья блок не видят.
     */
    fun strip(text: String): String {
        var result = text
        var idx = result.indexOf(MARKER)
        while (idx >= 0) {
            val closeIdx = result.indexOf("```", idx + MARKER.length)
            val end = if (closeIdx >= 0) closeIdx + 3 else result.length
            result = result.substring(0, idx) + result.substring(end)
            idx = result.indexOf(MARKER, idx)
        }
        return result
    }

    /**
     * Число код-фенсов "```" в тексте: цикл indexOf, O(n) по длине без regex —
     * ответ модели может достигать 150 КБ. Маркер "```task-state" начинается
     * с "```" и тоже считается, поэтому вызывать имеет смысл для уже
     * стрипнутого текста (см. [strip]).
     */
    fun countCodeFences(markdown: String): Int {
        var count = 0
        var idx = markdown.indexOf(FENCE)
        while (idx >= 0) {
            count++
            idx = markdown.indexOf(FENCE, idx + FENCE.length)
        }
        return count
    }

    /**
     * Контент-гейт (план bypass-fixes, задача 3): превышает ли ответ порог
     * «выглядит как реализация, а не план». Число блоков =
     * (fences + fences % 2) / 2 — нечётный хвост считается незакрытым блоком
     * (+1). Порог: blocks >= 2, ИЛИ единственный блок длиннее 10 строк
     * (строки содержимого между фенсами; у незакрытого блока содержимое —
     * от первого фенса до конца текста).
     */
    fun codeBlocksExceedThreshold(markdown: String): Boolean {
        val fences = countCodeFences(markdown)
        val blocks = (fences + fences % 2) / 2
        if (blocks == 0) return false
        if (blocks >= 2) return true

        val contentStart = markdown.indexOf(FENCE) + FENCE.length
        val content = if (fences % 2 == 1) {
            markdown.substring(contentStart)
        } else {
            markdown.substring(contentStart, markdown.indexOf(FENCE, contentStart))
        }
        return content.split('\n').size > 10
    }

    /**
     * Содержимое ПОСЛЕДНЕГО fenced-блока ```task-state ... ``` в тексте
     * (обрезанное по краям); null, если блока нет или закрывающий забор
     * отсутствует.
     */
    private fun lastBlock(text: String): String? {
        val start = text.lastIndexOf(MARKER)
        if (start < 0) return null
        val contentStart = start + MARKER.length
        val end = text.indexOf("```", contentStart)
        if (end < 0) return null
        return text.substring(contentStart, end).trim()
    }
}
