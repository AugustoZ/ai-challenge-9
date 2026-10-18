package com.example.aiweb.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket

/**
 * Общий хелпер auth-фикстуры для HTTP-тестов маршрутов (задача 6): после
 * появления централизованного 401-intercept каждый запрос к защищённым роутам
 * несёт `X-Auth-Token`. [withApp]-обвязки тестов создают фикстурного пользователя
 * через открытый POST /api/auth/users и кладут его токен в [TestAuth]; клиенты
 * тестов строятся через [testHttpClient] и добавляют заголовок автоматически.
 */
object TestAuth {

    private val token = ThreadLocal<String?>()

    /** Устанавливает/снимает (null) токен фикстурного пользователя. */
    fun set(value: String?) {
        if (value == null) token.remove() else token.set(value)
    }

    fun current(): String? = token.get()
}

/**
 * HttpClient с ContentNegotiation и авто-заголовком X-Auth-Token из [TestAuth]
 * (значение читается при построении клиента — вызывать после посева пользователя).
 */
fun testHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(DefaultRequest) {
        TestAuth.current()?.let { headers.append("X-Auth-Token", it) }
    }
}

/**
 * Создаёт фикстурного пользователя через открытый роут POST /api/auth/users
 * и кладёт его токен в [TestAuth]. Возвращает id пользователя.
 */
fun seedAuthUser(base: String, name: String = "Тест-пилот"): String = kotlinx.coroutines.runBlocking {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    client.use {
        val response = it.post("$base/api/auth/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","pin":"0407"}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        check(response.status.value == 200) {
            "Не удалось создать фикстурного пользователя: ${response.status} $body"
        }
        TestAuth.set(body["token"]!!.jsonPrimitive.content)
        body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }
}

/**
 * Результат [withAuthApp] (задача 11): адрес запущенного сервера, токен и
 * идентификатор фикстурного пользователя.
 */
class AuthedApp(
    val base: String,
    val token: String,
    val userId: String,
) {
    /** HttpClient с авто-заголовком X-Auth-Token фикстурного пользователя. */
    val client: HttpClient by lazy { testHttpClient() }
}

/**
 * Общая фикстура интеграционных тестов (задача 11): поднимает приложение с
 * [module] на случайном порту, сеет фикстурного пользователя ([seedAuthUser])
 * и передаёт блоку [AuthedApp] с валидным токеном. Клиенты, построенные из
 * [AuthedApp.client] или через [testHttpClient] внутри блока, несут токен
 * автоматически; после блока токен снимается и сервер останавливается.
 */
fun withAuthApp(module: Application.() -> Unit, block: (app: AuthedApp) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
        install(ServerContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        module()
    }
    server.start(wait = false)
    try {
        val userId = seedAuthUser("http://127.0.0.1:$port")
        block(AuthedApp(base = "http://127.0.0.1:$port", token = TestAuth.current()!!, userId = userId))
    } finally {
        TestAuth.set(null)
        server.stop(100, 500)
    }
}

/**
 * Хелпер плана задачи 11 — authed(request, token): добавляет заголовок
 * X-Auth-Token к запросу. По умолчанию берёт токен из [TestAuth].
 */
fun HttpRequestBuilder.authed(token: String = TestAuth.current()!!) {
    header("X-Auth-Token", token)
}
