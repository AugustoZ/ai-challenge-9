package com.example.aiweb.routes

import com.example.aiweb.client.AIClient
import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ReasoningPrompts
import com.example.aiweb.client.TeamRole
import com.example.aiweb.config.AppConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.ReasoningMode
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Определяет HTTP-маршруты приложения.
 */
fun Route.aiRoutes(config: AppConfig) {
    val aiClient = AIClient(config)

    // POST /api/chat — принимает сообщение и возвращает ответ AI
    post("/api/chat") {
        val body = try {
            call.receive<ChatRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Некорректный запрос.")
            )
            return@post
        }

        if (body.message.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Сообщение не может быть пустым.")
            )
            return@post
        }

        val mode = ReasoningMode.fromId(body.reasoningMode)

        try {
            val response = when (mode) {
                ReasoningMode.TEAM -> handleTeam(aiClient, body)
                else -> handleSingle(aiClient, body, mode)
            }
            call.respond(response)
        } catch (e: Exception) {
            println("Ошибка при обращении к API: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ChatResponse("Ошибка при обращении к API: ${e.message}")
            )
        }
    }
}

/**
 * Режимы с одним обращением к модели: прямой ответ, пошаговое решение, промт-на-промт.
 */
private suspend fun handleSingle(
    aiClient: AIClient,
    body: ChatRequest,
    mode: ReasoningMode
): ChatResponse {
    val context = listOf(
        OpenAIMessage(role = "system", content = ReasoningPrompts.systemFor(mode)),
        OpenAIMessage(role = "user", content = body.message)
    )

    val result = aiClient.ask(
        messages = context,
        maxTokens = body.maxTokens,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop
    )

    return result.toResponse()
}

/**
 * Режим «Команда»: Архитектор, Инженер и Исследователь решают задачу параллельно
 * (у каждого свой system-промт и набор навыков), затем отдельным вызовом строится
 * саммари сравнения их ответов. Токены всех вызовов суммируются.
 */
private suspend fun handleTeam(aiClient: AIClient, body: ChatRequest): ChatResponse =
    coroutineScope {
        val attempts: List<Pair<TeamRole, Result<ChatResult>>> =
            ReasoningPrompts.TEAM_ROLES.map { teamRole ->
                async {
                    teamRole to runCatching {
                        aiClient.ask(
                            messages = listOf(
                                OpenAIMessage(role = "system", content = teamRole.prompt),
                                OpenAIMessage(role = "user", content = body.message)
                            ),
                            maxTokens = body.maxTokens,
                            temperature = body.temperature,
                            topP = body.topP,
                            stop = body.stop
                        )
                    }
                }
            }.awaitAll()

        // Если не удалось ни одно из трёх обращений — отдаём ошибку как в одиночных режимах
        val failures = attempts.mapNotNull { it.second.exceptionOrNull() }
        if (failures.size == attempts.size) throw failures.first()

        val answersBlock = attempts.joinToString("\n\n") { (teamRole, outcome) ->
            val solution = outcome.fold(
                onSuccess = { it.reply },
                onFailure = { "Решение недоступно: участник вернул ошибку (${it.message})." }
            )
            "### Решение: ${teamRole.title}\n\n$solution"
        }

        val synthesis = runCatching {
            aiClient.ask(
                messages = listOf(
                    OpenAIMessage(role = "system", content = ReasoningPrompts.TEAM_SYNTHESIS_SYSTEM),
                    OpenAIMessage(
                        role = "user",
                        content = ReasoningPrompts.teamSynthesisUser(body.message, answersBlock)
                    )
                ),
                maxTokens = body.maxTokens,
                temperature = body.temperature,
                topP = body.topP,
                stop = body.stop
            )
        }

        val reply = buildString {
            attempts.forEach { (teamRole, outcome) ->
                val solution = outcome.fold(
                    onSuccess = { it.reply },
                    onFailure = { "Решение недоступно: участник вернул ошибку (${it.message})." }
                )
                append("## ${teamRole.title}\n\n")
                append(solution)
                append("\n\n")
            }
            append("---\n\n## Саммари сравнения\n\n")
            append(
                synthesis.fold(
                    onSuccess = { it.reply },
                    onFailure = { "Саммари построить не удалось: ${it.message}." }
                )
            )
        }

        val results = attempts.map { it.second.getOrNull() } + synthesis.getOrNull()
        ChatResponse(
            reply = reply,
            promptTokens = sumTokens(results.map { it?.promptTokens }),
            completionTokens = sumTokens(results.map { it?.completionTokens }),
            totalTokens = sumTokens(results.map { it?.totalTokens })
        )
    }

/** Суммирует значения токенов; null, если API не вернул ни одного значения. */
private fun sumTokens(values: List<Int?>): Int? =
    values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

private fun ChatResult.toResponse(): ChatResponse = ChatResponse(
    reply = reply,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens
)
