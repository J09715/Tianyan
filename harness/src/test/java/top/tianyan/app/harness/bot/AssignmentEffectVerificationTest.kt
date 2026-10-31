package top.tianyan.app.harness.bot

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.effects.ToolRecoveryNotice

class AssignmentEffectVerificationTest {
    @Test fun `known completed tools allow a blocked outcome but interrupted writes preserve uncertainty`() {
        val write = ToolCall("write", 1, HarnessTool.WRITE, JsonObject(emptyMap()))
        assertFalse(assignmentEffectsUncertain(emptyList(), null))
        assertTrue(assignmentEffectsUncertain(listOf(write), null))
        val result = ToolResult("result", 2, "write", true, "saved")
        assertFalse(assignmentEffectsUncertain(listOf(write, result), null))
        assertTrue(assignmentEffectsUncertain(listOf(write,
            result.copy(success = false, errorCode = ToolRecoveryNotice.OUTCOME_UNKNOWN)), null))
        assertTrue(assignmentEffectsUncertain(listOf(result.copy(success = false, errorCode = ToolRecoveryNotice.OUTCOME_UNKNOWN)), null))
    }

    @Test fun `prior history is excluded and missing anchor cannot claim a safe result`() {
        val oldWrite = ToolCall("old", 1, HarnessTool.WRITE, JsonObject(emptyMap()))
        val anchor = AssistantText("anchor", 2, "previous turn")
        assertFalse(assignmentEffectsUncertain(listOf(oldWrite, anchor), "anchor"))
        assertTrue(assignmentEffectsUncertain(listOf(oldWrite), "missing-anchor"))
    }
}
