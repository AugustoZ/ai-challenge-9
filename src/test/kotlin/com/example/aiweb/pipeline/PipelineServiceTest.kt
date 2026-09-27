package com.example.aiweb.pipeline

import com.example.aiweb.tools.Tool
import com.example.aiweb.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Реестр и статусы пайплайна (задача 10): загрузка из директории, in-memory
 * статус и гвард «один активный запуск» (повторный run активного → 409).
 */
class PipelineServiceTest {

    private class Stub(
        override val name: String,
        private val handler: suspend (String) -> ToolResult
    ) : Tool {
        override val description = "stub"
        override val parametersJsonSchema: JsonElement = JsonObject(emptyMap())
        override suspend fun execute(argsJson: String): ToolResult = handler(argsJson)
    }

    private fun writeScenario(dir: Path, fileName: String, content: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(fileName), content)
    }

    private fun tempDir(): Path = Files.createTempDirectory("pipeline-service-test")

    @Test
    fun `loads scenarios from directory and reports count`() {
        val dir = tempDir()
        writeScenario(
            dir, "one.json",
            """{"name":"one","steps":[{"id":"s1","tool":"t1","args":{}}]}"""
        )
        writeScenario(dir, "two.json", """{"name":"two","steps":[]}""")

        val service = PipelineService(dir.toString(), { null }, CoroutineScope(Job() + Dispatchers.Default))

        assertEquals(2, service.count())
        assertEquals("one", service.find("one")?.name)
        assertTrue(service.find("one")!!.steps.isNotEmpty())
        assertEquals(emptyList(), service.find("two")?.steps)
        assertNull(service.find("missing"))
    }

    @Test
    fun `empty directory yields zero scenarios not error`() {
        val service = PipelineService(tempDir().toString(), { null }, CoroutineScope(Job() + Dispatchers.Default))
        assertEquals(0, service.count())
    }

    @Test
    fun `corrupt scenario file is skipped`() {
        val dir = tempDir()
        writeScenario(dir, "bad.json", "{not valid json")
        writeScenario(dir, "good.json", """{"name":"good","steps":[]}""")

        val service = PipelineService(dir.toString(), { null }, CoroutineScope(Job() + Dispatchers.Default))
        assertEquals(1, service.count())
        assertEquals("good", service.find("good")?.name)
    }

    @Test
    fun `run returns accepted then succeeds with step done`() {
        val dir = tempDir()
        writeScenario(dir, "doc.json", """{"name":"doc","steps":[{"id":"s1","tool":"echo","args":{}}]}""")
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val service = PipelineService(
            dir.toString(),
            { name -> if (name == "echo") Stub("echo") { ToolResult(true, "OK") } else null },
            scope
        )

        val outcome = service.run("doc")
        assertTrue(outcome is PipelineRunOutcome.Accepted)
        val runId = (outcome as PipelineRunOutcome.Accepted).runId

        waitUntil { service.status("doc")?.state == "succeeded" }
        val status = service.status("doc")!!
        assertEquals("succeeded", status.state)
        assertEquals(runId, status.runId)
        assertEquals("done", status.steps.single().status)
        assertTrue(status.finishedAt != null)
    }

    @Test
    fun `second run of active scenario is conflict then accepted after completion`() {
        val dir = tempDir()
        writeScenario(dir, "blk.json", """{"name":"blk","steps":[{"id":"s1","tool":"block","args":{}}]}""")
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val service = PipelineService(
            dir.toString(),
            { name -> if (name == "block") Stub("block") { gate.await(); ToolResult(true, "done") } else null },
            scope
        )

        assertTrue(service.run("blk") is PipelineRunOutcome.Accepted)
        waitUntil { service.status("blk")?.state == "running" }

        assertEquals(PipelineRunOutcome.Conflict, service.run("blk"), "активный сценарий должен давать 409")

        gate.complete(Unit)
        waitUntil { service.status("blk")?.state == "succeeded" }

        assertTrue(service.run("blk") is PipelineRunOutcome.Accepted, "после завершения запуск снова 202")
        scope.cancel()
    }

    @Test
    fun `unknown scenario is not found`() {
        val service = PipelineService(tempDir().toString(), { null }, CoroutineScope(Job() + Dispatchers.Default))
        assertEquals(PipelineRunOutcome.NotFound, service.run("nope"))
        assertNull(service.status("nope"))
    }

    @Test
    fun `failed run reports failed state and step error`() {
        val dir = tempDir()
        writeScenario(dir, "fail.json", """{"name":"fail","steps":[{"id":"s1","tool":"boom","args":{}}]}""")
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val service = PipelineService(
            dir.toString(),
            { name -> if (name == "boom") Stub("boom") { ToolResult(false, "упал") } else null },
            scope
        )

        assertTrue(service.run("fail") is PipelineRunOutcome.Accepted)
        waitUntil { service.status("fail")?.state == "failed" }
        val status = service.status("fail")!!
        assertEquals("failed", status.state)
        assertEquals("failed", status.steps.single().status)
        assertEquals("упал", status.steps.single().error)
        scope.cancel()
    }

    private fun waitUntil(timeoutMs: Long = 5000, intervalMs: Long = 20, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "waitUntil: таймаут ${timeoutMs} мс" }
            Thread.sleep(intervalMs)
        }
    }
}
