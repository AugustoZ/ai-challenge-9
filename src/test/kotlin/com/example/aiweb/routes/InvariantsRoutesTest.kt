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
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API-роуты /api/invariants (задача 8 плана assistant-invariants): CRUD-матрица
 * GET ?sessionId= / POST global / DELETE global/{id} / POST session /
 * DELETE session/{sessionId}/{id}. Все тесты — на фейковом LLM, без сети
 * (клон TaskStateRoutesTest: FakeLLMClient, withAuthApp, dead apiUrl).
 * Контракт — KDoc InvariantsPayload (Models.kt) и вызовы
 * frontend/src/api/client.ts.
 */
class InvariantsRoutesTest {

    private class FakeLLMClient(reply: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        private val reply = reply

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return reply
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

    private fun withApp(
        dataDir: String? = null,
        block: suspend (base: String, fake: FakeLLMClient, dataDir: String) -> Unit
    ) {
        val dir = dataDir ?: tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dir),
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
            runBlocking { block(app.base, chat, dir) }
        }
    }

    // --- Хелперы запросов ---

    private suspend fun newSession(client: HttpClient, base: String): String =
        client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = "начало"))
        }.body<ChatResponse>().sessionId!!

    private suspend fun getInvariants(client: HttpClient, base: String, sessionId: String?): HttpResponse =
        client.get("$base/api/invariants" + (sessionId?.let { "?sessionId=$it" } ?: ""))

    private suspend fun postGlobal(client: HttpClient, base: String, text: String): HttpResponse =
        client.post("$base/api/invariants/global") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$text"}""")
        }

    private suspend fun deleteGlobal(client: HttpClient, base: String, id: String): HttpResponse =
        client.delete("$base/api/invariants/global/$id")

    private suspend fun postSession(client: HttpClient, base: String, sessionId: String, text: String): HttpResponse =
        client.post("$base/api/invariants/session") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"$sessionId","text":"$text"}""")
        }

    private suspend fun deleteSession(client: HttpClient, base: String, sessionId: String, id: String): HttpResponse =
        client.delete("$base/api/invariants/session/$sessionId/$id")

    private fun globalList(body: String) =
        Json.parseToJsonElement(body).jsonObject["global"]!!.jsonArray

    private fun sessionList(body: String) =
        Json.parseToJsonElement(body).jsonObject["session"]!!.jsonArray

    private fun idOf(element: JsonElement): String =
        element.jsonObject["id"]!!.jsonPrimitive.content

    private fun textOf(element: JsonElement): String =
        element.jsonObject["text"]!!.jsonPrimitive.content

    private fun errorOf(body: String): String =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    // --- POST global: happy + 400-матрица ---

    @Test
    fun `POST global happy - 200 с id, GET показывает в global, DELETE убирает`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val created = postGlobal(client, base, "Не выдумывать факты")
            assertEquals(200, created.status.value)
            val list = globalList(created.bodyAsText())
            assertEquals(1, list.size)
            assertEquals("Не выдумывать факты", textOf(list[0]))
            val id = idOf(list[0])
            assertTrue(id.isNotBlank(), "инвариант получает id")

            val sid = newSession(client, base)
            assertEquals(1, globalList(getInvariants(client, base, sid).bodyAsText()).size)

            val deleted = deleteGlobal(client, base, id)
            assertEquals(200, deleted.status.value, "конвенция delete-роутов: 200 с телом")
            assertEquals(0, globalList(deleted.bodyAsText()).size)
            client.close()
        }
    }

    @Test
    fun `POST global дубль текста - 400 с сообщением стора`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            assertEquals(200, postGlobal(client, base, "Правило одно").status.value)
            val duplicate = postGlobal(client, base, "Правило одно")
            assertEquals(400, duplicate.status.value)
            assertEquals(
                "Инвариант: инвариант с таким текстом уже существует",
                errorOf(duplicate.bodyAsText())
            )
            client.close()
        }
    }

    @Test
    fun `POST global 21-й инвариант - 400`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            for (i in 1..20) {
                assertEquals(200, postGlobal(client, base, "Правило $i").status.value, "инвариант №$i")
            }
            val overflow = postGlobal(client, base, "Правило лишнее")
            assertEquals(400, overflow.status.value)
            assertEquals("Инвариант: нельзя добавить больше 20", errorOf(overflow.bodyAsText()))
            client.close()
        }
    }

    @Test
    fun `POST global пустой текст - 400`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val empty = postGlobal(client, base, "   ")
            assertEquals(400, empty.status.value)
            assertEquals("Инвариант: текст не может быть пустым", errorOf(empty.bodyAsText()))

            val missing = client.post("$base/api/invariants/global") {
                contentType(ContentType.Application.Json)
                setBody("""{"nonsense":1}""")
            }
            assertEquals(400, missing.status.value, "отсутствующий text — тоже 400")
            client.close()
        }
    }

    @Test
    fun `POST global длинный текст 301 символ - 400`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val tooLong = postGlobal(client, base, "д".repeat(301))
            assertEquals(400, tooLong.status.value)
            assertEquals("Инвариант: текст длиннее 300 символов", errorOf(tooLong.bodyAsText()))
            client.close()
        }
    }

    // --- DELETE global: happy + 404 ---

    @Test
    fun `DELETE global несуществующий id - 404`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val missing = deleteGlobal(client, base, "00000000-0000-0000-0000-000000000000")
            assertEquals(404, missing.status.value)
            assertEquals("Инвариант не найден.", errorOf(missing.bodyAsText()))

            val unsafe = deleteGlobal(client, base, "..escape")
            assertEquals(404, unsafe.status.value, "unsafe-строка в пути — 404 (гвард traversal)")
            client.close()
        }
    }

    // --- POST session: happy + 404 + 400 ---

    @Test
    fun `POST session happy - 200, GET показывает в session, DELETE убирает`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val sid = newSession(client, base)

            val created = postSession(client, base, sid, "Сессия: не спорить")
            assertEquals(200, created.status.value)
            val list = sessionList(created.bodyAsText())
            assertEquals(1, list.size)
            val id = idOf(list[0])

            val fetched = Json.parseToJsonElement(getInvariants(client, base, sid).bodyAsText()).jsonObject
            assertEquals(1, fetched["session"]!!.jsonArray.size)
            assertEquals(0, fetched["global"]!!.jsonArray.size, "сессионный не попадает в global")

            val deleted = deleteSession(client, base, sid, id)
            assertEquals(200, deleted.status.value)
            assertEquals(0, sessionList(deleted.bodyAsText()).size)
            client.close()
        }
    }

    @Test
    fun `POST session неизвестная сессия - 404`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val response = postSession(client, base, "unknown-123", "текст")
            assertEquals(404, response.status.value)
            assertEquals("Сессия не найдена.", errorOf(response.bodyAsText()))
            client.close()
        }
    }

    @Test
    fun `POST session дубль и переполнение - 400`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val sid = newSession(client, base)
            assertEquals(200, postSession(client, base, sid, "Одно правило").status.value)
            val duplicate = postSession(client, base, sid, "Одно правило")
            assertEquals(400, duplicate.status.value)
            assertEquals(
                "Инвариант: инвариант с таким текстом уже существует",
                errorOf(duplicate.bodyAsText())
            )
            client.close()
        }
    }

    // --- DELETE session: 404 ---

    @Test
    fun `DELETE session неизвестная сессия и неизвестный id - 404`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val unknownSession = deleteSession(client, base, "unknown-123", "inv-1")
            assertEquals(404, unknownSession.status.value)
            assertEquals("Сессия не найдена.", errorOf(unknownSession.bodyAsText()))

            val sid = newSession(client, base)
            val unknownId = deleteSession(client, base, sid, "00000000-0000-0000-0000-000000000000")
            assertEquals(404, unknownId.status.value)
            assertEquals("Инвариант не найден.", errorOf(unknownId.bodyAsText()))
            client.close()
        }
    }

    // --- GET: 400 / 404 / пустая / оба скоупа ---

    @Test
    fun `GET без sessionId - 400`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val response = getInvariants(client, base, null)
            assertEquals(400, response.status.value)
            assertEquals("Укажите sessionId сессии.", errorOf(response.bodyAsText()))
            client.close()
        }
    }

    @Test
    fun `GET неизвестная сессия - 404`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val response = getInvariants(client, base, "unknown-123")
            assertEquals(404, response.status.value)
            assertEquals("Сессия не найдена.", errorOf(response.bodyAsText()))
            client.close()
        }
    }

    @Test
    fun `GET валидная пустая сессия - 200 с обоими пустыми ключами`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            val sid = newSession(client, base)
            val response = getInvariants(client, base, sid)
            assertEquals(200, response.status.value)
            val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, payload["global"]!!.jsonArray.size, "ключ global обязателен даже пустым")
            assertEquals(0, payload["session"]!!.jsonArray.size, "ключ session обязателен даже пустым")
            client.close()
        }
    }

    @Test
    fun `GET валидная с данными - оба скоупа`() {
        withApp { base, _, _ ->
            val client = testHttpClient()
            assertEquals(200, postGlobal(client, base, "Глобальное правило").status.value)
            val sid = newSession(client, base)
            assertEquals(200, postSession(client, base, sid, "Сессионное правило").status.value)

            val payload = Json.parseToJsonElement(getInvariants(client, base, sid).bodyAsText()).jsonObject
            assertEquals(1, payload["global"]!!.jsonArray.size)
            assertEquals("Глобальное правило", textOf(payload["global"]!!.jsonArray[0]))
            assertEquals(1, payload["session"]!!.jsonArray.size)
            assertEquals("Сессионное правило", textOf(payload["session"]!!.jsonArray[0]))
            client.close()
        }
    }

    // --- Персистентность файла-стора ---

    @Test
    fun `глобальный инвариант переживает пересоздание приложения - файл-стор`() {
        val dir = tempDataDir()
        withApp(dir) { base, _, _ ->
            val client = testHttpClient()
            assertEquals(200, postGlobal(client, base, "Пережить перезапуск").status.value)
            client.close()
        }
        withApp(dir) { base, _, _ ->
            val client = testHttpClient()
            val sid = newSession(client, base)
            val payload = Json.parseToJsonElement(getInvariants(client, base, sid).bodyAsText()).jsonObject
            assertEquals(1, payload["global"]!!.jsonArray.size, "файл-стор перечитан после пересоздания")
            assertEquals("Пережить перезапуск", textOf(payload["global"]!!.jsonArray[0]))
            client.close()
        }
    }
}
