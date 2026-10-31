package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowApprovalTimeoutTest {
    private fun definition(raw: String?) = WorkflowDefinition("wf", "approval", nodes = listOf(
        WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "approve",
            config = raw?.let { mapOf(WorkflowApprovalTimeout.CONFIG_KEY to it) } ?: emptyMap()),
    ))

    @Test fun missingIsUnlimitedAndBoundariesAreExact() {
        assertNull(WorkflowApprovalTimeout.milliseconds(null))
        assertTrue(WorkflowValidator.validate(definition(null)).isEmpty())
        listOf("1" to 1000L, "3600" to 3600000L).forEach { (raw, millis) ->
            assertEquals(millis, WorkflowApprovalTimeout.milliseconds(raw))
            assertTrue(WorkflowValidator.validate(definition(raw)).isEmpty())
        }
    }

    @Test fun explicitInvalidValuesNeverBecomeUnlimited() {
        listOf("", " ", "0", "-1", "3601", "1.5", "abc", "\${WAIT}", Long.MAX_VALUE.toString(), "999999999999999999999").forEach { raw ->
            assertNotNull(raw, WorkflowApprovalTimeout.validationError(raw))
            assertTrue(raw, WorkflowValidator.validate(definition(raw)).any { it.field == "nodes.approve.config.timeoutSeconds" })
            try { WorkflowApprovalTimeout.milliseconds(raw); fail(raw) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun importAndExportRejectInvalidWaitingLimits() {
        val invalid = definition("0")
        try { WorkflowDefinitionImport.parse(Json.encodeToString(WorkflowDefinition.serializer(), invalid)); fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("审批等待")) }
        try { WorkflowDefinitionExport.encode(invalid); fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("审批等待")) }
        val valid = definition("3600")
        assertEquals(valid.nodes, WorkflowDefinitionImport.parse(WorkflowDefinitionExport.encode(valid)).nodes)
    }
}
