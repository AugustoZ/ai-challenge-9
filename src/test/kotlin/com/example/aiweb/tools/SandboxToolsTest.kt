package com.example.aiweb.tools

import com.example.aiweb.model.SandboxToolConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки sandbox-инструментов на фальшивом транспорте (шов post подменяется,
 * реального HTTP нет): маппинг ответов сервиса в ToolResult, отказы без падения
 * цикла и валидация аргументов ДО любого сетевого вызова.
 */
class SandboxToolsTest {

    /** Фальшивый транспорт: запоминает вызовы, возвращает заготовку или бросает. */
    private class FakeSandboxClient(
        private val reply: SandboxReply? = null,
        private val thrown: Exception? = null
    ) : SandboxClient(SandboxToolConfig()) {
        var calls = 0
        var lastPath: String? = null
        var lastBody: String? = null

        override suspend fun post(path: String, body: String): SandboxReply {
            calls++
            lastPath = path
            lastBody = body
            thrown?.let { throw it }
            return reply ?: SandboxReply(ok = false, error = "заглушка")
        }

        fun bodyJson() = Json.parseToJsonElement(lastBody.orEmpty()).jsonObject
    }

    // --- run_code: маппинг ответов сервиса ---

    @Test
    fun `успешный код с кодом 0 возвращает stdout как успех`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = "4\n"))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "print(2 + 2)"}""")

        assertEquals("/run", fake.lastPath)
        assertEquals(true, result.ok)
        assertEquals("4\n", result.content)
    }

    @Test
    fun `stderr при успехе дописывается к stdout`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = "out", stderr = "warn"))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "x"}""")

        assertEquals(true, result.ok)
        assertEquals("out\nwarn", result.content)
    }

    @Test
    fun `ненулевой код завершения даёт текст с кодом и потоками`() = runBlocking {
        val fake = FakeSandboxClient(
            SandboxReply(ok = true, exitCode = 1, stdout = "частично", stderr = "Traceback")
        )
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "1/0"}""")

        assertEquals(true, result.ok)
        assertTrue(result.content.contains("Процесс завершился с кодом 1"))
        assertTrue(result.content.contains("частично"))
        assertTrue(result.content.contains("Traceback"))
    }

    @Test
    fun `отказ сервиса ok=false уходит модели как текст ошибки`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = false, error = "Таймаут исполнения"))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "while True: pass"}""")

        assertEquals(false, result.ok)
        assertEquals("Таймаут исполнения", result.content)
    }

    @Test
    fun `сбой связи не роняет вызов - текст про недоступность`() = runBlocking {
        val fake = FakeSandboxClient(thrown = RuntimeException("Connection refused"))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "print(1)"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Sandbox недоступен"), "Текст: ${result.content}")
        assertTrue(result.content.contains("Connection refused"))
    }

    @Test
    fun `отказ без описания заменяется понятным текстом`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = false))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "print(1)"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Sandbox недоступен"))
    }

    // --- run_code: валидация аргументов до HTTP ---

    @Test
    fun `пустой код отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "   "}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `код длиннее капа отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "${"x".repeat(65_537)}"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `неподдерживаемый язык отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "echo hi", "language": "bash"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `язык по умолчанию python попадает в тело запроса`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = ""))
        RunCodeTool(fake, timeoutSeconds = 30).execute("""{"code": "print(1)"}""")

        assertEquals("python", fake.bodyJson()["language"]?.jsonPrimitive?.content)
    }

    // --- run_code: таймаут ---

    @Test
    fun `таймаут из конфига передаётся в тело запроса`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = ""))
        RunCodeTool(fake, timeoutSeconds = 15).execute("""{"code": "print(1)"}""")

        assertEquals(15, fake.bodyJson()["timeoutSeconds"]?.jsonPrimitive?.int)
    }

    @Test
    fun `таймаут зажимается снизу до 1 секунды`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = ""))
        RunCodeTool(fake, timeoutSeconds = 0).execute("""{"code": "print(1)"}""")

        assertEquals(1, fake.bodyJson()["timeoutSeconds"]?.jsonPrimitive?.int)
    }

    @Test
    fun `таймаут зажимается сверху до 60 секунд`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, exitCode = 0, stdout = ""))
        RunCodeTool(fake, timeoutSeconds = 100).execute("""{"code": "print(1)"}""")

        assertEquals(60, fake.bodyJson()["timeoutSeconds"]?.jsonPrimitive?.int)
    }

    // --- read_file ---

    @Test
    fun `чтение файла возвращает содержимое`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, content = "отчёт готов"))
        val result = ReadFileTool(fake).execute("""{"path": "report.txt"}""")

        assertEquals("/read", fake.lastPath)
        assertEquals(true, result.ok)
        assertEquals("отчёт готов", result.content)
    }

    @Test
    fun `обрезанное содержимое помечается`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, content = "начало…", truncated = true))
        val result = ReadFileTool(fake).execute("""{"path": "big.txt"}""")

        assertEquals(true, result.ok)
        assertTrue(result.content.endsWith("…(обрезано)"))
    }

    @Test
    fun `ошибка чтения уходит текстом без исключения`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = false, error = "Файл не найден"))
        val result = ReadFileTool(fake).execute("""{"path": "nope.txt"}""")

        assertEquals(false, result.ok)
        assertEquals("Файл не найден", result.content)
    }

    @Test
    fun `относительный путь без точек проходит валидацию`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, content = ""))
        ReadFileTool(fake).execute("""{"path": "out/data.csv"}""")

        assertEquals(1, fake.calls)
        assertEquals("out/data.csv", fake.bodyJson()["path"]?.jsonPrimitive?.content)
    }

    // --- read_file: валидация аргументов до HTTP ---

    @Test
    fun `путь с точка-точка отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = ReadFileTool(fake).execute("""{"path": "../secrets.txt"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `абсолютный путь отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = ReadFileTool(fake).execute("""{"path": "/etc/passwd"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `пустой путь отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = ReadFileTool(fake).execute("""{"path": "  "}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `путь длиннее капа отклоняется без вызова песочницы`() = runBlocking {
        val fake = FakeSandboxClient()
        val result = ReadFileTool(fake).execute("""{"path": "${"a".repeat(1025)}"}""")

        assertEquals(false, result.ok)
        assertTrue(result.content.contains("Некорректные аргументы"))
        assertEquals(0, fake.calls)
    }

    @Test
    fun `не json-объект аргументы дают ошибку без исключения`() = runBlocking {
        val fake = FakeSandboxClient(SandboxReply(ok = true, content = ""))
        val result = RunCodeTool(fake, timeoutSeconds = 30).execute("[]")

        assertFalse(result.ok)
        assertEquals(0, fake.calls)
    }
}
