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
import com.example.aiweb.session.InMemorySessionStore
import com.example.aiweb.session.SessionStore
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Изоляция сессий между пользователями (user-isolation spec): список сессий
 * отдаёт только сессии владельца, чужая сессия по id отвечает 404 (не 403)
 * на чтение, удаление, ветки и рабочую память; чат в чужую сессию отклоняется
 * 404 и не дописывает чужую историю. Legacy-сессии без userId (до-миграционные)
 * невидимы для любого пользователя, но сохраняются: их атрибутирует только
 * миграция (задача 10).
 */
class SessionIsolationRoutesTest {

    private class FakeLLMClient : LLMClient {
        override suspend fun ask(call: LLMCall) =
            ChatResult(reply = "ответ", promptTokens = 1, completionTokens = 1, totalTokens = 2, elapsedMs = 1L)
    }

    private fun config() = AppConfig(
        port = 0,
        default = ProviderConfig(
            id = "default",
            name = "Основной",
            type = ProviderConfig.TYPE_OPENAI,
            apiUrl = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "k",
            model = "m"
        )
    )

    /** Приложение с общим стором сессий; пользователь фикстуры сеется в withAuthApp. */
    private fun withApp(store: SessionStore, block: suspend (base: String, store: SessionStore) -> Unit) {
        withAuthApp(module = {
            routing {
                aiRoutes(
                    config = config(),
                    store = store,
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
            kotlinx.coroutines.runBlocking { block(it.base, store) }
        }
    }

    private fun jsonOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private suspend fun chat(client: HttpClient, base: String, body: String): HttpResponse =
        client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Создаёт текущему пользователю сессию реальным чатом; возвращает её id. */
    private suspend fun chatSession(client: HttpClient, base: String, message: String): String =
        jsonOf(chat(client, base, """{"message":"$message"}""").bodyAsText())["sessionId"]!!
            .jsonPrimitive.content

    @Test
    fun `два пользователя не видят сессий друг друга, чужая сессия 404`() {
        withApp(InMemorySessionStore()) { base, _ ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!

            // A ведёт диалог: сессия принадлежит A
            val idA = chatSession(client, base, "вопрос пилота A")

            // B: отдельный аккаунт и свой токен
            seedAuthUser(base, "Второй пилот")
            val tokenB = TestAuth.current()!!
            TestAuth.set(tokenB)

            // Список B пуст: сессии A не видны
            val listedB = jsonOf(client.get("$base/api/sessions").bodyAsText())
            assertTrue(listedB["sessions"]!!.jsonArray.isEmpty(), "B не видит сессий A")

            // Чужая сессия по id: GET и DELETE отвечают 404 (не 403)
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/$idA").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/api/session/$idA").status)

            // Сессия A не пострадала: A по-прежнему видит и читает её
            TestAuth.set(tokenA)
            val listedA = jsonOf(client.get("$base/api/sessions").bodyAsText())["sessions"]!!.jsonArray
            assertEquals(1, listedA.size, "A видит свою сессию")
            assertEquals(idA, listedA[0].jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.get("$base/api/session/$idA").status)
            client.close()
        }
    }

    @Test
    fun `чат в чужую сессию - 404 и чужая история не растёт`() {
        withApp(InMemorySessionStore()) { base, store ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!
            val idA = chatSession(client, base, "вопрос A")
            val before = store.get(idA)!!.messages.size
            assertTrue(before > 0, "Сессия A после первого хода не пуста")

            seedAuthUser(base, "Второй пилот")

            // Блокирующий и стриминговый чат в чужую сессию отклоняются
            val hijack = """{"message":"вломился","sessionId":"$idA"}"""
            assertEquals(HttpStatusCode.NotFound, chat(client, base, hijack).status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("$base/api/chat/stream") {
                    contentType(ContentType.Application.Json)
                    setBody(hijack)
                }.status
            )

            // История A не изменилась, у B не завелось новой сессии
            assertEquals(before, store.get(idA)!!.messages.size, "В чужую сессию не дописано")
            assertTrue(
                jsonOf(client.get("$base/api/sessions").bodyAsText())["sessions"]!!.jsonArray.isEmpty(),
                "У B нет сессий"
            )

            // A продолжает свой диалог как ни в чём не бывало
            TestAuth.set(tokenA)
            assertEquals(HttpStatusCode.OK, chat(client, base, """{"message":"продолжаю","sessionId":"$idA"}""").status)
            assertEquals(before + 2, store.get(idA)!!.messages.size, "Свой диалог работает")
            client.close()
        }
    }

    @Test
    fun `полная чистка сессий стирает только свои`() {
        withApp(InMemorySessionStore()) { base, _ ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!
            val idA = chatSession(client, base, "вопрос A")

            seedAuthUser(base, "Второй пилот")
            val idB = chatSession(client, base, "вопрос B")
            assertNotNull(idB)

            // B стирает сессии: только свои
            val cleared = jsonOf(client.delete("$base/api/sessions").bodyAsText())
            assertEquals("1", cleared["deleted"]!!.jsonPrimitive.content, "Удалена только сессия B")
            assertTrue(
                jsonOf(client.get("$base/api/sessions").bodyAsText())["sessions"]!!.jsonArray.isEmpty(),
                "У B пусто после чистки"
            )

            // Сессия A уцелела
            TestAuth.set(tokenA)
            assertEquals(HttpStatusCode.OK, client.get("$base/api/session/$idA").status, "Сессия A не тронута")
            assertEquals(1, jsonOf(client.get("$base/api/sessions").bodyAsText())["sessions"]!!.jsonArray.size)
            client.close()
        }
    }

    @Test
    fun `чужие ветки и рабочая память - 404`() {
        withApp(InMemorySessionStore()) { base, _ ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!
            val idA = chatSession(client, base, "вопрос A")

            // A создаёт заметку рабочей памяти и ветку
            val upserted = client.put("$base/api/session/$idA/working") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"заметка","content":"приборная запись"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val entryId = jsonOf(upserted.bodyAsText())["entries"]!!.jsonArray[0]
                .jsonObject["id"]!!.jsonPrimitive.content
            val forked = client.post("$base/api/session/$idA/branches")
            assertEquals(HttpStatusCode.OK, forked.status)
            val branchId = jsonOf(forked.bodyAsText())["branchId"]!!.jsonPrimitive.content

            seedAuthUser(base, "Второй пилот")

            // B: ветки чужой сессии недоступны
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/$idA/branches").status)
            assertEquals(HttpStatusCode.NotFound, client.post("$base/api/session/$idA/branches").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("$base/api/session/$idA/branches/activate") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"branchId":"$branchId"}""")
                }.status
            )

            // B: рабочая память чужой сессии недоступна на всех методах
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/$idA/working").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("$base/api/session/$idA/working") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"взлом","content":"взлом"}""")
                }.status
            )
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/api/session/$idA/working/$entryId").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/api/session/$idA/working").status)

            // У A всё на месте: заметка и обе записи веток
            TestAuth.set(tokenA)
            val workingA = jsonOf(client.get("$base/api/session/$idA/working").bodyAsText())
            assertEquals(1, workingA["entries"]!!.jsonArray.size, "Заметка A цела")
            assertEquals(2, jsonOf(client.get("$base/api/session/$idA/branches").bodyAsText())["branches"]!!.jsonArray.size)
            client.close()
        }
    }

    @Test
    fun `overview со сводкой памяти не читает чужую сессию`() {
        withApp(InMemorySessionStore()) { base, sharedStore ->
            val client = testHttpClient()
            val tokenA = TestAuth.current()!!
            val idA = chatSession(client, base, "вопрос A")

            // A кладёт заметку рабочей памяти: её счётчик не должен утечь B
            client.put("$base/api/session/$idA/working") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"заметка","content":"приборная запись"}""")
            }

            // Legacy-сессия без владельца тоже должна быть неотличима от несуществующей
            val legacy = sharedStore.create()

            seedAuthUser(base, "Второй пилот")

            // B: сводка памяти по чужой и по legacy-сессии — 404 (не 403, без счётчиков)
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/memory/overview?sessionId=$idA").status)
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/memory/overview?sessionId=${legacy.id}").status)

            // Неизвестная-прежнему не ошибка (панель для свежей)
            val unknown = jsonOf(client.get("$base/api/memory/overview?sessionId=нет-такой").bodyAsText())
            assertEquals("0", unknown["shortTerm"]!!.jsonObject["turns"]!!.jsonPrimitive.content)

            // Своя сессия отдаёт обзор целиком
            val idB = chatSession(client, base, "вопрос B")
            val own = jsonOf(client.get("$base/api/memory/overview?sessionId=$idB").bodyAsText())
            assertEquals("2", own["shortTerm"]!!.jsonObject["turns"]!!.jsonPrimitive.content, "Ходы своей сессии")

            // A видит обзор своей сессии с той же заметкой
            TestAuth.set(tokenA)
            val overviewA = jsonOf(client.get("$base/api/memory/overview?sessionId=$idA").bodyAsText())
            assertEquals("2", overviewA["shortTerm"]!!.jsonObject["turns"]!!.jsonPrimitive.content)
            assertEquals("1", overviewA["working"]!!.jsonObject["entries"]!!.jsonPrimitive.content)
            client.close()
        }
    }

    @Test
    fun `legacy-сессия без владельца невидима и сохранна`() {
        val store = InMemorySessionStore()
        val legacy = store.create()
        store.append(legacy.id, OpenAIMessage(role = "user", content = "до-авторизационный ход"))
        val legacySize = store.get(legacy.id)!!.messages.size

        withApp(store) { base, sharedStore ->
            val client = testHttpClient()

            // Не видна в списке и по id
            assertTrue(
                jsonOf(client.get("$base/api/sessions").bodyAsText())["sessions"]!!.jsonArray.isEmpty(),
                "Legacy-сессия не приписана пользователю"
            )
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/${legacy.id}").status)

            // Чат в legacy-сессию отклонён, история не тронута
            assertEquals(
                HttpStatusCode.NotFound,
                chat(client, base, """{"message":"взлом","sessionId":"${legacy.id}"}""").status
            )
            assertEquals(legacySize, sharedStore.get(legacy.id)!!.messages.size)

            // Чужая/ничья сессия не удаляется ни по id, ни полной чисткой
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/api/session/${legacy.id}").status)
            client.delete("$base/api/sessions")
            assertNotNull(sharedStore.get(legacy.id), "Legacy-сессия пережила чистку")

            // Второй пользователь видит то же: невидима и сохранна
            seedAuthUser(base, "Второй пилот")
            assertEquals(HttpStatusCode.NotFound, client.get("$base/api/session/${legacy.id}").status)
            assertNotNull(sharedStore.get(legacy.id), "Legacy-сессия сохранена")
            client.close()
        }
    }
}
