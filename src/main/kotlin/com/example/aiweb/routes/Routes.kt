package com.example.aiweb.routes

import com.example.aiweb.agent.AgentLoop
import com.example.aiweb.client.AIClient
import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ExchangeLog
import com.example.aiweb.client.JudgePrompts
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.ReasoningPrompts
import com.example.aiweb.client.TeamRole
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.ExchangeLogResponse
import com.example.aiweb.model.JudgeReport
import com.example.aiweb.model.ModelsResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.ProviderModels
import com.example.aiweb.model.ReasoningMode
import com.example.aiweb.model.SessionSummary
import com.example.aiweb.model.SessionsCleared
import com.example.aiweb.model.SessionsResponse
import com.example.aiweb.model.StreamEvent
import com.example.aiweb.session.Session
import com.example.aiweb.session.SessionHistory
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.sessionStoreFor
import com.example.aiweb.tools.NativeToolTransport
import com.example.aiweb.tools.PromptToolTransport
import com.example.aiweb.tools.ToolRegistry
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Режимы, ведущие серверную историю диалога; «Команда» и Prompt-to-Prompt остаются stateless. */
private val HISTORY_MODES = setOf(
    ReasoningMode.DEFAULT,
    ReasoningMode.DIRECT,
    ReasoningMode.STEP_BY_STEP,
    ReasoningMode.AGENT
)

/**
 * Определяет HTTP-маршруты приложения. Каждый настроенный провайдер LLM получает
 * собственный AIClient; выбор провайдера — по ChatRequest.provider.
 */
fun Route.aiRoutes(config: AppConfig) {
    val clients: Map<ProviderConfig, AIClient> =
        config.providers.associateWith { AIClient(it) }

    // Серверная память диалога для одиночных режимов: без каталога данных (AI_DATA_DIR)
    // — память процесса, с каталогом — файловое хранилище (маршруты не меняются)
    val sessions: SessionStore = sessionStoreFor(config.dataDir)

    // Активные запросы стриминга для POST /api/cancel
    val activeRequests = ActiveRequests()

    // Реестр инструментов агента: строится один раз из раздела "tools" конфига
    // (отключённые в конфиге инструменты не видны модели)
    val registry = ToolRegistry.forConfig(config.tools)

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
        val body = call.receiveChatRequest() ?: return@post
        val startedAt = System.currentTimeMillis()
        val chat = resolveChat(config, clients, sessions, body)

        try {
            val response = when (chat.mode) {
                ReasoningMode.TEAM -> handleTeam(chat.aiClient, body)
                ReasoningMode.PROMPT_TO_PROMPT -> handlePromptToPrompt(chat.aiClient, body)
                ReasoningMode.AGENT -> handleAgent(chat.aiClient, body, chat.provider, chat.session, registry)
                else -> handleSingle(chat.aiClient, body, chat.mode, chat.session)
            }

            call.respond(finalizeResponse(chat.aiClient, body, response, chat.session, sessions, startedAt))
        } catch (e: Exception) {
            println("Ошибка при обращении к API: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ChatResponse("Ошибка при обращении к API: ${e.message}")
            )
        }
    }

    // POST /api/chat/stream — тот же ChatRequest, что у /api/chat, но ответ отдаётся
    // кадрами SSE (text/event-stream): живые события агента (или статус этапа для
    // остальных режимов), затем ровно один кадр final с полным ChatResponse.
    // Отмена прогона — POST /api/cancel по body.requestId.
    post("/api/chat/stream") {
        val body = call.receiveChatRequest() ?: return@post
        val startedAt = System.currentTimeMillis()
        val chat = resolveChat(config, clients, sessions, body)

        val requestId = body.requestId?.trim().orEmpty()
        val frames = Channel<StreamEvent>(Channel.UNLIMITED)

        try {
            if (requestId.isNotEmpty()) {
                activeRequests.register(requestId, currentCoroutineContext().job)
            }

            // Обработка запроса идёт в дочерней корутине и наполняет канал кадрами;
            // писатель ниже параллельно вычитывает их в HTTP-поток (живой стрим)
            val processor = launch {
                try {
                    if (chat.mode != ReasoningMode.AGENT) {
                        frames.trySend(StreamEvent.Status("Обрабатываю запрос…"))
                    }
                    val response = when (chat.mode) {
                        ReasoningMode.TEAM -> handleTeam(chat.aiClient, body)
                        ReasoningMode.PROMPT_TO_PROMPT -> handlePromptToPrompt(chat.aiClient, body)
                        ReasoningMode.AGENT -> handleAgent(chat.aiClient, body, chat.provider, chat.session, registry) { event ->
                            frames.trySend(StreamEvent.Agent(event))
                        }
                        else -> handleSingle(chat.aiClient, body, chat.mode, chat.session)
                    }
                    val finalized = finalizeResponse(
                        chat.aiClient, body, response, chat.session, sessions, startedAt
                    ) { status ->
                        frames.trySend(StreamEvent.Status(status))
                    }
                    frames.trySend(StreamEvent.Final(finalized))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("Ошибка при обращении к API: ${e.message}")
                    frames.trySend(StreamEvent.Error(e.message ?: "Неизвестная ошибка"))
                } finally {
                    frames.close()
                }
            }

            // Content-Type задаётся в respondTextWriter; no-cache — вручную, чтобы
            // прокси не буферизовали поток
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    for (frame in frames) {
                        write(encodeFrame(frame))
                        flush()
                    }
                    processor.join()
                } catch (e: CancellationException) {
                    // Отмена по /api/cancel (или обрыв клиента): best-effort кадр
                    // cancelled и чистое закрытие — исключение не перебрасывается,
                    // реестр чистится в finally обработчика
                    withContext(NonCancellable) {
                        runCatching {
                            write(encodeFrame(StreamEvent.Cancelled(requestId)))
                            flush()
                        }
                    }
                } finally {
                    frames.close()
                }
            }
        } finally {
            if (requestId.isNotEmpty()) activeRequests.unregister(requestId)
        }
    }

    // POST /api/cancel — отмена активного стрима по клиентскому requestId:
    // {"requestId": "..."} → {"cancelled": true|false}; неизвестный или уже
    // завершённый идентификатор — {"cancelled": false}
    post("/api/cancel") {
        val requestId = try {
            call.receive<JsonObject>()["requestId"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }

        if (requestId == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("cancelled", false) })
            return@post
        }

        call.respond(buildJsonObject { put("cancelled", activeRequests.cancel(requestId)) })
    }

    // GET /api/sessions — список сессий пользователя для панели справа, самые свежие первыми
    get("/api/sessions") {
        val summaries = sessions.list().map { session ->
            SessionSummary(
                id = session.id,
                title = sessionTitle(session),
                updatedAt = session.updatedAt,
                messageCount = session.messages.size
            )
        }
        call.respond(SessionsResponse(sessions = summaries))
    }

    // GET /api/session/{id} — возвращает ход диалога сессии для восстановления
    // журнала после перезагрузки страницы; system-сообщения в истории не живут —
    // только user/assistant/tool. Неизвестный id — 404
    get("/api/session/{id}") {
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(session)
        }
    }

    // DELETE /api/session/{id} — сброс диалога: сессия удаляется (идемпотентно)
    delete("/api/session/{id}") {
        sessions.delete(call.parameters["id"] ?: "")
        call.respond(HttpStatusCode.NoContent)
    }

    // DELETE /api/sessions — стирает все сессии пользователя
    delete("/api/sessions") {
        call.respond(SessionsCleared(deleted = sessions.clear()))
    }
}

/** Оставляет в списке только разрешённые модели, сохраняя порядок white-списка. */
private fun keepOrder(allowed: List<String>, fetched: List<String>): List<String> {
    val present = fetched.toSet()
    return allowed.filter { it in present }
}

/** Максимум символов в заголовке сессии в списке; длиннее — обрезается с «…». */
private const val SESSION_TITLE_LIMIT = 80

/**
 * Заголовок сессии для списка: первое user-сообщение с непустым текстом, без
 * пробелов по краям и обрезанное до [SESSION_TITLE_LIMIT]; если user-сообщений
 * нет — пустая строка.
 */
private fun sessionTitle(session: Session): String {
    val text = session.messages
        .firstOrNull { it.role == "user" && !it.content.isNullOrBlank() }
        ?.content?.trim()
        ?: return ""
    return if (text.length <= SESSION_TITLE_LIMIT) text else text.take(SESSION_TITLE_LIMIT) + "…"
}

/**
 * Читает и валидирует тело запроса чата (общий вход /api/chat и /api/chat/stream).
 * Возвращает null, если ответ уже отправлен: некорректный JSON или пустое
 * сообщение → 400 с ChatResponse с текстом ошибки.
 */
private suspend fun ApplicationCall.receiveChatRequest(): ChatRequest? {
    val body = try {
        receive<ChatRequest>()
    } catch (e: Exception) {
        println("Ошибка десериализации запроса: ${e.message}")
        respond(HttpStatusCode.BadRequest, ChatResponse("Некорректный запрос."))
        return null
    }

    if (body.message.isBlank()) {
        respond(HttpStatusCode.BadRequest, ChatResponse("Сообщение не может быть пустым."))
        return null
    }

    return body
}

/**
 * Контекст запроса чата после общего препроцессинга /api/chat и /api/chat/stream:
 * выбранный режим, провайдер, его клиент LLM и сессия диалога.
 */
private class ChatContext(
    val mode: ReasoningMode,
    val provider: ProviderConfig,
    val aiClient: AIClient,
    val session: Session?
)

/**
 * Общий препроцессинг /api/chat и /api/chat/stream: выбор режима, провайдера,
 * клиента LLM и сессии диалога. Память диалога — только у режимов с историей:
 * без sessionId создаётся новая сессия, неизвестный/выселенный id тоже начинает
 * диалог заново; «Команда» и Prompt-to-Prompt остаются stateless.
 */
private fun resolveChat(
    config: AppConfig,
    clients: Map<ProviderConfig, AIClient>,
    sessions: SessionStore,
    body: ChatRequest
): ChatContext {
    val mode = ReasoningMode.fromId(body.reasoningMode)
    val provider = config.provider(body.provider)
    return ChatContext(
        mode = mode,
        provider = provider,
        aiClient = clients.getValue(provider),
        session = if (mode in HISTORY_MODES) {
            sessions.get(body.sessionId) ?: sessions.create()
        } else {
            null
        }
    )
}

/**
 * Общая пост-обработка ответа режима (используется /api/chat и /api/chat/stream):
 * история сессии пополняется только после успешного ответа модели — одним
 * пользовательским сообщением и финальным ответом (промежуточный трафик
 * инструментов в память не пишет); затем приборный замер полного времени
 * обработки и опциональная контрольная проверка судьи.
 *
 * [onStatus] — шов стриминга: уведомление «Судья проверяет ответ…» уходит в поток
 * кадром Status до начала проверки; в блокирующем /api/chat остаётся пустым.
 */
private suspend fun finalizeResponse(
    aiClient: AIClient,
    body: ChatRequest,
    response: ChatResponse,
    session: Session?,
    sessions: SessionStore,
    startedAt: Long,
    onStatus: suspend (String) -> Unit = {}
): ChatResponse {
    if (session != null) {
        sessions.append(session.id, OpenAIMessage(role = "user", content = body.message))
        sessions.append(session.id, OpenAIMessage(role = "assistant", content = response.reply))
    }

    val elapsedMs = System.currentTimeMillis() - startedAt
    return if (body.judgeEnabled == true) {
        onStatus("Судья проверяет ответ…")
        response.copy(
            elapsedMs = elapsedMs,
            judge = runJudge(aiClient, body, response, elapsedMs)
        )
    } else {
        response.copy(elapsedMs = elapsedMs)
    }
}

/**
 * Режимы с одним обращением к модели: по умолчанию, прямой ответ и пошаговое решение.
 * DEFAULT передаёт вопрос как есть — без системного промта режима (только при заданном
 * лимите токенов указывается бюджет ответа). Лимит maxTokens передаётся в API и
 * дополнительно указывается модели в системной инструкции, чтобы ответ не обрывался.
 *
 * Если передана серверная сессия [session], между system-промтом и новым вопросом
 * вставляется обрезанная история диалога (SessionHistory.trim с запасом под «Топливо»);
 * идентификатор сессии возвращается в ChatResponse.sessionId. System-сообщения в
 * сессии не живут — они собираются в момент запроса.
 */
private suspend fun handleSingle(
    aiClient: AIClient,
    body: ChatRequest,
    mode: ReasoningMode,
    session: Session?
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    val history = session
        ?.let { SessionHistory.trim(it.messages, body.maxTokens ?: 0) }
        ?: emptyList()

    val context = if (mode == ReasoningMode.DEFAULT) {
        buildList {
            ReasoningPrompts.budgetNote(body.maxTokens)?.let {
                add(OpenAIMessage(role = "system", content = it))
            }
            addAll(history)
            add(OpenAIMessage(role = "user", content = body.message))
        }
    } else {
        buildList {
            add(
                OpenAIMessage(
                    role = "system",
                    content = ReasoningPrompts.withBudget(ReasoningPrompts.systemFor(mode), body.maxTokens)
                )
            )
            addAll(history)
            add(OpenAIMessage(role = "user", content = body.message))
        }
    }

    val result = aiClient.ask(
        LLMCall(
            messages = context,
            model = body.model,
            maxTokens = body.maxTokens,
            temperature = body.temperature,
            topP = body.topP,
            stop = body.stop,
            thinkingEnabled = body.thinkingEnabled
        )
    )

    return result.toResponse().copy(
        sessionId = session?.id,
        exchanges = ExchangeLog.since(startSeq)
    )
}

/**
 * Режим «Агент»: AgentLoop итерирует модель с инструментами до финального ответа.
 * Транспорт выбирается по возможностям провайдера: нативный function calling
 * (supportsTools = true) либо промт-протокол. Реестр инструментов [registry]
 * строится один раз в aiRoutes из раздела "tools" конфига. История сессии и цель
 * пользователя передаются в цикл как seed — system-промт агента добавляет сам
 * AgentLoop.
 *
 * События хода работы собираются через sink в ChatResponse.events; [onEvent] —
 * шов стриминга (P1: живые кадры Agent в /api/chat/stream), вызывается для каждого
 * события в дополнение к коплению списка. В историю сессии попадают только
 * пользовательское сообщение и финальный ответ — промежуточный трафик инструментов
 * (общий пост-when блок после ответа) в память не пишет.
 */
private suspend fun handleAgent(
    aiClient: AIClient,
    body: ChatRequest,
    provider: ProviderConfig,
    session: Session?,
    registry: ToolRegistry,
    onEvent: suspend (AgentEvent) -> Unit = {}
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    val loop = AgentLoop(
        client = aiClient,
        transport = if (provider.supportsTools) NativeToolTransport() else PromptToolTransport(),
        registry = registry
    )

    val history = session
        ?.let { SessionHistory.trim(it.messages, body.maxTokens ?: 0) }
        ?: emptyList()
    val seed = history + OpenAIMessage(role = "user", content = body.message)

    val events = mutableListOf<AgentEvent>()
    val outcome = loop.run(
        seed = seed,
        maxTokens = body.maxTokens,
        onEvent = { event ->
            events.add(event)
            onEvent(event)
        },
        model = body.model,
        temperature = body.temperature,
        topP = body.topP,
        stop = body.stop,
        thinkingEnabled = body.thinkingEnabled
    )

    return ChatResponse(
        reply = outcome.reply,
        promptTokens = outcome.promptTokens,
        completionTokens = outcome.completionTokens,
        totalTokens = outcome.totalTokens,
        sessionId = session?.id,
        events = events,
        exchanges = ExchangeLog.since(startSeq)
    )
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
                            LLMCall(
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
                LLMCall(
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
        LLMCall(
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
    )

    val generatedPrompt = extractPrompt(stage1.reply)

    // Промт самодостаточен (роль, контекст, формат уже внутри него), поэтому из
    // системных сообщений на втором этапе уходит только указание лимита токенов
    val stage2Messages = ReasoningPrompts.budgetNote(stageBudget)
        ?.let { listOf(OpenAIMessage(role = "system", content = it)) }
        ?: emptyList()

    val stage2 = runCatching {
        aiClient.ask(
            LLMCall(
                messages = stage2Messages + OpenAIMessage(role = "user", content = generatedPrompt),
                model = body.model,
                maxTokens = stageBudget,
                temperature = body.temperature,
                topP = body.topP,
                stop = body.stop,
                thinkingEnabled = body.thinkingEnabled
            )
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
            LLMCall(
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
