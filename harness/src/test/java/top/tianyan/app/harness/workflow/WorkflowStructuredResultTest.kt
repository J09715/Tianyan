package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowStructuredResultTest {
    private val schema = """{"type":"object","required":["approved"],"properties":{"approved":{"type":"boolean"}}}"""

    @Test fun invalidResponseFailsAndCannotBecomeABranchValue() = runBlocking {
        var receivedRequest: WorkflowAgentRequest? = null
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
                receivedRequest = request
                return WorkflowAgentResult(true, """{"approved":"yes"}""")
            }
            override suspend fun delegate(request: WorkflowAgentRequest) = infer(request)
        }
        val node = WorkflowNode("audit", WorkflowNodeType.AGENT_INFERENCE, "审核", config = mapOf("prompt" to "审核", "resultSchema" to schema))
        val context = WorkflowRuntimeContext("run", "wf", "/workspace")
        val output = AgentNodeExecutor(port).execute(node, context) { _, _ -> }
        assertEquals(NodeRunStatus.FAILED, output.status)
        assertNull(output.structuredResult)
        assertTrue(output.error!!.contains("校验失败"))
        assertTrue(receivedRequest!!.prompt.contains(schema))
        receivedRequest = null
        val invalid = AgentNodeExecutor(port).execute(node.copy(config = node.config + ("resultSchema" to "{}")), context) { _, _ -> }
        assertEquals(NodeRunStatus.FAILED, invalid.status)
        assertNull(receivedRequest)
    }

    @Test fun validatedResultRoutesConditionAndInterpolatesNextTask() = runBlocking {
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest) = WorkflowAgentResult(true, """{"approved":true}""")
            override suspend fun delegate(request: WorkflowAgentRequest) = infer(request)
        }
        val probe = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.TERMINAL_OUTPUT)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
                assertEquals("批准=true", interpolate("批准=\${audit.result.approved}", context))
                assertEquals("批准=true", interpolateAgentPrompt("批准=\${audit.result.approved}", context))
                return NodeExecutionOutput(NodeRunStatus.SUCCESS)
            }
        }
        val workflow = WorkflowDefinition("wf", "审核", nodes = listOf(
            WorkflowNode("audit", WorkflowNodeType.AGENT_INFERENCE, "审核", config = mapOf("prompt" to "审核", "resultSchema" to schema)),
            WorkflowNode("condition", WorkflowNodeType.CONDITION_BRANCH, "判断", config = mapOf("expression" to "\${audit.result.approved} == true")),
            WorkflowNode("yes", WorkflowNodeType.TERMINAL_OUTPUT, "通过"),
            WorkflowNode("no", WorkflowNodeType.TERMINAL_OUTPUT, "不通过"),
        ), edges = listOf(
            WorkflowEdge("a", "audit", toNodeId = "condition"),
            WorkflowEdge("y", "condition", toNodeId = "yes", conditionExpression = "exitCode == 0"),
            WorkflowEdge("n", "condition", toNodeId = "no", conditionExpression = "exitCode != 0"),
        ))
        val run = WorkflowScheduler(setOf(AgentNodeExecutor(port), ConditionNodeExecutor(), probe), WorkflowApprovalBroker())
            .execute(workflow, emptyMap(), "/workspace", this)
        val result = run.state.first { it.status == WorkflowRunStatus.SUCCESS || it.status == WorkflowRunStatus.FAILED }
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(NodeRunStatus.SKIPPED, result.nodeStates.getValue("no").status)
    }

    @Test fun cancellationWhileParallelNodeWaitsRetainsCompletedResultsAndArtifacts() = runBlocking {
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, artifacts = listOf("/workspace/report.md"), durationMs = 1L,
            structuredResult = Json.parseToJsonElement("""{"approved":true}""").jsonObject)
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.TRIGGER)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
                if (node.id == "slow") awaitCancellation()
                return output
            }
        }
        val workflow = WorkflowDefinition("wf", "并行", nodes = listOf(
            WorkflowNode("fast", WorkflowNodeType.TRIGGER, "快"), WorkflowNode("slow", WorkflowNodeType.TRIGGER, "慢"),
        ))
        val run = WorkflowScheduler(setOf(executor), WorkflowApprovalBroker()).execute(workflow, emptyMap(), "/workspace", this)
        withTimeout(2_000) { run.state.first { it.context?.nodeOutputs?.get("fast") != null } }
        run.cancel()
        val cancelled = withTimeout(2_000) { run.state.first { it.status == WorkflowRunStatus.CANCELLED } }
        assertEquals(output, cancelled.context!!.nodeOutputs.getValue("fast"))
        assertEquals(output, cancelled.nodeStates.getValue("fast").output)
    }
}
