package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowDurabilityTest {
    private val definition = WorkflowDefinition("wf", "test", nodes = listOf(
        WorkflowNode("a", WorkflowNodeType.BASH_COMMAND, "a"), WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "b"),
    ), edges = listOf(WorkflowEdge("edge", "a", toNodeId = "b")))

    private fun executor(action: suspend (WorkflowNode, WorkflowRuntimeContext) -> NodeExecutionOutput) = object : NodeExecutor {
        override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
        override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node, context)
    }

    private suspend fun WorkflowRunHandle.terminal() = withTimeout(3_000) {
        state.first { it.status in setOf(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED) }
    }

    @Test fun startedAndSettledRecordsAreCommittedBeforeDispatchingNextAction() = runBlocking {
        var persisted: WorkflowRuntimeState? = null
        val visits = mutableListOf<String>()
        val run = WorkflowScheduler(setOf(executor { node, _ ->
            val entry = persisted!!.journal.last()
            assertEquals(node.id, entry.nodeId)
            assertEquals(WorkflowJournalKind.STARTED, entry.kind)
            if (node.id == "b") assertTrue(persisted!!.journal.any { it.nodeId == "a" && it.kind == WorkflowJournalKind.SETTLED })
            visits += node.id
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), WorkflowApprovalBroker()).execute(definition, emptyMap(), "/workspace", this, checkpoint = { persisted = it })
        assertEquals(WorkflowRunStatus.SUCCESS, run.terminal().status)
        assertEquals(listOf("a", "b"), visits)
        assertEquals(listOf(1, 2, 3, 4), run.state.value.journal.map { it.sequence })
    }

    @Test fun failedStartCommitPreventsSideEffects() = runBlocking {
        var calls = 0
        val run = WorkflowScheduler(setOf(executor { _, _ -> calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }), WorkflowApprovalBroker())
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = { if (it.journal.isNotEmpty()) error("disk full") })
        assertEquals(WorkflowRunStatus.FAILED, run.terminal().status)
        assertEquals(0, calls)
    }

    @Test fun failedResultCommitStopsDownstreamAndPersistedSnapshotRequiresRetry() = runBlocking {
        var persisted: WorkflowRuntimeState? = null
        val visits = mutableListOf<String>()
        val run = WorkflowScheduler(setOf(executor { node, _ -> visits += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS) }), WorkflowApprovalBroker())
            .execute(definition, emptyMap(), "/workspace", this, checkpoint = {
                if (it.journal.lastOrNull()?.kind == WorkflowJournalKind.SETTLED) error("disk full")
                persisted = it
            })
        assertEquals(WorkflowRunStatus.FAILED, run.terminal().status)
        assertEquals(listOf("a"), visits)
        val plan = WorkflowRecovery.inspect(persisted!!.copy(status = WorkflowRunStatus.CANCELLED))
        assertEquals(setOf("a"), plan.retryNodeIds)
    }

    @Test fun continuationRunsOnlyPendingNodesAndKeepsOriginalIdAndResults() = runBlocking {
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "cached", artifacts = listOf("/workspace/report.md"), variables = mapOf("VALUE" to "saved"))
        val snapshot = WorkflowRuntimeState.initial("original", definition).copy(status = WorkflowRunStatus.IDLE, journalVersion = 1, resumeCount = 1,
            context = WorkflowRuntimeContext("original", "wf", "/workspace", mapOf("VALUE" to "saved"), mapOf("a" to output)),
            journal = listOf(WorkflowJournalEntry(1, "a", 1, WorkflowJournalKind.STARTED, 1L), WorkflowJournalEntry(2, "a", 1, WorkflowJournalKind.SETTLED, 2L, output)))
        val visits = mutableListOf<String>()
        val run = WorkflowScheduler(setOf(executor { node, context ->
            visits += node.id
            assertEquals("saved", context.globalVariables["VALUE"])
            assertEquals("cached", context.nodeOutputs.getValue("a").textOutput)
            NodeExecutionOutput(NodeRunStatus.SUCCESS)
        }), WorkflowApprovalBroker()).execute(definition, emptyMap(), "/workspace", this, checkpoint = {}, restored = snapshot)
        val result = run.terminal()
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals("original", result.executionId)
        assertEquals(listOf("b"), visits)
        assertEquals(output, result.context!!.nodeOutputs["a"])
    }
}
