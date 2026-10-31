package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowFrontierTest {
    private fun commands(action: suspend (WorkflowNode, WorkflowRuntimeContext) -> NodeExecutionOutput) = object : NodeExecutor {
        override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
        override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
            onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node, context)
    }
    private fun node(id: String) = WorkflowNode(id, WorkflowNodeType.BASH_COMMAND, id)
    private val definition = WorkflowDefinition("frontier", "frontier", nodes = listOf(node("slow"), node("fast"), node("next"), node("join")),
        edges = listOf(WorkflowEdge("next", "fast", toNodeId = "next"),
            WorkflowEdge("slow-join", "slow", toNodeId = "join"), WorkflowEdge("next-join", "next", toNodeId = "join")))

    @Test fun fastBranchAdvancesWhileUnrelatedNodeWaitsAndJoinWaitsForAllDependencies() = runTest {
        val slow = CompletableDeferred<Unit>()
        val visits = mutableListOf<String>()
        val run = WorkflowScheduler(setOf(commands { node, context ->
            visits += node.id
            if (node.id == "slow") slow.await()
            if (node.id == "next") assertEquals(listOf("fast"), context.upstreamNodeIds)
            if (node.id == "join") assertEquals(setOf("slow", "next"), context.upstreamNodeIds!!.toSet())
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), WorkflowApprovalBroker()).execute(definition, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertTrue("next" in visits)
        assertFalse("join" in visits)
        assertEquals(NodeRunStatus.RUNNING, run.state.value.nodeStates.getValue("slow").status)
        slow.complete(Unit)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(1, visits.count { it == "next" })
    }

    @Test fun completionInUiSnapshotDoesNotReleaseDependenciesUntilItsCheckpointReturns() = runTest {
        val commit = CompletableDeferred<Unit>()
        var visits = 0
        val graph = definition.copy(nodes = listOf(node("fast"), node("next")), edges = listOf(definition.edges.first()))
        val run = WorkflowScheduler(setOf(commands { _, _ -> visits++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }), WorkflowApprovalBroker())
            .execute(graph, emptyMap(), "/workspace", backgroundScope, checkpoint = {
                if (it.journal.lastOrNull()?.let { entry -> entry.nodeId == "fast" && entry.kind == WorkflowJournalKind.SETTLED } == true) commit.await()
            })
        runCurrent()
        assertEquals(NodeRunStatus.SUCCESS, run.state.value.nodeStates.getValue("fast").status)
        assertEquals(1, visits)
        commit.complete(Unit)
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertEquals(2, visits)
    }

    @Test fun fatalFailureCancelsIndependentWaitAndDoesNotLeaveWorkflowStuck() = runTest {
        val visits = mutableListOf<String>()
        var cancelled = false
        val run = WorkflowScheduler(setOf(commands { node, _ ->
            visits += node.id
            if (node.id == "slow") try { awaitCancellation() } finally { cancelled = true }
            NodeExecutionOutput(NodeRunStatus.FAILED, error = "failed")
        }), WorkflowApprovalBroker()).execute(definition, emptyMap(), "/workspace", backgroundScope, checkpoint = {})
        runCurrent()
        assertTrue(cancelled)
        assertEquals(WorkflowRunStatus.FAILED, run.state.value.status)
        assertEquals(NodeRunStatus.CANCELLED, run.state.value.nodeStates.getValue("slow").status)
        assertFalse("next" in visits)
        val plan = WorkflowRecovery.inspect(run.state.value)
        assertTrue(plan.blockedReason, plan.canResume)
        assertEquals(setOf("slow", "fast"), plan.retryNodeIds)
    }
}
