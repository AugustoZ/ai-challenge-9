package com.example.aiweb.routes

import com.example.aiweb.client.AIClient
import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ExchangeLog
import com.example.aiweb.client.JudgePrompts
import com.example.aiweb.client.ReasoningPrompts
import com.example.aiweb.client.TeamRole
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.ExchangeLogResponse
import com.example.aiweb.model.JudgeReport
import com.example.aiweb.model.ModelsResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.ProviderModels
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
 * Определяет HTTP-маршруты приложения. Каждый настроенный провайдер LLM получает
 * собственный AIClient; выбор провайдера — по ChatRequest.provider.
 */
fun Route.aiRoutes(config: AppConfig) {
    val clients: Map<ProviderConfig, AIClient> =
        config.providers.associateWith { AIClient(it) }

    // GET /api/log — сырой протокол обменов с моделью (самописец)
    get("/api/log") {
        call.respond(ExchangeLogResponse(entries = ExchangeLog.all()))
    }

    // GET /api/models — модели по провайдерам: у провайдера по умолчанию применяется
    // белый список allowedModels (в порядке конфига), у остальных — их явный список
    // или список от API
    get("/api/models") {
        val groups = config.providers.map { provider ->
            val fetched = runCatching { clients.getValue(provider).listModels() }
                .onFailure { println("Не удалось получить список моделей «${provider.id}»: ${it.message}") }
                .getOrDefault(emptyList())
            val models = if (provider.id == AppConfig.DEFAULT_PROVIDER_ID && config.allowedModels.isNotEmpty()) {
                keepOrder(config.allowedModels, fetched.ifEmpty { config.allowedModels })
            } else {
                provider.models.ifEmpty { fetched }
            }
            ProviderModels(id = provider.id, name = provider.name, models = models)
        }
        call.respond(
            ModelsResponse(
                providers = groups,
                defaultProvider = config.default.id,
                default = config.default.model
            )
        )
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
        val provider = config.provider(body.provider)
        val aiClient = clients.getValue(provider)
        val startedAt = System.currentTimeMillis()

        try {
            val response = when (mode) {
                ReasoningMode.TEAM -> handleTeam(aiClient, body)
                ReasoningMode.PROMPT_TO_PROMPT -> handlePromptToPrompt(aiClient, body)
                else -> handleSingle(aiClient, body, mode)
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            val judged = if (body.judgeEnabled == true) {
                response.copy(
                    elapsedMs = elapsedMs,
                    judge = runJudge(aiClient, body, response, elapsedMs)
                )
            } else {
                response.copy(elapsedMs = elapsedMs)
            }
            call.respond(judged)
        } catch (e: Exception) {
            println("Ошибка при обращении к API: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ChatResponse("Ошибка при обращении к API: ${e.message}")
            )
        }
    }
}

/** Оставляет в списке только разрешённые модели, сохраняя порядок white-списка. */
private fun keepOrder(allowed: List<String>, fetched: List<String>): List<String> {
    val present = fetched.toSet()
    return allowed.filter { it in present }
}

/**
 * Режимы с одним обращением к модели: по умолчанию, прямой ответ и пошаговое решение.
 * DEFAULT передаёт вопрос как есть — без системного промта режима (только при заданном
 * лимите токенов указывается бюджет ответа). Лимит maxTokens передаётся в API и
 * дополнительно указывается модели в системной инструкции, чтобы ответ не обрывался.
 */
private suspend fun handleSingle(
    aiClient: AIClient,
    body: ChatRequest,
    mode: ReasoningMode
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    val context = if (mode == ReasoningMode.DEFAULT) {
        buildList {
            ReasoningPrompts.budgetNote(body.maxTokens)?.let {
                add(OpenAIMessage(role = "system", content = it))
            }
            add(OpenAIMessage(role = "user", content = body.message))
        }
    } else {
        listOf(
            OpenAIMessage(
                role = "system",
                content = ReasoningPrompts.withBudget(ReasoningPrompts.systemFor(mode), body.maxTokens)
            ),
            OpenAIMessage(role = "user", content = body.message)
        )
    }

    val result = aiClient.ask(
        messages = context,
        model = body.model,
        maxTokens = body.maxTokens,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop,
        thinkingEnabled = body.thinkingEnabled
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
                            model = body.model,
                            maxTokens = perCallBudget,
                            temperature = body.temperature,
                            topP = body.topP,
                            stop = body.stop,
                            thinkingEnabled = body.thinkingEnabled
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
                model = body.model,
                maxTokens = perCallBudget,
                temperature = body.temperature,
                topP = body.topP,
                stop = body.stop,
                thinkingEnabled = body.thinkingEnabled
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
        model = body.model,
        maxTokens = stageBudget,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop,
        thinkingEnabled = body.thinkingEnabled
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
            model = body.model,
            maxTokens = stageBudget,
            temperature = body.temperature,
            topP = body.topP,
            stop = body.stop,
            thinkingEnabled = body.thinkingEnabled
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

/**
 * Режим «Судья»: готовый ответ перепроверяется отдельным вызовом LLM.
 * Судья получает вопрос, ответ и приборные замеры (время, токены) и оценивает
 * факты, качество, скорость и ресурсоёмкость. Срыв проверки не роняет основной
 * ответ — вместо заключения возвращается отчёт с указанием причины.
 */
private suspend fun runJudge(
    aiClient: AIClient,
    body: ChatRequest,
    response: ChatResponse,
    elapsedMs: Long
): JudgeReport {
    val startedAt = System.currentTimeMillis()
    val outcome = runCatching {
        val result = aiClient.ask(
            messages = JudgePrompts.buildMessages(
                question = body.message,
                answer = response.reply,
                elapsedMs = elapsedMs,
                promptTokens = response.promptTokens,
                completionTokens = response.completionTokens,
                totalTokens = response.totalTokens
            ),
            model = body.model,
            temperature = 0.1,
            thinkingEnabled = body.thinkingEnabled
        )
        JudgePrompts.parseReport(result.reply)
    }

    return outcome.fold(
        onSuccess = { report ->
            report.copy(elapsedMs = System.currentTimeMillis() - startedAt)
        },
        onFailure = { error ->
            println("Режим «Судья»: проверка не удалась: ${error.message}")
            JudgeReport(
                verdict = "Проверка не выполнена",
                raw = error.message,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        }
    )
}

private fun ChatResult.toResponse(): ChatResponse = ChatResponse(
    reply = reply,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
    elapsedMs = elapsedMs
)
