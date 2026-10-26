package top.tianyan.app.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.core.ToolCheckpoint
import top.tianyan.app.harness.core.ToolCheckpoints
import top.tianyan.app.harness.core.ToolGateDecision

class ToolExecutionBoundaryTest {
    private val request = ToolExecutionRequest(ToolCall("call", 1, HarnessTool.READ, JsonObject(emptyMap())), "session", "", null)

    private fun hook(block: Boolean = false) = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
        override val id = "note"
        override suspend fun before(request: ToolExecutionRequest) =
            if (block) ToolGateDecision.Block("secret-text") else ToolGateDecision.Allow
        override suspend fun after(request: ToolExecutionRequest, result: ToolResult) = "secret-text"
    }

    @Test fun `formatting failure preserves effect identity and approval state without leaking notes`() = runTest {
        val original = ToolResult("result", 1, "call", false, "waiting", awaitingApproval = true, approvalRequestId = "approval")
        val boundary = ToolExecutionBoundary(ToolCheckpoints(listOf(hook()))) { _, _ -> error("formatter failed") }
        val result = boundary.execute(request) { original }
        assertEquals(original.id, result.id)
        assertEquals(original.toolCallId, result.toolCallId)
        assertEquals(original.success, result.success)
        assertEquals(original.awaitingApproval, result.awaitingApproval)
        assertEquals(original.approvalRequestId, result.approvalRequestId)
        assertFalse(result.output.contains("secret-text"))
    }

    @Test fun `formatting a veto fails closed without calling policy or exposing raw reason`() = runTest {
        var executed = false
        val boundary = ToolExecutionBoundary(ToolCheckpoints(listOf(hook(true)))) { _, _ -> error("formatter failed") }
        val result = boundary.execute(request) { executed = true; ToolResult("result", 1, "call", true, "done") }
        assertFalse(executed)
        assertFalse(result.success)
        assertEquals("call", result.toolCallId)
        assertFalse(result.output.contains("secret-text"))
    }

    @Test fun `cancelled non cancellable formatter cannot return a successful tool result`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val boundary = ToolExecutionBoundary(ToolCheckpoints(listOf(hook()))) { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); release.await(); "safe output" }
        }
        var returned = false
        val job = launch { boundary.execute(request) { ToolResult("result", 1, "call", true, "done") }; returned = true }
        runCurrent()
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(returned)
    }

    @Test fun `observation collections cannot mutate original result or influence the next checkpoint`() = runTest {
        val original = ToolResult("result", 1, "call", true, "done", metadata = mapOf("first" to "one", "second" to "two"))
        val mutator = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "mutator"
            override suspend fun after(request: ToolExecutionRequest, result: ToolResult): String? {
                @Suppress("UNCHECKED_CAST")
                (result.metadata as MutableMap<String, String>)["first"] = "tampered"
                return null
            }
        }
        val observer = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "observer"
            override suspend fun after(request: ToolExecutionRequest, result: ToolResult): String {
                assertEquals("one", result.metadata["first"])
                return "checked"
            }
        }
        val result = ToolExecutionBoundary(ToolCheckpoints(listOf(mutator, observer))) { _, text -> text }
            .execute(request) { original }
        assertTrue(result.success)
        assertEquals(original.metadata, result.metadata)
        assertTrue(result.output.contains("checked"))
    }

    @Test fun `nested request entries cannot change the arguments used by execution policy`() = runTest {
        val nested = mutableMapOf<String, JsonElement>("path" to JsonPrimitive("original"))
        val input = request.copy(call = request.call.copy(args = JsonObject(mapOf("items" to JsonArray(listOf(JsonObject(nested)))))))
        val mutator = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "mutator"
            override suspend fun before(request: ToolExecutionRequest): ToolGateDecision {
                @Suppress("UNCHECKED_CAST")
                val entry = request.call.args.getValue("items").jsonArray[0].jsonObject.entries.first()
                    as MutableMap.MutableEntry<String, JsonElement>
                entry.setValue(JsonPrimitive("tampered"))
                return ToolGateDecision.Allow
            }
        }
        var executed = false
        val result = ToolExecutionBoundary(ToolCheckpoints(listOf(mutator))) { _, text -> text }
            .execute(input) { executed = true; ToolResult("result", 1, "call", true, "done") }
        assertFalse(executed)
        assertFalse(result.success)
        assertEquals(JsonPrimitive("original"), nested["path"])
    }
}
