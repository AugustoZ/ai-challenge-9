package com.example.aiweb.invariants

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.routes.aiRoutes
import com.example.aiweb.routes.testHttpClient
import com.example.aiweb.routes.withAuthApp
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.SummarizeResult
import com.example.aiweb.session.sessionStoreFor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Инъекция блока инвариантов в seed каждого LLM-запроса (задача 6 плана
 * assistant-invariants): 4-й хвостовой system-слот после SessionHistory.trim
 * (порядок: предпочтения → состояние → инварианты → память), guard пустоты
 * (оба скоупа пусты — блока нет) и echo ChatResponse.invariants из
 * finalizeResponse. Саб-вызовы блок не получают (их промпты строятся вне seed).
 */
class InvariantInjectionTest {

    /** Фейковый транспорт: отдаёт заготовленный ответ, запоминает вызовы. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-invariants").toString()

    private fun config(dataDir: String) = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m",
            supportsTools = true
        ),
        dataDir = dataDir
    )

    /** Тексты фикстурных инвариантов. */
    private val globalText = "Глобальное правило: не спорить о политике"
    private val sessionText = "Сессионное правило: отвечать кратко"

    /**
     * Поднимает приложение с фейковым LLM и общим стором сессий; [seedGlobal] —
     * посев одного глобального инварианта в файл-стор. Экземпляр стора один и
     * тот же в тесте и в приложении: session-инварианты сеются блоком через
     * [SessionStore.updateInvariants] (отдельных API-роутов ещё нет — задача 8).
     */
    private fun withApp(
        seedGlobal: Boolean = false,
        block: suspend (base: String, fake: FakeLLMClient, store: SessionStore, userId: String) -> Unit
    ) {
        val dataDir = tempDataDir()
        if (seedGlobal) {
            GlobalInvariantStore(dataDir).add(globalText)
        }
        val store = sessionStoreFor(dataDir)
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir),
                    store = store,
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : com.example.aiweb.session.HistorySummarizer {
                            override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>) =
                                SummarizeResult("", 0)
                        }
                    }
                )
            }
        }) { app ->
            runBlocking { block(app.base, chat, store, app.userId) }
        }
    }

    private fun httpClient(): HttpClient = testHttpClient()

    /** Системные сообщения вызова. */
    private fun LLMCall.systemTexts(): List<String> =
        messages.filter { it.role == "system" }.map { it.content.orEmpty() }

    /** Блок инвариантов из вызова (system-сообщение с маркером). */
    private fun LLMCall.invariantsBlock(): String? =
        messages.lastOrNull { it.role == "system" && it.content.orEmpty().contains(INVARIANTS_MARKER) }?.content

    /** Ход чата с опциональной сессией. */
    private suspend fun HttpClient.chat(base: String, message: String, sessionId: String?): ChatResponse =
        post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = message, sessionId = sessionId))
        }.body<ChatResponse>()

    // --- Блок в seed каждого запроса ---

    @Test
    fun `глобальный и сессионный инварианты доходят до LLM последним system-блоком`() {
        withApp(seedGlobal = true) { base, fake, store, userId ->
            val client = httpClient()
            val sid = store.create(userId).id
            store.updateInvariants(sid, listOf(StoredInvariant.create(sessionText)))

            val response = client.chat(base, "вопрос", sid)

            val block = assertNotNull(fake.calls.last().invariantsBlock(), "блок инвариантов должен быть в seed")
            assertTrue(block.contains(INVARIANTS_MARKER), "маркер-заголовок: $block")
            assertTrue(block.contains("Глобальные:"), "секция глобальных: $block")
            assertTrue(block.contains("Сессия:"), "секция сессии: $block")
            assertTrue(block.contains(globalText), "текст глобального инварианта: $block")
            assertTrue(block.contains(sessionText), "текст сессионного инварианта: $block")
            assertTrue(block.contains(REFUSAL_INSTRUCTION), "инструкция отказа: $block")

            val systemMessages = fake.calls.last().messages.filter { it.role == "system" }
            assertTrue(
                systemMessages.last().content.orEmpty().contains(INVARIANTS_MARKER),
                "блок инвариантов — последняя system-строка seed: ${systemMessages.map { it.content?.take(40) }}"
            )

            assertEquals(1, response.invariants?.global?.size, "echo: глобальный скоуп")
            assertEquals(1, response.invariants?.session?.size, "echo: сессионный скоуп")
            assertEquals(globalText, response.invariants?.global?.first()?.text)
            assertEquals(sessionText, response.invariants?.session?.first()?.text)
            client.close()
        }
    }

    // --- Guard пустоты ---

    @Test
    fun `оба скоупа пусты - блока инвариантов нет и эхо null`() {
        withApp(seedGlobal = false) { base, fake, store, userId ->
            val client = httpClient()
            val sid = store.create(userId).id

            val response = client.chat(base, "обычный вопрос", sid)

            assertFalse(
                fake.calls.last().systemTexts().any { it.contains(INVARIANTS_MARKER) },
                "пустые скоупы — блок не инъектируется: ${fake.calls.last().systemTexts()}"
            )
            assertNull(response.invariants, "пустые скоупы — echo null")
            client.close()
        }
    }

    // --- Trim-pressure ---

    @Test
    fun `блок инвариантов переживает trim при длинной истории`() {
        withApp(seedGlobal = true) { base, fake, store, userId ->
            val client = httpClient()
            val sid = store.create(userId).id
            store.updateInvariants(sid, listOf(StoredInvariant.create(sessionText)))

            // Длинная история: 12 ходов по ~500 символов
            repeat(12) { turn ->
                client.chat(base, "ход номер $turn: ${"д".repeat(500)}", sid)
            }

            // Резерв под «Топливо» режет бюджет истории: старые ходы уходят
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "итоговый вопрос", sessionId = sid, maxTokens = 23000))
            }.body<ChatResponse>()

            val main = fake.calls.last()
            val block = assertNotNull(main.invariantsBlock(), "блок инвариантов должен присутствовать после trim")
            assertTrue(block.contains(globalText), "глобальный инвариант уцелел: $block")
            assertTrue(block.contains(sessionText), "сессионный инвариант уцелел: $block")
            assertTrue(
                main.messages.count { it.role == "user" } < 12,
                "trim должен был срезать старую историю: ${main.messages.count { it.role == "user" }}"
            )
            client.close()
        }
    }
}
