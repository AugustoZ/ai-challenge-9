package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.ServerSocket

/**
 * Routes-уровневая проверка интеграции компрессии истории в одиночные режимы
 * (задача 6): поднятие реального Application (embeddedServer Netty на случайном
 * порту), FakeLLMClient как транспорт и FakeSummarizer как суммаризатор — ноль сети.
 *
 * Покрывает: merge по достижении порога (summary в хранилище, слитые удалены),
 * деградацию при сбое суммаризатора (200, прежний summary, summaryTokens=null),
 * выключение компрессии (явное поле запроса > конфиг), stage-2 trim (сводка +
 * окно сохраняются в бюджете MAX_HISTORY_TOKENS) и guard: Team/P2P-режимы
 * остаются stateless и не трогают summary.
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Поднимает Application на случайном порту с инъецированными фейками и передаёт [block] harness. */
    private fun withApp(
        config: AppConfig,
        block: Harness.() -> Unit
    ) {
        val store: SessionStore = InMemorySessionStore()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 0L)
        )
        val summarizer = FakeSummarizer()
        val port = freePort()

        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            routing {
                aiRoutes(
                    config = config,
                    store = store,
                    clientFactory = { chat },
                    summarizerFactory = { summarizer }
                )
            }
        }
        server.start(wait = false)
        try {
            Harness("http://127.0.0.1:$port", store, chat, summarizer)
                .block()
        } finally {
            server.stop(100, 500)
        }
    }

    private class Harness(
        val base: String,
        val sessions: SessionStore,
        val chat: FakeLLMClient,
        val summarizer: FakeSummarizer
    ) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        /** Синхронный POST /api/chat (без suspend в теле теста). */
        fun postChat(
            message: String,
            sessionId: String? = null,
            compression: Boolean? = null,
            reasoningMode: String? = null,
            contextStrategy: String? = null
        ): HttpResponse = kotlinx.coroutines.runBlocking {
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        message = message,
                        sessionId = sessionId,
                        compressionEnabled = compression,
                        reasoningMode = reasoningMode,
                        contextStrategy = contextStrategy
                    )
                )
            }
        }

        /** GET /api/session/{id} — снимок сессии из хранилища (для non-destructive проверок). */
        fun getSession(id: String): HttpResponse = kotlinx.coroutines.runBlocking {
            client.get("$base/api/session/$id")
        }

        /** Декодирует тело ответа в ChatResponse. */
        fun HttpResponse.json(): ChatResponse = kotlinx.coroutines.runBlocking { body() }

        /** Декодирует тело ответа GET /api/session/{id} в Session. */
        fun HttpResponse.session(): Session = kotlinx.coroutines.runBlocking { body() }

        fun seed(messages: List<OpenAIMessage>, summary: String? = null): Session {
            val s = sessions.create()
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

    // --- 6. Guard: Team/P2P stateless ---

    @Test
    fun `режим Команда игнорирует compressionEnabled - stateless, сводка не пишется`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 1)) {
            val response = postChat("вопрос для команды", compression = true, reasoningMode = "team")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(parsed.summaryApplied, "team-путь не знает о компрессии")
            assertNull(parsed.summaryTokens)
            assertNull(parsed.sessionId, "stateless — сессия не создаётся")
            assertEquals(0, summarizer.calls.size)
            assertEquals(0, sessions.list().size, "сессий не создано, summary нигде не записан")
        }
    }

    @Test
    fun `режим Prompt-to-Prompt игнорирует compressionEnabled - stateless`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 1)) {
            val response = postChat("вопрос", compression = true, reasoningMode = "prompt_to_prompt")
            val parsed = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(parsed.summaryApplied)
            assertNull(parsed.summaryTokens)
            assertNull(parsed.sessionId)
            assertEquals(0, summarizer.calls.size)
            assertEquals(0, sessions.list().size)
        }
    }

    // Гарантия отсутствия сети: сам тест держит только локальный сервер на случайном порту.
    @Test
    fun `поднятие Application с фейками - приватный порт, без внешних вызовов`() {
        withApp(config(enabled = true, window = 2, mergeEvery = 2)) {
            assertTrue(base.startsWith("http://127.0.0.1:"))
        }
    }

    // --- 7. Агентный режим (задача 8): та же схема, что в одиночных, но seed цикла ---

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
            val response = postChat("новый вопрос", s.id, reasoningMode = "agent")
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
            val response = postChat("новый вопрос", s.id, reasoningMode = "agent")
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
            val response = postChat("новый вопрос", s.id, reasoningMode = "agent")

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

            val response = postChat("новый вопрос", s.id, reasoningMode = "agent")
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
            val response = postChat("новый вопрос", s.id, compression = false, reasoningMode = "agent")
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
    fun `стратегия window - в LLMCall ровно последние 5, хранилище не урезано`() {
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

            // DEFAULT-режим без maxTokens: системного промта режима нет —
            // LLMCall = окно (5) + новый вопрос
            val ask = chat.calls.single().messages
            assertEquals(6, ask.size)
            assertEquals(
                listOf("вопрос 5", "ответ 5", "вопрос 6", "ответ 6", "вопрос 7"),
                ask.dropLast(1).map { it.content!!.take(8).trimEnd() },
                "в запрос уходят ровно последние 5 сообщений"
            )
            assertEquals("новый вопрос", ask.last().content)
            assertFalse(ask.any { it.content.orEmpty().startsWith("вопрос 1") }, "внеоконные ходы в запрос не попадают")
            assertFalse(
                ask.any { it.role == "tool" },
                "история без инструментов — window не приносит лишнего"
            )

            // Non-destructive: GET /api/session возвращает ВСЕ ходы + новый
            val after = getSession(s.id).session()
            assertEquals(15, after.messages.size, "13 сообщений seed + новый ход, ничего не удалено")
            assertTrue(after.messages.any { it.content!!.startsWith("вопрос 1") }, "первый ход остался в хранилище")
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
            assertEquals(1 + 3 + 1, ask.size, "блок фактов + окно 3 + новый вопрос")
            val factsBlock = ask.first()
            assertEquals("system", factsBlock.role)
            assertTrue(
                factsBlock.content!!.startsWith("Известные факты о диалоге:"),
                "блок фактов начинается с заголовка: ${factsBlock.content}"
            )
            assertTrue(factsBlock.content!!.contains("- цель: проверить блок фактов"))
            assertTrue(factsBlock.content!!.contains("- язык: kotlin"))

            val tail = ask.drop(1).dropLast(1)
            assertEquals(
                listOf("вопрос 2", "ответ 2", "вопрос 3"),
                tail.map { it.content!!.take(8).trimEnd() },
                "после блока фактов — последние 3 сообщения"
            )
            assertEquals("новый вопрос", ask.last().content)
        }
    }

    @Test
    fun `стратегия facts - пустые факты - блока нет, только окно`() {
        withApp(config(enabled = false, window = 5, mergeEvery = 100)) {
            val s = seed(turn(1, 20) + turn(2, 20) + user("вопрос 3 без ответа"))

            val response = postChat("новый вопрос", s.id, contextStrategy = "facts")

            assertEquals(HttpStatusCode.OK, response.status)
            val ask = chat.calls.single().messages
            assertEquals(3 + 1, ask.size, "пустые факты — пустой вклад, окно 3 + новый вопрос")
            assertFalse(ask.any { it.role == "system" })
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
            assertEquals(8, after.messages.size, "хранилище не урезано: 6 seed + новый ход")
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
}
