package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowParallelismTest {
    private fun definition(limit: Int = 4, count: Int = 6) = WorkflowDefinition("wf", "parallel", maxParallelNodes = limit,
        nodes = (0 until count).map { WorkflowNode("n$it", WorkflowNodeType.BASH_COMMAND, "n$it") })
    private fun executor(action: suspend (WorkflowNode) -> NodeExecutionOutput) = object : NodeExecutor {
        override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
        override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
            onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node)
    }

    @Test fun defaultBoundsWorkAndOneCompletionImmediatelyReleasesOneSlot() = runTest {
        val gates = (0..5).associate { "n$it" to CompletableDeferred<Unit>() }
        val visits = mutableListOf<String>()
        var active = 0
        var peak = 0
        val run = WorkflowScheduler(setOf(executor { node ->
            visits += node.id
            active++
            peak = maxOf(peak, active)
            try { gates.getValue(node.id).await(); NodeExecutionOutput(NodeRunStatus.SUCCESS) } finally { active-- }
        }), WorkflowApprovalBroker()).execute(definition(), emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertEquals(listOf("n0", "n1", "n2", "n3"), visits)
        assertEquals(NodeRunStatus.PENDING, run.state.value.nodeStates.getValue("n4").status)
        gates.getValue("n0").complete(Unit)
        runCurrent()
        assertEquals(listOf("n0", "n1", "n2", "n3", "n4"), visits)
        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(4, peak)
    }

    @Test fun serialLimitAlsoCoversAutomaticRetryAttempts() = runTest {
        val gate = CompletableDeferred<Unit>()
        val visits = mutableListOf<String>()
        var attempts = 0
        val graph = definition(1, 2).let { it.copy(nodes = it.nodes.map { node -> if (node.id == "n0") node.copy(failurePolicy = FailurePolicy.RETRY_ONCE) else node }) }
        val run = WorkflowScheduler(setOf(executor { node ->
            visits += node.id
            if (node.id == "n0") {
                attempts++
                if (attempts == 1) return@executor NodeExecutionOutput(NodeRunStatus.FAILED)
                gate.await()
            }
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), WorkflowApprovalBroker()).execute(graph, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertEquals(listOf("n0", "n0"), visits)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("n0", "n0", "n1"), visits)
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
    }

    @Test fun manualApprovalDoesNotOccupyExecutionSlot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val visits = mutableListOf<String>()
        val graph = definition(1, 2).let { it.copy(nodes = it.nodes + WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review")) }
        val broker = WorkflowApprovalBroker()
        val run = WorkflowScheduler(setOf(executor { node -> visits += node.id; if (node.id == "n0") gate.await(); NodeExecutionOutput(NodeRunStatus.SUCCESS) },
            ApprovalNodeExecutor(broker)), broker).execute(graph, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertEquals(listOf("n0"), visits)
        assertEquals("review", run.approvalRequest.value?.nodeId)
        assertTrue(run.decide("review", true))
        runCurrent()
        assertEquals(listOf("n0"), visits)
        gate.complete(Unit)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(listOf("n0", "n1"), visits)
    }

    @Test fun slotIsHeldUntilResultCheckpointCompletes() = runTest {
        val commit = CompletableDeferred<Unit>()
        val visits = mutableListOf<String>()
        val run = WorkflowScheduler(setOf(executor { node -> visits += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS) }), WorkflowApprovalBroker())
            .execute(definition(1, 2), emptyMap(), "/workspace", backgroundScope, checkpoint = {
                if (it.journal.lastOrNull()?.let { entry -> entry.nodeId == "n0" && entry.kind == WorkflowJournalKind.SETTLED } == true) commit.await()
            })
        runCurrent()
        assertEquals(listOf("n0"), visits)
        commit.complete(Unit)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(listOf("n0", "n1"), visits)
    }

    @Test fun queuedNodeTimeoutBeginsWhenDispatched() = runTest {
        val gate = CompletableDeferred<Unit>()
        val graph = definition(1, 2).let { it.copy(nodes = it.nodes.map { node -> if (node.id == "n1") node.copy(timeoutSeconds = 1) else node }) }
        val run = WorkflowScheduler(setOf(executor { node ->
            if (node.id == "n0") gate.await() else delay(500)
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), WorkflowApprovalBroker()).execute(graph, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(NodeRunStatus.PENDING, run.state.value.nodeStates.getValue("n1").status)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
    }

    @Test fun invalidLimitFailsBeforeAnyEffectsAndCancellationDoesNotDispatchQueuedNodes() = runTest {
        val visits = mutableListOf<String>()
        val scheduler = WorkflowScheduler(setOf(executor { node -> visits += node.id; awaitCancellation() }), WorkflowApprovalBroker())
        val invalid = scheduler.execute(definition(0), emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertEquals(WorkflowRunStatus.FAILED, invalid.state.value.status)
        assertTrue(visits.isEmpty())
        val valid = scheduler.execute(definition(1), emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        valid.cancel()
        runCurrent()
        assertEquals(listOf("n0"), visits)
        assertEquals(WorkflowRunStatus.CANCELLED, valid.state.value.status)
        assertEquals(setOf("n0"), WorkflowRecovery.inspect(valid.state.value).retryNodeIds)
    }

    @Test fun restoredRunKeepsStoredLimitAndDoesNotDispatchWholePendingGraph() = runTest {
        val visits = mutableListOf<String>()
        val scheduler = WorkflowScheduler(setOf(executor { node -> visits += node.id; awaitCancellation() }), WorkflowApprovalBroker())
        val first = scheduler.execute(definition(1), emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        first.cancel()
        runCurrent()
        val snapshot = Json.decodeFromString<WorkflowRuntimeState>(Json.encodeToString(WorkflowRuntimeState.serializer(), first.state.value))
        val started = snapshot.journal.last()
        val restored = snapshot.copy(status = WorkflowRunStatus.IDLE, resumeCount = 1,
            journal = snapshot.journal + WorkflowJournalEntry(snapshot.journal.size + 1, started.nodeId, started.attempt, WorkflowJournalKind.INTERRUPTED, 1L))
        val second = scheduler.execute(restored.definition, emptyMap(), "/workspace", backgroundScope, checkpoint = {}, restored = restored)
        runCurrent()
        assertEquals(1, second.state.value.definition.maxParallelNodes)
        assertEquals(listOf("n0", "n0"), visits)
        second.cancel()
        runCurrent()
        assertEquals(WorkflowRunStatus.CANCELLED, second.state.value.status)
    }

    @Test fun dynamicFanOutUsesSameLimit() = runTest {
        val patch = WorkflowExpansion((0..5).map { WorkflowNode("review.n$it", WorkflowNodeType.BASH_COMMAND, "work") },
            (0..5).map { WorkflowEdge("add$it", "review", "success", "review.n$it") })
        val graph = WorkflowDefinition("dynamic", "dynamic", maxParallelNodes = 2, nodes = listOf(
            WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "plan", config = mapOf("prompt" to "plan", "resultSchema" to """{"type":"object","additionalProperties":true}""")),
            WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")),
        ), edges = listOf(WorkflowEdge("review", "plan", "success", "review")))
        val port = object : WorkflowAgentExecutionPort {
            override suspend fun infer(request: WorkflowAgentRequest) = WorkflowAgentResult(true, WorkflowExpansions.encode(patch))
            override suspend fun delegate(request: WorkflowAgentRequest): WorkflowAgentResult = error("not used")
        }
        val gate = CompletableDeferred<Unit>()
        val visits = mutableListOf<String>()
        val broker = WorkflowApprovalBroker()
        val run = WorkflowScheduler(setOf(AgentNodeExecutor(port), ApprovalNodeExecutor(broker), executor { node ->
            visits += node.id; gate.await(); NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), broker).execute(graph, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertTrue(run.decide("review", true, reviewToken = run.approvalRequest.value!!.reviewToken))
        runCurrent()
        assertEquals(2, visits.size)
        assertEquals(2, run.state.value.definition.maxParallelNodes)
        gate.complete(Unit)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(6, visits.size)
    }
}
