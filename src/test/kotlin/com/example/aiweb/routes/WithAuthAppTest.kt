package com.example.aiweb.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Проверки общей фикстуры [withAuthApp] (задача 11): приложение поднимается
 * с посевом фикстурного пользователя, [AuthedApp.client] открывает защищённые
 * роуты, запрос без токена получает 401, [authed] добавляет заголовок.
 */
class WithAuthAppTest {

    private fun testConfig() = com.example.aiweb.config.AppConfig(
        port = 0,
        default = com.example.aiweb.config.ProviderConfig(
            id = "default",
            name = "Основной",
            type = com.example.aiweb.config.ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m"
        )
    )

    @Test
    fun `фикстура создаёт пользователя и его токен открывает защищённые роуты`() {
        withAuthApp(module = { routing { aiRoutes(config = testConfig()) } }) { app ->
            kotlinx.coroutines.runBlocking {
                val client = app.client
                assertEquals(200, client.get("${app.base}/api/sessions").status.value)

                val users = Json.parseToJsonElement(
                    client.get("${app.base}/api/auth/users").bodyAsText()
                ).jsonArray
                assertEquals(1, users.size)
                assertEquals(app.userId, users[0].jsonObject["id"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `клиент без токена получает 401 на защищённом роуте`() {
        withAuthApp(module = { routing { aiRoutes(config = testConfig()) } }) { app ->
            kotlinx.coroutines.runBlocking {
                HttpClient(CIO).use { bare ->
                    assertEquals(401, bare.get("${app.base}/api/sessions").status.value)
                }
            }
        }
    }

    @Test
    fun `authed добавляет заголовок токена к произвольному запросу`() {
        withAuthApp(module = { routing { aiRoutes(config = testConfig()) } }) { app ->
            kotlinx.coroutines.runBlocking {
                HttpClient(CIO).use { bare ->
                    val response = bare.get("${app.base}/api/sessions") { authed(app.token) }
                    assertEquals(200, response.status.value)
                }
            }
        }
    }
}
