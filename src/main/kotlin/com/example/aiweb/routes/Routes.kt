package com.example.aiweb.routes

import com.example.aiweb.agent.AgentLoop
import com.example.aiweb.auth.LoginGuard
import com.example.aiweb.auth.LoginResult
import com.example.aiweb.auth.TokenManager
import com.example.aiweb.auth.UserStore
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
import com.example.aiweb.invariants.GlobalInvariantStore
import com.example.aiweb.invariants.StoredInvariant
import com.example.aiweb.invariants.renderInvariantBlock
import com.example.aiweb.model.ActivateBranchRequest
import com.example.aiweb.model.AgentEvent
import com.example.aiweb.model.AuthErrorResponse
import com.example.aiweb.model.AuthTokenResponse
import com.example.aiweb.model.AuthUserView
import com.example.aiweb.model.BranchInfo
import com.example.aiweb.model.BranchesResponse
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.ContextStrategy
import com.example.aiweb.model.CreateUserRequest
import com.example.aiweb.model.ExchangeLogResponse
import com.example.aiweb.model.InvariantsPayload
import com.example.aiweb.model.JudgeReport
import com.example.aiweb.model.LoginRequest
import com.example.aiweb.model.MemoryCreateRequest
import com.example.aiweb.model.MemoryConfig
import com.example.aiweb.model.MemoryDeletedResponse
import com.example.aiweb.model.MemoryEntryResponse
import com.example.aiweb.model.MemoryEntryView
import com.example.aiweb.model.MemoryListResponse
import com.example.aiweb.model.MemoryLongTermOverview
import com.example.aiweb.model.MemoryOverviewResponse
import com.example.aiweb.model.MemoryShortTermOverview
import com.example.aiweb.model.MemoryUpdateRequest
import com.example.aiweb.model.MemoryWorkingOverview
import com.example.aiweb.model.ModelsResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.ProviderModels
import com.example.aiweb.model.SessionSummary
import com.example.aiweb.model.SessionsCleared
import com.example.aiweb.model.SessionsResponse
import com.example.aiweb.model.StreamEvent
import com.example.aiweb.model.TurnUsage
import com.example.aiweb.model.UserCreatedResponse
import com.example.aiweb.model.WorkingEntriesResponse
import com.example.aiweb.model.WorkingUpsertRequest
import com.example.aiweb.memory.PerUserLongTermMemory
import com.example.aiweb.memory.MemoryEntry
import com.example.aiweb.memory.MemoryLimits
import com.example.aiweb.memory.MemoryRetriever
import com.example.aiweb.memory.MemorySearchEngine
import com.example.aiweb.memory.WorkingEntry
import com.example.aiweb.memory.WorkingMemoryStore
import com.example.aiweb.prefs.PreferenceLimits
import com.example.aiweb.prefs.PreferencesStore
import com.example.aiweb.prefs.UserPreferences
import com.example.aiweb.prefs.renderPromptBlock
import com.example.aiweb.session.HistoryCompressor
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.MergeResult
import com.example.aiweb.session.PausedRun
import com.example.aiweb.session.Session
import com.example.aiweb.session.SessionHistory
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.sessionStoreFor
import com.example.aiweb.session.view
import com.example.aiweb.task.TaskStage
import com.example.aiweb.task.TaskState
import com.example.aiweb.task.TaskStateHeuristic
import com.example.aiweb.task.TaskStateMachine
import com.example.aiweb.task.TaskStateJson
import com.example.aiweb.task.renderTaskStateBlock
import com.example.aiweb.tools.MemoryRecallTool
import com.example.aiweb.tools.MemorySaveTool
import com.example.aiweb.tools.NativeToolTransport
import com.example.aiweb.tools.PromptToolTransport
import com.example.aiweb.tools.TaskNotesTool
import com.example.aiweb.tools.ToolRegistry
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    // Память агента: строится один раз и живёт между запросами. Базовый каталог —
    // тот же dataDir, что у хранилища сессий; без каталога данных (P0-режим)
    // память живёт во временном каталоге процесса (переживать рестарт не обязана).
    // Долговременная память per-user (задача 8): файлы memory/{userId}/longterm.json,
    // стор пользователя резолвится по userId из токена на уровне роутов
    val memoryBaseDir = config.dataDir?.takeIf { it.isNotBlank() }
        ?: Files.createTempDirectory("ai-memory").toString()
    val workingMemory = WorkingMemoryStore(memoryBaseDir)
    val longTermMemory = PerUserLongTermMemory(memoryBaseDir)
    val memory = AgentMemory(
        workingMemory,
        longTermMemory,
        config.memory
    )

    // Сироты рабочей памяти (файлы сессий, удалённых из хранилища) чистятся
    // при старте; SessionStore умеет перечислять сессии через list()
    workingMemory.pruneOrphans(sessions.list().map { it.id }.toSet())

    // Домен авторизации (задача 2): пользователи на диске, токены и кулдаун —
    // только в памяти процесса. Единственные экземпляры на процесс — здесь,
    // в замыкании aiRoutes (без DI): рестарт сервера сбрасывает входы.
    val users = UserStore(memoryBaseDir)
    val tokens = TokenManager()
    val loginGuard = LoginGuard()

    // Предпочтения пользователей (задачи 4 и 7): по файлу на пользователя в
    // preferences/{userId}.json; отсутствие файла — дефолтные значения.
    // Единственный экземпляр на процесс; инъекция блока в seed каждого запроса
    // (задача 12) берёт предпочтения отсюда — preferences.get(userId)
    val preferences = PreferencesStore(memoryBaseDir)

    // Инварианты (задача 6 плана assistant-invariants): один файл-стор на весь
    // деплой — <dir>/invariants.json в том же каталоге данных (memoryBaseDir /
    // AI_DATA_DIR), что и остальные сторы. Единственный экземпляр на процесс:
    // инъекция блока в seed (handleAgent, 4-й хвостовой слот) и эхо
    // ChatResponse.invariants (finalizeResponse) читают отсюда; chat-команды и
    // API-роуты инвариантов (задачи 7-8) переиспользуют этот же экземпляр
    val invariantStore = GlobalInvariantStore(memoryBaseDir)

    // ЦЕНТРАЛЬНАЯ защита роутов (choke point, задача 6): единый intercept для
    // всей поверхности /api*. Токен НЕ проверяется вручную ни в одном
    // route-блоке. Открытые роуты (whitelist, зеркально specs/user-auth/spec.md):
    //
    // | Роут                        | Метод  | Диспозиция                              |
    // |-----------------------------|--------|-----------------------------------------|
    // | /api/auth/login             | POST   | open                                    |
    // | /api/auth/users             | GET    | open (список пользователей)             |
    // | /api/auth/users             | POST   | open (создание пользователя)            |
    // | статика / и /static/*       | GET    | open (маршрутизируются вне aiRoutes)    |
    //
    // Всё остальное под /api/* — protected/user-scoped, по таблице disposition:
    // | #  | Роут                              | Метод  | Диспозиция                          |
    // |----|-----------------------------------|--------|-------------------------------------|
    // | 1  | /api/log                          | GET    | user-scoped (свои записи)           |
    // | 2  | /api/models                       | GET    | protected (общий справочник)        |
    // | 3  | /api/chat                         | POST   | user-scoped                         |
    // | 4  | /api/chat/stream                  | POST   | user-scoped (SSE)                   |
    // | 5  | /api/cancel                       | POST   | protected                           |
    // | 6  | /api/sessions                     | GET    | user-scoped                         |
    // | 7  | /api/sessions                     | DELETE | user-scoped                         |
    // | 8  | /api/session/{id}                 | GET    | user-scoped                         |
    // | 9  | /api/session/{id}                 | DELETE | user-scoped                         |
    // | 10 | /api/session/{id}/branches        | GET    | user-scoped                         |
    // | 11 | /api/session/{id}/branches        | POST   | user-scoped                         |
    // | 12 | /api/session/{id}/branches/activate | POST | user-scoped                         |
    // | 13 | /api/session/{id}/working         | GET    | user-scoped                         |
    // | 14 | /api/session/{id}/working         | PUT    | user-scoped                         |
    // | 15 | /api/session/{id}/working         | DELETE | user-scoped                         |
    // | 16 | /api/session/{id}/working/{entryId} | DELETE | user-scoped                       |
    // | 17 | /api/memory                       | GET    | user-scoped                         |
    // | 18 | /api/memory                       | POST   | user-scoped                         |
    // | 19 | /api/memory/{id}                  | PUT    | user-scoped                         |
    // | 20 | /api/memory/{id}                  | DELETE | user-scoped                         |
    // | 21 | /api/memory/overview              | GET    | user-scoped                         |
    // | 22 | /api/files/{name}                 | GET    | protected (общая песочница)         |
    // | 23 | /api/preferences                  | GET    | user-scoped (свои предпочтения)     |
    // | 24 | /api/preferences                  | PUT    | user-scoped (свои предпочтения)     |
    // | 25 | /api/task/pause                   | POST   | user-scoped (своя сессия)           |
    // | 26 | /api/task/resume                  | POST   | user-scoped (своя сессия)           |
    // | 27 | /api/invariants                   | GET    | user-scoped (своя сессия)           |
    // | 28 | /api/invariants/global            | POST   | protected (общий справочник)        |
    // | 29 | /api/invariants/global/{id}       | DELETE | protected                           |
    // | 30 | /api/invariants/session           | POST   | user-scoped (своя сессия)           |
    // | 31 | /api/invariants/session/{sessionId}/{id} | DELETE | user-scoped (своя сессия)    |
    // | +  | /api/auth/users/{id}              | DELETE | protected (нужен валидный токен)    |
    //
    // user-scoped означает: ресурс адресуется только в границах своего userId
    // (сессии/ветки/рабочая память — по владельцу сессии); чужой id и legacy-сессия
    // без владельца отвечают 404, неотличимо от несуществующего.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val method = call.request.httpMethod
        // Статика и `/` приходят сюда только потому, что intercept стоит на узле
        // routing рядом с ними; все не-/api пути открыты по disposition
        val open = !path.startsWith("/api/") ||
            (method == HttpMethod.Post && path == "/api/auth/login") ||
            (path == "/api/auth/users" && (method == HttpMethod.Get || method == HttpMethod.Post))
        if (open) return@intercept

        val userId = call.request.headers["X-Auth-Token"]?.let { tokens.resolve(it) }
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("Требуется вход"))
            finish()
        } else {
            call.attributes.put(UserIdKey, userId)
        }
    }

    // POST /api/auth/login — вход по ПИН: кулдаун 429, неверный ПИН/юзер 401,
    // успех — токен (AuthTokenResponse). Неудачи неизвестных id тоже учитываются
    // guard'ом: ответ не раскрывает, существует ли пользователь
    post("/api/auth/login") {
        val request = try {
            call.receive<LoginRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Некорректный запрос."))
            return@post
        }
        val userId = request.userId.trim()
        loginGuard.check(userId)?.let { cooldown ->
            call.respond(HttpStatusCode.TooManyRequests, AuthErrorResponse(cooldown.message))
            return@post
        }
        val result = users.findById(userId)
            ?.let { account -> loginGuard.verify(account, request.pin) }
            ?: run {
                loginGuard.recordFailure(userId)
                LoginResult.InvalidCredentials
            }
        when (result) {
            is LoginResult.Success -> call.respond(
                AuthTokenResponse(
                    token = tokens.issue(result.userId),
                    user = users.findById(result.userId)?.let { AuthUserView(it.id, it.name) }
                        ?: AuthUserView(result.userId, "")
                )
            )
            LoginResult.InvalidCredentials ->
                call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("Неверный ПИН или пользователь"))
            is LoginResult.Locked ->
                call.respond(HttpStatusCode.TooManyRequests, AuthErrorResponse(result.cooldown.message))
        }
    }

    // GET /api/auth/users — список пользователей для экрана входа (открытый роут):
    // только id и имя, без хешей и ПИНов
    get("/api/auth/users") {
        call.respond(users.list().map { AuthUserView(id = it.id, name = it.name) })
    }

    // POST /api/auth/users — создание пользователя (открытый роут): валидация
    // домена (русский текст с полем → 400), затем мгновенный автовход — токен
    // в ответе UserCreatedResponse. Дубликаты имён разрешены (различают ПИНЫ)
    post("/api/auth/users") {
        val request = try {
            call.receive<CreateUserRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Некорректный запрос."))
            return@post
        }
        val account = try {
            users.create(request.name, request.pin)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse(e.message ?: "Некорректные данные."))
            return@post
        }
        call.respond(UserCreatedResponse(token = tokens.issue(account.id), user = AuthUserView(account.id, account.name)))
    }

    // DELETE /api/auth/users/{id} — удаление пользователя с каскадом его данных
    // (сессии с рабочей памятью, предпочтения, каталог долговременной памяти,
    // токены). Единственный оставшийся пользователь не удаляется (400).
    // Неизвестный id — 404 без деталей
    delete("/api/auth/users/{id}") {
        val userId = call.parameters["id"] ?: ""
        if (users.findById(userId) == null) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Пользователь не найден."))
            return@delete
        }
        if (users.list().size <= 1) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse("Нельзя удалить единственного пользователя")
            )
            return@delete
        }
        cascadeDeleteUserData(userId, sessions, workingMemory, tokens, users, memoryBaseDir)
        call.respond(HttpStatusCode.NoContent)
    }

    // GET /api/preferences — предпочтения текущего пользователя (опросник из 7
    // вопросов, задача 4). Файла ещё нет (визард не проходили) — дефолтные
    // значения, а не 404: прерванный визард обычным состоянием является
    get("/api/preferences") {
        val userId = requireNotNull(call.attributes.getOrNull(UserIdKey)) {
            "Intercept обязан выставить UserIdKey для защищённого маршрута"
        }
        call.respond(preferences.get(userId))
    }

    // PUT /api/preferences — сохранить ответы опросника: серверная валидация
    // задачи 4 (enum-словари, лимиты 255/1000/1000; нарушение — 400 с русским
    // текстом, называющим поле), затем атомарное сохранение. Ответ —
    // сохранённый (санитизированный) объект. Дефолты полей допускают
    // частичный JSON: отсутствующие поля заменяются значениями по умолчанию
    put("/api/preferences") {
        val request = try {
            call.receive<UserPreferences>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Некорректный запрос."))
            return@put
        }
        val valid = try {
            PreferenceLimits.validate(request)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse(e.message ?: "Некорректные данные."))
            return@put
        }
        val userId = requireNotNull(call.attributes.getOrNull(UserIdKey)) {
            "Intercept обязан выставить UserIdKey для защищённого маршрута"
        }
        preferences.save(userId, valid)
        call.respond(valid)
    }

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

    // GET /api/log — сырой протокол обменов с моделью (самописец): только записи
    // текущего пользователя (userId из токена; записи без владельца не видны никому)
    get("/api/log") {
        val userId = call.attributes.getOrNull(UserIdKey)
        call.respond(ExchangeLogResponse(entries = ExchangeLog.forUser(userId)))
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
        val userId = currentUserId(call)
        if (!call.activateRequestedBranch(sessions, body, config, userId)) return@post
        val startedAt = System.currentTimeMillis()
        val chat = resolveChat(config, clients, sessions, body, userId) ?: run {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@post
        }

        // Точные команды «пауза»/«продолжить» перехватываются ДО вызова LLM (Q8):
        // мутация состояния + системный ответ; всё остальное — обычный путь
        handleTaskCommand(chat.session, sessions, invariantStore, body.message, startedAt)?.let { commandReply ->
            call.respond(commandReply)
            return@post
        }

        // Chat-команды инвариантов (задача 7) — тоже до LLM: список/добавление/
        // удаление сессионных инвариантов системным ответом с echo обоих скоупов
        handleInvariantCommand(chat.session, sessions, invariantStore, body.message, startedAt)?.let { commandReply ->
            call.respond(commandReply)
            return@post
        }

        // Пре-создание задачи (пауза во время первого полёта): интент пользователя —
        // авторитетный сигнал, ответ модели не нужен; PAUSED не трогаем. Дальнейшее
        // продвижение — как раньше, в finalizeResponse (paused-wins guard сохранён)
        chat.session?.let { s ->
            val ensured = TaskStateHeuristic.ensureForRequest(body.message, sessions.getTaskState(s.id))
            if (ensured != null) {
                sessions.updateTaskState(s.id, ensured)
            }
            // Checkpoint прерванного паузой прогона: блокирующий fallback-путь НЕ
            // инъектирует resumeContext (не раздуваем одиночный вызов парами прерванного
            // прогона) — checkpoint здесь только потребляется. Правила те же, что у
            // стрима: ensured != null (новая задача по интенту) — устаревший checkpoint
            // выбрасывается; checkpoint при PAUSED не трогается (переждёт до «Продолжить»).
            if (sessions.getTaskState(s.id)?.stage != TaskStage.PAUSED) {
                consumePausedRunCheckpoint(sessions, s, ensured, inject = false)
            }
        }

        try {
            val response = withContext(ExchangeLog.userContext(userId)) {
                handleAgent(
                    chat.aiClient, body, chat.provider, chat.session,
                    sessions, config, compressors.getValue(chat.provider), registry, memory,
                    preferences, invariantStore, userId
                )
            }

            call.respond(
                withContext(ExchangeLog.userContext(userId)) {
                    finalizeResponse(
                        chat.aiClient, body, response, chat.session, sessions, invariantStore, startedAt,
                        hooks = contextHooks(chat.provider, body)
                    )
                }
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
        val userId = currentUserId(call)
        if (!call.activateRequestedBranch(sessions, body, config, userId)) return@post
        val startedAt = System.currentTimeMillis()
        val chat = resolveChat(config, clients, sessions, body, userId) ?: run {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@post
        }

        // Точные команды «пауза»/«продолжить» — до LLM; системный ответ уходит
        // одним кадром Final того же SSE-протокола (без processor/activeRequests)
        handleTaskCommand(chat.session, sessions, invariantStore, body.message, startedAt)?.let { commandReply ->
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                write(encodeFrame(StreamEvent.Final(commandReply)))
                flush()
            }
            return@post
        }

        // Chat-команды инвариантов (задача 7) — до LLM; тот же SSE-кадр Final,
        // что у паузы/продолжения
        handleInvariantCommand(chat.session, sessions, invariantStore, body.message, startedAt)?.let { commandReply ->
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                write(encodeFrame(StreamEvent.Final(commandReply)))
                flush()
            }
            return@post
        }

        // Пре-создание задачи (пауза во время первого полёта): интент пользователя —
        // авторитетный сигнал, ответ модели не нужен; PAUSED не трогаем. Дальнейшее
        // продвижение — как раньше, в finalizeResponse (paused-wins guard сохранён)
        val ensuredTaskState = chat.session?.let { s ->
            TaskStateHeuristic.ensureForRequest(body.message, sessions.getTaskState(s.id))?.also { ensured ->
                sessions.updateTaskState(s.id, ensured)
            }
        }
        // id сессии для раннего кадра: на первом ходе клиент ещё не знает id —
        // без него клик паузы в полёте ушёл бы в 400
        val ensuredSessionId = chat.session?.id

        // Инъекция checkpoint прерванного паузой прогона (PausedRun): ensured != null —
        // по интенту заведена НОВАЯ задача, устаревший checkpoint выбрасывается; иначе
        // checkpoint с ненулевой дельтой потребляется однократно как resumeContext
        // (не на паузе — на PAUSED checkpoint пережидает до «Продолжить»)
        val resumeContext: List<OpenAIMessage>? = chat.session
            ?.takeIf { sessions.getTaskState(it.id)?.stage != TaskStage.PAUSED }
            ?.let { s -> consumePausedRunCheckpoint(sessions, s, ensuredTaskState, inject = true) }

        val requestId = body.requestId?.trim().orEmpty()
        val frames = Channel<StreamEvent>(Channel.UNLIMITED)
        // Флаг «отмена вызвана паузой»: /api/cancel с saveContext=true выставляет его
        // ДО отмены Job, onCancelled агента читает и сохраняет checkpoint
        val saveOnCancel = AtomicBoolean(false)

        try {
            if (requestId.isNotEmpty()) {
                activeRequests.register(requestId, currentCoroutineContext().job, saveOnCancel)
            }

            // Ранний кадр несёт taskState и sessionId до первого события агента —
            // фронтенд включает «Паузу» на время генерации и узнаёт id сессии уже
            // при первом ходе (клик паузы без id ушёл бы в 400). Кадр уходит в канал
            // до запуска processor (канал UNLIMITED) — порядок кадров сохранится:
            // ранний статус → agent-кадры → final
            ensuredTaskState?.let {
                frames.trySend(StreamEvent.Status("Задача заведена: планирование", it, ensuredSessionId))
            }

            // Обработка запроса идёт в дочерней корутине и наполняет канал кадрами;
            // писатель ниже параллельно вычитывает их в HTTP-поток (живой стрим)
            val processor = launch {
                try {
                    val response = withContext(ExchangeLog.userContext(userId)) {
                        handleAgent(
                            chat.aiClient, body, chat.provider, chat.session,
                            sessions, config, compressors.getValue(chat.provider), registry, memory,
                            preferences, invariantStore, userId,
                            onEvent = { event ->
                                frames.trySend(StreamEvent.Agent(event))
                            },
                            onCancelled = { delta ->
                                // Отмена прогона с паузой: checkpoint дельты в сессию.
                                // Callback зовётся из coroutine-цикла (внутри NonCancellable),
                                // sessions потокобезопасен. Дельта без сессии не бывает
                                // (цикл есть только у сессии), но guard не мешает
                                if (saveOnCancel.get()) {
                                    ensuredSessionId?.let { sid ->
                                        sessions.savePausedRun(
                                            sid,
                                            PausedRun(goal = body.message, messages = delta)
                                        )
                                    }
                                }
                            },
                            resumeContext = resumeContext
                        )
                    }
                    val finalized = withContext(ExchangeLog.userContext(userId)) {
                        finalizeResponse(
                            chat.aiClient, body, response, chat.session, sessions, invariantStore, startedAt,
                            hooks = contextHooks(chat.provider, body)
                        ) { status ->
                            frames.trySend(StreamEvent.Status(status))
                        }
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
    // {"requestId": "...", "saveContext": false} → {"cancelled": true|false};
    // saveContext опционален (default false): true присылает фронтенд, когда отмена
    // вызвана ПАУЗОЙ (не «Стоп») — флаг выставляется до отмены Job и checkpoint
    // дельты прогона сохранится в onCancelled агента. Неизвестный или уже
    // завершённый идентификатор — {"cancelled": false}
    post("/api/cancel") {
        val payload = try {
            call.receive<JsonObject>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        val requestId = payload?.get("requestId")?.jsonPrimitive?.contentOrNull
        val saveContext = payload?.get("saveContext")?.jsonPrimitive?.booleanOrNull ?: false

        if (requestId == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("cancelled", false) })
            return@post
        }

        call.respond(buildJsonObject { put("cancelled", activeRequests.cancel(requestId, saveContext)) })
    }

    // POST /api/task/pause — пауза задачи сессии (FSM, задача 5): {"sessionId": "..."}
    // → {"taskState": <состояние|null>, "resumeRun": <true|false>}.
    // Неизвестная или чужая сессия — 404.
    // Идемпотентно: пауза при null-состоянии и повторная пауза — no-op без ошибки,
    // состояние не портится; ответ — актуальное состояние (null, если задачи нет)
    post("/api/task/pause") {
        val sessionId = call.receiveSessionId() ?: return@post
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(currentUserId(call))) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@post
        }
        val next = taskStateMachine.pause(sessions.getTaskState(sessionId))
        if (next != null) sessions.updateTaskState(sessionId, next)
        call.respond(taskStateResponse(sessions.getTaskState(sessionId), sessions.getPausedRun(sessionId) != null))
    }

    // POST /api/task/resume — продолжение задачи после паузы: {"sessionId": "..."}
    // → {"taskState": <состояние|null>, "resumeRun": <true|false>}.
    // Неизвестная или чужая сессия — 404.
    // Идемпотентно: resume не на паузе (включая null-состояние) — no-op без ошибки
    post("/api/task/resume") {
        val sessionId = call.receiveSessionId() ?: return@post
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(currentUserId(call))) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@post
        }
        val next = taskStateMachine.resume(sessions.getTaskState(sessionId))
        if (next != null) sessions.updateTaskState(sessionId, next)
        call.respond(taskStateResponse(sessions.getTaskState(sessionId), sessions.getPausedRun(sessionId) != null))
    }

    // === Инварианты (задача 8 плана assistant-invariants). Контракт — KDoc
    // InvariantsPayload (Models.kt) и вызовы frontend/src/api/client.ts:
    // все пять роутов за токен-интерцептом, ошибки единым {"error": "…"}
    // (AuthErrorResponse), ответы собираются явно — серверный Json с
    // encodeDefaults=false omitил бы пустые списки, а фронтенд читает
    // global/session как обязательные ключи.

    // GET /api/invariants?sessionId=X — оба скоупа одним пейлоадом. Без
    // sessionId — 400; неизвестная/чужая сессия — 404; валидная сессия —
    // 200 с обоими ключами (пустые списки — тоже)
    get("/api/invariants") {
        val sessionId = call.request.queryParameters["sessionId"]?.takeIf { it.isNotBlank() }
        if (sessionId == null) {
            call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("Укажите sessionId сессии."))
            return@get
        }
        val userId = currentUserId(call)
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Сессия не найдена."))
            return@get
        }
        call.respond(
            buildJsonObject {
                put("global", invariantListJson(invariantStore.list()))
                put("session", invariantListJson(session.invariants))
            }
        )
    }

    // POST /api/invariants/global — {"text": "…"}: добавить глобальный
    // инвариант. Пустой/длинный текст, точный дубль и кап 20 —
    // IllegalArgumentException стора → 400 с русским сообщением;
    // успех — 200 {global: обновлённый список}
    post("/api/invariants/global") {
        val text = call.receiveInvariantText() ?: return@post
        val updated = try {
            invariantStore.add(text)
            invariantStore.list()
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse(e.message ?: "Некорректный текст инварианта.")
            )
            return@post
        }
        call.respond(buildJsonObject { put("global", invariantListJson(updated)) })
    }

    // DELETE /api/invariants/global/{id} — убрать глобальный инвариант.
    // Неизвестный или unsafe (traversal) id — 404, чужие данные не
    // раскрываются; успех — 200 {global} (конвенция /api/memory/{id})
    delete("/api/invariants/global/{id}") {
        val id = call.parameters["id"] ?: ""
        if (!isSafeInvariantSegment(id) || !invariantStore.remove(id)) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Инвариант не найден."))
            return@delete
        }
        call.respond(buildJsonObject { put("global", invariantListJson(invariantStore.list())) })
    }

    // POST /api/invariants/session — {"sessionId": "…", "text": "…"}: добавить
    // инвариант сессии. Неизвестная/чужая сессия — 404; валидация/дубль/кап —
    // 400 (сообщения клонированы из GlobalInvariantStore.add, без DRY-сливания
    // с глобальным стором); успех — 200 {session: обновлённый список}
    post("/api/invariants/session") {
        val (sessionId, text) = call.receiveInvariantSessionBody() ?: return@post
        val userId = currentUserId(call)
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Сессия не найдена."))
            return@post
        }
        val updated = try {
            val trimmed = StoredInvariant.normalizeAndValidate(text)
            val current = session.invariants
            require(current.none { it.text == trimmed }) {
                "Инвариант: инвариант с таким текстом уже существует"
            }
            require(current.size < StoredInvariant.MAX_COUNT) {
                "Инвариант: нельзя добавить больше ${StoredInvariant.MAX_COUNT}"
            }
            (current + StoredInvariant.create(trimmed)).also {
                sessions.updateInvariants(sessionId, it)
            }
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse(e.message ?: "Некорректный текст инварианта.")
            )
            return@post
        }
        call.respond(buildJsonObject { put("session", invariantListJson(updated)) })
    }

    // DELETE /api/invariants/session/{sessionId}/{id} — убрать инвариант
    // сессии. Неизвестная/чужая сессия, неизвестный или unsafe id — 404;
    // успех — 200 {session}
    delete("/api/invariants/session/{sessionId}/{id}") {
        val sessionId = call.parameters["sessionId"] ?: ""
        val id = call.parameters["id"] ?: ""
        if (!isSafeInvariantSegment(sessionId) || !isSafeInvariantSegment(id)) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Инвариант не найден."))
            return@delete
        }
        val userId = currentUserId(call)
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Сессия не найдена."))
            return@delete
        }
        val updated = session.invariants.filterNot { it.id == id }
        if (updated.size == session.invariants.size) {
            call.respond(HttpStatusCode.NotFound, AuthErrorResponse("Инвариант не найден."))
            return@delete
        }
        sessions.updateInvariants(sessionId, updated)
        call.respond(buildJsonObject { put("session", invariantListJson(updated)) })
    }

    // GET /api/sessions — список сессий ТЕКУЩЕГО пользователя для панели справа,
    // самые свежие первыми (изоляция per-user: чужие и legacy-сессии не видны)
    get("/api/sessions") {
        val summaries = sessions.listForUser(currentUserId(call)).map { session ->
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
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
            // Чужая сессия неотличима от несуществующей: 404 без деталей
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(session)
        }
    }

    // GET /api/session/{id}/branches — плоский список веток (задача 6): первой
    // запись живого активного состояния (active=true), затем снимки веток в порядке
    // создания. Неизвестная сессия — 404
    get("/api/session/{id}/branches") {
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
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
        val userId = currentUserId(call)
        val sessionId = call.parameters["id"] ?: ""
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(userId)) {
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
        val userId = currentUserId(call)
        val sessionId = call.parameters["id"] ?: ""
        val session = sessions.get(sessionId)
        if (session == null || session.foreignTo(userId)) {
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

    // DELETE /api/session/{id} — сброс диалога: сессия удаляется (идемпотентно);
    // вместе с сессией стирается её рабочая память
    delete("/api/session/{id}") {
        val userId = currentUserId(call)
        val id = call.parameters["id"] ?: ""
        // Идемпотентно для своих и несуществующих; чужая (и legacy без владельца)
        // сессия не удаляется и не раскрывается: 404
        val session = sessions.get(id)
        if (session != null && session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@delete
        }
        sessions.delete(id)
        workingMemory.clear(id)
        call.respond(HttpStatusCode.NoContent)
    }

    // DELETE /api/sessions — стирает все сессии ТЕКУЩЕГО пользователя (изоляция
    // per-user): сессии других владельцев и legacy-сессии без владельца не трогаются
    delete("/api/sessions") {
        call.respond(SessionsCleared(deleted = sessions.deleteAllForUser(currentUserId(call))))
    }

    // --- Память агента: HTTP-маршруты ---

    // GET /api/session/{id}/working — записи рабочей памяти сессии.
    // Неизвестная сессия — 404
    get("/api/session/{id}/working") {
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@get
        }
        call.respond(WorkingEntriesResponse(workingMemory.entries(session.id)))
    }

    // PUT /api/session/{id}/working — добавить или заменить заметку рабочей
    // памяти (id null → UUID, createdAt = now). Ответ — актуальный список записей.
    // Неизвестная сессия — 404, пустые заголовок/текст — 400, капы хранилища —
    // 400 с сообщением хранилища
    put("/api/session/{id}/working") {
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@put
        }
        val request = try {
            call.receive<WorkingUpsertRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Некорректный запрос."))
            return@put
        }
        if (request.title.isBlank() || request.content.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Заголовок и текст заметки не могут быть пустыми.")
            )
            return@put
        }
        try {
            val entries = workingMemory.upsert(
                session.id,
                WorkingEntry(
                    id = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    title = request.title,
                    content = request.content,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = 0L
                )
            )
            call.respond(WorkingEntriesResponse(entries))
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse(e.message ?: "Не удалось сохранить заметку.")
            )
        }
    }

    // DELETE /api/session/{id}/working/{entryId} — удалить заметку по id
    // (неизвестный id — без ошибки). Ответ — остаток записей. Неизвестная сессия — 404
    delete("/api/session/{id}/working/{entryId}") {
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@delete
        }
        val entries = workingMemory.remove(session.id, call.parameters["entryId"] ?: "")
        call.respond(WorkingEntriesResponse(entries))
    }

    // DELETE /api/session/{id}/working — стереть всю рабочую память сессии.
    // Неизвестная сессия — 404
    delete("/api/session/{id}/working") {
        val userId = currentUserId(call)
        val session = sessions.get(call.parameters["id"] ?: "")
        if (session == null || session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@delete
        }
        workingMemory.clear(session.id)
        call.respond(WorkingEntriesResponse(emptyList()))
    }

    // GET /api/memory?kind=&q= — записи долговременной памяти (свежие первыми)
    // с фильтром по виду и/или подстроке; счётчики по видам — всегда полные.
    // Неизвестный вид в query — 400. Записи и счётчики — память текущего
    // пользователя (userId из токена)
    get("/api/memory") {
        val long = longTermMemory.forUser(currentUserId(call))
        val kind = call.request.queryParameters["kind"]?.takeIf { it.isNotBlank() }
        if (kind != null && kind !in MemoryLimits.KINDS) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse(
                    "Неизвестный вид памяти '$kind' " +
                        "(допустимы: ${MemoryLimits.KINDS.joinToString(", ")})"
                )
            )
            return@get
        }
        val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
        call.respond(
            MemoryListResponse(
                entries = long.list(kind, q).map { it.toView() },
                counts = long.counts()
            )
        )
    }

    // POST /api/memory — новая запись долговременной памяти (источник "user").
    // Неизвестный вид — 400, пустые заголовок/текст — 400, лимиты хранилища —
    // 400 с сообщением хранилища
    post("/api/memory") {
        val long = longTermMemory.forUser(currentUserId(call))
        val request = try {
            call.receive<MemoryCreateRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Некорректный запрос."))
            return@post
        }
        if (request.kind !in MemoryLimits.KINDS) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Вид записи должен быть одним из: profile, decision, knowledge")
            )
            return@post
        }
        if (request.title.isBlank() || request.content.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Заголовок и текст записи не могут быть пустыми.")
            )
            return@post
        }
        val now = System.currentTimeMillis()
        try {
            val entry = long.add(
                MemoryEntry(
                    id = UUID.randomUUID().toString(),
                    kind = request.kind,
                    title = request.title,
                    content = request.content,
                    tags = request.tags.orEmpty(),
                    source = "user",
                    createdAt = now,
                    updatedAt = now
                )
            )
            call.respond(MemoryEntryResponse(entry.toView()))
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse(e.message ?: "Не удалось сохранить запись.")
            )
        }
    }

    // PUT /api/memory/{id} — частичное обновление записи (null-поля остаются
    // прежними, updatedAt проставляет хранилище). Неизвестный id — 404,
    // нарушения лимитов — 400
    put("/api/memory/{id}") {
        val long = longTermMemory.forUser(currentUserId(call))
        val request = try {
            call.receive<MemoryUpdateRequest>()
        } catch (e: Exception) {
            println("Ошибка десериализации запроса: ${e.message}")
            null
        }
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Некорректный запрос."))
            return@put
        }
        if (request.kind != null && request.kind !in MemoryLimits.KINDS) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse("Вид записи должен быть одним из: profile, decision, knowledge")
            )
            return@put
        }
        if (request.title != null && request.title.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Заголовок не может быть пустым."))
            return@put
        }
        if (request.content != null && request.content.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse("Текст записи не может быть пустым."))
            return@put
        }
        try {
            val updated = long.update(call.parameters["id"] ?: "") { existing ->
                existing.copy(
                    kind = request.kind ?: existing.kind,
                    title = request.title ?: existing.title,
                    content = request.content ?: existing.content,
                    tags = request.tags ?: existing.tags
                )
            }
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ChatResponse("Запись не найдена."))
            } else {
                call.respond(MemoryEntryResponse(updated.toView()))
            }
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse(e.message ?: "Не удалось обновить запись.")
            )
        }
    }

    // DELETE /api/memory/{id} — удалить запись памяти текущего пользователя;
    // {"deleted": true} либо 404, когда записи нет: чужая запись неотличима
    // от неизвестного id, существование чужих данных не раскрывается
    delete("/api/memory/{id}") {
        val long = longTermMemory.forUser(currentUserId(call))
        val deleted = long.delete(call.parameters["id"] ?: "")
        if (deleted) {
            call.respond(MemoryDeletedResponse(deleted = true))
        } else {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Запись не найдена."))
        }
    }

    // GET /api/memory/overview?sessionId= — сводка всех слоёв памяти для панели
    // памяти. Неизвестная сессия НЕ ошибка (панель открывают и для свежей
    // сессии): turns/working — нули, долговременные счётчики — по хранилищу.
    // Чужая (или legacy без владельца) сессия — 404: счётчики чужой памяти
    // неотличимы от несуществующей сессии
    get("/api/memory/overview") {
        val userId = currentUserId(call)
        val sessionId = call.request.queryParameters["sessionId"]?.takeIf { it.isNotBlank() }
        val session = sessionId?.let { sessions.get(it) }
        if (session != null && session.foreignTo(userId)) {
            call.respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
            return@get
        }
        val counts = longTermMemory.forUser(currentUserId(call)).counts()
        call.respond(
            MemoryOverviewResponse(
                shortTerm = MemoryShortTermOverview(
                    turns = session?.messages?.size ?: 0,
                    hasSummary = session?.summary != null
                ),
                working = MemoryWorkingOverview(
                    entries = sessionId?.let { workingMemory.entries(it) }?.size ?: 0
                ),
                longTerm = MemoryLongTermOverview(
                    total = counts.values.sum(),
                    profile = counts["profile"] ?: 0,
                    decision = counts["decision"] ?: 0,
                    knowledge = counts["knowledge"] ?: 0
                )
            )
        )
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

/**
 * Идентификатор пользователя, проверенный intercept'ом по X-Auth-Token:
 * кладётся в атрибуты вызова, читается роутами (call.attributes.getOrNull(UserIdKey)).
 */
internal val UserIdKey = AttributeKey<String>("shturman.userId")

/**
 * Владелец запроса к защищённому маршруту: инвариант intercept'а — атрибут
 * [UserIdKey] выставлен всегда, ручной проверки токена здесь нет и быть не должно.
 */
private fun currentUserId(call: ApplicationCall): String =
    requireNotNull(call.attributes.getOrNull(UserIdKey)) {
        "Intercept обязан выставить UserIdKey для защищённого маршрута"
    }

/**
 * Сессия чужая для владельца запроса: принадлежит другому пользователю или
 * никому (legacy, userId == null; атрибутирует её только миграция). Кросс-доступ
 * отвечает 404, не 403 (specs/user-isolation/spec.md): чужая сессия неотличима
 * от несуществующей.
 */
private fun Session.foreignTo(userId: String): Boolean = this.userId != userId

/**
 * Каскадное удаление всех данных пользователя (DELETE /api/auth/users/{id}):
 *  1. отзыв всех его токенов — живые клиенты разлогиниваются первым же запросом;
 *  2. его сессии из хранилища вместе с файлами рабочей памяти каждой
 *     (deleteAllForUser затрагивает только сессии этого userId, legacy-сессии
 *     без userId не трогаются);
 *  3. файл предпочтений preferences/{userId}.json и каталог долговременной
 *     памяти memory/{userId}/ (Task 8 раскладывает LTM per-user);
 *  4. файл пользователя — последним: сбой на середине каскада оставляет
 *     аккаунт живым, и DELETE можно повторить.
 * userId прошёл проверку findById в вызывающем маршруте — безопасен для пути.
 */
private fun cascadeDeleteUserData(
    userId: String,
    sessions: SessionStore,
    workingMemory: WorkingMemoryStore,
    tokens: TokenManager,
    users: UserStore,
    baseDir: String
) {
    tokens.revokeAllFor(userId)
    val userSessionIds = sessions.listForUser(userId).map { it.id }
    sessions.deleteAllForUser(userId)
    userSessionIds.forEach { workingMemory.clear(it) }
    File(baseDir, "preferences").resolve("$userId.json").delete()
    File(baseDir, "memory").resolve(userId).deleteRecursively()
    users.delete(userId)
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
    config: AppConfig,
    userId: String
): Boolean {
    if (body.branchId == null) return true
    val strategy = ContextStrategy.resolve(
        body.contextStrategy,
        body.compressionEnabled ?: config.compressionEnabled,
        config.contextStrategy
    ) ?: ContextStrategy.OFF
    if (strategy != ContextStrategy.BRANCHING) return true

    val session = sessions.get(body.sessionId) ?: return true
    if (session.foreignTo(userId)) {
        // В чужую сессию ветку не переключаем и её существование не раскрываем
        respond(HttpStatusCode.NotFound, ChatResponse("Сессия не найдена."))
        return false
    }
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
 * Компоненты памяти агента, построенные один раз в aiRoutes и живущие между
 * запросами: рабочая память сессий, per-user долговременная память и
 * ретриверы блока памяти (по одному на пользователя, кешируются по userId).
 */
private class AgentMemory(
    val working: WorkingMemoryStore,
    val long: PerUserLongTermMemory,
    /** Настройки поиска памяти — для поискового движка каждого пользователя. */
    private val memoryConfig: MemoryConfig?
) {

    private val retrievers = ConcurrentHashMap<String, MemoryRetriever>()

    /** Ретривер блока памяти пользователя: стор и поисковый движок per-user. */
    fun retrieverFor(userId: String): MemoryRetriever {
        val store = long.forUser(userId)
        return retrievers.computeIfAbsent(userId) {
            MemoryRetriever(working, store, MemorySearchEngine.forConfig(memoryConfig, store))
        }
    }
}

/**
 * Запись долговременной памяти в HTTP-ответ: MemoryEntry с encodeDefaults=false
 * omitил бы source="user" и пустые tags — вид [MemoryEntryView] кодирует их всегда.
 */
private fun MemoryEntry.toView(): MemoryEntryView = MemoryEntryView(
    id = id,
    kind = kind,
    title = title,
    content = content,
    tags = tags,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt
)

/**
 * Общий препроцессинг /api/chat и /api/chat/stream: выбор провайдера, клиента LLM
 * и сессии диалога. Память диалога есть у каждого запроса: без sessionId создаётся
 * новая сессия ВЛАДЕЛЬЦА ЗАПРОСА (userId из токена), неизвестный id тоже начинает
 * диалог заново. Указанный sessionId ЧУЖОЙ сессии (другой владелец или legacy
 * без userId) возвращает null: вызывающий маршрут отвечает 404 — дописывать в
 * чужую историю нельзя, и чужая сессия неотличима от несуществующей.
 */
private fun resolveChat(
    config: AppConfig,
    clients: Map<ProviderConfig, LLMClient>,
    sessions: SessionStore,
    body: ChatRequest,
    userId: String?
): ChatContext? {
    val requested = body.sessionId?.let { sessions.get(it) }
    if (requested != null && requested.foreignTo(userId.orEmpty())) return null
    val provider = config.provider(body.provider)
    return ChatContext(
        provider = provider,
        aiClient = clients.getValue(provider),
        session = requested ?: sessions.create(userId)
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
    invariantStore: GlobalInvariantStore,
    startedAt: Long,
    hooks: ContextHooks? = null,
    onStatus: suspend (String) -> Unit = {}
): ChatResponse {
    // FSM (задача 5, G3): стрип task-state блоков из reply ДО append истории
    // и ДО judge — история и судья блока не видят. Парсинг перехода идёт по
    // ИСХОДНОМУ reply (после стрипа блоков там уже нет)
    val rawReply = response.reply
    val cleanReply = TaskStateJson.strip(rawReply)

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
                content = cleanReply,
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

        // FSM (задача 5): pipeline обновления состояния — СТРОГО до хуков
        // стратегий (setFacts остаётся после updateTaskState) и на СВЕЖЕМ
        // перечитании store (G2): пауза, приложившаяся во время полёта,
        // побеждает переход, предложенный моделью
        val fresh = sessions.get(session.id)
        if (fresh != null) {
            // Запись отказа (задача 6 плана task-lifecycle-gates): фиксируется
            // ТОЛЬКО нелегальный LLM-блок вне паузы (G4, E3, E7) — создание
            // задачи, same-stage и эвристический фолбэк отказа не дают;
            // любой легальный переход (LLM или фолбэк) отказ очищает
            val blockProposal = TaskStateJson.parse(rawReply, fresh.taskState)
            val proposed = blockProposal ?: TaskStateHeuristic.infer(body.message, cleanReply, fresh.taskState)
            // Контент-гейт (план bypass-fixes, задача 3): code-dump на PLANNING/
            // PLAN_APPROVED без объявленного блока task-state — мягкий отказ:
            // контент ответа не вырезается, стадия держится, эвристическое
            // продвижение (включая EXEC_MARKERS по ```) отбрасывается. Порог
            // считается по cleanReply (блок task-state уже стрипнут). Ветка
            // стоит ПЕРВОЙ — до clear-ветки, которая иначе стёрла бы отказ.
            val preStage = fresh.taskState?.stage
            val gateFires = (preStage == TaskStage.PLANNING || preStage == TaskStage.PLAN_APPROVED) &&
                blockProposal == null &&
                TaskStateJson.codeBlocksExceedThreshold(cleanReply)
            when {
                gateFires -> {
                    val fenceCount = TaskStateJson.countCodeFences(cleanReply)
                    sessions.updateTaskRejection(
                        fresh.id,
                        contentGateRejectionDescription(preStage!!, (fenceCount + fenceCount % 2) / 2)
                    )
                }
                proposed == null || fresh.taskState?.stage == TaskStage.PAUSED -> {
                    // Перехода нет (фолбэк с null — тихий no-op) либо пауза в
                    // полёте победила (G2): нелегального перехода не было
                    sessions.updateTaskRejection(fresh.id, null)
                }
                else -> {
                    val next = taskStateMachine.apply(fresh.taskState, proposed)
                    if (next != null) {
                        sessions.updateTaskState(fresh.id, next)
                        sessions.updateTaskRejection(fresh.id, null)
                    } else if (blockProposal != null) {
                        sessions.updateTaskRejection(
                            fresh.id,
                            rejectedTransitionDescription(fresh.taskState, blockProposal)
                        )
                    }
                    // blockProposal == null && next == null: эвристика нелегальное
                    // предложение не даёт (задача 3) — записи нет
                }
            }
        }
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
    // FSM (задача 5, Q5): echo актуального состояния задачи каждый ход —
    // читается из store ПОСЛЕ возможного updateTaskState выше
    val echoTaskState = session?.let { sessions.getTaskState(it.id) }
    // Эхо отклонённого перехода (задача 6 плана task-lifecycle-gates): свежее
    // значение из store после мутаций выше, каждый ход (null — ключ omitится)
    val echoTaskRejected = session?.let { sessions.getTaskRejection(it.id) }
    // Инварианты (задача 6 плана assistant-invariants): echo обоих скоупов
    // каждый ход — глобальный читается из store на свежем списке, сессионный —
    // из снимка [session]; null, когда оба пусты (ключ omitится из JSON)
    val echoInvariants = InvariantsPayload(
        global = invariantStore.list(),
        session = session?.invariants ?: emptyList()
    ).takeIf { it.global.isNotEmpty() || it.session.isNotEmpty() }
    return if (body.judgeEnabled == true) {
        onStatus("Судья проверяет ответ…")
        response.copy(
            reply = cleanReply,
            elapsedMs = elapsedMs,
            judge = runJudge(aiClient, body, response.copy(reply = cleanReply), elapsedMs),
            facts = currentFacts,
            taskState = echoTaskState,
            taskRejected = echoTaskRejected,
            invariants = echoInvariants
        )
    } else {
        response.copy(
            reply = cleanReply,
            elapsedMs = elapsedMs,
            facts = currentFacts,
            taskState = echoTaskState,
            taskRejected = echoTaskRejected,
            invariants = echoInvariants
        )
    }
}

/** Единственный экземпляр автомата: чистый класс без состояния (FSM always-on, без конфиг-шва). */
private val taskStateMachine = TaskStateMachine()

/**
 * Человекочитаемое описание отклонённого перехода для
 * [Session.lastRejectedTransition] (задача 6 плана task-lifecycle-gates):
 * «Переход <текущий> → <предложенный> отклонён: … (разрешено: …)». Список
 * разрешённых целей выводится пробным [TaskStateMachine.apply] по каждому
 * этапу — у автомата нет публичного API таблицы рёбер (изменять
 * TaskStateMachine запретил план).
 */
private fun rejectedTransitionDescription(current: TaskState?, proposed: TaskState): String {
    val from = current?.stage?.name ?: "без задачи"
    val allowed = TaskStage.entries
        .filter { candidate ->
            taskStateMachine.apply(
                current,
                TaskState(stage = candidate, step = "", expectedAction = "")
            ) != null
        }
        .joinToString(", ") { it.name }
        .ifEmpty { "—" }
    return "Переход $from → ${proposed.stage.name} отклонён: недопустимый переход (разрешено: $allowed)"
}

/**
 * Человекочитаемое описание срабатывания контент-гейта (план bypass-fixes,
 * задача 3) для [Session.lastRejectedTransition]: на стадии реализации модель
 * выдала код-дамп, не объявив переход блоком task-state. Переход не
 * применяется, стадия держится, модель получает причину через инъекцию на
 * следующем ходу.
 */
private fun contentGateRejectionDescription(stage: TaskStage, blocks: Int): String =
    "Реализация на стадии $stage заблокирована: ответ содержит $blocks код-блоков " +
        "без объявления перехода task-state. Составь план и дождись утверждения."

/**
 * Системная заметка при инъекции checkpoint прерванного паузой прогона: модель
 * продолжает с места остановки, не повторяя уже выполненные вызовы.
 */
private const val RESUME_NOTE =
    "Задача была прервана паузой. Выше — вызовы инструментов и их результаты из прерванного прогона. " +
        "Продолжай с места остановки: не повторяй уже выполненные вызовы, опирайся на полученные данные. " +
        "Если данных достаточно — дай финальный ответ по-русски."

/**
 * Потребление checkpoint прерванного паузой прогона ([PausedRun]) на входе хода
 * (общее для /api/chat и /api/chat/stream, после ensureForRequest):
 *  - [ensured] != null — по интенту пользователя создана НОВАЯ задача: устаревший
 *    checkpoint выбрасывается, НЕ инъектируется (null);
 *  - checkpoint есть — потребляется однократно (savePausedRun(null)): при [inject] true
 *    возвращаются его сообщения + [RESUME_NOTE] (resumeContext для AgentLoop.run),
 *    при false (блокирующий /api/chat) — только очистка, без инъекции: fallback-путь
 *    не раздувается парами прерванного прогона;
 *  - checkpoint на PAUSED не должен доходить сюда: вызывающие гвардят stage
 *    (на паузе checkpoint пережидает до «Продолжить»), при попадании вернёт null
 *    без мутаций — checkpoint не потеряется.
 */
private fun consumePausedRunCheckpoint(
    sessions: SessionStore,
    session: Session,
    ensured: TaskState?,
    inject: Boolean
): List<OpenAIMessage>? {
    val pausedRun = sessions.getPausedRun(session.id)
    return when {
        ensured != null -> {
            sessions.savePausedRun(session.id, null)
            null
        }
        pausedRun != null -> {
            sessions.savePausedRun(session.id, null)
            if (inject) pausedRun.messages + OpenAIMessage(role = "system", content = RESUME_NOTE) else null
        }
        else -> null
    }
}

/** Точные chat-команды паузы/продолжения (trim+lowercase); «сделай паузу» командой НЕ является (E8). */
private val TASK_PAUSE_COMMANDS = setOf("пауза", "/пауза")
private val TASK_RESUME_COMMANDS = setOf("продолжить", "/продолжить")

/**
 * Chat-команды инвариантов (задача 7 плана assistant-invariants): список —
 * ТОЧНОЕ совпадение после trim+lowercase (проверяется первым, чтобы не
 * глотаться add-префиксом); добавление/удаление — префиксы с пробелом.
 * Командой считается только сообщение, НАЧИНАЮЩЕЕСЯ с команды; глобальные
 * инварианты командами не управляются (только панель настроек / API).
 */
private val INVARIANT_LIST_COMMANDS = setOf("инварианты", "/инварианты")
private val INVARIANT_ADD_PREFIXES = listOf("инвариант ", "/инвариант ")
private val INVARIANT_REMOVE_PREFIXES = listOf("убери инвариант ", "/убери инвариант ")

/** Префиксное сравнение без учёта регистра (по исходному тексту — сохраняет длину для substring). */
private fun String.startsWithIgnoreCase(prefix: String): Boolean =
    length >= prefix.length && regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

/**
 * Точная chat-команда паузы/продолжения (задача 5): «пауза», «/пауза»,
 * «продолжить», «/продолжить» — точное совпадение после trim+lowercase.
 * Выполняется ДО вызова LLM: мутация состояния автоматом + системный ответ
 * (LLM не вызывается, Q8). Всё остальное — null (обычный путь через AgentLoop).
 * Ответ всегда эхоит актуальное состояние (и инварианты — задача 7: фронт
 * принимает эхо безусловно, без него панель затирается); история командой
 * не пополняется.
 */
private suspend fun handleTaskCommand(
    session: Session?,
    sessions: SessionStore,
    invariantStore: GlobalInvariantStore,
    message: String,
    startedAt: Long
): ChatResponse? {
    val normalized = message.trim().lowercase()
    val isPause = normalized in TASK_PAUSE_COMMANDS
    val isResume = !isPause && normalized in TASK_RESUME_COMMANDS
    if (!isPause && !isResume) return null

    val current = session?.let { sessions.getTaskState(it.id) }
    val next = if (isPause) taskStateMachine.pause(current) else taskStateMachine.resume(current)
    if (next != null && session != null) {
        sessions.updateTaskState(session.id, next)
    }

    val reply = when {
        isPause && current == null -> "Нет активной задачи"
        isPause -> "Задача поставлена на паузу. Этап: ${stageLabel(next ?: requireNotNull(current))}"
        next != null -> "Задача продолжена. Этап: ${stageLabel(next)}"
        else -> "Нет активной задачи"
    }
    return ChatResponse(
        reply = reply,
        elapsedMs = System.currentTimeMillis() - startedAt,
        sessionId = session?.id,
        taskState = session?.let { sessions.getTaskState(it.id) },
        invariants = invariantsEcho(invariantStore, session?.invariants ?: emptyList())
    )
}

/**
 * Chat-команды инвариантов (задача 7 плана assistant-invariants) — зеркало
 * [handleTaskCommand]: выполняется ДО вызова LLM, историю не пополняет,
 * всё не-командное — null (обычный путь через AgentLoop).
 *
 * Грамматика: exact «инварианты»/«/инварианты» (проверяется ПЕРВЫМ) —
 * нумерованный список сессии + отдельная строка глобальных; префиксы
 * «инвариант »/«/инвариант » — добавить (валидация [StoredInvariant.normalizeAndValidate],
 * дубль и кап — системный ответ с причиной, не исключение); префиксы
 * «убери инвариант »/«/убери инвариант » — удалить по номеру 1-based
 * (не число/вне диапазона — мягкая подсказка, 200). Глобальные инварианты
 * командами не управляются. Каждый ответ эхоит [InvariantsPayload] обоих
 * скоупов (null, когда оба пусты), как finalizeResponse.
 */
private suspend fun handleInvariantCommand(
    session: Session?,
    sessions: SessionStore,
    invariantStore: GlobalInvariantStore,
    message: String,
    startedAt: Long
): ChatResponse? {
    val trimmed = message.trim()
    val normalized = trimmed.lowercase()

    if (normalized in INVARIANT_LIST_COMMANDS) {
        return invariantCommandResponse(
            sessions, invariantStore, startedAt, session,
            session?.invariants ?: emptyList(),
            invariantListReply(invariantStore, session)
        )
    }

    if (normalized == "инвариант" || normalized == "/инвариант") {
        return invariantCommandResponse(
            sessions, invariantStore, startedAt, session,
            session?.invariants ?: emptyList(),
            "Укажите текст инварианта. Пример: «инвариант Только Kotlin»."
        )
    }

    val removePrefix = INVARIANT_REMOVE_PREFIXES.firstOrNull { trimmed.startsWithIgnoreCase(it) }
    if (removePrefix != null) {
        if (session == null) {
            return invariantCommandResponse(
                sessions, invariantStore, startedAt, null, emptyList(),
                "Нет активной сессии: инварианты сессии недоступны."
            )
        }
        val current = session.invariants
        val argument = trimmed.substring(removePrefix.length).trim()
        val index = argument.toIntOrNull()
        if (index == null || index !in current.indices.map { it + 1 }) {
            val hint = if (current.isEmpty()) {
                "Сессионных инвариантов нет. Пример: «инвариант Только Kotlin»."
            } else {
                "Инварианта с номером «$argument» нет — в сессии ${current.size}. Пример: «убери инвариант 1»."
            }
            return invariantCommandResponse(sessions, invariantStore, startedAt, session, current, hint)
        }
        val removed = current[index - 1]
        val updated = current.filterIndexed { position, _ -> position != index - 1 }
        sessions.updateInvariants(session.id, updated)
        return invariantCommandResponse(
            sessions, invariantStore, startedAt, session,
            sessions.getInvariants(session.id),
            "Инвариант удалён: ${removed.text}"
        )
    }

    val addPrefix = INVARIANT_ADD_PREFIXES.firstOrNull { trimmed.startsWithIgnoreCase(it) }
    if (addPrefix != null) {
        if (session == null) {
            return invariantCommandResponse(
                sessions, invariantStore, startedAt, null, emptyList(),
                "Нет активной сессии: инварианты сессии недоступны."
            )
        }
        val current = session.invariants
        val validated = try {
            StoredInvariant.normalizeAndValidate(trimmed.substring(addPrefix.length))
        } catch (e: IllegalArgumentException) {
            return invariantCommandResponse(
                sessions, invariantStore, startedAt, session, current,
                "Инвариант не добавлен: ${e.message}. Пример: «инвариант Только Kotlin»."
            )
        }
        if (current.any { it.text.trim() == validated } || invariantStore.list().any { it.text.trim() == validated }) {
            return invariantCommandResponse(
                sessions, invariantStore, startedAt, session, current,
                "Такой инвариант уже есть."
            )
        }
        if (current.size >= StoredInvariant.MAX_COUNT) {
            return invariantCommandResponse(
                sessions, invariantStore, startedAt, session, current,
                "Лимит: не больше ${StoredInvariant.MAX_COUNT} сессионных инвариантов."
            )
        }
        sessions.updateInvariants(session.id, current + StoredInvariant.create(validated))
        return invariantCommandResponse(
            sessions, invariantStore, startedAt, session,
            sessions.getInvariants(session.id),
            "Инвариант добавлен: $validated"
        )
    }

    return null
}

/**
 * Echo обоих скоупов инвариантов для command-ответов — то же выражение, что
 * в finalizeResponse: null, когда оба скоупа пусты (ключ omitится из JSON).
 */
private fun invariantsEcho(
    invariantStore: GlobalInvariantStore,
    sessionInvariants: List<StoredInvariant>
): InvariantsPayload? = InvariantsPayload(
    global = invariantStore.list(),
    session = sessionInvariants
).takeIf { it.global.isNotEmpty() || it.session.isNotEmpty() }

/** Системный ответ инвариант-команды: зеркало паузы — эхо taskState + обоих скоупов инвариантов. */
private suspend fun invariantCommandResponse(
    sessions: SessionStore,
    invariantStore: GlobalInvariantStore,
    startedAt: Long,
    session: Session?,
    sessionInvariants: List<StoredInvariant>,
    reply: String
): ChatResponse = ChatResponse(
    reply = reply,
    elapsedMs = System.currentTimeMillis() - startedAt,
    sessionId = session?.id,
    taskState = session?.let { sessions.getTaskState(it.id) },
    invariants = invariantsEcho(invariantStore, sessionInvariants)
)

/** Текст ответа exact-команды «инварианты»: нумерованный список сессии + глобальные без номеров. */
private fun invariantListReply(invariantStore: GlobalInvariantStore, session: Session?): String {
    val sessionList = session?.invariants ?: emptyList()
    val sessionPart = if (sessionList.isEmpty()) {
        "Сессионных инвариантов нет. Добавьте: „инвариант <текст>“."
    } else {
        "Сессионные инварианты:\n" +
            sessionList.mapIndexed { position, invariant -> "${position + 1}. ${invariant.text}" }
                .joinToString("\n")
    }
    val globals = invariantStore.list()
    val globalPart = if (globals.isEmpty()) {
        "Глобальных инвариантов: 0 (управляются через панель настроек)."
    } else {
        "Глобальные инварианты (управляются через панель настроек):\n" +
            globals.joinToString("\n") { "- ${it.text}" }
    }
    return "$sessionPart\n\n$globalPart"
}

/** Название этапа для системных ответов; на паузе показывается приостановленный этап. */
private fun stageLabel(state: TaskState): String =
    (if (state.stage == TaskStage.PAUSED) state.prevStage else state.stage)
        ?.name?.lowercase() ?: state.stage.name.lowercase()

/** Формат кодирования TaskState в ответ паузы/продолжения. */
private val taskStateJsonFormat = Json { ignoreUnknownKeys = true }

/**
 * Ответ паузы/продолжения по контракту задачи 5: {"taskState": <состояние|null>}.
 *
 * @param resumeRun true — в сессии есть checkpoint прерванного паузой прогона;
 *        фронт по нему на resume сам отправляет continuation-запрос.
 */
private fun taskStateResponse(state: TaskState?, resumeRun: Boolean): JsonObject = buildJsonObject {
    put("taskState", state?.let { taskStateJsonFormat.encodeToJsonElement(it) } ?: JsonNull)
    put("resumeRun", JsonPrimitive(resumeRun))
}

/**
 * Читает sessionId из тела паузы/продолжения (общий вход /api/task/pause и
 * /api/task/resume, образец /api/cancel). Возвращает null, если ответ уже
 * отправлен: некорректный JSON или пустой sessionId → 400.
 */
private suspend fun ApplicationCall.receiveSessionId(): String? {
    val sessionId = try {
        receive<JsonObject>()["sessionId"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        println("Ошибка десериализации запроса: ${e.message}")
        null
    }
    if (sessionId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ChatResponse("Укажите sessionId сессии."))
        return null
    }
    return sessionId
}

/**
 * Гвард path-traversal для {id}/{sessionId} в путях DELETE-роутов инвариантов
 * (образец — isSafeId из PreferencesStore): сегмент пути не должен содержать
 * разделителей пути и родительских ссылок; нарушение отвечает 404.
 */
private fun isSafeInvariantSegment(segment: String): Boolean =
    segment.isNotBlank() &&
        !segment.contains('/') &&
        !segment.contains('\\') &&
        !segment.contains("..") &&
        !segment.contains('\u0000')

/**
 * Читает {"text": "…"} из тела POST-роутов инвариантов. Возвращает null, если
 * ответ уже отправлен: некорректный JSON или отсутствующий text → 400.
 */
private suspend fun ApplicationCall.receiveInvariantText(): String? {
    val text = try {
        receive<JsonObject>()["text"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        println("Ошибка десериализации запроса: ${e.message}")
        null
    }
    if (text == null) {
        respond(HttpStatusCode.BadRequest, AuthErrorResponse("Укажите текст инварианта."))
        return null
    }
    return text
}

/**
 * Читает {"sessionId": "…", "text": "…"} из тела POST /api/invariants/session.
 * Возвращает null, если ответ уже отправлен: некорректный JSON, пустой
 * sessionId или отсутствующий text → 400 (строгость receiveSessionId).
 */
private suspend fun ApplicationCall.receiveInvariantSessionBody(): Pair<String, String>? {
    val body = try {
        receive<JsonObject>()
    } catch (e: Exception) {
        println("Ошибка десериализации запроса: ${e.message}")
        null
    }
    val sessionId = body?.get("sessionId")?.jsonPrimitive?.contentOrNull
    val text = body?.get("text")?.jsonPrimitive?.contentOrNull
    if (sessionId.isNullOrBlank() || text == null) {
        respond(
            HttpStatusCode.BadRequest,
            AuthErrorResponse("Укажите sessionId сессии и текст инварианта.")
        )
        return null
    }
    return sessionId to text
}

/**
 * Список StoredInvariant как JSON-массив для тел роутов инвариантов. Явная
 * сборка вместо InvariantsPayload через ContentNegotiation: серверный Json с
 * encodeDefaults=false omitил бы пустые списки, а фронтенд читает global и
 * session как обязательные ключи.
 */
private fun invariantListJson(list: List<StoredInvariant>): JsonElement =
    Json.encodeToJsonElement(ListSerializer(StoredInvariant.serializer()), list)

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
 * Память агента ([memory], компоненты построены один раз в aiRoutes): при
 * ChatRequest.memoryTools != false к базовому реестру добавляются инструменты
 * task_notes/memory_save/memory_recall (без мутации базового [registry]), а в seed
 * после trim добавляется системное сообщение с блоком памяти (MemoryRetriever;
 * null — оба слоя памяти пусты).
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
 *
 * Checkpoint прерванного паузой прогона (PausedRun): [onCancelled] и [resumeContext]
 * — прозрачные швы в AgentLoop.run — стрим-обработчик сохраняет дельту прогона при
 * отмене с saveContext=true и подставляет потреблённый checkpoint в контекст при
 * продолжении; сам handleAgent их только прокидывает.
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
    memory: AgentMemory,
    preferences: PreferencesStore,
    invariantStore: GlobalInvariantStore,
    userId: String,
    onEvent: suspend (AgentEvent) -> Unit = {},
    onCancelled: ((List<OpenAIMessage>) -> Unit)? = null,
    resumeContext: List<OpenAIMessage>? = null
): ChatResponse {
    val startSeq = ExchangeLog.mark()

    // Память запроса: по умолчанию (null/true) инструменты памяти добавляются
    // к копии реестра; memoryTools = false — базовый реестр без изменений.
    // Долговременный слой — память текущего пользователя (задача 8)
    val memoryEnabled = body.memoryTools ?: true
    val userLongTerm = memory.long.forUser(userId)
    val requestRegistry = if (memoryEnabled) {
        registry.augmentedWith(
            listOf(
                TaskNotesTool(memory.working, session?.id),
                MemorySaveTool(userLongTerm),
                MemoryRecallTool(userLongTerm)
            )
        )
    } else {
        registry
    }

    val loop = AgentLoop(
        client = aiClient,
        transport = if (provider.supportsTools) NativeToolTransport() else PromptToolTransport(),
        registry = requestRegistry
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
    val trimmedSeed = viewSession
        ?.let {
            SessionHistory.trim(
                view(strategy, it.summary, it.messages, it.facts, compressor.windowSize, compressor) +
                    OpenAIMessage(role = "user", content = body.message),
                body.maxTokens ?: 0
            )
        }
        ?: listOf(OpenAIMessage(role = "user", content = body.message))

    // Блок памяти добавляется ПОСЛЕ trim (в конец seed), чтобы блок не резался:
    // trim считается по истории до обрезки, и попадание памяти под срез лишило бы
    // модель рабочего контекста. null — память выключена или оба слоя пусты
    val memoryMessage =
        if (memoryEnabled && session != null) {
            memory.retrieverFor(userId).memoryBlock(session.id, body.message)
                ?.let { OpenAIMessage(role = "system", content = it) }
        } else {
            null
        }

    // Слот предпочтений (задача 12): тот же хвостовой слот, что и у памяти,
    // порядок по плану — предпочтения → состояние → инварианты → память;
    // безусловная инъекция (нет файла — блок дефолтов). Саб-вызовы (экстрактор,
    // сводка, судья) блоки не получают: их промпты строятся вне seed
    // (инварианты саб-вызовам тоже не передаются — задача 6 плана
    // assistant-invariants).
    val preferencesMessage = OpenAIMessage(
        role = "system",
        content = renderPromptBlock(preferences.get(userId))
    )

    // Слот состояния задачи (FSM, задача 5 плана task-state-fsm): тот же
    // хвостовой слот, что у предпочтений и памяти — между ними (порядок:
    // предпочтения → состояние → инварианты → память). Эфемерная system-строка: в
    // Session.messages не пишется (G5), trim её не режет (инъекция после trim).
    // Снимок [session] достаточен: пауза в полёте перехватывается в
    // finalizeResponse на свежем перечитании (G2). Чекпоинт-дисциплина и строка
    // об отклонённом переходе (задача 6 плана task-lifecycle-gates) рендерятся
    // в тот же слот — новых слотов нет
    val taskStateMessage = OpenAIMessage(
        role = "system",
        content = renderTaskStateBlock(session?.taskState, session?.lastRejectedTransition)
    )

    // Слот инвариантов (задача 6 плана assistant-invariants): 4-й хвостовой
    // слот — между состоянием задачи и памятью. Guard пустоты: оба скоупа
    // пусты → null, пустой блок не инъектируется. Эфемерная system-строка:
    // в Session.messages не пишется, trim её не режет (инъекция после trim).
    // Глобальный скоуп читается с диска на каждый ход (стор без кэша),
    // сессионный — из снимка [session]
    val invariantsMessage = renderInvariantBlock(
        invariantStore.list(),
        session?.invariants ?: emptyList()
    )?.let { OpenAIMessage(role = "system", content = it) }
    val seed = trimmedSeed + listOfNotNull(preferencesMessage, taskStateMessage, invariantsMessage, memoryMessage)

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
        thinkingEnabled = body.thinkingEnabled,
        onCancelled = onCancelled,
        resumeContext = resumeContext
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
