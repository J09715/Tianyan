package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowRecoveryTest {
    private val success = NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "done", artifacts = listOf("/workspace/report.md"))
    private fun snapshot(journal: List<WorkflowJournalEntry> = emptyList(), type: WorkflowNodeType = WorkflowNodeType.BASH_COMMAND): WorkflowRuntimeState {
        val definition = WorkflowDefinition("wf", "test", nodes = listOf(
            WorkflowNode("a", type, "a"), WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "b"),
        ), edges = listOf(WorkflowEdge("edge", "a", toNodeId = "b")))
        return WorkflowRuntimeState.initial("run", definition).copy(status = WorkflowRunStatus.CANCELLED,
            context = WorkflowRuntimeContext("run", "wf", "/workspace"), journalVersion = 1, journal = journal)
    }
    private fun event(seq: Int, node: String, kind: WorkflowJournalKind, output: NodeExecutionOutput? = null, attempt: Int = 1) =
        WorkflowJournalEntry(seq, node, attempt, kind, 1L, output)

    @Test fun completedResultsAreReusedWhileUnstartedNodesRemainPending() {
        val state = snapshot(listOf(event(1, "a", WorkflowJournalKind.STARTED), event(2, "a", WorkflowJournalKind.SETTLED, success)))
        val plan = WorkflowRecovery.inspect(state)
        assertTrue(plan.canResume)
        assertEquals(mapOf("a" to success), plan.cachedOutputs)
        assertEquals(setOf("b"), plan.pendingNodeIds)
        assertTrue(plan.retryNodeIds.isEmpty())
        assertEquals(state, Json.decodeFromString<WorkflowRuntimeState>(Json.encodeToString(WorkflowRuntimeState.serializer(), state)))
    }

    @Test fun missingResultRequiresExplicitRetryAndRemainsUncertainAfterInterruptionMarker() {
        val state = snapshot(listOf(event(1, "a", WorkflowJournalKind.STARTED)))
        assertEquals(setOf("a"), WorkflowRecovery.inspect(state).retryNodeIds)
        val marked = state.copy(journal = state.journal + event(2, "a", WorkflowJournalKind.INTERRUPTED))
        assertEquals(setOf("a"), WorkflowRecovery.inspect(marked).retryNodeIds)
        val retried = marked.copy(journal = marked.journal + listOf(
            event(3, "a", WorkflowJournalKind.STARTED, attempt = 2), event(4, "a", WorkflowJournalKind.SETTLED, success, 2)))
        assertTrue(WorkflowRecovery.inspect(retried).retryNodeIds.isEmpty())
    }

    @Test fun approvalWaitCanBeRequestedAgainWithoutRetryingAnExternalEffect() {
        val state = snapshot(listOf(event(1, "a", WorkflowJournalKind.STARTED)), WorkflowNodeType.HUMAN_APPROVAL)
        assertTrue(WorkflowRecovery.inspect(state).retryNodeIds.isEmpty())
        assertEquals(setOf("a", "b"), WorkflowRecovery.inspect(state).pendingNodeIds)
    }

    @Test fun rejectsLegacyMalformedAndResourceDependentSnapshots() {
        assertFalse(WorkflowRecovery.inspect(snapshot().copy(journalVersion = 0)).canResume)
        assertFalse(WorkflowRecovery.inspect(snapshot(listOf(event(2, "a", WorkflowJournalKind.STARTED)))).canResume)
        assertFalse(WorkflowRecovery.inspect(snapshot(listOf(event(1, "a", WorkflowJournalKind.SETTLED, success)))).canResume)
        assertFalse(WorkflowRecovery.inspect(snapshot(type = WorkflowNodeType.PROCESS_SERVICE)).canResume)
        val screen = snapshot(type = WorkflowNodeType.HOST_ACTION).let { state ->
            state.copy(definition = state.definition.copy(nodes = state.definition.nodes.map {
                if (it.id == "a") it.copy(config = mapOf("action" to "virtual_screen_close")) else it
            }))
        }
        assertFalse(WorkflowRecovery.inspect(screen).canResume)
    }

    @Test fun failedAbortRequiresRetryButContinueRetainsTheFailedOutcome() {
        val failed = success.copy(status = NodeRunStatus.FAILED, exitCode = 1)
        val state = snapshot(listOf(event(1, "a", WorkflowJournalKind.STARTED), event(2, "a", WorkflowJournalKind.SETTLED, failed)))
        assertEquals(setOf("a"), WorkflowRecovery.inspect(state).retryNodeIds)
        val continuing = state.copy(definition = state.definition.copy(nodes = state.definition.nodes.map { it.copy(failurePolicy = FailurePolicy.CONTINUE) }))
        assertEquals(failed, WorkflowRecovery.inspect(continuing).cachedOutputs["a"])
    }
}
