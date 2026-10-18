package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.invariants.GlobalInvariantStore
import com.example.aiweb.invariants.StoredInvariant
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.InvariantsPayload
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.Session
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chat-команды инвариантов (задача 7 плана assistant-invariants): exact «инварианты»,
 * префиксы «инвариант »/«убери инвариант » — перехват до LLM (FakeLLMClient.calls не
 * растёт), валидация/дубль/кап — системные ответы 200, echo InvariantsPayload в каждом
 * ответе, команды в середине сообщения уходят в LLM.
 */
class InvariantsCommandTest {

    private class FakeLLMClient(reply: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        @Volatile
        var next: ChatResult = reply

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return next
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-invariants-cmd").toString()

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

    private fun withApp(block: suspend (base: String, fake: FakeLLMClient, dataDir: String) -> Unit) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir),
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : HistorySummarizer {
                            override suspend fun summarize(previous: String?, batch: List<OpenAIMessage>) =
                                SummarizeResult("", 0)
                        }
                    },
                    factExtractorFactory = {
                        object : FactExtractor {
                            override suspend fun extract(
                                current: List<com.example.aiweb.model.Fact>,
                                userMessage: String
                            ): List<com.example.aiweb.model.Fact>? = null
                        }
                    }
                )
            }
        }) { app ->
            runBlocking { block(app.base, chat, dataDir) }
        }
    }

    private suspend fun chat(
        client: HttpClient,
        base: String,
        message: String,
        sessionId: String? = null
    ): ChatResponse = client.post("$base/api/chat") {
        contentType(ContentType.Application.Json)
        setBody(ChatRequest(message = message, sessionId = sessionId))
    }.body<ChatResponse>()

    private suspend fun chatRaw(
        client: HttpClient,
        base: String,
        message: String,
        sessionId: String
    ): HttpResponse = client.post("$base/api/chat") {
        contentType(ContentType.Application.Json)
        setBody(ChatRequest(message = message, sessionId = sessionId))
    }

    private suspend fun awaitCalls(fake: FakeLLMClient, expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (fake.calls.size < expected) {
            check(System.currentTimeMillis() < deadline) { "LLM-вызов №$expected не случился" }
            delay(20)
        }
    }

    // --- Полный командный цикл: add → list → remove, без LLM ---

    @Test
    fun `полный цикл add list remove - подтверждения со списком и echo, LLM не вызван`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val added = chat(client, base, "инвариант Только Kotlin", sid)
            assertEquals(callsBefore, fake.calls.size, "add-команда не должна вызывать LLM")
            assertTrue(added.reply.contains("Инвариант добавлен: Только Kotlin"), added.reply)
            val addedEcho = assertNotNull(added.invariants, "add-ответ должен эхоить инварианты")
            assertTrue(addedEcho.session.any { it.text == "Только Kotlin" })
            assertTrue(addedEcho.global.isEmpty())

            val slashAdded = chat(client, base, "/инвариант Второй", sid)
            assertEquals(callsBefore, fake.calls.size)
            assertTrue(slashAdded.reply.contains("Инвариант добавлен: Второй"), slashAdded.reply)

            val listed = chat(client, base, "инварианты", sid)
            assertEquals(callsBefore, fake.calls.size, "list-команда не должна вызывать LLM")
            assertTrue(listed.reply.contains("1. Только Kotlin"), listed.reply)
            assertTrue(listed.reply.contains("2. Второй"), listed.reply)
            assertTrue(listed.reply.contains("Глобальных инвариантов: 0"), listed.reply)
            val listedEcho = assertNotNull(listed.invariants)
            assertEquals(2, listedEcho.session.size)

            val removed = chat(client, base, "убери инвариант 1", sid)
            assertEquals(callsBefore, fake.calls.size, "remove-команда не должна вызывать LLM")
            assertTrue(removed.reply.contains("Инвариант удалён: Только Kotlin"), removed.reply)
            val removedEcho = assertNotNull(removed.invariants, "после удаления в echo остаётся «Второй»")
            assertEquals(listOf("Второй"), removedEcho.session.map { it.text })

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(listOf("Второй"), stored.invariants.map { it.text })
            client.close()
        }
    }

    // --- Удаление: мягкие подсказки (200, не ошибка) ---

    @Test
    fun `убери инвариант 99 и абв - мягкая подсказка с примером, инвариант на месте`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            chat(client, base, "инвариант Только Kotlin", sid)
            val callsBefore = fake.calls.size

            val outOfRange = chatRaw(client, base, "убери инвариант 99", sid)
            assertEquals(200, outOfRange.status.value, "вне диапазона — 200, не ошибка")
            val outOfRangeBody = outOfRange.body<ChatResponse>()
            assertTrue(outOfRangeBody.reply.contains("Пример: «убери инвариант 1»"), outOfRangeBody.reply)
            assertTrue(outOfRangeBody.reply.contains("в сессии 1"), outOfRangeBody.reply)
            assertTrue(outOfRangeBody.invariants!!.session.any { it.text == "Только Kotlin" })

            val notANumber = chat(client, base, "убери инвариант абв", sid)
            assertTrue(notANumber.reply.contains("Пример: «убери инвариант 1»"), notANumber.reply)
            assertEquals(callsBefore, fake.calls.size, "подсказки не должны вызывать LLM")

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(listOf("Только Kotlin"), stored.invariants.map { it.text }, "ничего не удалилось")
            client.close()
        }
    }

    // --- Список и подсказки без текста ---

    @Test
    fun `инварианты exact на пустой сессии - заглушка и глобальный блок, LLM не вызван`() {
        withApp { base, fake, dataDir ->
            GlobalInvariantStore(dataDir).add("Глобальное правило")
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val listed = chat(client, base, "инварианты", sid)
            assertEquals(callsBefore, fake.calls.size)
            assertTrue(listed.reply.contains("Сессионных инвариантов нет. Добавьте: „инвариант <текст>“."), listed.reply)
            assertTrue(listed.reply.contains("Глобальное правило"), listed.reply)
            assertTrue(listed.reply.contains("управляются через панель настроек"), listed.reply)
            val echo = assertNotNull(listed.invariants)
            assertTrue(echo.global.any { it.text == "Глобальное правило" })
            assertTrue(echo.session.isEmpty())
            client.close()
        }
    }

    @Test
    fun `инвариант без текста и слеш-форма - подсказка без LLM`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val bare = chat(client, base, "инвариант", sid)
            assertEquals(callsBefore, fake.calls.size)
            assertTrue(bare.reply.contains("Укажите текст инварианта"), bare.reply)

            val bareSlash = chat(client, base, "/инвариант", sid)
            assertTrue(bareSlash.reply.contains("Укажите текст инварианта"), bareSlash.reply)

            assertEquals(callsBefore, fake.calls.size)
            client.close()
        }
    }

    // --- Валидация: дубль и кап ---

    @Test
    fun `дубль текста сессии и глобального - системный ответ, список не растёт`() {
        withApp { base, fake, dataDir ->
            GlobalInvariantStore(dataDir).add("Глобальное правило")
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            chat(client, base, "инвариант Только Kotlin", sid)
            val callsBefore = fake.calls.size

            val sessionDup = chat(client, base, "инвариант Только Kotlin", sid)
            assertTrue(sessionDup.reply.contains("Такой инвариант уже есть"), sessionDup.reply)
            assertEquals(callsBefore, fake.calls.size)

            val globalDup = chat(client, base, "инвариант Глобальное правило", sid)
            assertTrue(globalDup.reply.contains("Такой инвариант уже есть"), globalDup.reply)

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(1, stored.invariants.size, "дубли не добавляются")
            client.close()
        }
    }

    @Test
    fun `21-й инвариант отклоняется капом - системный ответ, осталось 20`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            for (i in 1..StoredInvariant.MAX_COUNT) {
                val added = chat(client, base, "инвариант Правило $i", sid)
                assertTrue(added.reply.contains("Инвариант добавлен"), added.reply)
            }
            val callsBefore = fake.calls.size

            val capped = chat(client, base, "инвариант Правило 21", sid)
            assertEquals(callsBefore, fake.calls.size, "кап-ответ — системный, без LLM")
            assertTrue(capped.reply.contains("Лимит"), capped.reply)
            assertTrue(capped.reply.contains("${StoredInvariant.MAX_COUNT}"), capped.reply)

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(StoredInvariant.MAX_COUNT, stored.invariants.size)
            client.close()
        }
    }

    // --- Не-команды уходят в LLM ---

    @Test
    fun `инвариант внутри длинного сообщения - уходит в LLM (не команда)`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val response = chat(client, base, "Напомни мне, инвариант foo соблюдай всегда", sid)
            awaitCalls(fake, callsBefore + 1)
            assertEquals(callsBefore + 1, fake.calls.size, "команда в середине — не команда")
            assertEquals("ответ сервера", response.reply, "ответ должен прийти от LLM")
            assertNull(response.invariants, "обычный ответ без инвариантов не содержит echo")
            client.close()
        }
    }

    // --- Регресс: пауз-команды работают как раньше ---

    @Test
    fun `регресс пауза и продолжить - работают как раньше рядом с инвариант-командами`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            fake.next = ChatResult(
                reply = "Ответ модели.\n\n```task-state\n" +
                    "{\"stage\": \"PLANNING\", \"step\": \"Составление плана\", \"expected_action\": \"Действие\"}\n" +
                    "```",
                promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L
            )
            chat(client, base, "планируем", sid)
            val callsAfterPlanning = fake.calls.size

            val paused = chat(client, base, "пауза", sid)
            assertEquals(callsAfterPlanning, fake.calls.size)
            assertEquals("Задача поставлена на паузу. Этап: planning", paused.reply)

            val resumed = chat(client, base, "продолжить", sid)
            assertEquals(callsAfterPlanning, fake.calls.size)
            assertEquals("Задача продолжена. Этап: planning", resumed.reply)

            val invariants = chat(client, base, "инварианты", sid)
            assertTrue(invariants.reply.contains("Сессионных инвариантов нет"), invariants.reply)
            assertEquals(callsAfterPlanning, fake.calls.size)
            client.close()
        }
    }

    // --- Echo: null, когда оба скоупа пусты ---

    @Test
    fun `list-ответ на пустых скоупах всё равно системный - echo отсутствует только при пустоте`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            chat(client, base, "инвариант Только Kotlin", sid)
            chat(client, base, "убери инвариант 1", sid)

            val response = chat(client, base, "инварианты", sid)
            val echo: InvariantsPayload? = response.invariants
            assertNull(echo, "оба скоупа пусты — echo omitится, как в finalizeResponse")
            assertTrue(response.reply.contains("Сессионных инвариантов нет"), response.reply)
            client.close()
        }
    }

    // --- Echo паузы/продолжения (ревью F1): command-ответ тоже эхоит инварианты ---

    @Test
    fun `ответ паузы эхоит сессионный инвариант - панель не затирается`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            fake.next = ChatResult(
                reply = "Ответ модели.\n\n```task-state\n" +
                    "{\"stage\": \"PLANNING\", \"step\": \"Составление плана\", \"expected_action\": \"Действие\"}\n" +
                    "```",
                promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L
            )
            chat(client, base, "планируем", sid)
            chat(client, base, "инвариант Только Kotlin", sid)
            val callsBefore = fake.calls.size

            val paused = chat(client, base, "пауза", sid)
            assertEquals(callsBefore, fake.calls.size, "пауза не должна вызывать LLM")
            assertEquals("Задача поставлена на паузу. Этап: planning", paused.reply)
            val echo = assertNotNull(paused.invariants, "пауза без echo затрёт панель на фронте")
            assertEquals(listOf("Только Kotlin"), echo.session.map { it.text })

            val resumed = chat(client, base, "продолжить", sid)
            val resumedEcho = assertNotNull(resumed.invariants, "продолжить эхоит так же")
            assertEquals(listOf("Только Kotlin"), resumedEcho.session.map { it.text })
            client.close()
        }
    }

    @Test
    fun `пауза на пустых скоупах - echo null, как в finalizeResponse`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            fake.next = ChatResult(
                reply = "Ответ модели.\n\n```task-state\n" +
                    "{\"stage\": \"PLANNING\", \"step\": \"Составление плана\", \"expected_action\": \"Действие\"}\n" +
                    "```",
                promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L
            )
            chat(client, base, "планируем", sid)

            val paused = chat(client, base, "пауза", sid)
            assertEquals("Задача поставлена на паузу. Этап: planning", paused.reply)
            assertNull(paused.invariants, "оба скоупа пусты — ключ omitится")
            client.close()
        }
    }
}
