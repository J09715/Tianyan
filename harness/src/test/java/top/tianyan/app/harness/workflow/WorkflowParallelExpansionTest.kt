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
class WorkflowParallelExpansionTest {
    private val definition = WorkflowDefinition("parallel", "parallel", nodes = listOf("a", "b").flatMap { id -> listOf(
        WorkflowNode("plan_$id", WorkflowNodeType.AGENT_INFERENCE, "plan $id", config = mapOf("prompt" to "plan", "resultSchema" to """{"type":"object","additionalProperties":true}""")),
        WorkflowNode(id, WorkflowNodeType.HUMAN_APPROVAL, "review $id", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan_$id")),
    ) }, edges = listOf("a", "b").map { WorkflowEdge("edge_$it", "plan_$it", "success", it) })
    private fun patch(id: String) = WorkflowExpansion(listOf(WorkflowNode("$id.done", WorkflowNodeType.BASH_COMMAND, "do $id", config = mapOf("command" to "echo $id"))),
        listOf(WorkflowEdge("added_$id", id, "success", "$id.done")))

    private fun TestScope.manager(repository: FakeWorkflowRepository, requests: MutableList<String>,
        action: suspend (WorkflowNode) -> NodeExecutionOutput): WorkflowRunManager {
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest): WorkflowAgentResult {
                requests += request.nodeId
                return WorkflowAgentResult(true, WorkflowExpansions.encode(patch(request.nodeId.removePrefix("plan_"))))
            }
            override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult = error("not used")
        }
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
                onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node)
        }
        val broker = WorkflowApprovalBroker()
        return WorkflowRunManager(WorkflowScheduler(setOf(AgentNodeExecutor(port), executor, ApprovalNodeExecutor(broker)), broker),
            repository, FakeLinuxRuntime(RuntimeState.Ready), Json { ignoreUnknownKeys = true }, AppLogger(ContextWrapper(null)) { it }, backgroundScope)
    }
    private fun WorkflowRunManager.approve(request: WorkflowApprovalRequest) =
        decide(request.executionId, request.nodeId, true, reviewToken = request.reviewToken)

    @Test fun firstConfirmedBranchExecutesWhileSecondApprovalIsStillWaiting() = runTest {
        val repository = FakeWorkflowRepository()
        val commands = mutableListOf<String>()
        val manager = manager(repository, mutableListOf()) { node ->
            commands += node.id
            assertTrue(repository.saved.last().state.journal.any { it.nodeId == node.id.substringBefore('.') && it.expansion != null })
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }
        val id = manager.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        assertEquals("a", manager.pendingApprovals.value.getValue(id).nodeId)
        assertTrue(manager.approve(manager.pendingApprovals.value.getValue(id)))
        runCurrent()
        assertEquals("b", manager.pendingApprovals.value.getValue(id).nodeId)
        assertEquals(listOf("a.done"), commands)
        assertEquals(WorkflowRunStatus.WAITING_APPROVAL, manager.activeRuns.value.getValue(id).status)
        manager.approve(manager.pendingApprovals.value.getValue(id))
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(listOf("a.done", "b.done"), commands)
        assertEquals(listOf("a", "b"), result.journal.filter { it.expansion != null }.map { it.nodeId })
        WorkflowExpansions.validateHistory(result)
    }

    @Test fun restoringPendingParallelApprovalKeepsOtherBranchCompleted() = runTest {
        val repository = FakeWorkflowRepository()
        val requests = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val action: suspend (WorkflowNode) -> NodeExecutionOutput = { node -> commands += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
        val first = manager(repository, requests, action)
        val id = first.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        first.approve(first.pendingApprovals.value.getValue(id))
        runCurrent()
        val old = first.pendingApprovals.value.getValue(id)
        first.cancel(id)
        runCurrent()
        val snapshot = repository.saved.last().state
        val plan = WorkflowRecovery.inspect(snapshot)
        assertTrue(plan.blockedReason, plan.canResume)
        assertTrue(plan.retryNodeIds.isEmpty())
        assertTrue("a.done" in plan.cachedOutputs)
        val second = manager(repository, requests, action)
        assertTrue(second.resume(id, emptySet(), 0, snapshot.journal.size).accepted)
        runCurrent()
        assertFalse(second.approve(old))
        second.approve(second.pendingApprovals.value.getValue(id))
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, repository.saved.last().state.status)
        assertEquals(listOf("a.done", "b.done"), commands)
        assertEquals(2, requests.size)
        assertEquals(2, repository.saved.last().state.journal.count { it.expansion != null })
    }

    @Test fun rejectingParallelBranchCancelsActiveActionAndPreservesUncertainRetryEvidence() = runTest {
        val repository = FakeWorkflowRepository()
        val commands = mutableListOf<String>()
        var cancelled = false
        val manager = manager(repository, mutableListOf()) { node ->
            commands += node.id
            try { awaitCancellation() } finally { cancelled = true }
        }
        val id = manager.start(definition, workspacePath = "/workspace").executionId
        runCurrent()
        manager.approve(manager.pendingApprovals.value.getValue(id))
        runCurrent()
        assertEquals(listOf("a.done"), commands)
        manager.decide(id, "b", false)
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.FAILED, result.status)
        assertTrue(cancelled)
        assertFalse(result.definition.nodes.any { it.id == "b.done" })
        val plan = WorkflowRecovery.inspect(result)
        assertTrue(plan.blockedReason, plan.canResume)
        assertEquals(setOf("a.done", "b"), plan.retryNodeIds)
    }
}
