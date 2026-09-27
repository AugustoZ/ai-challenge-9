package com.example.aiweb.pipeline

import com.example.aiweb.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Допустимые статусы шага в отчёте пайплайна. */
enum class PipelineStepState { running, done, failed, skipped }

/** Событие хода шага, публикуемое в [PipelineRunner.onEvent]. */
sealed class PipelineEvent {
    /** Шаг взят в работу: статус шага становится running. */
    data class StepStarted(val id: String, val tool: String) : PipelineEvent()

    /** Шаг завершился: терминальный статус, длительность и (для done) preview. */
    data class StepFinished(
        val id: String,
        val tool: String,
        val status: PipelineStepState,
        val durationMs: Long,
        val error: String? = null,
        val resultPreview: String? = null
    ) : PipelineEvent()
}

/** Итог прогона пайплайна. */
data class PipelineRunResult(
    val succeeded: Boolean,
    val error: String? = null
)

/** Ошибка резолва плейсхолдера (ссылка на неизвестный или ещё не выполненный шаг). */
private class PlaceholderResolutionException(message: String) : Exception(message)

/** Исход исполнения одного шага. */
private sealed class StepOutcome {
    data class Ok(val content: String) : StepOutcome()
    data class Failed(val error: String) : StepOutcome()
}

/**
 * Движок пайплайна: исполняет шаги [scenario] линейно (по порядку), без
 * ветвлений, циклов и ретраев.
 *
 * **Плейсхолдеры** в аргументах шага — строковое значение `${steps.<id>.result}`:
 * - значение РОВНО равное плейсхолдеру → структурная подстановка: текст
 *   результата шага парсится как JSON, если валиден (вставка как [JsonElement]),
 *   иначе — как [JsonPrimitive]-строка;
 * - вхождение внутри большей строки → строковая интерполяция (сырой текст);
 * - ссылка на неизвестный или ещё не выполненный шаг → провал пайплайна.
 *
 * Каждый шаг исполняется на [Dispatchers.IO]; результат капится
 * [MAX_RESULT_LENGTH] символов. Упавший шаг: при `optional=true` пайплайн
 * продолжается (шаг помечается failed), иначе останавливается.
 */
class PipelineRunner(
    private val scenario: PipelineScenario,
    private val toolLookup: (String) -> Tool?,
    private val onEvent: (PipelineEvent) -> Unit
) {
    /** Результаты выполненных шагов (по id) — источник плейсхолдеров. */
    private val results = LinkedHashMap<String, String>()

    suspend fun run(): PipelineRunResult {
        for (step in scenario.steps) {
            onEvent(PipelineEvent.StepStarted(step.id, step.tool))
            val started = System.currentTimeMillis()
            val outcome = executeStep(step)
            val durationMs = System.currentTimeMillis() - started
            when (outcome) {
                is StepOutcome.Ok -> {
                    results[step.id] = outcome.content
                    onEvent(
                        PipelineEvent.StepFinished(
                            id = step.id,
                            tool = step.tool,
                            status = PipelineStepState.done,
                            durationMs = durationMs,
                            resultPreview = outcome.content.take(RESULT_PREVIEW_LIMIT)
                        )
                    )
                }
                is StepOutcome.Failed -> {
                    onEvent(
                        PipelineEvent.StepFinished(
                            id = step.id,
                            tool = step.tool,
                            status = PipelineStepState.failed,
                            durationMs = durationMs,
                            error = outcome.error
                        )
                    )
                    if (!step.optional) {
                        return PipelineRunResult(succeeded = false, error = outcome.error)
                    }
                }
            }
        }
        return PipelineRunResult(succeeded = true)
    }

    private suspend fun executeStep(step: PipelineStep): StepOutcome {
        val args = try {
            resolvePlaceholders(step.args, results)
        } catch (e: PlaceholderResolutionException) {
            return StepOutcome.Failed(e.message ?: "не удалось подставить плейсхолдер")
        }
        val tool = toolLookup(step.tool)
            ?: return StepOutcome.Failed("Шаг '${step.id}': инструмент '${step.tool}' не найден")
        return try {
            val result = withContext(Dispatchers.IO) { tool.execute(args.toString()) }
            val content = result.content.take(MAX_RESULT_LENGTH)
            if (result.ok) StepOutcome.Ok(content) else StepOutcome.Failed(content)
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown"
            StepOutcome.Failed("Шаг '${step.id}': упал инструмент '${step.tool}': $detail")
        }
    }

    /** Рекурсивно резолвит плейсхолдеры в аргументах (без мутации исходного JSON). */
    private fun resolvePlaceholders(element: JsonElement, results: Map<String, String>): JsonElement =
        when (element) {
            is JsonPrimitive ->
                if (element.isString) resolveString(element.content, results) else element
            is JsonObject -> buildJsonObject {
                for ((key, value) in element) put(key, resolvePlaceholders(value, results))
            }
            is JsonArray -> JsonArray(element.map { resolvePlaceholders(it, results) })
            is JsonNull -> element
        }

    private fun resolveString(value: String, results: Map<String, String>): JsonElement {
        val exact = PLACEHOLDER.matchEntire(value)
        if (exact != null) {
            val id = exact.groupValues[1]
            val content = results[id]
                ?: throw PlaceholderResolutionException("Ссылка на неизвестный или ещё не выполненный шаг '$id'")
            return runCatching { Json.parseToJsonElement(content) }
                .getOrElse { JsonPrimitive(content) }
        }
        if (PLACEHOLDER.containsMatchIn(value)) {
            val interpolated = PLACEHOLDER.replace(value) { match ->
                val id = match.groupValues[1]
                results[id]
                    ?: throw PlaceholderResolutionException("Ссылка на неизвестный или ещё не выполненный шаг '$id'")
            }
            return JsonPrimitive(interpolated)
        }
        return JsonPrimitive(value)
    }

    companion object {
        /** Кап текста результата шага (символов) — свой лимит, вне агентского цикла. */
        const val MAX_RESULT_LENGTH = 20_000

        /** Кап превью результата в отчёте статуса (символов). */
        const val RESULT_PREVIEW_LIMIT = 500

        /** Токен плейсхолдера `${steps.<id>.result}`. */
        val PLACEHOLDER: Regex = Regex("""\$\{steps\.(.+?)\.result\}""")
    }
}
