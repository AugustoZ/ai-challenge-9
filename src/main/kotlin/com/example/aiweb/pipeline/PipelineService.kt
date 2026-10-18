package com.example.aiweb.pipeline

import com.example.aiweb.tools.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Статус одного шага в отчёте [PipelineStatus]. */
@Serializable
data class PipelineStepStatus(
    val id: String,
    val tool: String,
    val status: String,
    val durationMs: Long? = null,
    val error: String? = null,
    val resultPreview: String? = null
)

/**
 * In-memory статус запуска сценария: последний прогон (или текущий, если running).
 * [state]: running | succeeded | failed; [steps][].status: running | done | failed | skipped.
 */
@Serializable
data class PipelineStatus(
    val name: String,
    val runId: String,
    val state: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val steps: List<PipelineStepStatus> = emptyList()
)

/** Тело POST /api/pipeline/run. */
@Serializable
data class PipelineRunRequest(val name: String)

/** Ответ 202 POST /api/pipeline/run. */
@Serializable
data class PipelineRunAccepted(val runId: String)

/** Тело ошибки (404/409). */
@Serializable
data class PipelineErrorResponse(val error: String)

/** Результат вызова [PipelineService.run]. */
sealed class PipelineRunOutcome {
    data class Accepted(val runId: String) : PipelineRunOutcome()
    object NotFound : PipelineRunOutcome()
    object Conflict : PipelineRunOutcome()
}

/**
 * Реестр сценариев пайплайна и in-memory статус запусков.
 *
 * Сценарии загружаются из директории [scenarioDir] (файлы `*.json`); пустая или
 * отсутствующая директория — ноль сценариев (не ошибка). Статус живёт только в
 * памяти процесса: никакого persist, SSE/websocket.
 *
 * На один сценарий допускается один активный запуск: повторный [run] активного
 * сценария → [PipelineRunOutcome.Conflict] (409); после завершения запуск можно
 * повторить.
 */
class PipelineService(
    private val scenarioDir: String = "config/pipelines",
    private val toolLookup: (String) -> Tool?,
    private val scope: CoroutineScope
) {
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scenarios: Map<String, PipelineScenario> = loadScenarios(scenarioDir)
    private val statuses = ConcurrentHashMap<String, PipelineStatus>()
    private val lock = Any()


    /** Сценарий по имени; неизвестное имя — null. */
    fun find(name: String): PipelineScenario? = scenarios[name]

    /** Число загруженных сценариев. */
    fun count(): Int = scenarios.size

    /**
     * Запускает сценарий асинхронно на [scope]. Возвращает 202-эквивалент
     * сразу: тело сценария исполняется в отдельной корутине.
     */
    fun run(name: String): PipelineRunOutcome {
        val scenario = scenarios[name] ?: return PipelineRunOutcome.NotFound
        synchronized(lock) {
            val current = statuses[name]
            if (current != null && current.state == "running") {
                return PipelineRunOutcome.Conflict
            }
            val runId = UUID.randomUUID().toString()
            statuses[name] = PipelineStatus(
                name = name,
                runId = runId,
                state = "running",
                startedAt = nowIso(),
                steps = scenario.steps.map {
                    PipelineStepStatus(id = it.id, tool = it.tool, status = "pending")
                }
            )
            scope.launch { executeRun(name, scenario) }
            return PipelineRunOutcome.Accepted(runId)
        }
    }

    /** Статус последнего/текущего запуска сценария; никогда не запускался — null. */
    fun status(name: String): PipelineStatus? = statuses[name]

    private suspend fun executeRun(name: String, scenario: PipelineScenario) {
        val result = try {
            PipelineRunner(scenario, toolLookup, onEvent = { handleEvent(name, it) }).run()
        } catch (e: Exception) {
            PipelineRunResult(succeeded = false, error = e.message ?: "неизвестная ошибка пайплайна")
        }
        synchronized(lock) {
            val current = statuses[name] ?: return@synchronized
            statuses[name] = current.copy(
                state = if (result.succeeded) "succeeded" else "failed",
                finishedAt = nowIso()
            )
        }
    }

    private fun handleEvent(name: String, event: PipelineEvent) {
        when (event) {
            is PipelineEvent.StepStarted -> updateStatus(name) { status ->
                status.copy(
                    steps = status.steps.map {
                        if (it.id == event.id) it.copy(status = "running") else it
                    }
                )
            }
            is PipelineEvent.StepFinished -> updateStatus(name) { status ->
                status.copy(
                    steps = status.steps.map {
                        if (it.id == event.id) it.copy(
                            status = event.status.name,
                            durationMs = event.durationMs,
                            error = event.error,
                            resultPreview = event.resultPreview
                        ) else it
                    }
                )
            }
        }
    }

    private fun updateStatus(name: String, transform: (PipelineStatus) -> PipelineStatus) {
        statuses[name]?.let { statuses[name] = transform(it) }
    }

    private fun loadScenarios(dir: String): Map<String, PipelineScenario> {
        val directory = File(dir)
        if (!directory.isDirectory) return emptyMap()
        return directory.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<PipelineScenario>(file.readText()) }
                    .onFailure {
                        System.err.println(
                            "pipeline: не удалось прочитать сценарий '${file.name}': ${it.message}"
                        )
                    }
                    .getOrNull()
            }
            ?.associateBy { it.name }
            ?: emptyMap()
    }

    private fun nowIso(): String = Instant.now().toString()
}
