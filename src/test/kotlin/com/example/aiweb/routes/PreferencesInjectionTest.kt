package com.example.aiweb.routes

import com.example.aiweb.client.ChatResult
import com.example.aiweb.client.FactExtractor
import com.example.aiweb.client.LLMCall
import com.example.aiweb.client.LLMClient
import com.example.aiweb.client.OpenAIFactExtractor
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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Инъекция блока предпочтений в seed каждого LLM-запроса (задача 12):
 * хвостовое system-сообщение «Предпочтения пользователя» после
 * SessionHistory.trim, в одном слоте с блоком памяти (сначала предпочтения,
 * затем память), во всех режимах (прямой, пошаговый, агент, стрим).
 * Саб-вызовы (экстрактор фактов, сводка, судья) блок НЕ получают.
 */
class PreferencesInjectionTest {

    /** Фейковый транспорт: отдаёт заготовленный ответ, запоминает вызовы. */
    private class FakeLLMClient(private val canned: ChatResult) : LLMClient {
        val calls = mutableListOf<LLMCall>()

        override suspend fun ask(call: LLMCall): ChatResult {
            calls.add(call)
            return canned
        }
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

    /** Поднимает приложение с фейковым LLM; экстрактор фактов — по умолчанию заглушка (без вызова LLM). */
    private fun withApp(
        factExtractorFactory: (LLMClient) -> FactExtractor = {
            object : FactExtractor {
                override suspend fun extract(current: List<com.example.aiweb.model.Fact>, userMessage: String): List<com.example.aiweb.model.Fact>? = null
            }
        },
        block: suspend (base: String, fake: FakeLLMClient, dataDir: String) -> Unit
    ) {
        val dataDir = tempDataDir()
        val chat = FakeLLMClient(
            ChatResult(reply = "ответ сервера", promptTokens = 1, completionTokens = 2, totalTokens = 3, elapsedMs = 42L)
        )
        withAuthApp(module = {
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
                    factExtractorFactory = factExtractorFactory
                )
            }
        }) { app ->
            kotlinx.coroutines.runBlocking { block(app.base, chat, dataDir) }
        }
    }

    private fun httpClient() = testHttpClient()

    /** Заголовок блока предпочтений. */
    private val HEADER = "Предпочтения пользователя"

    /** Системные сообщения вызова. */
    private fun LLMCall.systemTexts(): List<String> =
        messages.filter { it.role == "system" }.map { it.content.orEmpty() }

    /** Вызов содержит блок предпочтений (по заголовку). */
    private fun LLMCall.hasPrefsBlock(): Boolean = systemTexts().any { it.contains(HEADER) }

    /** Сообщение блока предпочтений из вызова. */
    private fun LLMCall.prefsBlock(): String? =
        messages.lastOrNull { it.role == "system" && it.content.orEmpty().contains(HEADER) }?.content

    /** PUT /api/preferences с полным набором полей (контракт замещения, задача 7). */
    private suspend fun putPrefs(client: io.ktor.client.HttpClient, base: String, body: String) =
        client.put("$base/api/preferences") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Создаёт сессию ходом чата и возвращает её идентификатор. */
    private suspend fun createSession(client: io.ktor.client.HttpClient, base: String): String {
        val response = client.post("$base/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = "разговор для создания сессии"))
        }.body<ChatResponse>()
        return assertNotNull(response.sessionId)
    }

    // --- Блок в seed каждого запроса ---

    @Test
    fun `каждый запрос несёт хвостовой блок предпочтений с дефолтными значениями`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            createSession(client, base)

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход"))
            }.body<ChatResponse>()

            val main = fake.calls.last()
            val block = assertNotNull(main.prefsBlock(), "дефолтный блок предпочтений должен быть в seed")
            assertTrue(block.contains("Форма обращения: ты"), "дефолт «ты»: $block")
            assertTrue(block.contains("Тон: дружелюбный"), "дефолт «дружелюбный»: $block")
            assertTrue(block.contains("Язык: Русский"), "дефолт «Русский»: $block")
            client.close()
        }
    }

    @Test
    fun `изменение предпочтений применяется к следующему запросу`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            createSession(client, base)

            val saved = putPrefs(
                client, base,
                """{"addressForm":"гонщик","tone":"роботизированный","language":"Английский",""" +
                    """"answerStructure":"таблицы","length":"развернуто","avoidTopics":"политика","interests":"гонки"}"""
            )
            assertEquals(200, saved.status.value, "фикстура: PUT предпочтений должен пройти: ${saved.bodyAsText()}")

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход"))
            }.body<ChatResponse>()

            val block = assertNotNull(fake.calls.last().prefsBlock(), "блок должен быть после PUT")
            assertTrue(block.contains("Форма обращения: гонщик"), "новое обращение: $block")
            assertTrue(block.contains("Тон: роботизированный"), "новый тон: $block")
            assertTrue(block.contains("Язык: Английский"), "новый язык: $block")
            assertTrue(block.contains("Избегаемые темы: политика"), "избегаемые темы: $block")
            assertTrue(block.contains("Интересы: гонки"), "интересы: $block")
            assertFalse(block.contains("дружелюбный"), "старое значение ушло: $block")
            client.close()
        }
    }

    @Test
    fun `блок предпочтений добавляется до блока памяти и переживает trim`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            client.post("$base/api/memory") {
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"profile","title":"Профиль","content":"Аугусто"}""")
            }

            // Длинная история: 12 ходов по ~200 токенов
            repeat(12) { turn ->
                client.post("$base/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(message = "ход номер $turn: ${"д".repeat(500)}", sessionId = sid))
                }.body<ChatResponse>()
            }

            // Резерв под «Топливо» режет бюджет истории: старые ходы уходят
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "итоговый вопрос", sessionId = sid, maxTokens = 23000))
            }.body<ChatResponse>()

            val main = fake.calls.last()
            val messages = main.messages
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            val prefsIdx = messages.indexOfLast { it.role == "system" && it.content.orEmpty().contains(HEADER) }
            val memoryIdx = messages.indexOfLast {
                it.role == "system" && it.content.orEmpty().contains("Долговременная память:")
            }

            assertTrue(prefsIdx > lastUserIdx, "блок предпочтений идёт хвостом после trim (после user-сообщения)")
            assertTrue(memoryIdx > prefsIdx, "порядок слота: сначала предпочтения, затем память (задача 12)")
            assertEquals(messages.lastIndex, memoryIdx, "блок памяти остаётся последним сообщением seed")
            assertTrue(
                messages.count { it.role == "user" } < 12,
                "trim должен был срезать старую историю: ${messages.count { it.role == "user" }}"
            )
            client.close()
        }
    }

    @Test
    fun `блок присутствует во всех режимах, прямой и пошаговый и стрим`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            createSession(client, base)

            // Прямой ответ
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "ход 1", sessionId = null, thinkingEnabled = false))
            }.body<ChatResponse>()
            assertTrue(fake.calls.last().hasPrefsBlock(), "прямой режим: блок должен быть в seed")

            // Пошаговое решение
            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "ход 2", thinkingEnabled = true))
            }.body<ChatResponse>()
            assertTrue(fake.calls.last().hasPrefsBlock(), "пошаговый режим: блок должен быть в seed")

            // Стриминговый путь — тот же handleAgent
            client.post("$base/api/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "ход 3"))
            }.bodyAsText()
            assertTrue(fake.calls.last().hasPrefsBlock(), "стрим: блок должен быть в seed")
            client.close()
        }
    }

    @Test
    fun `memoryTools false — блок предпочтений всё равно инжектится`() {
        withApp { base, fake, _ ->
            val client = httpClient()
            val sid = createSession(client, base)

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "без памяти", sessionId = sid, memoryTools = false))
            }.body<ChatResponse>()

            val main = fake.calls.last()
            val block = assertNotNull(main.prefsBlock(), "выключение памяти не отменяет предпочтений")
            assertTrue(block.contains("Форма обращения: ты"))
            assertFalse(
                main.systemTexts().any { it.contains("Долговременная память:") },
                "блока памяти быть не должно (memoryTools=false)"
            )
            client.close()
        }
    }

    // --- Саб-вызовы без предпочтений ---

    @Test
    fun `саб-вызов экстрактора фактов не получает блок предпочтений`() {
        withApp(factExtractorFactory = { OpenAIFactExtractor(it) }) { base, fake, _ ->
            val client = httpClient()
            createSession(client, base)

            client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(message = "второй ход", sessionId = null, contextStrategy = "facts"))
            }.body<ChatResponse>()

            // Первый вызов — основной цикл, последний — экстрактор фактов
            assertTrue(fake.calls.size >= 2, "должны быть основной вызов и вызов экстрактора: ${fake.calls.size}")
            assertTrue(fake.calls.first().hasPrefsBlock(), "основной вызов несёт блок")
            val extractor = fake.calls.last()
            assertTrue(
                extractor.systemTexts().any { it.contains("Извлечение фактов") },
                "последний вызов — экстрактор фактов: ${extractor.systemTexts()}"
            )
            assertFalse(extractor.hasPrefsBlock(), "саб-вызов не должен получать блок предпочтений")
            client.close()
        }
    }
}
