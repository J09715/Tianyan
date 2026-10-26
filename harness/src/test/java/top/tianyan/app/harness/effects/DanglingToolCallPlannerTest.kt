package top.tianyan.app.harness.effects

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult

private fun call(id: String, tool: HarnessTool, raw: String? = null) =
    ToolCall(id = id, createdAt = 1L, tool = tool, args = buildJsonObject { }, rawToolName = raw)

class DanglingToolCallPlannerTest {

    @Test
    fun `interrupted run never replays and stubs everything`() {
        val actions = DanglingToolCallPlanner.plan(
            listOf(
                call("c1", HarnessTool.READ),
                call("c2", HarnessTool.WRITE, raw = "write"),
                call("c3", HarnessTool.MCP, raw = "mcp__fs__cat"),
            ),
            interrupted = true,
        )
        assertTrue(actions.all { it is DanglingToolCallPlanner.Stubbed })
        val notes = actions.filterIsInstance<DanglingToolCallPlanner.Stubbed>().map { it.note }
        assertTrue(notes.all { it.contains("用户停止") })
    }

    @Test
    fun `process death residue replays safe read-only tools only`() {
        val actions = DanglingToolCallPlanner.plan(
            listOf(
                call("r1", HarnessTool.READ),
                call("r2", HarnessTool.HISTORY_SEARCH, raw = "history_search"),
                call("w1", HarnessTool.WRITE),
                call("e1", HarnessTool.EDIT),
                call("b1", HarnessTool.BASE),
                call("m1", HarnessTool.MCP, raw = "mcp__fs__cat"),
                call("m2", HarnessTool.SUBAGENT, raw = "subagent"),
            ),
            interrupted = false,
        )
        val replayed = actions.filterIsInstance<DanglingToolCallPlanner.Replay>().map { it.call.id }.toSet()
        assertEquals(setOf("r1", "r2"), replayed)
        actions.filterIsInstance<DanglingToolCallPlanner.Stubbed>().forEach {
            assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, it.errorCode)
            assertTrue(it.note.contains("先通过只读查询核验"))
            assertTrue(it.note.contains("禁止直接重新发起"))
        }
    }

    @Test
    fun `answered calls are ignored`() {
        val answered = ToolResultAnswered.stubOf(call("done", HarnessTool.READ))
        val actions = DanglingToolCallPlanner.plan(listOf(call("done", HarnessTool.READ), answered), interrupted = false)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `user stop distinguishes read-only interruption from unknown side effects`() {
        val actions = DanglingToolCallPlanner.plan(
            listOf(call("read", HarnessTool.READ), call("write", HarnessTool.WRITE)), interrupted = true,
        ).filterIsInstance<DanglingToolCallPlanner.Stubbed>()
        assertEquals(ToolRecoveryNotice.INTERRUPTED, actions[0].errorCode)
        val result = actions[1].result("result", 3L)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, result.errorCode)
        assertTrue(result.output.contains("可能已产生全部或部分副作用"))
        assertEquals(false, result.success)
    }

    private object ToolResultAnswered {
        fun stubOf(call: ToolCall): ToolResult =
            ToolResult(
                id = "res-${call.id}",
                createdAt = 2L,
                toolCallId = call.id,
                success = true,
                output = "",
            )
    }

    @Test
    fun `old tool results decode without recovery code`() {
        val result = Json.decodeFromString(ToolResult.serializer(),
            """{"id":"old","createdAt":1,"toolCallId":"call","success":true,"output":"ok"}""")
        assertEquals(null, result.errorCode)
        val unknown = ToolRecoveryNotice.unknown().result("new", 2L, "call")
        assertEquals(unknown, Json.decodeFromString(ToolResult.serializer(), Json.encodeToString(ToolResult.serializer(), unknown)))
    }
}
