package com.example.aiweb.auth

import com.example.aiweb.config.AppConfig
import com.example.aiweb.config.ProviderConfig
import com.example.aiweb.module
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Миграция «Шумахер» (задача 10 плана).
 *
 * Три кейса из плана:
 *  1. Бут с legacy-артефактами (сессии без userId + memory/longterm.json) ->
 *     Шумахер создан, PIN напечатан в stdout с пометкой миграции, данные приписаны.
 *  2. Второй бут на том же dataDir -> no-op: дубликатов нет, PIN не печатается.
 *  3. Чистый бут (пустой dataDir) -> Шумахер не создаётся из воздуха.
 *
 * Бут поднимает настоящий Application.module: миграция обязана работать на
 * реальном пути запуска, а не только при прямом вызове.
 */
class MigrationTest {

    // --- Обвязка: бут настоящего приложения с захватом stdout ---

    private class BootedApp(
        val base: String,
        val stdout: String,
        val pin: String?,
        private val stopAction: () -> Unit
    ) {
        val client = HttpClient(CIO)

        fun stop() {
            stopAction()
            client.close()
        }
    }

    /** Поднимает приложение (Application.module, реальный путь запуска) и ловит stdout на время старта. */
    private fun bootApp(dataDir: File): BootedApp {
        val port = ServerSocket(0).use { it.localPort }
        val config = AppConfig(
            port = port,
            default = ProviderConfig(
                id = "default",
                name = "Основной",
                type = ProviderConfig.TYPE_OPENAI,
                apiUrl = "http://127.0.0.1:1/v1/chat/completions",
                apiKey = "k",
                model = "m"
            ),
            dataDir = dataDir.absolutePath
        )
        val buffer = ByteArrayOutputStream()
        val original = System.out
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { module(config) }
        System.setOut(PrintStream(buffer, true, StandardCharsets.UTF_8))
        try {
            server.start(wait = false)
        } finally {
            System.setOut(original)
        }
        val stdout = buffer.toString(StandardCharsets.UTF_8)
        val pin = Regex("PIN: (\\d{4})").find(stdout)?.groupValues?.get(1)
        return BootedApp(
            base = "http://127.0.0.1:$port",
            stdout = stdout,
            pin = pin,
            stopAction = { server.stop(100, 500) }
        )
    }

    private fun tempDataDir(): File = Files.createTempDirectory("shturman-migration-test").toFile()

    /** dataDir с копией checked-in legacy-фикстуры: 2 сессии без userId + общий memory/longterm.json. */
    private fun legacyDataDir(): File {
        val dir = tempDataDir()
        File(dir, "sessions").mkdirs()
        File(dir, "memory").mkdirs()
        File(dir, "sessions/legacy-alpha.json").writeText(resourceText("legacy/sessions/legacy-alpha.json"))
        File(dir, "sessions/legacy-beta.json").writeText(resourceText("legacy/sessions/legacy-beta.json"))
        File(dir, "memory/longterm.json").writeText(resourceText("legacy/memory/longterm.json"))
        return dir
    }

    private fun resourceText(path: String): String =
        javaClass.classLoader.getResourceAsStream(path)!!.readBytes().toString(StandardCharsets.UTF_8)

    private fun login(base: String, userId: String, pin: String): String {
        val client = HttpClient(CIO)
        return runBlocking {
            client.use {
                val response = it.post("$base/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"$userId","pin":"$pin"}""")
                }
                assertEquals(200, response.status.value, "вход Шумахера по PIN из лога миграции")
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
            }
        }
    }

    private fun httpGet(base: String, client: HttpClient, path: String, token: String? = null): String =
        runBlocking {
            client.get("$base$path") {
                token?.let { header("X-Auth-Token", it) }
            }.bodyAsText()
        }

    private fun usersList(base: String, client: HttpClient): kotlinx.serialization.json.JsonArray =
        Json.parseToJsonElement(httpGet(base, client, "/api/auth/users")).jsonArray

    private fun memoryEntryCount(raw: String): Int =
        Json.parseToJsonElement(raw).jsonObject["entries"]!!.jsonArray.size

    // --- Кейс 1: бут с legacy -> Шумахер создан, данные приписаны ---

    @Test
    fun `бут с legacy создаёт Шумахера и приписывает ему сессии и память`() {
        val dataDir = legacyDataDir()
        val app = bootApp(dataDir)
        try {
            // PIN напечатан в stdout один раз, 4 цифры, с пометкой миграции
            assertNotNull(app.pin, "PIN миграции должен быть напечатан в stdout при первом буте")
            assertTrue(app.stdout.contains("МИГРАЦИЯ"), "строка с PIN несёт пометку миграции")
            assertTrue(app.stdout.contains("Шумахер"))

            // Ровно один пользователь с именем «Шумахер»
            val users = usersList(app.base, app.client)
            assertEquals(1, users.size, "создаётся ровно один пользователь")
            assertEquals("Шумахер", users[0].jsonObject["name"]!!.jsonPrimitive.content)
            val shturmanId = users[0].jsonObject["id"]!!.jsonPrimitive.content

            // Вход по PIN из лога миграции
            val token = login(app.base, shturmanId, app.pin!!)

            // Обе legacy-сессии видны Шумахеру
            val sessionsJson = Json.parseToJsonElement(httpGet(app.base, app.client, "/api/sessions", token)).jsonObject
            assertEquals(2, sessionsJson["sessions"]!!.jsonArray.size, "обе legacy-сессии приписаны Шумахеру")

            // Память legacy видна Шумахеру; kind=profile остаётся памятью (НЕ превращается в предпочтения)
            val memoryRaw = httpGet(app.base, app.client, "/api/memory", token)
            assertEquals(2, memoryEntryCount(memoryRaw), "обе legacy-записи памяти у Шумахера")
            assertTrue(memoryRaw.contains("legacy-mem-1"))
            assertTrue(memoryRaw.contains("legacy-mem-2"))

            // Файлы на диске: legacy-файл памяти перемещён в per-user раскладку
            assertFalse(File(dataDir, "memory/longterm.json").exists(), "legacy-файл памяти должен быть перемещён")
            val moved = File(dataDir, "memory/$shturmanId/longterm.json")
            assertTrue(moved.isFile, "память переехала в memory/{userId}/longterm.json")
            assertTrue(moved.readText().contains("legacy-mem-1"), "содержимое файла сохранено при переносе")

            // Файлы сессий переписаны с владельцем
            val sessionFile = File(dataDir, "sessions/legacy-alpha.json")
            assertTrue(
                sessionFile.readText().contains("\"userId\":\"$shturmanId\""),
                "файл сессии переписан с userId Шумахера"
            )
        } finally {
            app.stop()
        }
    }

    // --- Кейс 2: двойной бут -> идемпотентно, без дубликатов ---

    @Test
    fun `второй бут на том же dataDir идемпотентен`() {
        val dataDir = legacyDataDir()
        val first = bootApp(dataDir)
        val firstPin = first.pin
        assertNotNull(firstPin, "первый бут: PIN напечатан")
        first.stop()

        val second = bootApp(dataDir)
        try {
            assertNull(second.pin, "второй бут не печатает PIN — миграция не запускается повторно")
            assertFalse(second.stdout.contains("МИГРАЦИЯ"), "нет пометки миграции при повторном буте")

            val users = usersList(second.base, second.client)
            assertEquals(1, users.size, "дубликат Шумахера не создаётся")
            val shturmanId = users[0].jsonObject["id"]!!.jsonPrimitive.content

            // Старый PIN по-прежнему работает: тот же пользователь, а не новый
            val token = login(second.base, shturmanId, firstPin)
            val sessionsJson = Json.parseToJsonElement(httpGet(second.base, second.client, "/api/sessions", token)).jsonObject
            assertEquals(2, sessionsJson["sessions"]!!.jsonArray.size, "сессии не задублированы и не потеряны")
            assertEquals(
                2,
                memoryEntryCount(httpGet(second.base, second.client, "/api/memory", token)),
                "записи памяти не дублируются"
            )
        } finally {
            second.stop()
        }
    }

    // --- Кейс 3: чистый бут без legacy -> Шумахер не создаётся ---

    @Test
    fun `чистый бут без legacy не создаёт Шумахера`() {
        val dataDir = tempDataDir()
        val app = bootApp(dataDir)
        try {
            assertNull(app.pin, "без legacy-артефактов PIN не печатается")
            assertFalse(app.stdout.contains("МИГРАЦИЯ"), "миграция молчит на пустом dataDir")
            assertEquals(0, usersList(app.base, app.client).size, "Шумахер не создаётся из воздуха")
        } finally {
            app.stop()
        }
    }

    // --- Прямые проверки функции миграции (детали контракта) ---

    @Test
    fun `миграция повторным вызовом no-op и защищает от ре-миграции`() {
        val dataDir = legacyDataDir()
        val reports = mutableListOf<String>()
        val first = ShturmanMigration.run(dataDir.absolutePath, randomPin = { "0407" }, report = { reports.add(it) })
        assertNotNull(first)
        assertEquals("Шумахер", first.name)
        assertEquals(1, reports.size, "строка с PIN печатается один раз")
        assertTrue(reports[0].contains("0407") && reports[0].contains("МИГРАЦИЯ"))

        // Повторный вызов: Шумахер уже есть -> no-op (даже если legacy-артефакты снова появились)
        val second = ShturmanMigration.run(dataDir.absolutePath, randomPin = { "1111" }, report = { reports.add(it) })
        assertNull(second, "после создания Шумахера миграция не запускается")
        assertEquals(1, reports.size, "второй вызов ничего не печатает")
        assertEquals(1, UserStore(dataDir.absolutePath).list().size)
    }

    @Test
    fun `миграция без legacy-артефактов no-op`() {
        val dataDir = tempDataDir()
        val result = ShturmanMigration.run(dataDir.absolutePath, randomPin = { "0407" }, report = {})
        assertNull(result)
        assertTrue(UserStore(dataDir.absolutePath).list().isEmpty())
    }

    @Test
    fun `миграция без dataDir no-op`() {
        assertNull(ShturmanMigration.run(null, randomPin = { "0407" }, report = {}))
        assertNull(ShturmanMigration.run("", randomPin = { "0407" }, report = {}))
    }
}
