package com.example.aiweb.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Сценарий пайплайна: именованный набор шагов, исполняемых линейно.
 */
@Serializable
data class PipelineScenario(
    val name: String,
    val description: String? = null,
    val steps: List<PipelineStep> = emptyList()
)

/**
 * Один шаг пайплайна — вызов инструмента с аргументами.
 */
@Serializable
data class PipelineStep(
    val id: String,
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val optional: Boolean = false
)
