package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowExpansionTest {
    private val patch = WorkflowExpansion(listOf(WorkflowNode("review.next", WorkflowNodeType.BASH_COMMAND, "后续动作",
        config = mapOf("command" to "echo verified"))), listOf(WorkflowEdge("new", "review", "success", "review.next")))
    private val definition = WorkflowDefinition("wf", "dynamic", nodes = listOf(
        WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "规划", config = mapOf("resultSchema" to """{"type":"object","additionalProperties":true}""")),
        WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "审阅", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")),
    ), edges = listOf(WorkflowEdge("entry", "plan", "success", "review")))

    private fun scheduler(broker: WorkflowApprovalBroker, proposal: WorkflowExpansion = patch,
        action: suspend (WorkflowNode) -> NodeExecutionOutput): WorkflowScheduler {
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.BASH_COMMAND)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
                onProgress: suspend (NodeRunStatus, String) -> Unit) = if (node.type == WorkflowNodeType.AGENT_INFERENCE)
                NodeExecutionOutput(NodeRunStatus.SUCCESS, structuredResult = Json.parseToJsonElement(WorkflowExpansions.encode(proposal)).jsonObject)
            else action(node)
        }
        return WorkflowScheduler(setOf(executor, ApprovalNodeExecutor(broker)), broker)
    }

    private suspend fun WorkflowRunHandle.terminal() = withTimeout(3_000) {
        state.first { it.status in setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED) }
    }

    @Test fun proposalRequiresExactReviewAndCommittedGraphBeforeDispatch() = runBlocking {
        val broker = WorkflowApprovalBroker()
        var persisted: WorkflowRuntimeState? = null
        var calls = 0
        val run = scheduler(broker) {
            calls++
            assertEquals(patch, persisted!!.journal.single { it.expansion != null }.expansion)
            assertEquals(definition, persisted!!.baseDefinition)
            assertTrue(persisted!!.definition.nodes.any { it.id == "review.next" })
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }.execute(definition, emptyMap(), "/workspace", this, checkpoint = { persisted = it })
        val request = withTimeout(3_000) { run.approvalRequest.first { it != null }!! }
        assertEquals(0, calls)
        assertTrue(request.description.contains("echo verified"))
        assertFalse(run.decide("review", true))
        assertFalse(run.decide("review", true, reviewToken = "old request"))
        assertTrue(run.decide("review", true, reviewToken = request.reviewToken))
        assertEquals(WorkflowRunStatus.SUCCESS, run.terminal().status)
        assertEquals(1, calls)
    }

    @Test fun rejectionKeepsOriginalGraphAndRunsNoNewActions() = runBlocking {
        var calls = 0
        val run = scheduler(WorkflowApprovalBroker()) { calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = {})
        withTimeout(3_000) { run.approvalRequest.first { it != null } }
        assertTrue(run.decide("review", false))
        val result = run.terminal()
        assertEquals(WorkflowRunStatus.FAILED, result.status)
        assertEquals(definition, result.definition)
        assertNull(result.baseDefinition)
        assertEquals(0, calls)
    }

    @Test fun invalidProposalFailsBeforeApprovalOrActions() = runBlocking {
        var calls = 0
        val invalid = patch.copy(edges = listOf(WorkflowEdge("bad", "review.next", toNodeId = "plan")))
        val run = scheduler(WorkflowApprovalBroker(), invalid) { calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = {})
        assertEquals(WorkflowRunStatus.FAILED, run.terminal().status)
        assertNull(run.approvalRequest.value)
        assertEquals(0, calls)
    }

    @Test fun failedExpansionCommitPreventsNewActions() = runBlocking {
        var calls = 0
        val run = scheduler(WorkflowApprovalBroker()) { calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = {
                if (it.journal.any { entry -> entry.expansion != null }) error("disk full")
            })
        val request = withTimeout(3_000) { run.approvalRequest.first { it != null }!! }
        run.decide("review", true, reviewToken = request.reviewToken)
        assertEquals(WorkflowRunStatus.FAILED, run.terminal().status)
        assertEquals(0, calls)
    }

    @Test fun restoredExpandedGraphReusesApprovalWithoutPlanningOrExpandingAgain() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val blocked = CompletableDeferred<Unit>()
        val run = scheduler(broker) { blocked.complete(Unit); awaitCancellation() }
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = {})
        val request = withTimeout(3_000) { run.approvalRequest.first { it != null }!! }
        run.decide("review", true, reviewToken = request.reviewToken)
        withTimeout(3_000) { blocked.await() }
        run.cancel()
        val snapshot = run.terminal()
        val plan = WorkflowRecovery.inspect(snapshot)
        assertTrue(plan.blockedReason, plan.canResume)
        assertEquals(setOf("plan", "review"), plan.cachedOutputs.keys)
        assertEquals(setOf("review.next"), plan.retryNodeIds)
        val interrupted = snapshot.journal.last()
        val restored = snapshot.copy(status = WorkflowRunStatus.IDLE, resumeCount = 1,
            context = snapshot.context!!.copy(nodeOutputs = plan.cachedOutputs),
            journal = snapshot.journal + WorkflowJournalEntry(snapshot.journal.size + 1, interrupted.nodeId,
                interrupted.attempt, WorkflowJournalKind.INTERRUPTED, 1L))
        var calls = 0
        val second = scheduler(WorkflowApprovalBroker()) { calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
            .execute(restored.definition, emptyMap(), "/workspace", this, checkpoint = {}, restored = restored)
        val result = second.terminal()
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(1, calls)
        assertEquals(1, result.journal.count { it.expansion != null })
        assertEquals(snapshot.definition, result.definition)
        assertNull(second.approvalRequest.value)
    }

    @Test fun expansionWithoutDurabilityFailsBeforeRequestingApproval() = runBlocking {
        val run = scheduler(WorkflowApprovalBroker()) { error("must not execute") }
            .execute(definition, emptyMap(), "/workspace", this)
        assertEquals(WorkflowRunStatus.FAILED, run.terminal().status)
        assertNull(run.approvalRequest.value)
    }
}
