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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Изоляция долговременной памяти между пользователями (задача 8): у каждого
 * пользователя свой файл `memory/{userId}/longterm.json`; чужая запись по id
 * отвечает 404 (не 403) на чтение, обновление и удаление. Legacy-файл
 * `memory/longterm.json` (до-авторизационная память) задачей не трогается:
 * через API он никому не виден, миграцию выполняет задача 10.
 */
class MemoryIsolationRoutesTest {

    private class FakeLLMClient : LLMClient {
        override suspend fun ask(call: LLMCall) =
            ChatResult(reply = "ответ", promptTokens = 1, completionTokens = 1, totalTokens = 2, elapsedMs = 1L)
    }

    private fun tempDataDir(): String = Files.createTempDirectory("aiweb-routes-mem-iso").toString()

    private fun config(dataDir: String) = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m"
        ),
        dataDir = dataDir
    )

    /** Приложение с per-user памятью над [dataDir]; пользователь фикстуры сеется в withAuthApp. */
    private fun withApp(dataDir: String, block: suspend (base: String, dataDir: String) -> Unit) {
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(dataDir),
                    clientFactory = { FakeLLMClient() },
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
        }) {
            kotlinx.coroutines.runBlocking { block(it.base, dataDir) }
        }
    }

    private suspend fun postMemory(client: HttpClient, base: String, body: String) =
        client.post("$base/api/memory") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun jsonOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `два пользователя — ноль пересечений в списке, чужой id 404, каталоги раздельные`() {
        val dataDir = tempDataDir()
        withApp(dataDir) { base, _ ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!

            // Пользователь A (фикстурный) сохраняет запись
            val created = jsonOf(
                postMemory(
                    client, base,
                    """{"kind":"knowledge","title":"Заметка A","content":"секрет A"}"""
                ).bodyAsText()
            )
            val idA = created["entry"]!!.jsonObject["id"]!!.jsonPrimitive.content

            // Пользователь B: отдельный аккаунт и свой токен
            val userB = seedAuthUser(base, "Второй пилот")
            val tokenB = TestAuth.current()!!

            // Список B пуст, счётчики нулевые
            TestAuth.set(tokenB)
            val listedB = jsonOf(client.get("$base/api/memory").bodyAsText())
            assertTrue(listedB["entries"]!!.jsonArray.isEmpty(), "B не видит записей A")
            val countsB = listedB["counts"]!!.jsonObject
            assertEquals(3, countsB.size)
            assertTrue(countsB.values.all { it.jsonPrimitive.content == "0" }, "Счётчики B нулевые: $countsB")

            // Чужой id: GET/PUT/DELETE — 404 без деталей о существовании
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/memory/$idA").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("$base/api/memory/$idA") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"взлом"}""")
                }.status
            )
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/api/memory/$idA").status)

            // B сохраняет свою запись: каждый видит только свою
            postMemory(client, base, """{"kind":"decision","title":"Решение B","content":"своё"}""")
            val listB = jsonOf(client.get("$base/api/memory").bodyAsText())["entries"]!!.jsonArray
            assertEquals(1, listB.size, "B видит только свою запись")
            assertTrue(
                listB[0].jsonObject["id"]!!.jsonPrimitive.content != idA,
                "Запись B не совпадает с записью A"
            )

            // A по-прежнему видит только свою запись
            TestAuth.set(tokenA)
            val listA = jsonOf(client.get("$base/api/memory").bodyAsText())["entries"]!!.jsonArray
            assertEquals(1, listA.size, "A видит только свою запись")
            assertEquals(idA, listA[0].jsonObject["id"]!!.jsonPrimitive.content)

            // На диске: каталоги только этих двух пользователей, файлы longterm.json
            val memoryDir = File(dataDir, "memory")
            val dirs = memoryDir.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
            assertEquals(2, dirs.size, "Два каталога пользователей: $dirs")
            assertTrue(dirs.contains(userB), "Каталог пользователя B на диске: $dirs")
            dirs.forEach { assertTrue(File(memoryDir, "$it/longterm.json").isFile, "longterm.json у $it") }
            client.close()
        }
    }

    @Test
    fun `legacy-файл memory longterm json не читается через API и не трогается`() {
        val dataDir = tempDataDir()
        // До-авторизационный файл памяти (до задачи 10 он никому не принадлежит)
        val legacy = File(File(dataDir, "memory"), "longterm.json")
        legacy.parentFile.mkdirs()
        val legacyBody =
            """{"entries":[{"id":"old1","kind":"profile","title":"Профиль","content":"до-авторизационная запись","tags":[],"source":"user","createdAt":1,"updatedAt":2}]}"""
        legacy.writeText(legacyBody)

        withApp(dataDir) { base, _ ->
            val client = testHttpClient()

            // Под пользователем legacy-записи не видны
            val listed = jsonOf(client.get("$base/api/memory").bodyAsText())
            assertTrue(listed["entries"]!!.jsonArray.isEmpty(), "Legacy-записи не приписаны пользователю")
            assertTrue(
                listed["counts"]!!.jsonObject.values.all { it.jsonPrimitive.content == "0" },
                "Счётчики без legacy: ${listed["counts"]}"
            )

            // Новая запись не трогает legacy-файл
            postMemory(client, base, """{"kind":"profile","title":"Своя","content":"запись"}""")
            assertEquals(legacyBody, legacy.readText(), "Legacy-файл не должен измениться")
            assertEquals(1, jsonOf(client.get("$base/api/memory").bodyAsText())["entries"]!!.jsonArray.size)
            client.close()
        }
    }
}
