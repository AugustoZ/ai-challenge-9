package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.ChatRequest
import com.example.aiweb.model.ChatResponse
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки HTTP-маршрутов памяти агента: рабочая память сессии
 * (/api/session/{id}/working), долговременная память (/api/memory), сводка
 * (/api/memory/overview) и флаг ChatRequest.memoryTools (инструменты и блок
 * памяти в запросе к модели). Хранилища живут во временном dataDir — том же,
 * что и файловое хранилище сессий.
 */
class MemoryRoutesTest {

    /** Фейковый транспорт: отдаёт заготовленный ответ, запоминает вызовы. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-memory").toString()

    /** supportsTools = true: спецификации инструментов уходят в поле tools запроса к модели. */
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Поднимает приложение с фейковым LLM и временным dataDir; хранилище сессий — файловое над тем же каталогом. */
    private fun withApp(block: suspend (base: String, fake: FakeLLMClient, dataDir: String) -> Unit) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        val port = freePort()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
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
                            override suspend fun extract(current: List<Fact>, userMessage: String): List<Fact>? = null
                        }
                    }
                )
            }
        }
        server.start(wait = false)
        try {
            kotlinx.coroutines.runBlocking { block("http://127.0.0.1:$port", chat, dataDir) }
        } finally {
            server.stop(100, 500)
        }
    }

    private fun httpClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** Создаёт сессию ходом чата и возвращает её идентификатор. */
    private suspend fun createSession(client: HttpClient, base: String): String {
        val response = client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = "разговор для создания сессии"))
        }.body<ChatResponse>()
        return assertNotNull(response.sessionId)
    }

    private suspend fun putWorking(
        client: HttpClient,
        base: String,
        sessionId: String,
        body: String
    ): HttpResponse = client.put("$base/api/session/$sessionId/working") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun postMemory(client: HttpClient, base: String, body: String): HttpResponse =
        client.post("$base/api/memory") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun jsonOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    // --- Рабочая память: полный цикл ---

    @Test
    fun `рабочая память — PUT, GET, обновление по id, удаление записи и полная очистка`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            // PUT без id — запись создана
            val created = jsonOf(
                putWorking(
                    client, base, sid,
                    """{"title":"Шаг 1","content":"найти файл"}"""
                ).bodyAsText()
            )
            val entries = created["entries"]!!.jsonArray
            assertEquals(1, entries.size)
            val id = entries[0].jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(id.isNotBlank(), "сервер должен сгенерировать id")
            assertEquals("Шаг 1", entries[0].jsonObject["title"]!!.jsonPrimitive.content)

            // GET возвращает ту же запись
            val listed = jsonOf(client.get("$base/api/session/$sid/working").bodyAsText())
            assertEquals(1, listed["entries"]!!.jsonArray.size)

            // PUT с тем же id — обновление без дубликата
            val updated = jsonOf(
                putWorking(
                    client, base, sid,
                    """{"id":"$id","title":"Шаг 1 обновлён","content":"другой текст"}"""
                ).bodyAsText()
            )
            val updatedEntries = updated["entries"]!!.jsonArray
            assertEquals(1, updatedEntries.size, "обновление не должно плодить дубликат")
            assertEquals("Шаг 1 обновлён", updatedEntries[0].jsonObject["title"]!!.jsonPrimitive.content)

            // DELETE записи — остаток пуст
            val afterRemove = jsonOf(
                client.delete("$base/api/session/$sid/working/$id").bodyAsText()
            )
            assertEquals(0, afterRemove["entries"]!!.jsonArray.size)

            // Повторный PUT и полная очистка
            putWorking(client, base, sid, """{"title":"Ещё","content":"заметка"}""")
            val cleared = jsonOf(client.delete("$base/api/session/$sid/working").bodyAsText())
            assertEquals(0, cleared["entries"]!!.jsonArray.size)
            client.close()
        }
    }

    @Test
    fun `рабочая память — неизвестная сессия 404, пустой заголовок 400, кап хранилища 400`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            // Неизвестная сессия — 404 (GET/PUT/DELETE консистентно)
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/нет-такой/working").status)
            assertEquals(
                HttpStatusCode.NotFound,
                putWorking(client, base, "нет-такой", """{"title":"x","content":"y"}""").status
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("$base/api/session/нет-такой/working").status
            )

            // Пустой заголовок — 400 с русским текстом
            val blank = putWorking(client, base, sid, """{"title":"  ","content":"текст"}""")
            assertEquals(HttpStatusCode.BadRequest, blank.status)
            assertTrue(blank.bodyAsText().contains("не могут быть пустыми"))

            // Кап хранилища: заголовок длиннее 120 символов — 400 с сообщением хранилища
            val tooLong = putWorking(
                client, base, sid,
                """{"title":"${"д".repeat(121)}","content":"текст"}"""
            )
            assertEquals(HttpStatusCode.BadRequest, tooLong.status)
            assertTrue(tooLong.bodyAsText().contains("120"))
            client.close()
        }
    }

    // --- Долговременная память ---

    @Test
    fun `долговременная память — POST создаёт запись с источником user, PUT обновляет, DELETE удаляет`() {
        withApp { base, _, _ ->
            val client = httpClient()

            val created = jsonOf(
                postMemory(
                    client, base,
                    """{"kind":"profile","title":"Профиль","content":"любит краткость"}"""
                ).bodyAsText()
            )
            val entry = created["entry"]!!.jsonObject
            val id = entry["id"]!!.jsonPrimitive.content
            assertTrue(id.isNotBlank())
            assertEquals("profile", entry["kind"]!!.jsonPrimitive.content)
            assertEquals("user", entry["source"]!!.jsonPrimitive.content)
            val createdAt = entry["createdAt"]!!.jsonPrimitive.long

            // PUT обновляет запись: content и updatedAt меняются
            val updatedRaw = client.put("$base/api/memory/$id") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"теперь любит подробности"}""")
            }
            assertEquals(HttpStatusCode.OK, updatedRaw.status)
            val updated = jsonOf(updatedRaw.bodyAsText())["entry"]!!.jsonObject
            assertEquals("теперь любит подробности", updated["content"]!!.jsonPrimitive.content)
            assertTrue(updated["updatedAt"]!!.jsonPrimitive.long >= createdAt, "updatedAt должен измениться")
            assertEquals("Профиль", updated["title"]!!.jsonPrimitive.content, "null-поля остаются прежними")

            // PUT неизвестного id — 404
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("$base/api/memory/нет-такой") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"x"}""")
                }.status
            )

            // DELETE: первый true, второй false
            val deleted = jsonOf(client.delete("$base/api/memory/$id").bodyAsText())
            assertEquals(true, deleted["deleted"]!!.jsonPrimitive.content.toBooleanStrict())
            val again = jsonOf(client.delete("$base/api/memory/$id").bodyAsText())
            assertEquals(false, again["deleted"]!!.jsonPrimitive.content.toBooleanStrict())
            client.close()
        }
    }

    @Test
    fun `долговременная память — плохой вид 400, перебор длины 400, фильтры kind и q`() {
        withApp { base, _, _ ->
            val client = httpClient()

            // Неизвестный вид — 400 с точным текстом контракта
            val badKind = postMemory(client, base, """{"kind":"заметка","title":"Т","content":"К"}""")
            assertEquals(HttpStatusCode.BadRequest, badKind.status)
            assertEquals(
                "Вид записи должен быть одним из: profile, decision, knowledge",
                jsonOf(badKind.bodyAsText())["reply"]!!.jsonPrimitive.content
            )

            // Контент 2001 символ — 400 с сообщением хранилища
            val tooLong = postMemory(
                client, base,
                """{"kind":"knowledge","title":"Т","content":"${"д".repeat(2001)}"}"""
            )
            assertEquals(HttpStatusCode.BadRequest, tooLong.status)
            assertTrue(tooLong.bodyAsText().contains("2000"))

            // Пустой заголовок — 400
            assertEquals(
                HttpStatusCode.BadRequest,
                postMemory(client, base, """{"kind":"knowledge","title":"","content":"К"}""").status
            )

            // Фикстуры для фильтров
            postMemory(client, base, """{"kind":"profile","title":"Имя пользователя","content":"Аугусто"}""")
            postMemory(client, base, """{"kind":"decision","title":"Выбор стека","content":"Ktor"}""")
            postMemory(
                client, base,
                """{"kind":"knowledge","title":"Заметка про Ktor","content":"сервер","tags":["ktor","веб"]}"""
            )

            // Фильтр kind
            val onlyProfile = jsonOf(client.get("$base/api/memory?kind=profile").bodyAsText())
            val profileEntries = onlyProfile["entries"]!!.jsonArray
            assertTrue(profileEntries.isNotEmpty())
            assertTrue(profileEntries.all { it.jsonObject["kind"]!!.jsonPrimitive.content == "profile" })
            // Счётчики всегда полные, независимо от фильтра
            val counts = onlyProfile["counts"]!!.jsonObject
            assertEquals(3, counts.size)
            assertEquals(1, counts["profile"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, counts["decision"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, counts["knowledge"]!!.jsonPrimitive.content.toInt())

            // Фильтр q — подстрока в заголовке/тексте/тегах без учёта регистра
            val byQuery = jsonOf(client.get("$base/api/memory?q=ktor").bodyAsText())
            assertEquals(2, byQuery["entries"]!!.jsonArray.size, "q=ktor должно найти решение и знание по тегу")

            // Неизвестный вид в query — 400
            assertEquals(HttpStatusCode.BadRequest, client.get("$base/api/memory?kind=bogus").status)
            client.close()
        }
    }

    // --- Сводка памяти ---

    @Test
    fun `обзор памяти считает счётчики всех слоёв и терпит неизвестную сессию`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            // Сид: 2 заметки рабочей памяти, 1 профиль и 1 знание в долговременной
            putWorking(client, base, sid, """{"title":"Шаг 1","content":"а"}""")
            putWorking(client, base, sid, """{"title":"Шаг 2","content":"б"}""")
            postMemory(client, base, """{"kind":"profile","title":"Профиль","content":"П"}""")
            postMemory(client, base, """{"kind":"knowledge","title":"Знание","content":"З"}""")

            val overview = jsonOf(client.get("$base/api/memory/overview?sessionId=$sid").bodyAsText())
            val shortTerm = overview["shortTerm"]!!.jsonObject
            assertEquals(2, shortTerm["turns"]!!.jsonPrimitive.content.toInt(), "user + assistant")
            assertEquals(false, shortTerm["hasSummary"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(2, overview["working"]!!.jsonObject["entries"]!!.jsonPrimitive.content.toInt())
            val longTerm = overview["longTerm"]!!.jsonObject
            assertEquals(2, longTerm["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, longTerm["profile"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, longTerm["decision"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, longTerm["knowledge"]!!.jsonPrimitive.content.toInt())

            // Неизвестная сессия — не 404: нули, долговременные счётчики на месте
            val unknown = jsonOf(client.get("$base/api/memory/overview?sessionId=нет-такой").bodyAsText())
            assertEquals(0, unknown["shortTerm"]!!.jsonObject["turns"]!!.jsonPrimitive.content.toInt())
            assertEquals(false, unknown["shortTerm"]!!.jsonObject["hasSummary"]!!.jsonPrimitive.content.toBooleanStrict())
            assertEquals(0, unknown["working"]!!.jsonObject["entries"]!!.jsonPrimitive.content.toInt())
            assertEquals(2, unknown["longTerm"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())
            client.close()
        }
    }

    // --- Флаг memoryTools ---

    private fun FakeLLMClient.toolNames(): List<String> =
        calls.last().tools.orEmpty().map { it.function.name }

    @Test
    fun `memoryTools false — в запросе к модели нет инструментов памяти и блока памяти`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            // Сид обоих слоёв: без флага блок памяти точно попал бы в запрос
            putWorking(client, base, sid, """{"title":"Шаг 1","content":"найти файл"}""")
            postMemory(client, base, """{"kind":"profile","title":"Профиль","content":"Аугусто"}""")

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход", sessionId = sid, memoryTools = false))
            }.body<ChatResponse>()

            val call = fake.calls.last()
            val names = fake.toolNames()
            assertFalse(names.contains("task_notes"), "инструмент task_notes не должен попадать в запрос: $names")
            assertFalse(names.contains("memory_save"), "инструмент memory_save не должен попадать в запрос: $names")
            assertFalse(names.contains("memory_recall"), "инструмент memory_recall не должен попадать в запрос: $names")
            assertFalse(
                call.messages.any { it.role == "system" && it.content.orEmpty().contains("Долговременная память:") },
                "блок памяти не должен попадать в запрос"
            )
            assertFalse(
                call.messages.any { it.role == "system" && it.content.orEmpty().contains("Рабочая память (текущая задача):") },
                "рабочая секция блока памяти не должна попадать в запрос"
            )
            client.close()
        }
    }

    @Test
    fun `memoryTools по умолчанию — блок памяти в системном сообщении и инструменты памяти в спецификациях`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            putWorking(client, base, sid, """{"title":"Шаг 1","content":"найти файл"}""")
            postMemory(client, base, """{"kind":"profile","title":"Профиль","content":"Аугусто"}""")

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход", sessionId = sid))
            }.body<ChatResponse>()

            val call = fake.calls.last()
            val memorySystem = call.messages.lastOrNull {
                it.role == "system" && it.content.orEmpty().contains("Долговременная память:")
            }
            assertNotNull(memorySystem, "блок памяти должен быть системным сообщением в запросе")
            assertTrue(
                memorySystem.content.orEmpty().contains("Рабочая память (текущая задача):"),
                "блок должен содержать и рабочую секцию"
            )
            val names = fake.toolNames()
            assertTrue(names.contains("task_notes"), "спецификации должны включать task_notes: $names")
            assertTrue(names.contains("memory_save"))
            assertTrue(names.contains("memory_recall"))
            client.close()
        }
    }

    @Test
    fun `блок памяти добавляется в конец seed — после user-сообщения, минуя trim`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            val sid = createSession(client, base)
            postMemory(client, base, """{"kind":"profile","title":"Профиль","content":"Аугусто"}""")

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход", sessionId = sid))
            }.body<ChatResponse>()

            val messages = fake.calls.last().messages
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            val memoryIdx = messages.indexOfLast {
                it.role == "system" && it.content.orEmpty().contains("Долговременная память:")
            }
            assertNotNull(memoryIdx.takeIf { it >= 0 }, "блок памяти должен быть в запросе")
            assertTrue(
                memoryIdx > lastUserIdx,
                "блок памяти добавляется в конец seed (после trim), то есть после user-сообщения"
            )
            assertEquals(
                messages.lastIndex, memoryIdx,
                "блок памяти — последнее сообщение seed (AgentLoop добавляет свой system-промт первым)"
            )
            client.close()
        }
    }

    // --- Удаление сессии чистит рабочую память ---

    @Test
    fun `удаление сессии стирает её рабочую память`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val sid = createSession(client, base)
            putWorking(client, base, sid, """{"title":"Шаг 1","content":"а"}""")
            assertEquals(1, jsonOf(client.get("$base/api/session/$sid/working").bodyAsText())["entries"]!!.jsonArray.size)

            assertEquals(HttpStatusCode.NoContent, client.delete("$base/api/session/$sid").status)

            // Консистентно с прочими маршрутами: у неизвестной сессии рабочая память — 404
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/$sid/working").status)
            client.close()
        }
    }
}
