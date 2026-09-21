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
import com.example.aiweb.task.TaskStateJson
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
import kotlin.test.assertNull
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

    private fun plainReply(text: String): ChatResult =
        ChatResult(reply = text, promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)

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

            fake.next = replyWithBlock("PLAN_APPROVED", "План утверждён")
            val approved = chat(client, base, "утверждаем план", sid)
            assertEquals(TaskStage.PLAN_APPROVED, approved.taskState?.stage)

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

            for (stage in listOf("PLANNING", "PLAN_APPROVED", "EXECUTION", "VALIDATION", "DONE")) {
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
            fake.next = replyWithBlock("PLAN_APPROVED")
            chat(client, base, "утверждаем план", sid)
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

    // --- Авто-FSM: фолбэк без блока task-state ---

    @Test
    fun `многошаговый запрос без блока создаёт PLANNING и пауза доступна`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = plainReply("Приступаю к работе.")
            val created = chat(client, base, "Сделай план, выполни и проверь", sid)
            assertEquals(TaskStage.PLANNING, created.taskState?.stage, "фолбэк создал задачу без блока")
            assertEquals("Сделай план, выполни и проверь", created.taskState?.step)

            val paused = taskAction(client, base, "pause", sid)
            assertEquals(200, paused.status.value)
            assertEquals(
                "PAUSED",
                Json.parseToJsonElement(paused.bodyAsText()).jsonObject["taskState"]!!
                    .jsonObject["stage"]!!.jsonPrimitive.content
            )
            client.close()
        }
    }

    @Test
    fun `лестница доходит до done - утверждение блоком, продвижение маркерами`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            // Создание фолбэком (null → PLANNING); из PLANNING фолбэк вперёд не
            // идёт — план утверждает LLM-блок по реплике пользователя
            fake.next = plainReply("Приступаю к работе.")
            val planning = chat(client, base, "Сделай план, выполни и проверь", sid)
            assertEquals(TaskStage.PLANNING, planning.taskState?.stage)

            fake.next = replyWithBlock("PLAN_APPROVED", "План утверждён")
            val approved = chat(client, base, "утверждаем план", sid)
            assertEquals(TaskStage.PLAN_APPROVED, approved.taskState?.stage)

            // Дальше эвристическая лестница по маркерам, без блоков
            fake.next = plainReply("Вот код:\n```\nfun f() {}\n```")
            val execution = chat(client, base, "выполняй", sid)
            assertEquals(TaskStage.EXECUTION, execution.taskState?.stage)

            fake.next = plainReply("проверил, ошибок нет")
            val validation = chat(client, base, "работаем", sid)
            assertEquals(TaskStage.VALIDATION, validation.taskState?.stage)

            fake.next = plainReply("Все тесты зелёные. Готово.")
            val done = chat(client, base, "заканчиваем", sid)
            assertEquals(TaskStage.DONE, done.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `блок побеждает эвристику`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = plainReply("Приступаю к работе.")
            chat(client, base, "Сделай план, выполни и проверь", sid)

            fake.next = ChatResult(
                reply = "План утверждён.\n\n```task-state\n" +
                    "{\"stage\": \"PLAN_APPROVED\", \"step\": \"Шаг из блока\", \"expected_action\": \"Действие\"}\n" +
                    "```",
                promptTokens = 1,
                completionTokens = 2,
                totalTokens = 3,
                elapsedMs = 42L
            )
            chat(client, base, "утверждаем", sid)

            fake.next = ChatResult(
                reply = "Вот код:\n```\nfun f() {}\n```\n\n```task-state\n" +
                    "{\"stage\": \"EXECUTION\", \"step\": \"Из блока\", \"expected_action\": \"Действие\"}\n" +
                    "```",
                promptTokens = 1,
                completionTokens = 2,
                totalTokens = 3,
                elapsedMs = 42L
            )
            val response = chat(client, base, "работаем", sid)

            assertEquals(TaskStage.EXECUTION, response.taskState?.stage)
            assertEquals("Из блока", response.taskState?.step, "применён блок, а не эвристика")
            client.close()
        }
    }

    @Test
    fun `отказ модели не отменяет пре-создание - задача уже заведена по интенту`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            // Создание переехало на старт хода (ensureForRequest, до LLM): сигнал —
            // только интент пользователя, отказ модели в ответе уже не может его
            // отменить. infer в finalize видит current=PLANNING и не продвигает
            fake.next = plainReply("извините, я не могу")
            val response = chat(client, base, "напиши и затем проверь", sid)

            assertEquals(TaskStage.PLANNING, response.taskState?.stage, "задача пре-создана по интенту")
            assertEquals("напиши и затем проверь", response.taskState?.step)
            client.close()
        }
    }

    // --- Пре-создание на старте хода: ранний кадр статуса в стриме ---

    @Test
    fun `ранний кадр статуса несёт taskState до событий агента`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = plainReply("Приступаю к работе.")
            val stream = client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "Сделай план, выполни и проверь", sessionId = sid))
            }.bodyAsText()

            val frames = stream.split("\n\n").filter { it.startsWith("data: ") }
                .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
            assertTrue(frames.isNotEmpty(), "стрим не пуст")

            val first = frames.first()
            assertEquals("status", first["type"]!!.jsonPrimitive.content, "первый кадр — ранний статус")
            assertEquals("Задача заведена: планирование", first["text"]!!.jsonPrimitive.content)
            assertEquals(
                "PLANNING",
                first["taskState"]!!.jsonObject["stage"]!!.jsonPrimitive.content,
                "taskState придёт до agent-кадров — кнопка «Пауза» активна во время генерации"
            )
            assertEquals(
                sid,
                first["sessionId"]!!.jsonPrimitive.content,
                "sessionId в раннем кадре — клик паузы первого полёта не уйдёт в 400"
            )

            val stored = client.get("$base/api/session/$sid").body<Session>()
            assertEquals(TaskStage.PLANNING, stored.taskState?.stage)
            client.close()
        }
    }

    // --- Отклонённый LLM-блок (задача 6 плана task-lifecycle-gates) ---

    @Test
    fun `нелегальный блок - taskRejected заполнен, stage не меняется`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            val planning = chat(client, base, "планируем", sid)
            assertEquals(TaskStage.PLANNING, planning.taskState?.stage)
            assertNull(planning.taskRejected, "до нелегального блока отказа нет")

            fake.next = replyWithBlock("EXECUTION", "Прыжок через план")
            val response = chat(client, base, "работаем", sid)
            assertNotNull(response.taskRejected, "нелегальный блок фиксирует отказ")
            assertTrue(response.taskRejected!!.contains("PLANNING → EXECUTION"), response.taskRejected)
            assertEquals(TaskStage.PLANNING, response.taskState?.stage, "нелегальный переход не применился")
            client.close()
        }
    }

    @Test
    fun `нелегальный блок - seed следующего хода содержит блок отклонённого перехода`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("EXECUTION", "Прыжок через план")
            chat(client, base, "работаем", sid)

            fake.next = plainReply("обычный ответ")
            chat(client, base, "следующий ход", sid)

            val rejected = fake.calls.last().messages.lastOrNull {
                it.role == "system" && it.content.orEmpty().contains("ПОСЛЕДНИЙ ПРЕДЛОЖЕННЫЙ ПЕРЕХОД ОТКЛОНЁН")
            }
            assertNotNull(rejected, "блок отклонённого перехода должен быть в seed")
            assertTrue(rejected!!.content!!.contains("PLANNING → EXECUTION"), rejected.content)
            client.close()
        }
    }

    @Test
    fun `легальный блок - taskRejected очищен`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("EXECUTION", "Прыжок через план")
            val rejected = chat(client, base, "работаем", sid)
            assertNotNull(rejected.taskRejected, "отказ зафиксирован")

            fake.next = replyWithBlock("PLAN_APPROVED", "План утверждён")
            val approved = chat(client, base, "утверждаем план", sid)
            assertNull(approved.taskRejected, "легальный переход очищает отказ")
            assertEquals(TaskStage.PLAN_APPROVED, approved.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `ход без блока - taskRejected очищен`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("EXECUTION", "Прыжок через план")
            val rejected = chat(client, base, "работаем", sid)
            assertNotNull(rejected.taskRejected, "отказ зафиксирован")

            fake.next = plainReply("обычный ответ без блока")
            val response = chat(client, base, "обычный вопрос", sid)
            assertNull(response.taskRejected, "ход без блока снимает отказ")
            assertEquals(TaskStage.PLANNING, response.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `блок из EXECUTION в VALIDATION - легальный, отказа нет`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            for (stage in listOf("PLANNING", "PLAN_APPROVED", "EXECUTION")) {
                fake.next = replyWithBlock(stage)
                chat(client, base, "ход $stage", sid)
            }

            fake.next = replyWithBlock("VALIDATION", "Проверка результата")
            val response = chat(client, base, "проверяем", sid)
            assertNull(response.taskRejected, "EXECUTION→VALIDATION легален")
            assertEquals(TaskStage.VALIDATION, response.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `нелегальный блок при DONE - taskRejected с описанием и без чекпоинта в seed`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            for (stage in listOf("PLANNING", "PLAN_APPROVED", "EXECUTION", "VALIDATION", "DONE")) {
                fake.next = replyWithBlock(stage)
                chat(client, base, "ход $stage", sid)
            }

            fake.next = replyWithBlock("EXECUTION", "Прыжок с done")
            val response = chat(client, base, "прыжок с готового", sid)
            assertNotNull(response.taskRejected, "нелегальный блок при DONE фиксирует отказ")
            assertTrue(response.taskRejected!!.contains("DONE → EXECUTION"), response.taskRejected)
            assertEquals(TaskStage.DONE, response.taskState?.stage, "нелегальный переход не применился")

            fake.next = plainReply("обычный ответ")
            chat(client, base, "следующий ход", sid)
            val doneBlock = assertNotNull(
                fake.calls.last().messages.lastOrNull {
                    it.role == "system" && it.content.orEmpty().contains("ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ")
                },
                "активный блок состояния есть и при DONE"
            )
            assertTrue(doneBlock.content!!.contains("этап=done"), doneBlock.content)
            assertFalse(
                doneBlock.content!!.contains("ОБЯЗАТЕЛЬНО заверши ответ вопросом"),
                "на DONE чекпоинт не инжектится"
            )
            client.close()
        }
    }

    @Test
    fun `чекпоинт-инструкция в seed активной задачи`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана полёта")
            chat(client, base, "планируем", sid)

            fake.next = plainReply("обычный ответ")
            chat(client, base, "следующий ход", sid)

            val block = assertNotNull(
                fake.calls.last().messages.lastOrNull {
                    it.role == "system" && it.content.orEmpty().contains("ТЕКУЩЕЕ СОСТОЯНИЕ ЗАДАЧИ")
                },
                "активная задача: блок состояния должен быть в seed"
            )
            assertTrue(block.content!!.contains("ОБЯЗАТЕЛЬНО заверши ответ вопросом"), block.content)
            assertTrue(block.content.contains("Не переходи к следующему этапу до явного ответа"), block.content)
            client.close()
        }
    }

    @Test
    fun `без намерения раннего статус-кадра с taskState нет`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = plainReply("Просто ответ.")
            val stream = client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "Привет", sessionId = sid))
            }.bodyAsText()

            val frames = stream.split("\n\n").filter { it.startsWith("data: ") }
                .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
            assertTrue(
                frames.none { it.containsKey("taskState") },
                "без многошагового намерения задача не заводится — раннего кадра нет"
            )
            client.close()
        }
    }

    // --- Контент-гейт (план bypass-fixes, задача 3) ---

    /** Ответ-код-дамп: несколько фенсированных блоков, БЕЗ блока task-state. */
    private fun codeDumpReply(blocks: Int = 3, linesPerBlock: Int = 4): ChatResult {
        val sb = StringBuilder("Вот реализация:\n")
        repeat(blocks) { b ->
            sb.append("\n```python\n")
            repeat(linesPerBlock) { l -> sb.append("строка $l блока ${b + 1}\n") }
            sb.append("```")
        }
        return plainReply(sb.toString())
    }

    private fun singleBigBlockReply(lines: Int = 12): ChatResult {
        val sb = StringBuilder("```python\n")
        repeat(lines) { l -> sb.append("строка кода $l\n") }
        sb.append("```")
        return plainReply(sb.toString())
    }

    private fun replyWithCodeAndTaskState(stage: String, codeBlocks: Int = 3): ChatResult {
        val dump = codeDumpReply(codeBlocks)
        return ChatResult(
            reply = dump.reply + "\n```task-state\n" +
                "{\"stage\": \"$stage\", \"step\": \"Шаг $stage\", \"expected_action\": \"Действие $stage\"}\n" +
                "```",
            promptTokens = 1,
            completionTokens = 2,
            totalTokens = 3,
            elapsedMs = 42L
        )
    }

    @Test
    fun `гейт 1 - code-dump на PLANNING без блока ставит отказ, стадия держится, контент не тронут`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            val dump = codeDumpReply(blocks = 3)
            fake.next = dump
            val response = chat(client, base, "давай сразу код", sid)

            val rejection = assertNotNull(response.taskRejected, "гейт должен зафиксировать отказ")
            assertTrue(rejection.contains("Реализация на стадии PLANNING"), rejection)
            assertEquals(TaskStage.PLANNING, response.taskState?.stage, "стадия держится")

            val stored = client.get("$base/api/session/$sid").body<Session>()
            val assistant = stored.messages.last { it.role == "assistant" }
            assertEquals(dump.reply, assistant.content, "тело ответа в истории не модифицируется")
            client.close()
        }
    }

    @Test
    fun `гейт 2 - один блок длиннее 10 строк на PLAN_APPROVED отклоняется`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("PLAN_APPROVED", "План утверждён")
            val approved = chat(client, base, "утверждаем план", sid)
            assertEquals(TaskStage.PLAN_APPROVED, approved.taskState?.stage)

            fake.next = singleBigBlockReply(lines = 12)
            val response = chat(client, base, "пиши код", sid)

            val rejection = assertNotNull(response.taskRejected, "один длинный блок тоже превышает порог")
            assertTrue(rejection.contains("PLAN_APPROVED"), rejection)
            assertEquals(
                TaskStage.PLAN_APPROVED,
                response.taskState?.stage,
                "эвристическое продвижение в EXECUTION отброшено"
            )
            client.close()
        }
    }

    @Test
    fun `гейт 3 - на PAUSED фенсы не дают отказа, clear-поведение сохранено`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            val paused = chat(client, base, "пауза", sid)
            assertEquals(TaskStage.PAUSED, paused.taskState?.stage)

            fake.next = codeDumpReply(blocks = 3)
            val response = chat(client, base, "работаю дальше", sid)

            assertNull(response.taskRejected, "на PAUSED гейт не срабатывает")
            assertEquals(TaskStage.PAUSED, response.taskState?.stage)
            assertTrue(response.reply.contains("```"), "контент доставляется без вырезания")
            client.close()
        }
    }

    @Test
    fun `гейт 4 - без задачи фенсы не дают отказа`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = codeDumpReply(blocks = 3)
            val response = chat(client, base, "привет", sid)

            assertNull(response.taskState, "задача не создана")
            assertNull(response.taskRejected, "гейт не срабатывает без задачи")
            client.close()
        }
    }

    @Test
    fun `гейт 5 - легальный блок вместе с фенсами проходит без гейт-отказа`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = replyWithCodeAndTaskState("PLAN_APPROVED", codeBlocks = 3)
            val response = chat(client, base, "утверждаем план", sid)

            assertEquals(TaskStage.PLAN_APPROVED, response.taskState?.stage)
            assertNull(response.taskRejected, "объявленный переход — гейт не срабатывает")
            assertFalse(response.reply.contains("task-state"), "клиенту уходит стрипнутый reply")
            client.close()
        }
    }

    @Test
    fun `гейт 6 - нелегальный блок с фенсами даёт старый текст о переходе, не гейт-текст`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = replyWithCodeAndTaskState("EXECUTION", codeBlocks = 3)
            val response = chat(client, base, "работаем", sid)

            val rejection = assertNotNull(response.taskRejected, "нелегальный блок фиксирует отказ как раньше")
            assertTrue(rejection.contains("PLANNING → EXECUTION"), rejection)
            assertFalse(
                rejection.contains("Реализация на стадии"),
                "приоритет у отказа о нелегальном переходе, не у гейт-текста"
            )
            assertEquals(TaskStage.PLANNING, response.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `гейт 7 - текст отказа содержит стадию и число блоков`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = codeDumpReply(blocks = 3)
            val response = chat(client, base, "код давай", sid)

            val rejection = assertNotNull(response.taskRejected)
            assertTrue(rejection.contains("PLANNING"), rejection)
            assertTrue(rejection.contains("3 код-блоков"), rejection)
            client.close()
        }
    }

    @Test
    fun `гейт 8 - следующий чистый ход очищает отказ, на входе которого отказ ещё виден модели`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = codeDumpReply(blocks = 3)
            val gated = chat(client, base, "код давай", sid)
            assertNotNull(gated.taskRejected)

            fake.next = plainReply("план составлен, жду утверждения")
            val clean = chat(client, base, "как продвигается план", sid)

            assertNull(clean.taskRejected, "чистый ход без фенсов снимает отказ clear-веткой")
            assertEquals(TaskStage.PLANNING, clean.taskState?.stage)

            // Свойственная семантика инъекции: текст гейт-отказа виден модели
            // ровно на СЛЕДУЮЩИЙ ход (в seed хода, очищающего отказ)
            val rejectedInSeed = fake.calls.last().messages.lastOrNull {
                it.role == "system" && it.content.orEmpty().contains("ПОСЛЕДНИЙ ПРЕДЛОЖЕННЫЙ ПЕРЕХОД ОТКЛОНЁН")
            }
            assertNotNull(rejectedInSeed, "гейт-отказ должен попасть в seed следующего хода")
            assertTrue(rejectedInSeed!!.content!!.contains("Реализация на стадии PLANNING"), rejectedInSeed.content)
            client.close()
        }
    }

    @Test
    fun `гейт 9 - два гейт-хода подряд - отказ один и перезаписан`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)

            fake.next = codeDumpReply(blocks = 3)
            val first = chat(client, base, "код давай", sid)
            assertNotNull(first.taskRejected, "первый гейт-ход фиксирует отказ")

            fake.next = codeDumpReply(blocks = 2)
            val second = chat(client, base, "ещё код", sid)

            val rejection = assertNotNull(second.taskRejected, "второй гейт-ход снова фиксирует отказ")
            assertTrue(rejection.contains("2 код-блоков"), "свежий отказ перезаписал старый: $rejection")
            assertFalse(rejection.contains("3 код-блоков"), "старого счётчика быть не должно")
            assertEquals(TaskStage.PLANNING, second.taskState?.stage)
            client.close()
        }
    }

    @Test
    fun `гейт 10 - на PLAN_APPROVED фенсы бьют эвристику EXEC_MARKERS - стадия держится`() {
        withApp { base, fake, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(chat(client, base, "начало").sessionId)

            fake.next = replyWithBlock("PLANNING", "Составление плана")
            chat(client, base, "планируем", sid)
            fake.next = replyWithBlock("PLAN_APPROVED", "План утверждён")
            chat(client, base, "утверждаем план", sid)

            // Два крошечных блока: порог blocks >= 2, при этом эвристика по ```
            // продвинула бы PLAN_APPROVED → EXECUTION без гейта
            fake.next = codeDumpReply(blocks = 2, linesPerBlock = 4)
            val response = chat(client, base, "начинай реализацию", sid)

            val rejection = assertNotNull(response.taskRejected, "гейт перекрывает эвристическое продвижение")
            assertTrue(rejection.contains("PLAN_APPROVED"), rejection)
            assertEquals(TaskStage.PLAN_APPROVED, response.taskState?.stage, "стадия не ушла в EXECUTION")
            client.close()
        }
    }

    // --- Юнит-тесты счётчика фенсов/блоков (TaskStateJson) ---

    @Test
    fun `счётчик 1 - один маленький блок до 10 строк - порог не превышен`() {
        val text = "```kotlin\nfun f() {}\n```"
        assertEquals(2, TaskStateJson.countCodeFences(text))
        assertFalse(TaskStateJson.codeBlocksExceedThreshold(text))
    }

    @Test
    fun `счётчик 2 - два крошечных блока - порог превышен`() {
        val text = "```\nа\n```\n```\nб\n```"
        assertEquals(4, TaskStateJson.countCodeFences(text))
        assertTrue(TaskStateJson.codeBlocksExceedThreshold(text))
    }

    @Test
    fun `счётчик 3 - один блок 11 и больше строк - порог превышен`() {
        val text = "```python\n" + (1..12).joinToString("\n") { "строка $it" } + "\n```"
        assertEquals(2, TaskStateJson.countCodeFences(text))
        assertTrue(TaskStateJson.codeBlocksExceedThreshold(text))
    }

    @Test
    fun `счётчик 4 - нечётные фенсы - незакрытый хвост считается блоком`() {
        val closed = "```\nзакрытый блок\n```"
        val tail = "\nтекст между\n```python\n" + (1..12).joinToString("\n") { "строка $it" }
        val text = closed + tail
        assertEquals(3, TaskStateJson.countCodeFences(text))
        assertTrue(TaskStateJson.codeBlocksExceedThreshold(text), "нечётный хвост = +1 незакрытый блок")

        // Одиночный незакрытый фенс с длинным содержимым — тоже блок
        val unclosed = "```python\n" + (1..12).joinToString("\n") { "строка $it" }
        assertEquals(1, TaskStateJson.countCodeFences(unclosed))
        assertTrue(TaskStateJson.codeBlocksExceedThreshold(unclosed))
    }
}
