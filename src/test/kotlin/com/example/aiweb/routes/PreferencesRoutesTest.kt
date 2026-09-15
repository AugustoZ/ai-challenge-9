package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки маршрутов предпочтений (задача 7): GET отдаёт сохранённые
 * предпочтения пользователя или дефолты (файла ещё нет — прерванный визард
 * не ошибка), PUT валидирует доменом задачи 4 (русский текст об ошибке),
 * сохраняет и возвращает санитизированный объект. Без валидного токена —
 * 401 от центрального intercept'а (задача 6).
 */
class PreferencesRoutesTest {

    /** Фейковый транспорт: маршруты предпочтений LLM не вызывают, но клиент строится. */
    private class FakeLLMClient(private val canned: com.example.aiweb.client.ChatResult) : LLMClient {
        override suspend fun ask(call: LLMCall): ChatResult = canned
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-prefs").toString()

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

    /** Поднимает приложение с временным dataDir и фикстурным пользователем (токен в TestAuth). */
    private fun withApp(block: suspend (base: String, dataDir: String, userId: String) -> Unit) {
        val dataDir = tempDataDir()
        val port = freePort()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            routing {
                aiRoutes(
                    config = config(dataDir),
                    clientFactory = {
                        FakeLLMClient(
                            com.example.aiweb.client.ChatResult(
                                reply = "ответ сервера",
                                promptTokens = 1,
                                completionTokens = 2,
                                totalTokens = 3,
                                elapsedMs = 42L
                            )
                        )
                    }
                )
            }
        }
        server.start(wait = false)
        try {
            val userId = seedAuthUser("http://127.0.0.1:$port")
            kotlinx.coroutines.runBlocking { block("http://127.0.0.1:$port", dataDir, userId) }
        } finally {
            TestAuth.set(null)
            server.stop(100, 500)
        }
    }

    private fun httpClient() = testHttpClient()

    /** Клиент без заголовка X-Auth-Token: как неавторизованный фронтенд до входа. */
    private fun anonClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun jsonOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private suspend fun getPreferences(client: HttpClient, base: String) = client.get("$base/api/preferences")

    private suspend fun putPreferences(client: HttpClient, base: String, body: String) =
        client.put("$base/api/preferences") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // --- GET: дефолты при первом обращении ---

    @Test
    fun `GET без сохранённых предпочтений отдаёт дефолты, а не 404`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val response = getPreferences(client, base)
            assertEquals(HttpStatusCode.OK, response.status)
            val prefs = jsonOf(response.bodyAsText())
            // Все 7 полей опросника присутствуют явно (серверный Json не роняет дефолты)
            assertEquals("ты", prefs["addressForm"]!!.jsonPrimitive.content)
            assertEquals("дружелюбный", prefs["tone"]!!.jsonPrimitive.content)
            assertEquals("Русский", prefs["language"]!!.jsonPrimitive.content)
            assertEquals("текст", prefs["answerStructure"]!!.jsonPrimitive.content)
            assertEquals("средне", prefs["length"]!!.jsonPrimitive.content)
            assertEquals("", prefs["avoidTopics"]!!.jsonPrimitive.content)
            assertEquals("", prefs["interests"]!!.jsonPrimitive.content)
            client.close()
        }
    }

    // --- PUT: round-trip, частичный JSON, санитизация ---

    @Test
    fun `PUT валидных предпочтений возвращает их, сохраняет на диск и GET читает обратно`() {
        withApp { base, dataDir, userId ->
            val client = httpClient()
            val body = """
                {"addressForm":"пилот","tone":"панибратский","language":"Английский",
                 "answerStructure":"таблицы","length":"развернуто",
                 "avoidTopics":"политика","interests":"гонки"}
            """.trimIndent()
            val response = putPreferences(client, base, body)
            assertEquals(HttpStatusCode.OK, response.status)

            // Ответ — сохранённый объект целиком
            val saved = jsonOf(response.bodyAsText())
            assertEquals("пилот", saved["addressForm"]!!.jsonPrimitive.content)
            assertEquals("панибратский", saved["tone"]!!.jsonPrimitive.content)
            assertEquals("Английский", saved["language"]!!.jsonPrimitive.content)
            assertEquals("таблицы", saved["answerStructure"]!!.jsonPrimitive.content)
            assertEquals("развернуто", saved["length"]!!.jsonPrimitive.content)
            assertEquals("политика", saved["avoidTopics"]!!.jsonPrimitive.content)
            assertEquals("гонки", saved["interests"]!!.jsonPrimitive.content)

            // Персистентность: отдельный файл preferences/{userId}.json
            val file = File(dataDir, "preferences/$userId.json")
            assertTrue(file.isFile, "предпочтения должны жить в отдельном файле preferences/{userId}.json")

            // GET после PUT — тот же объект (круговой цикл без искажений)
            val reread = jsonOf(getPreferences(client, base).bodyAsText())
            assertEquals(saved, reread)
            client.close()
        }
    }

    @Test
    fun `PUT с частичным JSON заполняет отсутствующие поля дефолтами`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val response = putPreferences(client, base, """{"tone":"роботизированный"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val prefs = jsonOf(response.bodyAsText())
            assertEquals("роботизированный", prefs["tone"]!!.jsonPrimitive.content)
            // Отсутствующие поля — дефолты опросника (замена, а не слияние с прежним)
            assertEquals("ты", prefs["addressForm"]!!.jsonPrimitive.content)
            assertEquals("Русский", prefs["language"]!!.jsonPrimitive.content)
            assertEquals("текст", prefs["answerStructure"]!!.jsonPrimitive.content)
            assertEquals("средне", prefs["length"]!!.jsonPrimitive.content)
            assertEquals("", prefs["avoidTopics"]!!.jsonPrimitive.content)
            assertEquals("", prefs["interests"]!!.jsonPrimitive.content)
            client.close()
        }
    }

    @Test
    fun `PUT возвращает санитизированный объект — контрольные символы и края свободных полей срезаны`() {
        withApp { base, dataDir, userId ->
            val client = httpClient()
            // В JSON "\n" — управляющий символ: заменяется пробелом, края срезаются
            val response = putPreferences(
                client, base,
                """{"avoidTopics":"  политика\n  ","interests":"  гонки  "}"""
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val prefs = jsonOf(response.bodyAsText())
            assertEquals("политика", prefs["avoidTopics"]!!.jsonPrimitive.content)
            assertEquals("гонки", prefs["interests"]!!.jsonPrimitive.content)

            // На диск попала та же санитизированная версия, а не сырой ввод
            val stored = File(dataDir, "preferences/$userId.json").readText()
            assertFalse(stored.contains("\n  "), "на диск не должны попадать управляющие символы")
            client.close()
        }
    }

    // --- PUT: невалидные payloads — 400 с русским текстом, называющим поле ---

    @Test
    fun `PUT с неизвестным значением вопроса опросника — 400 с перечнем допустимых`() {
        withApp { base, _, _ ->
            val client = httpClient()
            val response = putPreferences(client, base, """{"length":"бесконечно"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("Длина ответов"), "ошибка должна называть поле: $text")
            assertTrue(text.contains("кратко"), "ошибка должна называть допустимые варианты: $text")
            client.close()
        }
    }

    @Test
    fun `PUT с превышением лимитов свободных полей — 400 с русским текстом`() {
        withApp { base, _, _ ->
            val client = httpClient()

            // Интересы: 1001 символ при лимите 1000
            val tooLongInterests = putPreferences(
                client, base,
                """{"interests":"${"x".repeat(1001)}"}"""
            )
            assertEquals(HttpStatusCode.BadRequest, tooLongInterests.status)
            val interestsError = tooLongInterests.bodyAsText()
            assertTrue(interestsError.contains("Интересы"), "ошибка должна называть поле: $interestsError")
            assertTrue(interestsError.contains("1000"), "ошибка должна называть лимит: $interestsError")

            // Избегаемые темы: 1001 символ
            val tooLongAvoid = putPreferences(
                client, base,
                """{"avoidTopics":"${"x".repeat(1001)}"}"""
            )
            assertEquals(HttpStatusCode.BadRequest, tooLongAvoid.status)
            assertTrue(tooLongAvoid.bodyAsText().contains("Избегаемые темы"))

            // Структура ответов: свободное описание длиннее 255 символов
            val tooLongStructure = putPreferences(
                client, base,
                """{"answerStructure":"${"x".repeat(256)}"}"""
            )
            assertEquals(HttpStatusCode.BadRequest, tooLongStructure.status)
            assertTrue(tooLongStructure.bodyAsText().contains("Структура ответов"))

            // Мусорное тело — 400 «Некорректный запрос»
            val garbage = putPreferences(client, base, "это не json")
            assertEquals(HttpStatusCode.BadRequest, garbage.status)
            client.close()
        }
    }

    // --- Без токена: центральный intercept отвечает 401 ---

    @Test
    fun `GET и PUT без токена — 401 «Требуется вход»`() {
        withApp { base, _, _ ->
            anonClient().use { anon ->
                val get = getPreferences(anon, base)
                assertEquals(HttpStatusCode.Unauthorized, get.status)
                assertTrue(get.bodyAsText().contains("Требуется вход"))

                val put = putPreferences(anon, base, """{"tone":"роботизированный"}""")
                assertEquals(HttpStatusCode.Unauthorized, put.status)

                // И с мусорным токеном — тоже 401
                TestAuth.set("мусорный-токен")
                val garbage = testHttpClient().use { garbageClient ->
                    garbageClient.get("$base/api/preferences")
                }
                assertEquals(HttpStatusCode.Unauthorized, garbage.status)
            }
        }
    }
}
