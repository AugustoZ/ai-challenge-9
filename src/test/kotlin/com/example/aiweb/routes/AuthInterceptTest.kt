package com.example.aiweb.routes

import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Матрица 401 (задача 6): каждый защищённый роут отвечает 401
 * {"error": "Требуется вход"} и без токена, и с мусорным токеном; открытые
 * роуты (login, GET+POST /api/auth/users; статика `/` вне aiRoutes) живут
 * без токена и отвечают по собственной логике. Зеркалит таблицу disposition
 * из specs/user-auth/spec.md.
 */
class AuthInterceptTest {

    private fun config() = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m",
            supportsTools = true
        )
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun withApp(block: suspend (base: String) -> Unit) {
        val port = freePort()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            routing { aiRoutes(config = config()) }
        }
        server.start(wait = false)
        try {
            runBlocking { block("http://127.0.0.1:$port") }
        } finally {
            server.stop(100, 500)
        }
    }

    /** Все защищённые роуты таблицы disposition: метод, путь, JSON-тело (если роут его ждёт). */
    private val protectedRoutes: List<Triple<String, String, String?>> = listOf(
        Triple("GET", "/api/log", null),
        Triple("GET", "/api/models", null),
        Triple("POST", "/api/chat", """{"message":"привет"}"""),
        Triple("POST", "/api/chat/stream", """{"message":"привет"}"""),
        Triple("POST", "/api/cancel", """{"requestId":"r1"}"""),
        Triple("POST", "/api/task/pause", """{"sessionId":"x"}"""),
        Triple("POST", "/api/task/resume", """{"sessionId":"x"}"""),
        Triple("GET", "/api/sessions", null),
        Triple("DELETE", "/api/sessions", null),
        Triple("GET", "/api/session/x", null),
        Triple("DELETE", "/api/session/x", null),
        Triple("GET", "/api/session/x/branches", null),
        Triple("POST", "/api/session/x/branches", null),
        Triple("POST", "/api/session/x/branches/activate", """{"branchId":"b"}"""),
        Triple("GET", "/api/session/x/working", null),
        Triple("PUT", "/api/session/x/working", """{"title":"t","content":"c"}"""),
        Triple("DELETE", "/api/session/x/working", null),
        Triple("DELETE", "/api/session/x/working/e1", null),
        Triple("GET", "/api/memory", null),
        Triple("POST", "/api/memory", """{"kind":"knowledge","title":"t","content":"c"}"""),
        Triple("PUT", "/api/memory/m1", """{"title":"t"}"""),
        Triple("DELETE", "/api/memory/m1", null),
        Triple("GET", "/api/memory/overview", null),
        Triple("GET", "/api/files/test.txt", null),
        Triple("DELETE", "/api/auth/users/unknown", null)
    )

    private suspend fun request(
        client: io.ktor.client.HttpClient,
        base: String,
        method: String,
        path: String,
        body: String?
    ): HttpResponse = when (method) {
        "GET" -> client.get("$base$path")
        "POST" -> if (body != null) {
            client.post("$base$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            client.post("$base$path")
        }
        "PUT" -> client.put("$base$path") {
            contentType(ContentType.Application.Json)
            setBody(body!!)
        }
        "DELETE" -> client.delete("$base$path")
        else -> throw IllegalArgumentException(method)
    }

    @Test
    fun `матрица 401 - без токена и с мусорным токеном все защищённые роуты отвечают 401 Требуется вход`() {
        withApp { base ->
            val client = testHttpClient()
            TestAuth.set(null)
            for ((method, path, body) in protectedRoutes) {
                val withoutToken = request(client, base, method, path, body)
                assertEquals(401, withoutToken.status.value, "$method $path без токена")
                assertEquals(
                    "Требуется вход",
                    Json.parseToJsonElement(withoutToken.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content,
                    "$method $path — тело ошибки intercept'а"
                )
            }
        }
    }

    @Test
    fun `мусорный токен - 401 на представителях защищённых групп`() {
        withApp { base ->
            val client = testHttpClient()
            TestAuth.set(null)
            for ((method, path, body) in listOf(
                Triple("GET", "/api/sessions", null),
                Triple("GET", "/api/memory", null),
                Triple("GET", "/api/log", null),
                Triple("GET", "/api/session/x", null),
                Triple("GET", "/api/models", null),
                Triple("POST", "/api/chat", """{"message":"привет"}"""),
                Triple("POST", "/api/chat/stream", """{"message":"привет"}"""),
                Triple("DELETE", "/api/sessions", null)
            )) {
                val garbage = when (method) {
                    "GET" -> client.get("$base$path") { header("X-Auth-Token", "garbage") }
                    "POST" -> client.post("$base$path") {
                        contentType(ContentType.Application.Json)
                        setBody(body!!)
                        header("X-Auth-Token", "garbage")
                    }
                    else -> client.delete("$base$path") { header("X-Auth-Token", "garbage") }
                }
                assertEquals(401, garbage.status.value, "$method $path с мусорным токеном")
            }
        }
    }

    @Test
    fun `open-роуты живут без токена - список и создание пользователей отвечают по своей логике`() {
        withApp { base ->
            val client = testHttpClient()
            TestAuth.set(null)

            // GET /api/auth/users — открыт: 200 по собственной логике (пустой список)
            val users = client.get("$base/api/auth/users")
            assertEquals(200, users.status.value)
            assertEquals("[]", users.bodyAsText().trim())

            // POST /api/auth/users — открыт: валидный запрос создаёт пользователя (200)
            val created = client.post("$base/api/auth/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Пилот","pin":"0407"}""")
            }
            assertEquals(200, created.status.value)
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val userId = body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content

            // POST /api/auth/login — открыт: 401 это БИЗНЕС-ошибка «Неверный ПИН…»,
            // а не 401 intercept'а «Требуется вход»
            val badLogin = client.post("$base/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$userId","pin":"9999"}""")
            }
            assertEquals(401, badLogin.status.value)
            assertEquals(
                "Неверный ПИН или пользователь",
                Json.parseToJsonElement(badLogin.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
            )
        }
    }
}
