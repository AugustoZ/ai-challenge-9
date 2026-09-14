package com.example.aiweb.routes

import com.example.aiweb.agent.AgentLoop
import com.example.aiweb.client.AIClient
import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.ExchangeLog
import com.example.aiweb.client.JudgePrompts
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.OpenAIFactExtractor
import com.example.aiweb.client.OpenAIHistorySummarizer
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ActivateBranchRequest
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.BranchInfo
import com.example.aiweb.model.BranchesResponse
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.ContextStrategy
import com.example.aiweb.model.ExchangeLogResponse
import com.example.aiweb.model.JudgeReport
import com.example.aiweb.model.ModelsResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.ProviderModels
import com.example.aiweb.model.SessionSummary
import com.example.aiweb.model.SessionsCleared
import com.example.aiweb.model.SessionsResponse
import com.example.aiweb.model.StreamEvent
import com.example.aiweb.model.TurnUsage
import com.example.aiweb.session.HistoryCompressor
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.MergeResult
import com.example.aiweb.session.Session
import com.example.aiweb.session.SessionHistory
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.sessionStoreFor
import com.example.aiweb.session.view
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Определяет HTTP-маршруты приложения. Каждый настроенный провайдер LLM получает
 * собственный LLMClient; выбор провайдера — по ChatRequest.provider.
 *
 * Швы для тестов (реализации по умолчанию работают в продакшене): хранилище
 * сессий, фабрика LLM-клиентов ([FakeLLMClient ] как транспорт в Routes-тестах)
 * и фабрика суммаризаторов (реальная — [OpenAIHistorySummarizer], фейк в тестах).
 */
fun Route.aiRoutes(
    config: AppConfig,
    store: SessionStore = sessionStoreFor(config.dataDir),
    clientFactory: (ProviderConfig) -> LLMClient = { AIClient(it) },
    summarizerFactory: (LLMClient) -> HistorySummarizer = {
        OpenAIHistorySummarizer(it, config.compressionSummaryMaxTokens)
    },
    factExtractorFactory: (LLMClient) -> FactExtractor = {
        OpenAIFactExtractor(it)
    }
) {
    val clients: Map<ProviderConfig, LLMClient> =
        config.providers.associateWith { clientFactory(it) }

    // Серверная память диалога: без каталога данных (AI_DATA_DIR)
    // — память процесса, с каталогом — файловое хранилище (маршруты не меняются)
    val sessions: SessionStore = store

    // Компрессор истории на каждый провайдер: окно/порог/бюджет из конфига,
    // суммаризатор реальный (оборачивает LLM-клиента провайдера) либо фейк в тестах.
    val compressors: Map<ProviderConfig, HistoryCompressor> =
        config.providers.associateWith { provider ->
            HistoryCompressor(
                windowSize = config.compressionWindowSize,
                mergeEvery = config.compressionMergeEvery,
                summaryMaxTokens = config.compressionSummaryMaxTokens,
                summarizer = summarizerFactory(clients.getValue(provider))
            )
        }

    // Экстракторы фактов на каждый провайдер (стратегия «Факты»): реальная
    // реализация оборачивает LLM-клиента провайдера либо фейк в тестах;
    // вызов при стратегии FACTS — интегрируется в обработчиках (задача 6).
    val factExtractors: Map<ProviderConfig, FactExtractor> =
        config.providers.associateWith { provider ->
            factExtractorFactory(clients.getValue(provider))
        }

    // Активные запросы стриминга для POST /api/cancel
    val activeRequests = ActiveRequests()

    // Реестр инструментов агента: строится один раз из раздела "tools" конфига
    // (отключённые в конфиге инструменты не видны модели)
    val registry = ToolRegistry.forConfig(config.tools)

    // Хуки контекстных стратегий после ответа (экстракция фактов / подрезка
    // окна): стратегия разрешается по тому же приоритету, что в обработчиках;
    // экстрактор — фабричный шов провайдера
    fun contextHooks(provider: ProviderConfig, body: ChatRequest): ContextHooks = ContextHooks(
        strategy = ContextStrategy.resolve(
            body.contextStrategy,
            body.compressionEnabled ?: config.compressionEnabled,
            config.contextStrategy
        ) ?: ContextStrategy.OFF,
        factExtractor = factExtractors.getValue(provider),
        windowSize = config.compressionWindowSize
    )

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
        if (!call.validContextStrategy(body)) return@post
        if (!call.activateRequestedBranch(sessions, body, config)) return@post
        val startedAt = System.currentTimeMillis()
        val chat = resolveChat(config, clients, sessions, body)

        try {
            val response = handleAgent(
                chat.aiClient, body, chat.provider, chat.session,
                sessions, config, compressors.getValue(chat.provider), registry
            )

            call.respond(
                finalizeResponse(
                    chat.aiClient, body, response, chat.session, sessions, startedAt,
                    hooks = contextHooks(chat.provider, body)
                )
            )
        } catch (e: Exception) {
            println("Ошибка при обращении к API: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ChatResponse("Ошибка при обращении к API: ${e.message}")
            )
        }
    }

    // POST /api/chat/stream — тот же ChatRequest, что у /api/chat, но ответ отдаётся
    // кадрами SSE (text/event-stream): живые события агента, затем ровно один кадр
    // final с полным ChatResponse. Отмена прогона — POST /api/cancel по body.requestId.
    post("/api/chat/stream") {
        val body = call.receiveChatRequest() ?: return@post
        if (!call.validContextStrategy(body)) return@post
        if (!call.activateRequestedBranch(sessions, body, config)) return@post
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
                    val response = handleAgent(
                        chat.aiClient, body, chat.provider, chat.session,
                        sessions, config, compressors.getValue(chat.provider), registry
                    ) { event ->
                        frames.trySend(StreamEvent.Agent(event))
                    }
                    val finalized = finalizeResponse(
                        chat.aiClient, body, response, chat.session, sessions, startedAt,
                        hooks = contextHooks(chat.provider, body)
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

    // GET /api/session/{id}/branches — плоский список веток (задача 6): первой
    // запись живого активного состояния (active=true), затем снимки веток в порядке
    // создания. Неизвестная сессия — 404
    get("/api/session/{id}/branches") {
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(branchesView(session))
        }
    }

    // POST /api/session/{id}/branches — ручная развилка (чекпоинт по кнопке):
    // снимок текущего активного состояния (messages, summary, facts) в новую
    // ветку-близнец «Ветка <seq>»; активное состояние не меняется. Единственный
    // способ создать ветку: автоматических форков по счётчику сообщений нет.
    // Ответ — запись созданной ветки (форма BranchInfo, active=false).
    // Неизвестная сессия — 404, достигнут кап веток — 409. Тела запроса нет
    post("/api/session/{id}/branches") {
        val sessionId = call.parameters["id"] ?: ""
        if (sessions.get(sessionId) == null) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@post
        }
        val twin = sessions.forkTwin(sessionId)
        if (twin == null) {
            call.respond(HttpStatusCode.Conflict, ChatResponse("Достигнут лимит веток (8)"))
            return@post
        }
        call.respond(
            BranchInfo(
                branchId = twin.branchId,
                label = twin.label,
                forkUserCount = twin.forkUserCount,
                userCountSinceFork = twin.userCountSinceFork,
                messageCount = twin.messages.size,
                active = false
            )
        )
    }

    // POST /api/session/{id}/branches/activate — переключить активную ветку
    // (своп без потерь); ответ — полная сессия, как у GET /api/session/{id}.
    // Неизвестная сессия или ветка — 404
    post("/api/session/{id}/branches/activate") {
        val sessionId = call.parameters["id"] ?: ""
        if (sessions.get(sessionId) == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val request = try {
            call.receive<ActivateBranchRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        val branchId = request?.branchId?.trim().orEmpty()
        if (branchId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Укажите branchId ветки."))
            return@post
        }
        val activated = sessions.activateBranch(sessionId, branchId)
        if (activated == null) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Ветка не найдена: $branchId"))
            return@post
        }
        call.respond(activated)
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

    // GET /api/files/{name} — пользователь скачивает файлы, записанные агентом
    // в рабочую папку sandbox (например, snake.py после «напиши игру и пришли файлом»).
    // Только плоские имена из латиницы/цифр/._- — вложенные пути и прочее 400.
    // Файл добывается через /read того же клиента песочницы, что и у файловых
    // инструментов; выключенные файловые инструменты 503, незнакомое имя 404.
    get("/api/files/{name}") {
        val name = call.parameters["name"].orEmpty()
        if (!name.matches(FILE_NAME_PATTERN)) {
            call.respond(HttpStatusCode.BadRequest, "Некорректное имя файла.")
            return@get
        }
        val sandbox = registry.fileClient()
        if (sandbox == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Файловые инструменты выключены в конфиге.")
            return@get
        }
        val reply = sandbox.post("/read", buildJsonObject { put("path", name) }.toString())
        if (!reply.ok) {
            call.respond(HttpStatusCode.NotFound, "Файл не найден: $name")
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"$name\""
        )
        call.respondBytes(
            reply.content.orEmpty().toByteArray(Charsets.UTF_8),
            ContentType.Application.OctetStream
        )
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
 * Допустимое имя файла в GET /api/files/{name}: только плоские имена из латиницы,
 * цифр, точки, подчёркивания и дефиса — вложенные пути и кириллица не проходят.
 */
private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

/**
 * Плоский список веток сессии для GET /api/session/{id}/branches (задача 6):
 * первой — запись живого активного состояния (active=true; для исходной истории
 * branchId=null и label «Основная»), затем снимки прочих веток в порядке создания.
 * Счётчик активной записи выводится по контракту T3: user-сообщения активного
 * состояния минус провенанс её записи (для исходной истории — минус ноль);
 * у неактивных — сохранённый userCountSinceFork.
 */
private fun branchesView(session: Session): BranchesResponse {
    val activeRecord = session.activeBranchId
        ?.let { activeId -> session.branches.firstOrNull { it.branchId == activeId } }
    val activeUsers = session.messages.count { it.role == "user" }
    val live = BranchInfo(
        branchId = session.activeBranchId,
        label = activeRecord?.label ?: "Основная",
        forkUserCount = activeRecord?.forkUserCount ?: 0,
        userCountSinceFork = activeUsers - (activeRecord?.forkUserCount ?: 0),
        messageCount = session.messages.size,
        active = true
    )
    val snapshots = session.branches
        .filter { it.branchId != session.activeBranchId }
        .map { branch ->
            BranchInfo(
                branchId = branch.branchId,
                label = branch.label,
                forkUserCount = branch.forkUserCount,
                userCountSinceFork = branch.userCountSinceFork,
                messageCount = branch.messages.size,
                active = false
            )
        }
    return BranchesResponse(branches = listOf(live) + snapshots)
}

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
 * Проверяет явную стратегию контекста запроса: неизвестный id — 400 с текстом
 * «Неизвестная стратегия контекста: <id>» ДО создания сессии и вызовов LLM
 * (общий вход /api/chat и /api/chat/stream). null или валидный id — без ответа.
 */
private suspend fun ApplicationCall.validContextStrategy(body: ChatRequest): Boolean {
    if (body.contextStrategy == null) return true
    if (ContextStrategy.fromId(body.contextStrategy) != null) return true
    respond(
        HttpStatusCode.BadRequest,
        ChatResponse("Неизвестная стратегия контекста: ${body.contextStrategy}")
    )
    return false
}

/**
 * Явное переключение ветки запросом (ChatRequest.branchId, стратегия branching,
 * требование 5 дельты branching): выполняется ДО resolveChat, чтобы обработчик
 * получил уже активированный снимок сессии. Неизвестная ветка — 404 (ответ уже
 * отправлен, вызывающий завершает маршрут). Без действий: branchId не указан,
 * стратегия не branching, сессии ещё нет (создастся новой).
 */
private suspend fun ApplicationCall.activateRequestedBranch(
    sessions: SessionStore,
    body: ChatRequest,
    config: AppConfig
): Boolean {
    if (body.branchId == null) return true
    val strategy = ContextStrategy.resolve(
        body.contextStrategy,
        body.compressionEnabled ?: config.compressionEnabled,
        config.contextStrategy
    ) ?: ContextStrategy.OFF
    if (strategy != ContextStrategy.BRANCHING) return true

    val session = sessions.get(body.sessionId) ?: return true
    val activated = sessions.activateBranch(session.id, body.branchId)
    if (activated == null) {
        respond(HttpStatusCode.NotFound, ChatResponse("Ветка не найдена: ${body.branchId}"))
        return false
    }
    return true
}

/**
 * Контекст запроса чата после общего препроцессинга /api/chat и /api/chat/stream:
 * провайдер, его клиент LLM и сессия диалога.
 */
private class ChatContext(
    val provider: ProviderConfig,
    val aiClient: LLMClient,
    val session: Session?
)

/**
 * Хуки контекстных стратегий после ответа (общий пост-блок finalizeResponse):
 * разрешённая стратегия, экстрактор фактов провайдера (стратегия facts) и
 * размер окна хранилища (стратегия window).
 */
private class ContextHooks(
    val strategy: ContextStrategy,
    val factExtractor: FactExtractor?,
    val windowSize: Int
)

/**
 * Общий препроцессинг /api/chat и /api/chat/stream: выбор провайдера, клиента LLM
 * и сессии диалога. Память диалога есть у каждого запроса: без sessionId создаётся
 * новая сессия, неизвестный/выселенный id тоже начинает диалог заново.
 */
private fun resolveChat(
    config: AppConfig,
    clients: Map<ProviderConfig, LLMClient>,
    sessions: SessionStore,
    body: ChatRequest
): ChatContext {
    val provider = config.provider(body.provider)
    return ChatContext(
        provider = provider,
        aiClient = clients.getValue(provider),
        session = sessions.get(body.sessionId) ?: sessions.create()
    )
}

/**
 * Общая пост-обработка ответа режима (используется /api/chat и /api/chat/stream):
 * история сессии пополняется только после успешного ответа модели — одним
 * пользовательским сообщением и финальным ответом (промежуточный трафик
 * инструментов в память не пишет); затем хуки контекстных стратегий, приборный
 * замер полного времени обработки и опциональная контрольная проверка судьи.
 *
 * [hooks] — контекстные стратегии после ответа (задача 6): при стратегии facts —
 * экстракция фактов по новому сообщению (сбой — деградация: прежние факты, ответ
 * не падает), при window — деструктивная подрезка хранилища до последних
 * windowSize сообщений (store-операция [SessionStore.applyWindow]; запрос
 * ТЕКУЩЕГО обмена уже собран до этого блока, подрезка влияет только на
 * следующие ходы). Ветки хуков не имеют: ветка создаётся только ручной
 * развилкой (POST /api/session/{id}/branches), после ответа ничего не форкается.
 * Путь «Сводки» здесь не задет: merge/applyCompressionResult выполняются
 * в обработчиках до этого блока.
 *
 * [onStatus] — шов стриминга: уведомление «Судья проверяет ответ…» уходит в поток
 * кадром Status до начала проверки; в блокирующем /api/chat остаётся пустым.
 */
private suspend fun finalizeResponse(
    aiClient: LLMClient,
    body: ChatRequest,
    response: ChatResponse,
    session: Session?,
    sessions: SessionStore,
    startedAt: Long,
    hooks: ContextHooks? = null,
    onStatus: suspend (String) -> Unit = {}
): ChatResponse {
    if (session != null) {
        sessions.append(session.id, OpenAIMessage(role = "user", content = body.message))
        // Трейс агента и показания топлива хранятся на assistant-сообщении —
        // фронтенд восстанавливает по ним шапку ответа и топливомер сессии
        // (GET /api/session/{id}). Время хода фиксируется до пост-обработки:
        // сжатие/факты ниже добавляют своё в elapsedMs ответа
        val turnElapsedMs = System.currentTimeMillis() - startedAt
        sessions.append(
            session.id,
            OpenAIMessage(
                role = "assistant",
                content = response.reply,
                events = response.events,
                usage = response.totalTokens?.let { total ->
                    TurnUsage(
                        elapsedMs = turnElapsedMs,
                        promptTokens = response.promptTokens,
                        completionTokens = response.completionTokens,
                        totalTokens = total,
                        summaryApplied = response.summaryApplied,
                        summaryTokens = response.summaryTokens
                    )
                }
            )
        )
    }

    // Хуки контекстных стратегий — после записи ответа в историю
    if (session != null && hooks != null) {
        // «Окно»: деструктивная подрезка хранилища — остаются только последние
        // windowSize сообщений, за окном отброшено безвозвратно. Запрос текущего
        // обмена собран до этого блока, свежий ход уцелел (подрезка после append);
        // summary/факты/ветки и ExchangeLog не затрагиваются
        if (hooks.strategy == ContextStrategy.WINDOW) {
            sessions.applyWindow(session.id, hooks.windowSize)
        }

        // «Факты»: экстракция только при активной стратегии facts; сбой (исключение
        // или null) — прежние факты сохраняются, чат не падает
        if (hooks.strategy == ContextStrategy.FACTS && hooks.factExtractor != null) {
            val extracted = runCatching { hooks.factExtractor.extract(session.facts, body.message) }
                .onFailure { e ->
                    println(
                        "Факты сессии «${session.id}»: экстрактор не сработал (${e.message}); " +
                            "сохраняю прежние факты"
                    )
                }
                .getOrNull()
            if (extracted != null) {
                sessions.setFacts(session.id, extracted)
            }
        }
    }

    val elapsedMs = System.currentTimeMillis() - startedAt
    // Перечитывание store нужно только для «Фактов»: только экстрактор выше мог
    // обновить факты после снимка сессии (activateBranch предшествовал resolveChat)
    val currentFacts =
        if (session != null && hooks != null && hooks.strategy == ContextStrategy.FACTS) {
            sessions.get(session.id)?.facts ?: emptyList()
        } else {
            session?.facts ?: emptyList()
        }
    return if (body.judgeEnabled == true) {
        onStatus("Судья проверяет ответ…")
        response.copy(
            elapsedMs = elapsedMs,
            judge = runJudge(aiClient, body, response, elapsedMs),
            facts = currentFacts
        )
    } else {
        response.copy(elapsedMs = elapsedMs, facts = currentFacts)
    }
}

/**
 * Агентная обработка запроса: AgentLoop итерирует модель с инструментами до финального ответа.
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
 * (общий пост-блок finalizeResponse после ответа) в память не пишет.
 *
 * Сжатие истории — только при стратегии SUMMARY (legacy-флаг с конфиг-фолбэком):
 *  - merge выполняется СИНХРОННО до запуска цикла; сбой суммаризатора — деградация
 *    (лог, прежний summary, summaryTokens=null), агентный прогон не падает;
 *  - seed цикла = view по стратегии из планировщика контекста (SUMMARY — планView
 *    «сводка как system-сообщение + окно», WINDOW — окно, FACTS — блок фактов +
 *    окно 3, OFF/BRANCHING — полная история) + новый вопрос, затем СТАДИЯ-2
 *    SessionHistory.trim — последний этап; пары assistant(tool_calls) → role="tool"
 *    в seed не рвутся;
 *  - AgentLoop без изменений: сводка для цикла — обычные сообщения seed (system-
 *    промт агент добавляет сам); у провайдера type=opencode сводка вплетается во
 *    флаттененный prompt как «Системная инструкция: …» — флаттенинг не чинится;
 *  - токены суммаризатора в TokenSums агента не входят: они уходят отдельно в
 *    ChatResponse.summaryTokens, токены самого агента цикл суммирует как раньше.
 */
private suspend fun handleAgent(
    aiClient: LLMClient,
    body: ChatRequest,
    provider: ProviderConfig,
    session: Session?,
    sessions: SessionStore,
    config: AppConfig,
    compressor: HistoryCompressor,
    registry: ToolRegistry,
    onEvent: suspend (AgentEvent) -> Unit = {}
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    val loop = AgentLoop(
        client = aiClient,
        transport = if (provider.supportsTools) NativeToolTransport() else PromptToolTransport(),
        registry = registry
    )

    // 1) Синхронный merge до запуска агентного цикла — ТОЛЬКО при стратегии SUMMARY
    //    (legacy-флаг с конфиг-фолбэком): сбой суммаризатора —
    //    деградация, а не падение прогона.
    val strategy = ContextStrategy.resolve(
        body.contextStrategy,
        body.compressionEnabled ?: config.compressionEnabled,
        config.contextStrategy
    ) ?: ContextStrategy.OFF
    var mergeResult: MergeResult? = null
    var viewSession: Session? = session
    if (strategy == ContextStrategy.SUMMARY && session != null) {
        mergeResult = runCatching { compressor.merge(session.summary, session.messages) }
            .onFailure { e ->
                println(
                    "Компрессия истории сессии «${session.id}»: суммаризатор не сработал (${e.message}); " +
                        "продолжаю без обновления сводки"
                )
            }
            .getOrNull()
        if (mergeResult != null) {
            viewSession = sessions.applyCompressionResult(session.id, mergeResult.newSummary, mergeResult.removeCount)
                ?: session
        }
    }

    // 2) Seed цикла: вид по стратегии из планировщика (view — чистая функция) +
    //    новый вопрос, затем СТАДИЯ-2 SessionHistory.trim (последний этап).
    val seed = viewSession
        ?.let {
            SessionHistory.trim(
                view(strategy, it.summary, it.messages, it.facts, compressor.windowSize, compressor) +
                    OpenAIMessage(role = "user", content = body.message),
                body.maxTokens ?: 0
            )
        }
        ?: listOf(OpenAIMessage(role = "user", content = body.message))

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
        exchanges = ExchangeLog.since(startSeq),
        summaryTokens = mergeResult?.tokens,
        summaryApplied = strategy == ContextStrategy.SUMMARY && viewSession?.summary != null,
        contextStrategy = strategy.id,
        branchId = if (strategy == ContextStrategy.BRANCHING) session?.activeBranchId else null
    )
}

/**
 * Контрольная проверка готового ответа отдельным вызовом LLM («Судья»).
 * Судья получает вопрос, ответ и приборные замеры (время, токены) и оценивает
 * факты, качество, скорость и ресурсоёмкость. Срыв проверки не роняет основной
 * ответ — вместо заключения возвращается отчёт с указанием причины.
 */
private suspend fun runJudge(
    aiClient: LLMClient,
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
