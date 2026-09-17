package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ActivateBranchRequest
import com.example.aiweb.model.BranchInfo
import com.example.aiweb.model.BranchesResponse
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenAIToolCallFunction
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.InMemorySessionStore
import com.example.aiweb.session.Session
import com.example.aiweb.session.SessionHistory
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Routes-уровневая проверка интеграции компрессии истории в агентную обработку
 * запроса (задача 6): поднятие реального Application (embeddedServer Netty на случайном
 * порту), FakeLLMClient как транспорт и FakeSummarizer как суммаризатор — ноль сети.
 *
 * Покрывает: merge по достижении порога (summary в хранилище, слитые удалены),
 * деградацию при сбое суммаризатора (200, прежний summary, summaryTokens=null),
 * выключение компрессии (явное поле запроса > конфиг), stage-2 trim (сводка +
 * окно сохраняются в бюджете MAX_HISTORY_TOKENS).
 */
class CompressionRoutesTest {

    // --- Тестовые швы ---

    /** Фейковый LLMClient-транспорт: запоминает вызовы, отдаёт заготовленный ответ. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    /** Фейковый суммаризатор: детерминированный, со счётчиком и записью аргументов. */
    private class FakeSummarizer(
        var result: SummarizeResult = SummarizeResult("сводка", 7)
    ) : HistorySummarizer {
        data class Call(val previous: String?, val batch: List<OpenAIMessage>)

        val calls = mutableListOf<Call>()
        var failWith: RuntimeException? = null

        override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>): SummarizeResult {
            calls.add(Call(previous, batch))
            failWith?.let { throw it }
            return result
        }
    }

    /** Фейковый экстрактор фактов: счётчик вызовов, заготовленный результат, управляемый сбой. */
    private class FakeFactExtractor(
        private val result: List<Fact>? = listOf(Fact("цель", "построить ветку"))
    ) : FactExtractor {
        data class Call(val current: List<Fact>, val userMessage: String)

        val calls = mutableListOf<Call>()
        var failWith: RuntimeException? = null

        override suspend fun extract(current: List<Fact>, userMessage: String): List<Fact>? {
            calls.add(Call(current, userMessage))
            failWith?.let { throw it }
            return result
        }
    }

    private fun user(text: String) = OpenAIMessage(role = "user", content = text)

    private fun assistant(text: String) = OpenAIMessage(role = "assistant", content = text)

    /** Ход диалога: вопрос и ответ по [chars] символов (оценка — ceil(chars / 2.5)). */
    private fun turn(index: Int, chars: Int): List<OpenAIMessage> = listOf(
        user("вопрос $index ".padEnd(chars, 'х')),
        assistant("ответ $index ".padEnd(chars, 'х'))
    )

    private fun config(
        enabled: Boolean = false,
        window: Int = 5,
        mergeEvery: Int = 5,
        maxTokens: Int = 400
    ) = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m"
        ),
        compressionEnabled = enabled,
        compressionWindowSize = window,
        compressionMergeEvery = mergeEvery,
        compressionSummaryMaxTokens = maxTokens
    )

    /** Поднимает Application на случайном порту с инъецированными фейками и передаёт [block] harness. */
    private fun withApp(
        config: AppConfig,
        factExtractor: FakeFactExtractor = FakeFactExtractor(),
        block: Harness.() -> Unit
    ) {
        val store: SessionStore = InMemorySessionStore()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 0L)
        )
        val summarizer = FakeSummarizer()

        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config,
                    store = store,
                    clientFactory = { chat },
                    summarizerFactory = { summarizer },
                    factExtractorFactory = { factExtractor }
                )
            }
        }) { app ->
            // Фикстурный пользователь уже посажен: DefaultRequest клиента Harness
            // читает токен при построении (внутри withAuthApp — до блока).
            // userId нужен seed(): сессии-фикстуры принадлежат этому пользователю
            // (изоляция сессий: чат в сессию без владельца отвечал бы 404)
            Harness(app.base, store, app.userId, chat, summarizer, factExtractor)
                .block()
        }
    }

    private class Harness(
        val base: String,
        val sessions: SessionStore,
        val userId: String,
        val chat: FakeLLMClient,
        val summarizer: FakeSummarizer,
        val extractor: FakeFactExtractor
    ) {
        val client = testHttpClient()

        /** Синхронный POST /api/chat (без suspend в теле теста). */
        fun postChat(
            message: String,
            sessionId: String? = null,
            compression: Boolean? = null,
            contextStrategy: String? = null,
            branchId: String? = null
        ): HttpResponse = kotlinx.coroutines.runBlocking {
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        sessionId = sessionId,
                        compressionEnabled = compression,
                        contextStrategy = contextStrategy,
                        branchId = branchId
                    )
                )
            }
        }

        /** POST /api/chat/stream — сырое SSE-тело (кадры data: {...}) для разбора в тесте. */
        fun postChatStream(
            message: String,
            sessionId: String? = null,
            contextStrategy: String? = null
        ): HttpResponse = kotlinx.coroutines.runBlocking {
            client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        sessionId = sessionId,
                        contextStrategy = contextStrategy
                    )
                )
            }
        }

        /** GET /api/session/{id}/branches — плоский список веток (активная первой). */
        fun getBranches(id: String): HttpResponse = kotlinx.coroutines.runBlocking {
            client.get("$base/api/session/$id/branches")
        }

        /** POST /api/session/{id}/branches — ручная развилка (чекпоинт активного состояния). */
        fun forkBranch(id: String): HttpResponse = kotlinx.coroutines.runBlocking {
            client.post("$base/api/session/$id/branches")
        }

        /** POST /api/session/{id}/branches/activate — переключение активной ветки. */
        fun activateBranch(id: String, branchId: String): HttpResponse = kotlinx.coroutines.runBlocking {
            client.post("$base/api/session/$id/branches/activate") {
                contentType(ContentType.Application.Json)
                setBody(ActivateBranchRequest(branchId))
            }
        }

        /** GET /api/session/{id} — снимок сессии из хранилища (для проверок содержимого). */
        fun getSession(id: String): HttpResponse = kotlinx.coroutines.runBlocking {
            client.get("$base/api/session/$id")
        }

        /** Декодирует тело ответа в ChatResponse. */
        fun HttpResponse.json(): ChatResponse = kotlinx.coroutines.runBlocking { body() }

        /** Декодирует тело GET /api/session/{id} в Session. */
        fun HttpResponse.session(): Session = kotlinx.coroutines.runBlocking { body() }

        /** Декодирует тело GET /api/session/{id}/branches. */
        fun HttpResponse.branchesView(): BranchesResponse = kotlinx.coroutines.runBlocking { body() }

        /** Декодирует тело POST /api/session/{id}/branches (запись созданной ветки). */
        fun HttpResponse.branchInfo(): BranchInfo = kotlinx.coroutines.runBlocking { body() }

        /** Разбирает SSE-тело в список JSON-кадров (по одному data: на кадр). */
        fun HttpResponse.sseFrames(): List<JsonObject> = kotlinx.coroutines.runBlocking {
            bodyAsText().split("\n\n").mapNotNull { chunk ->
                chunk.lineSequence()
                    .firstOrNull { it.startsWith("data: ") }
                    ?.removePrefix("data: ")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it).jsonObject }
            }
        }

        fun seed(messages: List<OpenAIMessage>, summary: String? = null): Session {
            val s = sessions.create(userId)
            messages.forEach { sessions.append(s.id, it) }
            if (summary != null) sessions.applyCompressionResult(s.id, summary, 0)
            return sessions.get(s.id)!!
        }
    }

    // --- 1. Compression on + порог достигнут ---

    @Test
    fun `сжатие включено и порог достигнут - summary в хранилище, слитые удалены, ответ 200`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            // 6 сообщений, окно 2 → вне окна 4 ≥ mergeEvery 2 → один merge
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))
            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(parsed.summaryApplied, "сводка должна быть подставлена в контекст")
            assertEquals(7, parsed.summaryTokens)

            // Суммаризатор вызван ровно один раз, батчем из всех внеоконных
            assertEquals(1, summarizer.calls.size)
            val batchUsers = summarizer.calls[0].batch.filter { it.role == "user" }.map { it.content!!.take(8) }
            assertEquals(listOf("вопрос 1", "вопрос 2"), batchUsers)

            // Хранилище: summary записан, слитые ходы удалены, окно + новый ход на месте
            val after = sessions.get(s.id)!!
            assertEquals("сводка", after.summary)
            assertEquals(4, after.messages.size)
            assertEquals(listOf("вопрос 3", "ответ 3"), after.messages.take(2).map { it.content!!.take(8).trimEnd() })
            assertEquals(listOf("новый вопрос", "ответ сервера"), after.messages.drop(2).map { it.content })
            assertFalse(after.messages.any { it.content!!.startsWith("вопрос 1") })
        }
    }

    @Test
    fun `сводка уже есть и порог не достигнут - сводка подставляется, суммаризатор не зван`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20), summary = "старая сводка")
            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(parsed.summaryApplied, "прежняя сводка подставлена в контекст")
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size)
            assertTrue(
                chat.calls.single().messages.any { it.role == "system" && it.content == "старая сводка" },
                "сводка должна уйти в запрос системным сообщением"
            )
        }
    }

    // --- 2. Сбой суммаризатора ---

    @Test
    fun `суммаризатор падает - ответ 200, прежний summary сохранён, summaryTokens null`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20), summary = "старая сводка")
            summarizer.failWith = RuntimeException("сбой суммаризатора")

            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(parsed.reply.isNotBlank(), "чат продолжает отвечать при сбое суммаризатора")
            assertNull(parsed.summaryTokens, "сводка не обновлялась — токенов суммаризатора нет")
            assertTrue(parsed.summaryApplied, "прежняя сводка подставлена в контекст")
            assertEquals(1, summarizer.calls.size)

            val after = sessions.get(s.id)!!
            assertEquals("старая сводка", after.summary, "прежний summary не потерян")
            assertTrue(after.messages.any { it.content!!.startsWith("вопрос 1") }, "слиять нельзя — сбой, история не тронута")
            assertEquals(8, after.messages.size, "6 исходных + новый ход, ничего не удалено")
        }
    }

    // --- 3/4. Выключение компрессии и приоритет запроса над конфигом ---

    @Test
    fun `компрессия выключена в конфиге - существующая сводка не подставляется, хранилище не меняется`() {
        withApp(config(enabled = false, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20), summary = "старая сводка")
            val response = postChat("новый вопрос", s.id) // поле запроса отсутствует → конфиг false
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(parsed.summaryApplied)
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size)
            assertFalse(chat.calls.single().messages.any { it.role == "system" && it.content == "старая сводка" })

            val after = sessions.get(s.id)!!
            assertEquals("старая сводка", after.summary, "сводка в хранилище не тронута")
            assertEquals(8, after.messages.size, "ничего не ужато и не удалено")
        }
    }

    @Test
    fun `приоритет запроса - конфиг включён, запрос явно выключен - компрессии нет`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))
            val response = postChat("новый вопрос", s.id, compression = false)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(parsed.summaryApplied, "явное false в запросе перекрывает конфиг true")
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size)

            val after = sessions.get(s.id)!!
            assertNull(after.summary)
            assertEquals(8, after.messages.size, "порог не сработал — ничего не удалено")
        }
    }

    // --- 5. Stage-2 trim: сводка + окно в бюджете 24k ---

    @Test
    fun `сводка плюс окно больше 24k - trim отрезает старые ходы, сводка сохраняется`() {
        withApp(config(enabled = true, window = 8, mergeEvery = 100, maxTokens = 400)) {
            // 8 сообщений по 8000 символов → ~3200 токенов каждое (суммарно > 24 000)
            val s = seed(turn(1, 8000) + turn(2, 8000) + turn(3, 8000) + turn(4, 8000), summary = "старая сводка")
            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)

            val ask = chat.calls.single()
            val summaryInjected = ask.messages.any { it.role == "system" && it.content == "старая сводка" }
            assertTrue(summaryInjected, "сводка не должна теряться при stage-2 trim")

            // История запроса (всё, кроме системного промта режима и нового сообщения) ≤ 24k
            val view = ask.messages
                .dropWhile { it.role == "system" && it.content != "старая сводка" }
                .dropLast(1)
            val viewTokens = view.sumOf { SessionHistory.estimateTokens(it) }
            assertTrue(viewTokens <= SessionHistory.MAX_HISTORY_TOKENS, "вид запроса превысил бюджет: $viewTokens")
            val windowAfterTrim = view.count { it.role != "system" }
            assertTrue(windowAfterTrim < s.messages.size, "trim должен отрезать старые ходы вида")
        }
    }

    // Гарантия отсутствия сети: сам тест держит только локальный сервер на случайном порту.
    @Test
    fun `поднятие Application с фейками - приватный порт, без внешних вызовов`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            assertTrue(base.startsWith("http://127.0.0.1:"))
        }
    }

    // --- 7. Агентная обработка (задача 8): merge до цикла, seed со сводкой ---

    /** Ход с инструментом: user → assistant(tool_calls) → role="tool" (паттерн AgentLoopTest). */
    private fun toolTurn(question: String, callId: String): List<OpenAIMessage> = listOf(
        user(question),
        OpenAIMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                OpenAIToolCall(
                    id = callId,
                    type = "function",
                    function = OpenAIToolCallFunction(name = "calculator", arguments = "{\"expression\":\"2+2\"}")
                )
            )
        ),
        OpenAIMessage(role = "tool", content = "4", toolCallId = callId)
    )

    @Test
    fun `агент - порог достигнут - merge до цикла, сводка в хранилище, слитые удалены, seed содержит system-сводку`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            // 6 сообщений, окно 2 → вне окна 4 ≥ mergeEvery 2 → merge до запуска цикла
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))
            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(parsed.summaryApplied, "сводка должна быть подставлена в seed цикла")
            assertEquals(7, parsed.summaryTokens, "токены суммаризатора — в summaryTokens, не в TokenSums агента")
            assertEquals("ответ сервера", parsed.reply, "агентный цикл отработал поверх сжатого seed")

            // Merge ровно один раз, ДО цикла (суммаризатор не зван из шагов цикла)
            assertEquals(1, summarizer.calls.size)

            // Хранилище: summary записан, слитые ходы удалены, окно + новый ход агента на месте
            val after = sessions.get(s.id)!!
            assertEquals("сводка", after.summary)
            assertEquals(4, after.messages.size)
            assertEquals(listOf("вопрос 3", "ответ 3"), after.messages.take(2).map { it.content!!.take(8).trimEnd() })
            assertEquals(listOf("новый вопрос", "ответ сервера"), after.messages.drop(2).map { it.content })
            assertFalse(after.messages.any { it.content!!.startsWith("вопрос 1") })

            // Первый LLMCall цикла: system-промт агента, затем seed со сводкой system
            val firstCall = chat.calls.first()
            val summaryIndex = firstCall.messages.indexOfFirst { it.role == "system" && it.content == "сводка" }
            assertTrue(summaryIndex > 0, "сводка должна быть system-сообщением seed (после промта агента)")
            val newUserIndex = firstCall.messages.indexOfFirst { it.role == "user" && it.content == "новый вопрос" }
            assertTrue(newUserIndex > summaryIndex, "новый вопрос — после сводки")
            assertTrue(
                firstCall.messages.any { it.role == "user" && it.content!!.startsWith("вопрос 3") },
                "окно после merge должно остаться в seed"
            )
            assertFalse(
                firstCall.messages.any { it.content.orEmpty().startsWith("вопрос 1") },
                "слитые в сводку ходы в seed не возвращаются"
            )
        }
    }

    @Test
    fun `агент - сводка уже есть и порога нет - summaryApplied true, summaryTokens null, суммаризатор не зван`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20), summary = "старая сводка")
            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(parsed.summaryApplied)
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size)
            assertTrue(
                chat.calls.first().messages.any { it.role == "system" && it.content == "старая сводка" },
                "сводка должна уйти в первый вызов цикла системным сообщением"
            )
        }
    }

    @Test
    fun `агент - окно наезжает на tool - пара assistant(tool_calls) и tool в seed не разорвана`() {
        withApp(config(enabled = true, window = 3, mergeEvery = 100)) {
            // 9 сообщений; окно 3 начинается ровно с role="tool" (индекс 6) —
            // sliceWindow обязан расшириться назад до user("вопрос 3")
            val s = seed(turn(1, 20) + turn(2, 20) + toolTurn("вопрос 3", "call-1") + turn(4, 20))
            val response = postChat("новый вопрос", s.id)

            assertEquals(HttpStatusCode.OK, response.status)

            val firstCall = chat.calls.first().messages
            assertTrue(
                firstCall.any { it.role == "tool" && it.toolCallId == "call-1" },
                "tool-сообщение должно попасть в seed — иначе сценарий наезда окна не воспроизведён"
            )
            // Инвариант пар: у каждого tool в seed есть предшествующий assistant(tool_calls) с тем же id
            firstCall.forEachIndexed { index, message ->
                if (message.role == "tool") {
                    val owner = firstCall.subList(0, index).lastOrNull {
                        it.role == "assistant" && it.toolCalls?.any { call -> call.id == message.toolCallId } == true
                    }
                    assertTrue(
                        owner != null,
                        "осиротевший tool без assistant(tool_calls) на позиции $index — провайдер вернёт 400"
                    )
                }
            }
        }
    }

    @Test
    fun `агент - суммаризатор падает - агент отвечает, прежний summary сохранён, summaryTokens null`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20), summary = "старая сводка")
            summarizer.failWith = RuntimeException("сбой суммаризатора")

            val response = postChat("новый вопрос", s.id)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ответ сервера", parsed.reply, "агент не падает от сбоя суммаризатора")
            assertNull(parsed.summaryTokens, "сводка не обновлялась — токенов суммаризатора нет")
            assertTrue(parsed.summaryApplied, "прежняя сводка подставлена в seed")
            assertEquals(1, summarizer.calls.size)

            val after = sessions.get(s.id)!!
            assertEquals("старая сводка", after.summary, "прежний summary не потерян")
            assertTrue(after.messages.any { it.content!!.startsWith("вопрос 1") }, "слиять нельзя — история не тронута")
            assertEquals(8, after.messages.size, "6 исходных + новый ход, ничего не удалено")
        }
    }

    @Test
    fun `агент - компрессия явно выключена в запросе - seed как раньше, сводка нигде`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20), summary = "старая сводка")
            val response = postChat("новый вопрос", s.id, compression = false)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(parsed.summaryApplied, "явное false в запросе перекрывает конфиг true")
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size)

            // Seed как раньше: полная история через trim, без system-сводки
            val firstCall = chat.calls.first().messages
            assertFalse(firstCall.any { it.role == "system" && it.content == "старая сводка" })
            assertTrue(firstCall.any { it.role == "user" && it.content!!.startsWith("вопрос 1") }, "история не ужата")

            val after = sessions.get(s.id)!!
            assertEquals("старая сводка", after.summary, "сводка в хранилище не тронута")
            assertEquals(8, after.messages.size, "ничего не ужато и не удалено")
        }
    }

    // --- 8. Стратегии контекста (задача 4): window / facts / legacy / приоритет / 400 ---

    @Test
    fun `стратегия window - в LLMCall ровно последние 5, хранилище подрезано до окна`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            // 13 сообщений: 6 ходов + «висячий» вопрос — окно 5 начинается с user
            // (ход 5 целиком + ход 6 + висячий), stage-2 trim ничего не срезает
            val s = seed(
                turn(1, 20) + turn(2, 20) + turn(3, 20) + turn(4, 20) +
                    turn(5, 20) + turn(6, 20) + user("вопрос 7 без ответа")
            )

            val response = postChat("новый вопрос", s.id, contextStrategy = "window")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("window", parsed.contextStrategy, "echo стратегии в ответе")
            assertNull(parsed.summaryTokens, "merge при WINDOW не выполняется")
            assertFalse(parsed.summaryApplied, "сводка при WINDOW не подставляется")

            // Агентная обработка: первый system — промт агента, последний —
            // описание инструментов промт-протокола (supportsTools = false);
            // между ними окно (5) + новый вопрос; хвостовое system-сообщение
            // предпочтений (задача 12) и блок состояния задачи (FSM) идут
            // перед описанием инструментов; запрос собирается ДО подрезки
            val ask = chat.calls.single().messages
            assertEquals(1 + 5 + 1 + 1 + 1 + 1, ask.size, "промт агента + окно 5 + новый вопрос + предпочтения + состояние задачи + описание инструментов")
            assertEquals(
                listOf("вопрос 5", "ответ 5", "вопрос 6", "ответ 6", "вопрос 7"),
                ask.drop(1).dropLast(4).map { it.content!!.take(8).trimEnd() },
                "в запрос уходят ровно последние 5 сообщений"
            )
            assertEquals("новый вопрос", ask[ask.size - 4].content)
            assertFalse(ask.any { it.content.orEmpty().startsWith("вопрос 1") }, "внеоконные ходы в запрос не попадают")
            assertFalse(
                ask.any { it.role == "tool" },
                "история без инструментов — window не приносит лишнего"
            )

            // Деструктивная подрезка: GET /api/session возвращает ровно окно
            // (13 seed + свежий ход → 15, после подрезки 5)
            val after = getSession(s.id).session()
            assertEquals(5, after.messages.size, "хранилище урезано до последних 5 сообщений")
            assertEquals(
                listOf("вопрос 6", "ответ 6", "вопрос 7"),
                after.messages.dropLast(2).map { it.content!!.take(8).trimEnd() },
                "окно хвоста seed уцелело"
            )
            assertEquals("новый вопрос", after.messages[3].content)
            assertEquals("ответ сервера", after.messages[4].content, "свежий ход уцелел")
            assertTrue(after.messages.none { it.content!!.startsWith("вопрос 1") }, "ход за окном отброшен безвозвратно")
        }
    }

    @Test
    fun `стратегия window - семь обменов подряд - в хранилище не больше окна, свежий ход уцелел`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))

            repeat(7) { i ->
                val response = postChat("вопрос обмена ${i + 1}", s.id, contextStrategy = "window")
                assertEquals(HttpStatusCode.OK, response.status)
                val after = getSession(s.id).session()
                assertTrue(
                    after.messages.size <= 5,
                    "после обмена ${i + 1} в хранилище не больше 5 сообщений (сейчас ${after.messages.size})"
                )
            }

            val final = getSession(s.id).session()
            assertEquals(5, final.messages.size, "семь обменов при окне 5 — ровно 5 сообщений")
            assertEquals("assistant", final.messages.last().role, "свежий ответ уцелел (подрезка после append)")
            assertEquals(
                listOf("вопрос обмена 6", "вопрос обмена 7"),
                final.messages.mapNotNull { it.content }.filter { it.startsWith("вопрос обмена") }
            )
            assertTrue(
                final.messages.none { it.content.orEmpty().startsWith("вопрос обмена 1") },
                "ранние обмены отброшены безвозвратно"
            )
        }
    }

    @Test
    fun `стратегии off, facts, summary, branching - хранилище не подрезается`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            for (strategy in listOf("off", "facts", "summary", "branching")) {
                val s = seed(
                    turn(1, 20) + turn(2, 20) + turn(3, 20) + turn(4, 20) +
                        turn(5, 20) + turn(6, 20) + user("вопрос 7 без ответа")
                )

                val response = postChat("новый вопрос", s.id, contextStrategy = strategy)

                assertEquals(HttpStatusCode.OK, response.status, "стратегия $strategy")
                val after = getSession(s.id).session()
                assertEquals(15, after.messages.size, "$strategy не подрезает хранилище")
                assertTrue(
                    after.messages.any { it.content!!.startsWith("вопрос 1") },
                    "$strategy: первый ход остался в хранилище"
                )
            }
        }
    }

    @Test
    fun `стратегия facts - system-блок фактов плюс последние 3 сообщения`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            // 5 сообщений: окно 3 = [вопрос 2, ответ 2, висячий вопрос] — с user,
            // stage-2 trim блок фактов и окно не режет
            val s = seed(turn(1, 20) + turn(2, 20) + user("вопрос 3 без ответа"))
            sessions.setFacts(s.id, listOf(Fact("цель", "проверить блок фактов"), Fact("язык", "kotlin")))

            val response = postChat("новый вопрос", s.id, contextStrategy = "facts")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("facts", parsed.contextStrategy, "echo стратегии в ответе")

            val ask = chat.calls.single().messages
            assertEquals(1 + 1 + 3 + 1 + 1 + 1 + 1, ask.size, "промт агента + блок фактов + окно 3 + новый вопрос + предпочтения + состояние задачи + описание инструментов")
            val factsBlock = ask[1]
            assertEquals("system", factsBlock.role)
            assertTrue(
                factsBlock.content!!.startsWith("Известные факты о диалоге:"),
                "блок фактов начинается с заголовка: ${factsBlock.content}"
            )
            assertTrue(factsBlock.content!!.contains("- цель: проверить блок фактов"))
            assertTrue(factsBlock.content!!.contains("- язык: kotlin"))

            val tail = ask.drop(2).dropLast(4)
            assertEquals(
                listOf("вопрос 2", "ответ 2", "вопрос 3"),
                tail.map { it.content!!.take(8).trimEnd() },
                "после блока фактов — последние 3 сообщения"
            )
            assertEquals("новый вопрос", ask[ask.size - 4].content)
        }
    }

    @Test
    fun `стратегия facts - пустые факты - блока нет, только окно`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20) + user("вопрос 3 без ответа"))

            val response = postChat("новый вопрос", s.id, contextStrategy = "facts")

            assertEquals(HttpStatusCode.OK, response.status)
            val ask = chat.calls.single().messages
            assertEquals(1 + 3 + 1 + 1 + 1 + 1, ask.size, "пустые факты — пустой вклад: промт агента, окно 3, новый вопрос, предпочтения, состояние задачи, описание инструментов")
            assertTrue(
                ask.none { it.role == "system" && it.content!!.startsWith("Известные факты") },
                "пустые факты — блока фактов в запросе нет"
            )
        }
    }

    @Test
    fun `legacy - compressionEnabled true - merge вызван, планView применён, echo summary`() {
        withApp(config(enabled = false, window = 2, mergeEvery = 2)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))

            val response = postChat("новый вопрос", s.id, compression = true)
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("summary", parsed.contextStrategy, "legacy-флаг разрешается в SUMMARY")
            assertTrue(parsed.summaryApplied)
            assertEquals(7, parsed.summaryTokens, "токены суммаризатора в ответе — старое поведение")
            assertEquals(1, summarizer.calls.size, "merge выполнен как раньше")
            assertTrue(
                chat.calls.single().messages.any { it.role == "system" && it.content == "сводка" },
                "планView применён: сводка system-сообщением"
            )
        }
    }

    @Test
    fun `приоритет - window плюс compressionEnabled true - WINDOW выигрывает, merge не зван`() {
        withApp(config(enabled = true, window = 5, mergeEvery = 1)) {
            val s = seed(turn(1, 20) + turn(2, 20) + turn(3, 20))

            val response = postChat("новый вопрос", s.id, compression = true, contextStrategy = "window")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("window", parsed.contextStrategy, "явная стратегия перекрывает legacy-флаг")
            assertNull(parsed.summaryTokens)
            assertEquals(0, summarizer.calls.size, "при WINDOW merge не вызывается вовсе")
            assertFalse(parsed.summaryApplied)
            assertFalse(
                chat.calls.single().messages.any { it.role == "system" && it.content == "сводка" },
                "сводка в контекст не подставляется"
            )
            assertFalse(chat.calls.single().messages.any { it.content.orEmpty().startsWith("вопрос 1") })

            val after = sessions.get(s.id)!!
            assertEquals(5, after.messages.size, "хранилище подрезано до окна: 8 сообщений → 5")
        }
    }

    @Test
    fun `неизвестная стратегия - 400 с текстом Неизвестная стратегия, сервер жив`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val bad = postChat("вопрос", contextStrategy = "magick")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val body = kotlinx.coroutines.runBlocking { bad.bodyAsText() }
            assertTrue(body.contains("Неизвестная стратегия контекста"), "текст ошибки: $body")
            assertTrue(body.contains("magick"), "неизвестный id в тексте ошибки: $body")
            assertEquals(0, chat.calls.size, "до LLM запрос не дошёл")
            assertEquals(0, sessions.list().size, "сессия не создана")

            val alive = postChat("вопрос после ошибки")
            assertEquals(HttpStatusCode.OK, alive.status, "сервер жив после 400")
            assertEquals("off", alive.json().contextStrategy, "echo дефолтной стратегии")
        }
    }

    // --- 9. Интеграция (задача 6): факты после ответа ---

    @Test
    fun `стратегия facts - после ответа экстракция записана в хранилище и echo в ответе`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + user("вопрос 2 без ответа"))
            sessions.setFacts(s.id, listOf(Fact("старый", "факт")))

            val response = postChat("меня зовут Аугусто", s.id, contextStrategy = "facts")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, extractor.calls.size, "экстрактор зван ровно один раз, после ответа ассистента")
            assertEquals(
                listOf(Fact("старый", "факт")),
                extractor.calls[0].current,
                "экстрактору переданы текущие факты сессии"
            )
            assertEquals("меня зовут Аугусто", extractor.calls[0].userMessage)

            val expected = listOf(Fact("цель", "построить ветку"))
            assertEquals(expected, parsed.facts, "ответ несёт обновлённый список фактов")
            assertEquals(expected, sessions.get(s.id)!!.facts, "обновлённые факты записаны в хранилище")
        }
    }

    @Test
    fun `экстрактор фактов падает - ответ 200, прежние факты сохранены`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(listOf(user("вопрос 1")))
            val previous = listOf(Fact("прежний", "факт"))
            sessions.setFacts(s.id, previous)
            extractor.failWith = RuntimeException("сбой экстрактора")

            val response = postChat("новый вопрос", s.id, contextStrategy = "facts")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ответ сервера", parsed.reply, "чат продолжает отвечать при сбое экстрактора")
            assertEquals(previous, parsed.facts, "в ответе прежние факты")
            assertEquals(previous, sessions.get(s.id)!!.facts, "хранилище не тронуто — прежние факты")
        }
    }

    @Test
    fun `стратегия не facts - экстрактор не зван, факты только echo`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(listOf(user("вопрос 1")))
            val previous = listOf(Fact("старый", "факт"))
            sessions.setFacts(s.id, previous)

            val response = postChat("новый вопрос", s.id, contextStrategy = "window")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, extractor.calls.size, "экстракция выполняется только при стратегии facts")
            assertEquals(previous, parsed.facts, "текущие факты сессии приходят echo без обновления")
        }
    }

    // --- 10. Интеграция (задача 6): авто-форк, эндпоинты веток, SSE checkpoint ---

    @Test
    fun `branching - семь сообщений подряд - веток нет, только живой корень`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            var sid: String? = null
            repeat(6) { index ->
                val response = postChat("вопрос ${index + 1}", sid, contextStrategy = "branching")
                assertEquals(HttpStatusCode.OK, response.status)
                if (sid == null) sid = response.json().sessionId
            }
            val id = sid!!

            // Седьмой ход — стримом: кадра checkpoint в потоке больше не существует
            val stream = postChatStream("вопрос 7", id, contextStrategy = "branching")
            assertEquals(HttpStatusCode.OK, stream.status)
            val types = stream.sseFrames().map { it["type"]?.jsonPrimitive?.content }
            assertTrue("checkpoint" !in types, "кадра checkpoint нет в потоке: $types")
            assertTrue("final" in types, "поток завершается кадром final: $types")

            // Регрессия авто-форка: обменов сколько угодно — ветки создаёт только ручная развилка
            val view = getBranches(id).branchesView()
            assertEquals(1, view.branches.size, "в перечне только живое состояние — близнецов нет")
            assertTrue(view.branches[0].active)
            assertNull(view.branches[0].branchId, "активна исходная история")
            assertEquals(7, view.branches[0].userCountSinceFork)
            assertTrue(sessions.get(id)!!.branches.isEmpty(), "в хранилище нет ни одного снимка-ветки")
        }
    }

    @Test
    fun `activate - переключение возвращает снимок ветки как активную сессию`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            var sid: String? = null
            repeat(2) { index ->
                val response = postChat("вопрос ${index + 1}", sid, contextStrategy = "branching")
                if (sid == null) sid = response.json().sessionId
            }
            val id = sid!!
            val forked = forkBranch(id)
            assertEquals(HttpStatusCode.OK, forked.status, "ветку b1 создаёт ручная развилка")

            val activated = activateBranch(id, "b1")
            assertEquals(HttpStatusCode.OK, activated.status)
            val sessionAfter = activated.session()
            val b1Snapshot = sessions.get(id)!!.branches.first { it.branchId == "b1" }
            assertEquals("b1", sessionAfter.activeBranchId)
            assertEquals(
                b1Snapshot.messages.map { it.content },
                sessionAfter.messages.map { it.content },
                "активной стала копия снимка ветки b1"
            )

            val viaGet = getSession(id).session()
            assertEquals(
                sessionAfter.messages.map { it.content },
                viaGet.messages.map { it.content },
                "GET /api/session возвращает те же сообщения, что и ответ activate"
            )
        }
    }

    @Test
    fun `activate - неизвестная ветка и неизвестная сессия - 404`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            assertEquals(HttpStatusCode.NotFound, activateBranch("нет-такой-сессии", "b1").status)

            val s = seed(listOf(user("вопрос 1")))
            assertEquals(HttpStatusCode.NotFound, activateBranch(s.id, "нет-такой-ветки").status)
        }
    }

    @Test
    fun `branchId в запросе - переключает ветку до обработки, неизвестная ветка - 404`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            var sid: String? = null
            repeat(2) { index ->
                val response = postChat("вопрос ${index + 1}", sid, contextStrategy = "branching")
                if (sid == null) sid = response.json().sessionId
            }
            val id = sid!!
            val forked = forkBranch(id)
            assertEquals(HttpStatusCode.OK, forked.status, "ветку b1 создаёт ручная развилка")

            val switched = postChat("вопрос в ветке", id, contextStrategy = "branching", branchId = "b1")
            assertEquals(HttpStatusCode.OK, switched.status)
            assertEquals("b1", switched.json().branchId, "echo активной ветки после переключения")
            assertEquals("b1", sessions.get(id)!!.activeBranchId, "сессия переключена на b1")

            val before = sessions.get(id)!!.messages.size
            val unknown = postChat("вопрос", id, contextStrategy = "branching", branchId = "нет")
            assertEquals(HttpStatusCode.NotFound, unknown.status, "неизвестная ветка — 404")
            assertEquals(before, sessions.get(id)!!.messages.size, "404 — обработка не началась, история не пишется")
        }
    }

    @Test
    fun `зеркало подавляет ветвление - шесть сообщений при off - веток нет`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            var sid: String? = null
            repeat(6) { index ->
                val response = postChat("вопрос ${index + 1}", sid, contextStrategy = "off")
                assertEquals(HttpStatusCode.OK, response.status)
                if (sid == null) sid = response.json().sessionId
            }
            val session = sessions.get(sid!!)!!
            assertTrue(session.branches.isEmpty(), "стратегия off никогда не форкает: ${session.branches}")
            assertNull(session.activeBranchId)
        }
    }

    // --- 11. Ручная развилка (post-completion): POST /api/session/{id}/branches ---

    @Test
    fun `ручная развилка - 200 с записью близнеца, активное состояние не тронуто, без хода в LLM`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20), summary = "сводка")
            sessions.setFacts(s.id, listOf(Fact("цель", "ручная развилка")))

            val response = forkBranch(s.id)
            assertEquals(HttpStatusCode.OK, response.status)
            val info = response.branchInfo()
            assertEquals("b1", info.branchId)
            assertEquals("Ветка 1", info.label, "форма label нужна фронту для маркера «Чекпоинт: создана …»")
            assertEquals(2, info.forkUserCount, "провенанс — user-сообщения активного состояния на момент снимка")
            assertEquals(0, info.userCountSinceFork)
            assertEquals(4, info.messageCount)
            assertFalse(info.active, "ручная развилка не переключает активную ветку")

            // Перечень: живое состояние первым и активным, близнец вторым
            val view = getBranches(s.id).branchesView()
            assertEquals(2, view.branches.size, "живое состояние + один близнец")
            assertTrue(view.branches[0].active)
            assertNull(view.branches[0].branchId, "корень остаётся активным — переключения не было")
            assertEquals("b1", view.branches[1].branchId)

            // Снимок дублирует активное состояние целиком, само состояние не меняется
            val after = sessions.get(s.id)!!
            assertEquals("сводка", after.summary, "ручная развилка не трогает сводку активного состояния")
            assertEquals(listOf(Fact("цель", "ручная развилка")), after.facts, "факты активного состояния не тронуты")
            val twin = after.branches.single { it.branchId == "b1" }
            assertEquals(after.messages.map { it.content }, twin.messages.map { it.content }, "снимок — копия активного состояния")
            assertEquals("сводка", twin.summary)
            assertEquals(after.facts, twin.facts)
            assertNull(after.activeBranchId, "активной остаётся исходная история")
            assertEquals(0, chat.calls.size, "ручная развилка — store-операция, LLM не вызывается")
        }
    }

    @Test
    fun `ручная развилка - неизвестная сессия - 404 с русским текстом`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val response = forkBranch("нет-такой-сессии")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Сессия не найдена.", response.json().reply, "текст ошибки для ленты — в ChatResponse")
        }
    }

    @Test
    fun `ручная развилка - кап 8 веток - девятый форк 409 с сообщением лимита`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20))

            repeat(2) { index ->
                val response = forkBranch(s.id)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("b${index + 1}", response.branchInfo().branchId)
            }
            assertEquals(3, getBranches(s.id).branchesView().branches.size, "два ручных форка: живое состояние + 2 близнеца")

            repeat(6) { forkBranch(s.id) }
            assertEquals(8, sessions.get(s.id)!!.branches.size, "ровно 8 снимков — кап MAX_BRANCHES")

            val ninth = forkBranch(s.id)
            assertEquals(HttpStatusCode.Conflict, ninth.status, "форк за капом — 409")
            assertEquals("Достигнут лимит веток (8)", ninth.json().reply, "сообщение лимита — для ленты")
            assertEquals(8, sessions.get(s.id)!!.branches.size, "кап не превышен: лишней ветки нет")
            assertNull(sessions.get(s.id)!!.activeBranchId, "кап не переключает активную ветку")
        }
    }
}
