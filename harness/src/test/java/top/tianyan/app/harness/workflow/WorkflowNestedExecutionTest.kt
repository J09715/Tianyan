package top.tianyan.app.harness.workflow

import android.content.ContextWrapper
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.workflow.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkflowNestedExecutionTest {
    private val schema = """{"type":"object","additionalProperties":true}"""
    private val definition = WorkflowDefinition("nested", "nested", nodes = listOf(
        WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "plan", config = mapOf("prompt" to "plan next steps", "resultSchema" to schema)),
        WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")),
    ), edges = listOf(WorkflowEdge("entry", "plan", "success", "review")))
    private val outer = WorkflowExpansion(nodes = listOf(
        WorkflowNode("review.probe", WorkflowNodeType.BASH_COMMAND, "probe", config = mapOf("command" to "probe")),
        WorkflowNode("review.plan", WorkflowNodeType.AGENT_INFERENCE, "plan from probe", config = mapOf(
            "prompt" to "plan based on \${previous.output}", "resultSchema" to schema)),
        WorkflowNode("review.next", WorkflowNodeType.HUMAN_APPROVAL, "next review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "review.plan")),
    ), edges = listOf(WorkflowEdge("probe", "review", "success", "review.probe"),
        WorkflowEdge("next-plan", "review.probe", "success", "review.plan"),
        WorkflowEdge("next-review", "review.plan", "success", "review.next")))
    private val inner = WorkflowExpansion(listOf(WorkflowNode("review.next.finish", WorkflowNodeType.BASH_COMMAND, "finish", config = mapOf("command" to "finish"))),
        listOf(WorkflowEdge("finish", "review.next", "success", "review.next.finish")))

    private fun TestScope.manager(repository: FakeWorkflowRepository, requests: MutableList<WorkflowAgentRequest>,
        proposal: WorkflowExpansion = inner, action: suspend (WorkflowNode) -> NodeExecutionOutput): WorkflowRunManager {
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
                requests += request
                return WorkflowAgentResult(true, WorkflowExpansions.encode(if (request.nodeId == "plan") outer else proposal))
            }
            override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult = error("no delegation")
        }
        val commands = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
                onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node)
        }
        val broker = WorkflowApprovalBroker()
        return WorkflowRunManager(WorkflowScheduler(setOf(AgentNodeExecutor(port), commands, ApprovalNodeExecutor(broker)), broker),
            repository, FakeLinuxRuntime(RuntimeState.Ready), Json { ignoreUnknownKeys = true },
            AppLogger(ContextWrapper(null)) { it }, backgroundScope)
    }

    private fun WorkflowRunManager.review(id: String) = pendingApprovals.value.getValue(id)
    private fun WorkflowRunManager.approve(request: WorkflowApprovalRequest) =
        decide(request.executionId, request.nodeId, true, reviewToken = request.reviewToken)

    @Test fun eachStageNeedsItsOwnConfirmationAndUsesRealTypedAgentInterpolation() = runTest {
        val repository = FakeWorkflowRepository()
        val requests = mutableListOf<WorkflowAgentRequest>()
        val commands = mutableListOf<String>()
        val manager = manager(repository, requests) { node ->
            commands += node.id
            val approved = repository.saved.last().state.journal.filter { it.expansion != null }.map { it.nodeId }
            assertTrue("review" in approved)
            if (node.id.endsWith("finish")) assertTrue("review.next" in approved)
            NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "probe-result")
        }
        val id = manager.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        val first = manager.review(id)
        assertTrue(first.description.contains("后续提案仍需分别确认"))
        assertEquals(listOf("plan"), requests.map { it.nodeId })
        assertTrue(commands.isEmpty())
        assertTrue(manager.approve(first))
        runCurrent()
        val second = manager.review(id)
        assertEquals("review.next", second.nodeId)
        assertTrue(second.description.contains("第 2 / 4 层"))
        assertTrue(requests.last().prompt.contains("plan based on probe-result"))
        assertEquals(listOf("review.probe"), commands)
        assertFalse(manager.decide(id, second.nodeId, true, reviewToken = first.reviewToken))
        assertTrue(manager.approve(second))
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(listOf("review.probe", "review.next.finish"), commands)
        assertEquals(listOf(outer, inner), result.journal.mapNotNull { it.expansion })
        assertEquals(definition, result.baseDefinition)
        WorkflowExpansions.validateHistory(result)
    }

    @Test fun rejectingLaterStagePreservesEarlierEffectsAndAddsNothingFromRejectedProposal() = runTest {
        val repository = FakeWorkflowRepository()
        val commands = mutableListOf<String>()
        val manager = manager(repository, mutableListOf()) { node -> commands += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "probe") }
        val id = manager.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        manager.approve(manager.review(id))
        runCurrent()
        val second = manager.review(id)
        assertTrue(manager.decide(id, second.nodeId, false))
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.FAILED, result.status)
        assertEquals(listOf("review.probe"), commands)
        assertEquals(1, result.journal.count { it.expansion != null })
        assertFalse(result.definition.nodes.any { it.id == "review.next.finish" })
        assertEquals(setOf("review.next"), WorkflowRecovery.inspect(result).retryNodeIds)
    }

    @Test fun restartingDuringLaterApprovalReusesEarlierWorkAndRequestsFreshConfirmation() = runTest {
        val repository = FakeWorkflowRepository()
        val requests = mutableListOf<WorkflowAgentRequest>()
        val commands = mutableListOf<String>()
        val action: suspend (WorkflowNode) -> NodeExecutionOutput = { node ->
            commands += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "probe")
        }
        val first = manager(repository, requests, action = action)
        val id = first.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        first.approve(first.review(id))
        runCurrent()
        val oldRequest = first.review(id)
        first.cancel(id)
        runCurrent()
        val snapshot = repository.saved.last().state
        val plan = WorkflowRecovery.inspect(snapshot)
        assertTrue(plan.blockedReason, plan.canResume)
        assertTrue(plan.retryNodeIds.isEmpty())
        val second = manager(repository, requests, action = action)
        assertTrue(second.resume(id, emptySet(), snapshot.resumeCount, snapshot.journal.size).accepted)
        runCurrent()
        val fresh = second.review(id)
        assertEquals(oldRequest.nodeId, fresh.nodeId)
        assertNotEquals(oldRequest.reviewToken, fresh.reviewToken)
        assertFalse(second.approve(oldRequest))
        assertEquals(2, requests.size)
        assertEquals(listOf("review.probe"), commands)
        second.approve(fresh)
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(1, result.resumeCount)
        assertEquals(2, result.journal.count { it.expansion != null })
        assertEquals(2, requests.size)
        assertEquals(listOf("review.probe", "review.next.finish"), commands)
    }

    @Test fun resumingAfterBothApprovalsRetriesOnlyInterruptedLeafAndReplaysBothGraphs() = runTest {
        val repository = FakeWorkflowRepository()
        val requests = mutableListOf<WorkflowAgentRequest>()
        val commands = mutableListOf<String>()
        var finish = false
        val action: suspend (WorkflowNode) -> NodeExecutionOutput = { node ->
            commands += node.id
            if (node.id.endsWith("finish") && !finish) awaitCancellation()
            NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "probe")
        }
        val first = manager(repository, requests, action = action)
        val id = first.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        first.approve(first.review(id))
        runCurrent()
        first.approve(first.review(id))
        runCurrent()
        first.cancel(id)
        runCurrent()
        val snapshot = repository.saved.last().state
        assertEquals(setOf("review.next.finish"), WorkflowRecovery.inspect(snapshot).retryNodeIds)
        finish = true
        val second = manager(repository, requests, action = action)
        assertTrue(second.resume(id, setOf("review.next.finish"), 0, snapshot.journal.size).accepted)
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(2, result.journal.count { it.expansion != null })
        assertEquals(2, requests.size)
        assertEquals(listOf("review.probe", "review.next.finish", "review.next.finish"), commands)
        assertTrue(second.pendingApprovals.value.isEmpty())
        assertEquals(snapshot.definition, result.definition)
    }

    @Test fun invalidLaterProposalStopsBeforeItsApprovalOrEffects() = runTest {
        val repository = FakeWorkflowRepository()
        val invalid = inner.copy(nodes = listOf(inner.nodes.single().copy(id = "outside")))
        val commands = mutableListOf<String>()
        val manager = manager(repository, mutableListOf(), invalid) { node -> commands += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "probe") }
        val id = manager.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        manager.approve(manager.review(id))
        runCurrent()
        assertEquals(WorkflowRunStatus.FAILED, repository.saved.last().state.status)
        assertTrue(manager.pendingApprovals.value.isEmpty())
        assertEquals(listOf("review.probe"), commands)
    }
}
