package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualCompactionBoundaryTest {
    private fun user(id: String) = UserMessage(id, 0, id)
    private fun text(id: String) = AssistantText(id, 0, id)
    private fun call(id: String) = ToolCall(id, 0, HarnessTool.READ, JsonObject(emptyMap()))
    private fun result(id: String) = ToolResult("result-$id", 0, id, true, "output")

    @Test fun keepsWholeTurnWhenCountWouldSplitToolPair() {
        val messages = listOf(user("old"), text("old-answer"), user("current"),
            call("a"), result("a"), call("b"), result("b"), text("answer"))
        assertEquals(2, manualCompactionBoundary(messages, 4))
    }

    @Test fun singleLongActiveTurnCannotBeFolded() {
        val messages = listOf(user("only"), call("a"), result("a"), call("b"), result("b"), text("answer"))
        assertEquals(0, manualCompactionBoundary(messages, 4))
    }

    @Test fun keepsCrossTurnToolResultsAndTheirOriginalCalls() {
        val messages = listOf(user("old"), text("old-answer"), user("work"), call("a"),
            user("steer"), result("a"), text("answer"), text("more"))
        assertEquals(2, manualCompactionBoundary(messages, 4))
    }

    @Test fun leavesShortAndUserlessHistoriesUntouched() {
        assertEquals(0, manualCompactionBoundary(emptyList(), 4))
        assertEquals(0, manualCompactionBoundary(listOf(user("u"), text("a")), 4))
        assertEquals(0, manualCompactionBoundary(List(6) { text("a$it") }, 4))
    }

    @Test fun keepsPendingCallsBeforeANewerUserMessage() {
        val messages = listOf(user("old"), text("old-answer"), user("work"), call("pending"),
            user("steer"), text("a"), text("b"), text("c"))
        assertEquals(2, manualCompactionBoundary(messages, 4))
    }

    @Test fun closesEarlierToolPairsExposedByMovingTheBoundaryBack() {
        val messages = listOf(user("old"), text("old-answer"), user("work"), call("a"),
            user("steer-a"), result("a"), call("b"), user("steer-b"), result("b"), text("a1"), text("a2"))
        assertEquals(2, manualCompactionBoundary(messages, 4))
    }

    @Test fun preservesCountWhenItAlreadyFallsOnUserBoundary() {
        val messages = listOf(user("old"), text("old-answer"), user("next"), text("next-answer"),
            user("last"), text("last-answer"))
        assertEquals(2, manualCompactionBoundary(messages, 4))
        assertEquals(4, manualCompactionBoundary(messages, 0))
        assertEquals(0, manualCompactionBoundary(messages, Int.MAX_VALUE))
    }
}
