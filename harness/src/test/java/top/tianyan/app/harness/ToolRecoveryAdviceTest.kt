package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.harness.session.ApiMessageProjector

class ToolRecoveryAdviceTest {
    @Test fun unknownMutationRequiresVerificationAndNeverSameArgumentReplay() {
        val result = ToolRecoveryNotice.unknown().result("r", 1, "c")
        assertFalse(result.success)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, result.errorCode)
        assertFalse(result.recovery!!.retrySameArguments)
        assertEquals("verify_state", result.recovery.nextActions.single().kind)
        assertEquals(ToolRecoveryNotice.unknown().output, result.output)
    }

    @Test fun suggestionsSurviveSerializationAndBothModelProjectionModes() {
        val call = ToolCall("c", 1, HarnessTool.EDIT, buildJsonObject { put("path", "a.txt") })
        val result = ToolResult("r", 2, "c", false, "match failed").withRecoveryAdvice(HarnessTool.EDIT)
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString(HarnessMessage.serializer(), json.encodeToString(HarnessMessage.serializer(), result)) as ToolResult
        assertEquals(result, restored)
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val projected = ApiMessageProjector.project(listOf(call, restored), mode, false).last().content!!
            assertTrue(projected.contains("\"retrySameArguments\":false"))
            assertTrue(projected.contains("inspect_current_file"))
            assertTrue(projected.contains("\"tool\":\"read\""))
        }
    }

    @Test fun approvalAdviceKeepsControlFlagsAndDoesNotPermitReplay() {
        val waiting = ToolResult("r", 1, "c", false, "wait", awaitingApproval = true, approvalRequestId = "approval")
            .withRecoveryAdvice(HarnessTool.BASE)
        assertTrue(waiting.awaitingApproval)
        assertEquals("approval", waiting.approvalRequestId)
        assertEquals("wait_approval", waiting.recovery!!.nextActions.single().kind)
        val deferred = waiting.copy(awaitingApproval = false, approvalDeferred = true, recovery = null).withRecoveryAdvice()
        assertTrue(deferred.approvalDeferred)
        assertEquals("return_to_parent", deferred.recovery!!.nextActions.single().kind)
        assertFalse(deferred.recovery.retrySameArguments)
    }

    @Test fun successfulAndLegacyResultsKeepOriginalModelContent() {
        val success = ToolResult("r", 1, "c", true, "done")
        assertSame(success, success.withRecoveryAdvice(HarnessTool.BASE))
        assertEquals("done", success.modelOutput())
        val old = Json.decodeFromString(HarnessMessage.serializer(),
            """{"type":"tool_result","id":"r","createdAt":1,"toolCallId":"c","success":false,"output":"old"}""") as ToolResult
        assertNull(old.recovery)
        assertEquals("old", HarnessApiMapper.toApiMessage(old).content)
    }

    @Test fun policyBlockDoesNotSuggestArgumentCorrectionOrClaimRecoverability() {
        for (code in listOf("PLAN_BLOCKED", "TOOL_CHECKPOINT_BLOCKED")) {
            val result = ToolResult("r", 1, "c", false, "blocked", errorCode = code).withRecoveryAdvice(HarnessTool.EDIT)
            assertFalse(result.recovery!!.recoverable)
            assertFalse(result.recovery.retrySameArguments)
            assertTrue(result.recovery.nextActions.single().kind.startsWith("respect_"))
        }
    }
}
