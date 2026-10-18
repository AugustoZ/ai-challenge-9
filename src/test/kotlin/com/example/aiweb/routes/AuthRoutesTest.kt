package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.model.Fact
import com.example.aiweb.model.OpenAIMessage
import com.example.aiweb.session.HistorySummarizer
import com.example.aiweb.session.SummarizeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * HTTP-проверки auth-маршрутов (задача 6): вход по ПИН, создание и список
 * пользователей, удаление с каскадом и запрет удаления последнего.
 * Каждый тест поднимает реальный Application (Netty, случайный порт) с фейковым
 * LLM и временным dataDir — файлы каскада проверяются на диске.
 */
class AuthRoutesTest {

    /** Фейковый транспорт: отдаёт заготовленный ответ, запоминает вызовы. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
    }

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

    /** Поднимает приложение с фейковым LLM и временным dataDir; block(base, dataDir). */
    private fun withApp(block: suspend (base: String, dataDir: String) -> Unit) {
        val dataDir = Files.createTempDirectory("aiweb-routes-auth").toString()
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
            runBlocking { block("http://127.0.0.1:$port", dataDir) }
        } finally {
            server.stop(100, 500)
        }
    }

    private fun jsonOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    /** Создаёт пользователя POST /api/auth/users (открытый роут); возвращает (token, userId). */
    private suspend fun createUser(
        client: HttpClient,
        base: String,
        name: String,
        pin: String
    ): Pair<String, String> {
        val response = client.post("$base/api/auth/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","pin":"$pin"}""")
        }
        val body = jsonOf(response.bodyAsText())
        return body["token"]!!.jsonPrimitive.content to body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun login(client: HttpClient, base: String, userId: String, pin: String): HttpResponse =
        client.post("$base/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","pin":"$pin"}""")
        }

    // --- Вход по ПИН ---

    @Test
    fun `успешный вход - 200 с токеном и пользователем, токен открывает защищённый роут`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (_, userId) = createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null) // login — открытый роут

            val response = login(client, base, userId, "0407")
            assertEquals(200, response.status.value)
            val body = jsonOf(response.bodyAsText())
            val token = body["token"]!!.jsonPrimitive.content
            assertEquals(userId, body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("Пилот", body["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(token.isNotBlank())

            val sessions = client.get("$base/api/sessions") { header("X-Auth-Token", token) }
            assertEquals(200, sessions.status.value, "Токен из login открывает /api/sessions")
        }
    }

    /** Создаёт пользователя и подставляет его токен в [TestAuth] (для защищённых вызовов). */
    private suspend fun createAndSelect(
        client: HttpClient,
        base: String,
        name: String,
        pin: String
    ): Pair<String, String> {
        val pair = createUser(client, base, name, pin)
        TestAuth.set(pair.first)
        return pair
    }

    @Test
    fun `неверный ПИН - 401 Неверный ПИН или пользователь`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (_, userId) = createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null)

            val response = login(client, base, userId, "9999")
            assertEquals(401, response.status.value)
            assertEquals(
                "Неверный ПИН или пользователь",
                jsonOf(response.bodyAsText())["error"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `неизвестный пользователь - 401 с тем же текстом без утечки существования`() {
        withApp { base, _ ->
            val client = testHttpClient()
            createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null)

            val response = login(client, base, "00000000-0000-0000-0000-000000000000", "0407")
            assertEquals(401, response.status.value)
            assertEquals(
                "Неверный ПИН или пользователь",
                jsonOf(response.bodyAsText())["error"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `четвёртая попытка после трёх неудач - кулдаун 429 даже с верным ПИН`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (_, userId) = createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null)

            repeat(3) { attempt ->
                val failed = login(client, base, userId, "9999")
                assertEquals(401, failed.status.value, "неудача #$attempt")
            }
            val locked = login(client, base, userId, "0407")
            assertEquals(429, locked.status.value, "верный ПИН в кулдауне тоже отклонён")
            assertTrue(
                jsonOf(locked.bodyAsText())["error"]!!.jsonPrimitive.content.contains("Слишком много"),
                "Русский текст о кулдауне: ${locked.bodyAsText()}"
            )
        }
    }

    @Test
    fun `успешный вход сбрасывает счётчик неудач`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (_, userId) = createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null)

            repeat(2) { assertEquals(401, login(client, base, userId, "9999").status.value) }
            assertEquals(200, login(client, base, userId, "0407").status.value)
            repeat(2) {
                assertEquals(
                    401,
                    login(client, base, userId, "9999").status.value,
                    "После успеха две неудачи кулдаун НЕ включают"
                )
            }
        }
    }

    // --- Создание и список пользователей ---

    @Test
    fun `создание пользователя - 200 с токеном, дубликат имени разрешён`() {
        withApp { base, _ ->
            val client = testHttpClient()
            TestAuth.set(null)

            val first = client.post("$base/api/auth/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Гонщик","pin":"0407"}""")
            }
            assertEquals(200, first.status.value)
            val firstBody = jsonOf(first.bodyAsText())
            assertTrue(firstBody.containsKey("token"))
            assertEquals("Гонщик", firstBody["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)

            val second = client.post("$base/api/auth/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Гонщик","pin":"1234"}""")
            }
            assertEquals(200, second.status.value, "Дубликат имени разрешён")

            val list = Json.parseToJsonElement(client.get("$base/api/auth/users").bodyAsText()).jsonArray
            assertEquals(2, list.size)
            assertNotEquals(
                list[0].jsonObject["id"]!!.jsonPrimitive.content,
                list[1].jsonObject["id"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `создание с невалидными полями - 400 с русским текстом и названием поля`() {
        withApp { base, _ ->
            val client = testHttpClient()
            TestAuth.set(null)

            val longName = client.post("$base/api/auth/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"${"И".repeat(26)}","pin":"0407"}""")
            }
            assertEquals(400, longName.status.value)
            assertTrue(
                jsonOf(longName.bodyAsText())["error"]!!.jsonPrimitive.content.contains("Имя"),
                "Текст называет поле «Имя»: ${longName.bodyAsText()}"
            )

            val badPin = client.post("$base/api/auth/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Гонщик","pin":"12a5"}""")
            }
            assertEquals(400, badPin.status.value)
            assertTrue(
                jsonOf(badPin.bodyAsText())["error"]!!.jsonPrimitive.content.contains("ПИН"),
                "Текст называет поле «ПИН»: ${badPin.bodyAsText()}"
            )
        }
    }

    @Test
    fun `список пользователей без хешей - только id и name, роут открыт без токена`() {
        withApp { base, _ ->
            val client = testHttpClient()
            TestAuth.set(null)
            createAndSelect(client, base, "Пилот", "0407")

            val response = client.get("$base/api/auth/users")
            assertEquals(200, response.status.value, "GET /api/auth/users открыт без токена")
            val raw = response.bodyAsText()
            val list = Json.parseToJsonElement(raw).jsonArray
            assertEquals(1, list.size)
            assertEquals(setOf("id", "name"), list[0].jsonObject.keys)
            assertFalse(raw.contains("pinHash"))
            assertFalse(raw.contains("salt"))
        }
    }

    // --- Удаление пользователя с каскадом ---

    @Test
    fun `удаление единственного пользователя - 400 Нельзя удалить единственного пользователя`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (token, userId) = createAndSelect(client, base, "Пилот", "0407")

            val response = client.delete("$base/api/auth/users/$userId")
            assertEquals(400, response.status.value)
            assertTrue(
                jsonOf(response.bodyAsText())["error"]!!.jsonPrimitive.content
                    .contains("Нельзя удалить единственного пользователя")
            )
            val users = client.get("$base/api/auth/users")
            assertEquals(1, Json.parseToJsonElement(users.bodyAsText()).jsonArray.size, "пользователь жив")
        }
    }

    @Test
    fun `каскад удаления - файлы пользователя, сессии, рабочей памяти, предпочтений, памяти, токен отозван`() {
        withApp { base, dataDir ->
            val client = testHttpClient()
            val (tokenA, userA) = createAndSelect(client, base, "Гонщик", "0407")

            // Данные пользователя A: сессия чатом, рабочая заметка, файлы предпочтений и памяти
            val chat = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"сессия для каскада"}""")
            }
            val sessionId = jsonOf(chat.bodyAsText())["sessionId"]!!.jsonPrimitive.content
            client.put("$base/api/session/$sessionId/working") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Заметка","content":"Содержимое"}""")
            }
            File(dataDir, "preferences").mkdirs()
            File(dataDir, "preferences/$userA.json").writeText(
                """{"addressForm":"ты","tone":"дружелюбный","language":"Русский",""" +
                    """"answerStructure":"текст","length":"средне","avoidTopics":"","interests":""}"""
            )
            File(dataDir, "memory/$userA").mkdirs()
            File(dataDir, "memory/$userA/longterm.json").writeText("{}")

            val (tokenB, userB) = createAndSelect(client, base, "Механик", "1234")
            TestAuth.set(tokenB)

            val response = client.delete("$base/api/auth/users/$userA")
            assertTrue(response.status.value in listOf(200, 204), "удаление успешно: ${response.status}")

            val ids = Json.parseToJsonElement(client.get("$base/api/auth/users").bodyAsText()).jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertFalse(userA in ids)
            assertTrue(userB in ids)

            assertFalse(File(dataDir, "users/$userA.json").exists(), "файл пользователя")
            assertFalse(File(dataDir, "preferences/$userA.json").exists(), "файл предпочтений")
            assertFalse(File(dataDir, "memory/$userA").exists(), "каталог долговременной памяти")
            assertFalse(File(dataDir, "sessions/$sessionId.json").exists(), "файл сессии")
            assertFalse(File(dataDir, "working/$sessionId.json").exists(), "файл рабочей памяти")

            assertTrue(File(dataDir, "users/$userB.json").exists(), "данные пользователя B целы")

            val stale = client.get("$base/api/sessions") { header("X-Auth-Token", tokenA) }
            assertEquals(401, stale.status.value, "токен удалённого пользователя отозван")
        }
    }

    @Test
    fun `удаление неизвестного пользователя - 404`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (token, _) = createAndSelect(client, base, "Пилот", "0407")
            createAndSelect(client, base, "Механик", "1234")

            val response = client.delete("$base/api/auth/users/00000000-0000-0000-0000-000000000000")
            assertEquals(404, response.status.value)
        }
    }

    @Test
    fun `удаление без токена - 401 от intercept`() {
        withApp { base, _ ->
            val client = testHttpClient()
            val (_, userId) = createAndSelect(client, base, "Пилот", "0407")
            TestAuth.set(null)

            assertEquals(401, client.delete("$base/api/auth/users/$userId").status.value)
        }
    }
}
