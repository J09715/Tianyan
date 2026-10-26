package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessServiceToolBackendTest {
    @Test
    fun `group main subagent calls are rejected before orchestrator execution`() = runBlocking {
        var seenSession: String? = null
        var seenTool: String? = null
        val backend = HarnessServiceToolBackend(
            blockSubagentInGroupMain = { sessionId, rawToolName ->
                seenSession = sessionId
                seenTool = rawToolName
                "请改用 assign-task 派给群成员"
            },
        )
        val result = backend.execute(
            HarnessServiceRequest(
                tool = HarnessTool.SUBAGENT,
                args = JsonObject(emptyMap()),
                rawToolName = "invoke_subagent",
                sessionId = "group-main",
                workspace = "/workspace",
            ),
        )
        assertFalse(result.first)
        assertTrue(result.second.contains("assign-task"))
        assertEquals("group-main", seenSession)
        assertEquals("invoke_subagent", seenTool)
    }

    @Test
    fun `dual agent calls use the same guard`() = runBlocking {
        val backend = HarnessServiceToolBackend(
            blockSubagentInGroupMain = { _, _ -> "blocked" },
        )
        val result = backend.execute(
            HarnessServiceRequest(
                tool = HarnessTool.SUBAGENT,
                args = JsonObject(emptyMap()),
                rawToolName = "invoke_dual_agent",
                sessionId = "group-main",
                workspace = "/workspace",
            ),
        )
        assertFalse(result.first)
        assertEquals("blocked", result.second)
    }
}
