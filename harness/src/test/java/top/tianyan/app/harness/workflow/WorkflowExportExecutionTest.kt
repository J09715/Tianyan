package top.tianyan.app.harness.workflow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowExportExecutionTest {
    @Test fun exportedAndImportedWorkflowStillPlansConfirmsAndExecutesDynamicNodes() = runTest {
        val source = WorkflowDefinition("source", "source", defaultVariables = mapOf("MESSAGE" to "kept"), maxParallelNodes = 2,
            nodes = listOf(WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "plan", config = mapOf("prompt" to "plan \${MESSAGE}",
                "resultSchema" to """{"type":"object","additionalProperties":true}""")),
                WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan"))),
            edges = listOf(WorkflowEdge("review", "plan", "success", "review")))
        val imported = WorkflowDefinitionImport.fresh(WorkflowDefinitionImport.parse(WorkflowDefinitionExport.encode(source)))
        val patch = WorkflowExpansion(listOf(WorkflowNode("review.done", WorkflowNodeType.BASH_COMMAND, "done", config = mapOf("command" to "echo \${MESSAGE}"))),
            listOf(WorkflowEdge("done", "review", "success", "review.done")))
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
                assertTrue(request.prompt.contains("plan kept"))
                assertEquals("plan", request.nodeId)
                return WorkflowAgentResult(true, WorkflowExpansions.encode(patch))
            }
            override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult = error("not used")
        }
        var calls = 0
        val commands = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
                onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
                calls++
                assertEquals(imported.id, context.workflowId)
                assertEquals("echo kept", interpolate(node.config.getValue("command"), context))
                return NodeExecutionOutput(NodeRunStatus.SUCCESS)
            }
        }
        val broker = WorkflowApprovalBroker()
        val run = WorkflowScheduler(setOf(AgentNodeExecutor(port), ApprovalNodeExecutor(broker), commands), broker)
            .execute(imported, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertEquals(0, calls)
        assertNotEquals(source.id, imported.id)
        assertTrue(run.decide("review", true, reviewToken = run.approvalRequest.value!!.reviewToken))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(2, run.state.value.definition.maxParallelNodes)
        WorkflowExpansions.validateHistory(run.state.value)
    }
}
