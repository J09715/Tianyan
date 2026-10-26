package top.tianyan.app.harness.workflow

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.model.workflow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunResumeTest {
    private val definition = WorkflowDefinition("wf", "test", nodes = listOf(
        WorkflowNode("a", WorkflowNodeType.BASH_COMMAND, "a"), WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "b"),
    ), edges = listOf(WorkflowEdge("edge", "a", toNodeId = "b")))

    private fun TestScope.manager(repository: FakeWorkflowRepository, action: suspend (WorkflowNode) -> NodeExecutionOutput): WorkflowRunManager {
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.BASH_COMMAND)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext, onProgress: suspend (NodeRunStatus, String) -> Unit) = action(node)
        }
        return WorkflowRunManager(WorkflowScheduler(setOf(executor), WorkflowApprovalBroker()), repository,
            FakeLinuxRuntime(RuntimeState.Ready), Json { ignoreUnknownKeys = true }, AppLogger(ContextWrapper(null)) { it }, backgroundScope)
    }

    @Test fun cancelledRunResumesOnlyAfterRetryConfirmationAndRejectsDuplicateRequests() = runTest {
        val repository = FakeWorkflowRepository()
        val visits = mutableListOf<String>()
        var finish = false
        val manager = manager(repository) { node ->
            visits += node.id
            if (node.id == "b" && !finish) awaitCancellation()
            NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = node.id, artifacts = if (node.id == "a") listOf("/workspace/report.md") else emptyList())
        }
        val started = manager.start(definition, workspacePath = "/workspace")
        runCurrent()
        manager.cancel(started.executionId)
        runCurrent()
        val cancelled = repository.saved.last().state
        assertEquals(WorkflowRunStatus.CANCELLED, cancelled.status)
        assertEquals(setOf("b"), WorkflowRecovery.inspect(cancelled).retryNodeIds)
        assertFalse(manager.resume(started.executionId, emptySet(), 0, cancelled.journal.size).accepted)
        assertEquals(listOf("a", "b"), visits)
        val resumed = manager.resume(started.executionId, setOf("b"), 0, cancelled.journal.size)
        assertTrue(resumed.accepted)
        assertEquals(started.executionId, resumed.executionId)
        assertFalse(manager.resume(started.executionId, setOf("b"), 0, cancelled.journal.size).accepted)
        finish = true
        runCurrent()
        val result = repository.saved.last().state
        assertEquals(WorkflowRunStatus.SUCCESS, result.status)
        assertEquals(1, result.resumeCount)
        assertEquals(listOf("a", "b", "b"), visits)
        assertEquals(listOf("/workspace/report.md"), result.context!!.nodeOutputs.getValue("a").artifacts)
        assertFalse(manager.resume(started.executionId, setOf("b"), 0, cancelled.journal.size).accepted)
    }

    @Test fun restartingManagerReconcilesAndContinuesCommittedResultsWithoutReexecutingThem() = runTest {
        val repository = FakeWorkflowRepository()
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "cached", variables = mapOf("SAVED" to "yes"))
        val snapshot = WorkflowRuntimeState.initial("interrupted", definition).copy(status = WorkflowRunStatus.RUNNING, journalVersion = 1,
            runtimeEnvironmentId = "", context = WorkflowRuntimeContext("interrupted", "wf", "/workspace"),
            journal = listOf(WorkflowJournalEntry(1, "a", 1, WorkflowJournalKind.STARTED, 1L),
                WorkflowJournalEntry(2, "a", 1, WorkflowJournalKind.SETTLED, 2L, output)))
        repository.saveExecution(snapshot, "SCHEDULE", "schedule")
        repository.unfinished = listOf(repository.stored.getValue("interrupted"))
        val visits = mutableListOf<String>()
        val manager = manager(repository) { node -> visits += node.id; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
        manager.reconcileInterruptedRuns()
        assertTrue(manager.resume("interrupted", emptySet(), 0, 2).accepted)
        runCurrent()
        val result = repository.saved.last()
        assertEquals(listOf("b"), visits)
        assertEquals(WorkflowRunStatus.SUCCESS, result.state.status)
        assertEquals("SCHEDULE", result.triggerSource)
        assertEquals("schedule", result.scheduleId)
        assertEquals("yes", result.state.context!!.globalVariables["SAVED"])
    }

    @Test fun changedEnvironmentOrStaleRevisionIsRejectedBeforeExecution() = runTest {
        val repository = FakeWorkflowRepository()
        val snapshot = WorkflowRuntimeState.initial("old", definition).copy(status = WorkflowRunStatus.CANCELLED,
            journalVersion = 1, runtimeEnvironmentId = "other-distro", context = WorkflowRuntimeContext("old", "wf", "/workspace"))
        repository.saveExecution(snapshot, "MANUAL", null)
        var calls = 0
        val manager = manager(repository) { calls++; NodeExecutionOutput(NodeRunStatus.SUCCESS) }
        assertFalse(manager.resume("old", emptySet(), 1, 0).accepted)
        assertFalse(manager.resume("old", emptySet(), 0, 0).accepted)
        runCurrent()
        assertEquals(0, calls)
    }
}
