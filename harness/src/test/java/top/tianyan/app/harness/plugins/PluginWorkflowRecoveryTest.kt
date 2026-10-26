package top.tianyan.app.harness.plugins

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.workflow.WorkflowApprovalBroker
import top.tianyan.app.harness.workflow.WorkflowScheduler

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginWorkflowRecoveryTest {
    @Test fun pluginApprovalAndSuccessAreJournalledAndCompletedNodesAreNotReplayed() = runTest {
        val registry = HarnessPluginRegistry(builtinHarnessPlugins())
        val broker = WorkflowApprovalBroker()
        val scheduler = WorkflowScheduler(setOf(PluginNodeExecutor(registry, broker, SecretRedactor())), broker)
        fun node(id: String) = WorkflowNode(id, WorkflowNodeType.PLUGIN_ACTION, id,
            config = mapOf("plugin" to "tianyan.harness", "extension" to "text-result", "arguments" to "{\"text\":\"$id\"}"))
        val definition = WorkflowDefinition("flow", "Fixture", nodes = listOf(node("first"), node("second")),
            edges = listOf(WorkflowEdge("next", "first", toNodeId = "second")))
        val checkpoints = mutableListOf<WorkflowRuntimeState>()
        val handle = scheduler.execute(definition, emptyMap(), "/workspace/fixed", this, checkpoint = { checkpoints += it })
        runCurrent()
        assertEquals(WorkflowJournalKind.STARTED, checkpoints.last().journal.last().kind)
        val firstToken = handle.approvalRequest.value!!.reviewToken
        assertTrue(handle.decide("first", true, reviewToken = firstToken))
        runCurrent()
        assertEquals(NodeRunStatus.SUCCESS, handle.state.value.nodeStates.getValue("first").status)
        assertEquals("second", handle.approvalRequest.value!!.nodeId)
        val interruptedToken = handle.approvalRequest.value!!.reviewToken
        handle.cancel(); runCurrent()
        val saved = handle.state.value
        val plan = WorkflowRecovery.inspect(saved)
        assertTrue(plan.canResume)
        assertTrue("first" in plan.cachedOutputs)
        assertTrue("second" in plan.retryNodeIds)
        val resumed = scheduler.execute(definition, emptyMap(), "/workspace/fixed", this,
            checkpoint = { checkpoints += it }, restored = saved)
        runCurrent()
        assertEquals("second", resumed.approvalRequest.value!!.nodeId)
        assertFalse(resumed.decide("second", true, reviewToken = interruptedToken))
        assertTrue(resumed.decide("second", true, reviewToken = resumed.approvalRequest.value!!.reviewToken))
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, resumed.state.value.status)
        assertEquals(1, resumed.state.value.journal.count { it.nodeId == "first" && it.kind == WorkflowJournalKind.STARTED })
    }
}
