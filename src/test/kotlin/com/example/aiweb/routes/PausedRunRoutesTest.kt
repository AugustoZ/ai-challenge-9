package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.OpenAIToolCall
import com.example.aiweb.model.OpenAIToolCallFunction
import com.example.aiweb.session.FileSessionStore
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.InMemorySessionStore
import com.example.aiweb.session.PausedRun
import com.example.aiweb.session.SessionStore
import com.example.aiweb.session.SummarizeResult
import com.example.aiweb.task.TaskState
import com.example.aiweb.task.TaskStage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.serialization.json.JsonObject
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
 * Контрольная точка прерванного паузой прогона (PausedRun) сквозь HTTP
 * (образец TaskStateRoutesTest): отмена стрима с saveContext=true (пауза в полёте)
 * сохраняет дельту прогона в сессию, «Стоп» (default) — нет; следующий стрим после
 * паузы получает пары checkpoint + RESUME_NOTE в вызове модели, checkpoint
 * потребляется однократно. Всё на фейковом LLM, без сети.
 */
class PausedRunRoutesTest {

    /**
     * Фейк с очередью ответов и гейтом: ask начиная с номера [gateFromCall]
     * (0-based по всем вызовам) блокируется на [gate] — окно для отмены прогона.
     */
    private class FakeLLMClient(
        @Volatile var canned: MutableList<ChatResult>
    ) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        @Volatile
        var gateFromCall: Int = Int.MAX_VALUE

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun ask(call: LLMCall): ChatResult {
            val index = calls.size
            calls.add(call)
            if (index >= gateFromCall) gate?.await()
            return canned.removeFirstOrNull() ?: error("Фейк: очередь ответов исчерпана на вызове №${index + 1}")
        }
    }

    /** Ответ с вызовом calculator: даёт в дельте прогона пару assistant(tool_calls) → tool. */
    private fun toolCallResult() = ChatResult(
        reply = "",
        promptTokens = 10,
        completionTokens = 3,
        totalTokens = 13,
        elapsedMs = 0L,
        toolCalls = listOf(
            OpenAIToolCall(
                id = "call-1",
                type = "function",
                function = OpenAIToolCallFunction(name = "calculator", arguments = "{\"expression\":\"2+2\"}")
            )
        )
    )

    private fun textResult(text: String) = ChatResult(
        reply = text, promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L
    )

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-pausedrun").toString()

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

    /**
     * Поднимает приложение с заданным хранилищем сессий ([store] или
     * файловое на [dataDir], как по умолчанию) и передаёт его в тест —
     * для прямых закладок checkpoint/taskState через SessionStore.
     */
    private fun withApp(
        store: SessionStore? = null,
        block: suspend (base: String, fake: FakeLLMClient, dataDir: String, store: SessionStore) -> Unit
    ) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(mutableListOf(textResult("ответ сервера")))
        val sessions = store ?: FileSessionStore(dataDir)
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir),
                    store = sessions,
                    clientFactory = { chat },
                    summarizerFactory = {
                        object : HistorySummarizer {
                            override suspend fun summarize(previous: String?, batch: List<com.example.aiweb.model.OpenAIMessage>) =
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
            runBlocking { block(app.base, chat, dataDir, sessions) }
        }
    }

    private suspend fun awaitCalls(fake: FakeLLMClient, expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (fake.calls.size < expected) {
            check(System.currentTimeMillis() < deadline) { "LLM-вызов №$expected не случился" }
            delay(20)
        }
    }

    private suspend fun cancel(client: HttpClient, base: String, body: String) =
        client.post("$base/api/cancel") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /**
     * Свежий экземпляр [FileSessionStore] на чтение: store читает файлы при
     * загрузке, поэтому снапшот checkpoint берётся пересозданием экземпляра.
     */
    private fun readPausedRun(dataDir: String, sessionId: String) =
        FileSessionStore(dataDir).getPausedRun(sessionId)

    /**
     * Checkpoint пишется onCancelled'ом уже отменённого прогона — стрим-ответ
     * может закрыться раньше сохранения: ждём появление с поллингом.
     */
    private suspend fun awaitCheckpoint(dataDir: String, sessionId: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (readPausedRun(dataDir, sessionId) == null) {
            check(System.currentTimeMillis() < deadline) { "checkpoint не сохранился за 5с" }
            delay(20)
        }
    }

    @Test
    fun `пауза в полёте сохраняет checkpoint - продолжение получает пары и RESUME_NOTE`() {
        withApp { base, fake, dataDir, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(
                client.post("$base/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(message = "начало"))
                }.body<com.example.aiweb.model.ChatResponse>().sessionId
            )

            // Прерванный прогон: первый вызов стрима отдаёт tool-call (пара попадёт
            // в дельту), второй — гейт; отмена с паузой приходит во время гейта
            fake.canned = mutableListOf(toolCallResult())
            fake.gateFromCall = 2
            fake.gate = CompletableDeferred()
            coroutineScope {
                val stream = launch {
                    client.post("$base/api/chat/stream") {
                        contentType(ContentType.Application.Json)
                        setBody(ChatRequest(message = "работаем над задачей", sessionId = sid, requestId = "req-1"))
                    }.bodyAsText()
                }
                awaitCalls(fake, 3)

                val cancelled = cancel(client, base, """{"requestId":"req-1","saveContext":true}""")
                assertEquals(200, cancelled.status.value)
                assertTrue(
                    Json.parseToJsonElement(cancelled.bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean
                )
                stream.join()
            }
            fake.gate = null
            awaitCheckpoint(dataDir, sid)

            // Checkpoint в сессии: цель + валидная дельта (assistant(tool_calls) → tool)
            val paused = assertNotNull(readPausedRun(dataDir, sid), "отмена с паузой должна сохранить checkpoint")
            assertEquals("работаем над задачей", paused.goal)
            assertEquals(2, paused.messages.size)
            assertEquals("assistant", paused.messages[0].role)
            assertEquals("call-1", paused.messages[0].toolCalls?.single()?.id)
            assertEquals("tool", paused.messages[1].role)
            assertEquals("call-1", paused.messages[1].toolCallId)

            // Продолжение: следующий стрим получает checkpoint + RESUME_NOTE в вызове модели
            fake.gateFromCall = Int.MAX_VALUE
            fake.canned = mutableListOf(textResult("Продолжаю с места остановки"))
            client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "двигаемся дальше", sessionId = sid, requestId = "req-2"))
            }.bodyAsText()

            val resumedCall = fake.calls.last()
            val noteIdx = resumedCall.messages.indexOfLast {
                it.role == "system" && it.content.orEmpty().contains("прервана паузой")
            }
            assertTrue(noteIdx >= 0, "RESUME_NOTE должна быть в контексте продолжения")
            assertEquals(resumedCall.messages.lastIndex, noteIdx, "RESUME_NOTE — последнее сообщение вызова")
            val assistantIdx = resumedCall.messages.indexOfFirst {
                it.role == "assistant" && it.toolCalls?.any { call -> call.id == "call-1" } == true
            }
            assertTrue(assistantIdx >= 0, "assistant с tool_calls из checkpoint должен быть в контексте")
            val toolIdx = assistantIdx + 1
            assertEquals("tool", resumedCall.messages[toolIdx].role)
            assertEquals("call-1", resumedCall.messages[toolIdx].toolCallId)
            assertTrue(toolIdx < noteIdx, "пары checkpoint идут перед заметкой возобновления")

            // Checkpoint потреблён однократно
            assertNull(readPausedRun(dataDir, sid), "checkpoint должен быть потреблён")

            // Второй запрос — уже без checkpoint
            fake.canned = mutableListOf(textResult("Обычный ответ"))
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "ещё ход", sessionId = sid))
            }.bodyAsText()
            assertFalse(
                fake.calls.last().messages.any { it.content.orEmpty().contains("прервана паузой") },
                "второй запрос уже без checkpoint"
            )
            client.close()
        }
    }

    /** POST /api/task/pause или /api/task/resume; возвращает parsed JSON ответа. */
    private suspend fun taskCall(client: HttpClient, base: String, path: String, sessionId: String): JsonObject {
        val body = client.post("$base$path") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"$sessionId"}""")
        }.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `Стоп без saveContext checkpoint не сохраняет`() {
        withApp { base, fake, dataDir, _ ->
            val client = testHttpClient()
            val sid = assertNotNull(
                client.post("$base/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(message = "начало"))
                }.body<com.example.aiweb.model.ChatResponse>().sessionId
            )

            fake.canned = mutableListOf(toolCallResult())
            fake.gateFromCall = 2
            fake.gate = CompletableDeferred()
            coroutineScope {
                val stream = launch {
                    client.post("$base/api/chat/stream") {
                        contentType(ContentType.Application.Json)
                        setBody(ChatRequest(message = "работаем над задачей", sessionId = sid, requestId = "req-1"))
                    }.bodyAsText()
                }
                awaitCalls(fake, 3)

                // «Стоп»: тело без saveContext — default false
                val cancelled = cancel(client, base, """{"requestId":"req-1"}""")
                assertEquals(200, cancelled.status.value)
                assertTrue(
                    Json.parseToJsonElement(cancelled.bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean
                )
                stream.join()
            }
            fake.gate = null
            // Grace: processor отменённого прогона может дорабатывать после закрытия
            // стрима; «Стоп» checkpoint не пишет никогда — даём ему догореть
            delay(500)

            assertNull(readPausedRun(dataDir, sid), "«Стоп» не сохраняет checkpoint")

            // Следующий запрос — без пар и RESUME_NOTE
            fake.gateFromCall = Int.MAX_VALUE
            fake.canned = mutableListOf(textResult("Обычный ответ"))
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "двигаемся дальше", sessionId = sid))
            }.bodyAsText()
            val nextCall = fake.calls.last()
            assertFalse(
                nextCall.messages.any { it.content.orEmpty().contains("прервана паузой") },
                "без checkpoint заметки возобновления быть не должно"
            )
            assertFalse(
                nextCall.messages.any { it.role == "tool" },
                "без checkpoint пар инструментов в контексте быть не должно"
            )
            client.close()
        }
    }

    @Test
    fun `resume при наличии checkpoint в сессии отвечает resumeRun true и taskState не null`() {
        withApp(store = InMemorySessionStore()) { base, _, _, store ->
            val client = testHttpClient()
            val sid = assertNotNull(
                client.post("$base/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(message = "начало"))
                }.body<com.example.aiweb.model.ChatResponse>().sessionId
            )
            // Задача в работе + checkpoint прерванного паузой прогона (прямая
            // закладка через store: роут паузы/resume только читает сессию)
            store.updateTaskState(sid, TaskState(stage = TaskStage.PLANNING, step = "шаг", expectedAction = ""))
            store.savePausedRun(sid, PausedRun(goal = "цель прогона", messages = emptyList()))

            val resumed = taskCall(client, base, "/api/task/resume", sid)
            assertTrue(resumed["resumeRun"]!!.jsonPrimitive.boolean, "checkpoint есть — resumeRun должен быть true")
            assertNotNull(resumed["taskState"], "taskState не null: ответ несёт состояние задачи")

            // Pause видит тот же checkpoint: флаг true и состояние не null
            val paused = taskCall(client, base, "/api/task/pause", sid)
            assertTrue(paused["resumeRun"]!!.jsonPrimitive.boolean, "checkpoint есть — pause тоже отвечает resumeRun true")
            assertNotNull(paused["taskState"])
            client.close()
        }
    }

    @Test
    fun `resume без checkpoint отвечает resumeRun false, и pause тоже`() {
        withApp(store = InMemorySessionStore()) { base, _, _, store ->
            val client = testHttpClient()
            val sid = assertNotNull(
                client.post("$base/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(message = "начало"))
                }.body<com.example.aiweb.model.ChatResponse>().sessionId
            )
            store.updateTaskState(sid, TaskState(stage = TaskStage.PLANNING, step = "шаг", expectedAction = ""))
            assertNull(store.getPausedRun(sid), "предусловие: checkpoint не заведён")

            val resumed = taskCall(client, base, "/api/task/resume", sid)
            assertFalse(resumed["resumeRun"]!!.jsonPrimitive.boolean, "checkpoint нет — resumeRun false")
            assertNotNull(resumed["taskState"])

            // Поле присутствует и в ответе pause (false, checkpoint не появилось)
            val paused = taskCall(client, base, "/api/task/pause", sid)
            assertFalse(paused["resumeRun"]!!.jsonPrimitive.boolean, "checkpoint нет — pause отвечает resumeRun false")
            assertNotNull(paused["taskState"])
            client.close()
        }
    }
}
