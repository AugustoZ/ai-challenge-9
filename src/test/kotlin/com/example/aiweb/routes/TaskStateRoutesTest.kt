package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.Session
import com.example.aiweb.session.SummarizeResult
import com.example.aiweb.task.TaskStage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FSM состояния задачи на сервере (задача 5 плана task-state-fsm): pipeline
 * в finalizeResponse (стрип до истории/судьи, свежее перечитание + paused-wins,
 * echo каждый ход), инъекция системного блока в seed, POST /api/task/pause|resume,
 * chat-команды «пауза»/«продолжить» до LLM. Все тесты — на фейковом LLM, без сети.
 */
class TaskStateRoutesTest {

    private class FakeLLMClient(reply: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        @Volatile
        var next: ChatResult = reply

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            gate?.await()
            return next
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-fsm").toString()

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

    private fun replyWithBlock(stage: String, step: String = "Шаг $stage"): ChatResult =
        ChatResult(
            reply = "Ответ модели.\n\n```task-state\n" +
                "{\"stage\": \"$stage\", \"step\": \"$step\", \"expected_action\": \"Действие $stage\"}\n" +
                "```",
            promptTokens = 1,
            completionTokens = 2,
            totalTokens = 3,
            elapsedMs = 42L
        )

    private suspend fun chat(
        client: HttpClient,
        base: String,
        message: String,
        sessionId: String? = null
    ): ChatResponse = client.post("$base/api/chat") {
        contentType(ContentType.Application.Json)
        setBody(ChatRequest(message = message, sessionId = sessionId))
    }.body<ChatResponse>()

    private suspend fun taskAction(
        client: HttpClient,
        base: String,
        action: String,
        sessionId: String
    ): HttpResponse = client.post("$base/api/task/$action") {
        contentType(ContentType.Application.Json)
        setBody("""{"sessionId":"$sessionId"}""")
    }

    private suspend fun awaitCalls(fake: FakeLLMClient, expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (fake.calls.size < expected) {
            check(System.currentTimeMillis() < deadline) { "LLM-вызов №$expected не случился" }
            delay(20)
        }
    }

    // --- Pipeline: legal / illegal / нет блока ---

    @Test
    fun `legal-переход мока применяется и эхоится каждый ход`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val created = chat(client, base, "начало работы")
            val sid = assertNotNull(created.sessionId)
            assertEquals(null, created.taskState, "до первого блока состояния быть не должно")

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            val planning = chat(client, base, "планируем", sid)
            assertEquals(TaskStage.PLANNING, planning.taskState?.stage)
            assertEquals("Составление плана", planning.taskState?.step)

            fake.next = replyWithBlock("EXECUTION", "Исполнение по плану")
            val execution = chat(client, base, "работаем", sid)
            assertEquals(TaskStage.EXECUTION, execution.taskState?.stage)
            assertEquals("Исполнение по плану", execution.taskState?.step)

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(TaskStage.EXECUTION, stored.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `illegal-переход done на execution отклоняется - состояние прежнее и ответ 200`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            for (stage in listOf("PLANNING", "EXECUTION", "VALIDATION", "DONE")) {
                fake.next = replyWithBlock(stage)
                chat(client, base, "ход $stage", sid)
            }

            fake.next = replyWithBlock("EXECUTION", "Прыжок с done")
            val response = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "прыжок с готового", sessionId = sid))
            }
            assertEquals(200, response.status.value)
            val body = response.body<ChatResponse>()
            assertEquals(TaskStage.DONE, body.taskState?.stage, "нелегальный переход не применился")
            client.close()
        }
    }

    @Test
    fun `нет блока в ответе - состояние не меняется`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = ChatResult(
                reply = "обычный ответ без блока",
                promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L
            )
            val response = chat(client, base, "без блока", sid)
            assertEquals(TaskStage.PLANNING, response.taskState?.stage)

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals("Составление плана", stored.taskState?.step, "step не должен перетираться пустым")
            client.close()
        }
    }

    // --- G2: пауза в полёте ---

    @Test
    fun `пауза в полёте побеждает переход от LLM - финализация не перезаписывает paused`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)

            val result = CompletableDeferred<ChatResponse>()
            fake.next = replyWithBlock("EXECUTION", "Не должен примениться")
            fake.gate = CompletableDeferred()
            coroutineScope {
                launch { result.complete(chat(client, base, "медленный ход", sid)) }
                awaitCalls(fake, 3)

                val paused = taskAction(client, base, "pause", sid)
                assertEquals(200, paused.status.value)
                val pausedState = Json.parseToJsonElement(paused.bodyAsText()).jsonObject["taskState"]!!.jsonObject
                assertEquals("PAUSED", pausedState["stage"]!!.jsonPrimitive.content)
                assertEquals("PLANNING", pausedState["prevStage"]!!.jsonPrimitive.content)

                fake.gate?.complete(Unit)
            }
            assertTrue(result.isCompleted)
            val finalized = result.await()
            assertEquals(TaskStage.PAUSED, finalized.taskState?.stage, "G2: финализация не должна затирать паузу")

            // Эхо следующим обычным сообщением (НЕ /api/task/resume — он мутирует состояние)
            fake.gate = null
            val echo = chat(client, base, "обычное сообщение", sid)
            assertEquals(TaskStage.PAUSED, echo.taskState?.stage)
            client.close()
        }
    }

    // --- Chat-команды ---

    @Test
    fun `команда пауза перехватывается до LLM - системный ответ, LLM не вызван`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)
            val callsBefore = fake.calls.size

            val paused = chat(client, base, "пауза", sid)
            assertEquals(callsBefore, fake.calls.size, "LLM не должен вызываться командой")
            assertEquals("Задача поставлена на паузу. Этап: planning", paused.reply)
            assertEquals(TaskStage.PAUSED, paused.taskState?.stage)

            val slash = chat(client, base, "/пауза", sid)
            assertEquals(callsBefore, fake.calls.size, "«/пауза» — та же команда")
            assertEquals(TaskStage.PAUSED, slash.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `команда продолжить снимает паузу - этап восстанавливается`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)
            val callsBefore = fake.calls.size

            val paused = chat(client, base, "пауза", sid)
            assertEquals(TaskStage.PAUSED, paused.taskState?.stage)

            val resumed = chat(client, base, "продолжить", sid)
            assertEquals(callsBefore, fake.calls.size, "LLM не должен вызываться командой")
            assertEquals("Задача продолжена. Этап: planning", resumed.reply)
            assertEquals(TaskStage.PLANNING, resumed.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `пауза без задачи и продолжить без паузы - системные ответы без LLM`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val paused = chat(client, base, "пауза", sid)
            assertEquals("Нет активной задачи", paused.reply)
            assertEquals(null, paused.taskState)

            val resumed = chat(client, base, "продолжить", sid)
            assertEquals("Нет активной задачи", resumed.reply)
            assertEquals(callsBefore, fake.calls.size)
            client.close()
        }
    }

    @Test
    fun `сделай паузу командой не является - уходит в LLM (E8)`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)
            val callsBefore = fake.calls.size

            val response = chat(client, base, "сделай паузу", sid)
            assertEquals(callsBefore + 1, fake.calls.size, "«сделай паузу» должно уйти в LLM")
            assertEquals("ответ сервера", response.reply, "ответ должен прийти от LLM, а не системный")
            assertEquals(null, response.taskState)
            client.close()
        }
    }

    // --- API паузы/продолжения ---

    @Test
    fun `неизвестная сессия паузы и продолжения - 404`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            for (action in listOf("pause", "resume")) {
                val response = client.post("$base/api/task/$action") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"unknown-123"}""")
                }
                assertEquals(404, response.status.value, "$action неизвестной сессии")
            }
            client.close()
        }
    }

    @Test
    fun `пауза и продолжение через API идемпотентны - повтор не портит состояние`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("EXECUTION")
            chat(client, base, "работаем", sid)

            val first = taskAction(client, base, "pause", sid)
            assertEquals(
                "PAUSED",
                Json.parseToJsonElement(first.bodyAsText()).jsonObject["taskState"]!!.jsonObject["stage"]!!.jsonPrimitive.content
            )
            val second = taskAction(client, base, "pause", sid)
            val still = Json.parseToJsonElement(second.bodyAsText()).jsonObject["taskState"]!!.jsonObject
            assertEquals("PAUSED", still["stage"]!!.jsonPrimitive.content, "повторная пауза — no-op")
            assertEquals("EXECUTION", still["prevStage"]!!.jsonPrimitive.content)

            val resumed = taskAction(client, base, "resume", sid)
            assertEquals(
                "EXECUTION",
                Json.parseToJsonElement(resumed.bodyAsText()).jsonObject["taskState"]!!.jsonObject["stage"]!!.jsonPrimitive.content
            )
            client.close()
        }
    }

    @Test
    fun `пауза без состояния задачи - no-op с пустым taskState`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            val response = taskAction(client, base, "pause", sid)
            assertEquals(200, response.status.value)
            assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("taskState"))
            assertEquals(
                "null",
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["taskState"]!!.toString()
            )
            client.close()
        }
    }

    // --- Отмена стрима ---

    @Test
    fun `отмена стрима не трогает taskState (E12)`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)

            fake.next = replyWithBlock("EXECUTION", "Не должен примениться после отмены")
            fake.gate = CompletableDeferred()
            coroutineScope {
                val stream = launch {
                    client.post("$base/api/chat/stream") {
                        contentType(ContentType.Application.Json)
                        setBody(ChatRequest(message = "медленный ход", sessionId = sid, requestId = "cancel-fsm"))
                    }.bodyAsText()
                }
                awaitCalls(fake, 3)

                val cancelled = client.post("$base/api/cancel") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"requestId":"cancel-fsm"}""")
                }
                assertEquals(200, cancelled.status.value)
                assertTrue(Json.parseToJsonElement(cancelled.bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean)

                stream.join()
            }
            fake.gate = null

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(TaskStage.PLANNING, stored.taskState?.stage, "отмена не должна менять состояние")
            client.close()
        }
    }

    // --- G3: история и ответ без блока ---

    @Test
    fun `reply и история не содержат блок task-state (G3)`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            val response = chat(client, base, "планируем", sid)

            assertFalse(response.reply.contains("task-state"), "клиенту уходит стрипнутый reply: ${response.reply}")
            assertFalse(response.reply.contains("```"))

            val stored = client.get("$base/api/session/$sid").body<Session>()
            val assistant = stored.messages.last { it.role == "assistant" }
            assertFalse(assistant.content.orEmpty().contains("task-state"), "история не должна содержать блок")
            assertTrue(assistant.content.orEmpty().contains("Ответ модели"), "текст без блока сохранён")
            client.close()
        }
    }

    // --- Инъекция системного блока ---

    @Test
    fun `исходящий запрос содержит системный блок состояния с текущим шагом`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            chat(client, base, "начало")
            assertTrue(
                fake.calls.last().messages.any {
                    it.role == "system" && it.content.orEmpty() == "Задача не начата. Приступая, объяви planning."
                },
                "null-состояние: лёгкий вариант «задача не начата»"
            )

            val sid = assertNotNull(chat(client, base, "второй ход").sessionId)
            fake.next = replyWithBlock("PLANNING", "Составление плана полёта")
            chat(client, base, "планируем", sid)

            // Состояние применяется ПОСЛЕ ответа — следующий ход инжектит уже
            // применённое состояние (этап+шаг предыдущих ходов)
            chat(client, base, "следующий ход", sid)

            val active = assertNotNull(
                fake.calls.last().messages.lastOrNull {
                    it.role == "system" && it.content.orEmpty().contains("ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ")
                },
                "активная задача: блок состояния должен быть в seed"
            )
            assertTrue(active.content!!.contains("этап=planning"), active.content)
            assertTrue(active.content.contains("шаг=Составление плана полёта"), active.content)
            assertTrue(active.content.contains("expected_action"), active.content)
            client.close()
        }
    }

    @Test
    fun `инъекция на паузе содержит запрет переходов`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)
            assertEquals(200, taskAction(client, base, "pause", sid).status.value)

            chat(client, base, "уточняющий вопрос", sid)
            val block = assertNotNull(
                fake.calls.last().messages.lastOrNull {
                    it.role == "system" && it.content.orEmpty().contains("Задача на паузе")
                },
                "пауза: блок-запрет должен быть в seed"
            )
            assertTrue(block.content!!.contains("Не объявляй переходы"), block.content)
            assertFalse(
                fake.calls.last().messages.any {
                    it.role == "system" && it.content.orEmpty().contains("ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ")
                },
                "на паузе активный блок с этапом не инжектится"
            )
            client.close()
        }
    }

    @Test
    fun `блок состояния не пишется в историю сессии`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING")
            chat(client, base, "планируем", sid)

            val stored = client.get("$base/api/session/$sid").body<Session>()
            stored.messages.forEach { message ->
                assertFalse(
                    message.content.orEmpty().contains("ТЕКУЩЕЕ СОСТОЯНИЕ"),
                    "инъекция не должна попадать в Session.messages"
                )
            }
            client.close()
        }
    }
}
