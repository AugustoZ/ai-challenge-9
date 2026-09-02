package com.example.aiweb.routes

import com.example.aiweb.client.AIClient
import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ExchangeLog
import com.example.aiweb.client.ReasoningPrompts
import com.example.aiweb.client.TeamRole
import com.example.aiweb.config.AppConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.ExchangeLogResponse
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

    // GET /api/log — сырой протокол обменов с моделью (самописец)
    get("/api/log") {
        call.respond(ExchangeLogResponse(entries = ExchangeLog.all()))
    }

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
                ReasoningMode.PROMPT_TO_PROMPT -> handlePromptToPrompt(aiClient, body)
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
 * Режимы с одним обращением к модели: прямой ответ и пошаговое решение.
 * Лимит maxTokens передаётся в API и дополнительно указывается модели в системной
 * инструкции, чтобы ответ не обрывался на середине.
 */
private suspend fun handleSingle(
    aiClient: AIClient,
    body: ChatRequest,
    mode: ReasoningMode
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    val context = listOf(
        OpenAIMessage(
            role = "system",
            content = ReasoningPrompts.withBudget(ReasoningPrompts.systemFor(mode), body.maxTokens)
        ),
        OpenAIMessage(role = "user", content = body.message)
    )

    val result = aiClient.ask(
        messages = context,
        maxTokens = body.maxTokens,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop
    )

    return result.toResponse().copy(exchanges = ExchangeLog.since(startSeq))
}

/**
 * Режим «Команда»: Архитектор, Инженер и Исследователь решают задачу параллельно
 * (у каждого свой system-промт и набор навыков), затем отдельным вызовом строится
 * саммари сравнения их ответов.
 *
 * Лимит maxTokens трактуется как бюджет всего видимого ответа, поэтому делится
 * поровну на 4 вызова (3 роли + саммари); иначе суммарный объём превысил бы
 * установленный лимит в 4 раза. Токены всех вызовов суммируются.
 */
private suspend fun handleTeam(aiClient: AIClient, body: ChatRequest): ChatResponse =
    coroutineScope {
        val startSeq = ExchangeLog.mark()
        val perCallBudget = body.maxTokens?.let { (it / 4).coerceAtLeast(1) }

        val attempts: List<Pair<TeamRole, Result<ChatResult>>> =
            ReasoningPrompts.TEAM_ROLES.map { teamRole ->
                async {
                    teamRole to runCatching {
                        aiClient.ask(
                            messages = listOf(
                                OpenAIMessage(
                                    role = "system",
                                    content = ReasoningPrompts.withBudget(teamRole.prompt, perCallBudget)
                                ),
                                OpenAIMessage(role = "user", content = body.message)
                            ),
                            maxTokens = perCallBudget,
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
                    OpenAIMessage(
                        role = "system",
                        content = ReasoningPrompts.withBudget(ReasoningPrompts.TEAM_SYNTHESIS_SYSTEM, perCallBudget)
                    ),
                    OpenAIMessage(
                        role = "user",
                        content = ReasoningPrompts.teamSynthesisUser(body.message, answersBlock)
                    )
                ),
                maxTokens = perCallBudget,
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
            totalTokens = sumTokens(results.map { it?.totalTokens }),
            exchanges = ExchangeLog.since(startSeq)
        )
    }

/**
 * Режим Prompt-to-Prompt, два этапа:
 * 1) модель-инженер промтов формирует готовый промт для решения задачи;
 * 2) этот промт передаётся в LLM как обычный запрос, и её решение показывается
 *    пользователю вместе с самим промтом.
 * Лимит maxTokens делится пополам между этапами.
 */
private suspend fun handlePromptToPrompt(aiClient: AIClient, body: ChatRequest): ChatResponse {
    val startSeq = ExchangeLog.mark()
    val stageBudget = body.maxTokens?.let { (it / 2).coerceAtLeast(1) }

    val stage1 = aiClient.ask(
        messages = listOf(
            OpenAIMessage(
                role = "system",
                content = ReasoningPrompts.withBudget(
                    ReasoningPrompts.systemFor(ReasoningMode.PROMPT_TO_PROMPT),
                    stageBudget
                )
            ),
            OpenAIMessage(role = "user", content = body.message)
        ),
        maxTokens = stageBudget,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop
    )

    val generatedPrompt = extractPrompt(stage1.reply)

    // Промт самодостаточен (роль, контекст, формат уже внутри него), поэтому из
    // системных сообщений на втором этапе уходит только указание лимита токенов
    val stage2Messages = ReasoningPrompts.budgetNote(stageBudget)
        ?.let { listOf(OpenAIMessage(role = "system", content = it)) }
        ?: emptyList()

    val stage2 = runCatching {
        aiClient.ask(
            messages = stage2Messages + OpenAIMessage(role = "user", content = generatedPrompt),
            maxTokens = stageBudget,
            temperature = body.temperature,
            topP = body.topP,
            stop = body.stop
        )
    }

    val reply = buildString {
        append("## Сгенерированный промт\n\n```text\n")
        append(generatedPrompt)
        append("\n```\n\n---\n\n## Ответ модели\n\n")
        append(
            stage2.fold(
                onSuccess = { it.reply },
                onFailure = { "Решение по промту получить не удалось: ${it.message}." }
            )
        )
    }

    val results = listOf(stage1, stage2.getOrNull())
    return ChatResponse(
        reply = reply,
        promptTokens = sumTokens(results.map { it?.promptTokens }),
        completionTokens = sumTokens(results.map { it?.completionTokens }),
        totalTokens = sumTokens(results.map { it?.totalTokens }),
        exchanges = ExchangeLog.since(startSeq)
    )
}

/**
 * Достаёт промт из блока кода (```text ... ```), который требует формировать
 * режим Prompt-to-Prompt; если блока нет — использует ответ как есть.
 */
private fun extractPrompt(reply: String): String =
    Regex("```[^\\n]*\\n?([\\s\\S]*?)```")
        .find(reply)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: reply.trim()

/** Суммирует значения токенов; null, если API не вернул ни одного значения. */
private fun sumTokens(values: List<Int?>): Int? =
    values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

private fun ChatResult.toResponse(): ChatResponse = ChatResponse(
    reply = reply,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens
)
