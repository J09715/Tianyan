package top.tianyan.app.harness.workflow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowApprovalTimeoutTest {
    private fun definition(raw: String? = null, policy: FailurePolicy = FailurePolicy.ABORT) =
        WorkflowDefinition("wf", "approval", nodes = listOf(WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL,
            "approve", timeoutSeconds = 1, failurePolicy = policy,
            config = raw?.let { mapOf("timeoutSeconds" to it) } ?: emptyMap())))

    @Test fun defaultWaitIgnoresGenericTimeoutAndAcceptsLaterDecision() = runTest {
        val broker = WorkflowApprovalBroker()
        val run = WorkflowScheduler(setOf(ApprovalNodeExecutor(broker)), broker)
            .execute(definition(), emptyMap(), "/workspace", backgroundScope)
        runCurrent()
        advanceTimeBy(3600001)
        runCurrent()
        assertEquals(WorkflowRunStatus.WAITING_APPROVAL, run.state.value.status)
        assertNotNull(run.approvalRequest.value)
        assertTrue(run.decide("approve", true))
        runCurrent()
        assertEquals(WorkflowRunStatus.SUCCESS, run.state.value.status)
        assertNull(run.approvalRequest.value)
        assertEquals(0, broker.retainedExecutionCount)
    }

    @Test fun explicitExpiryClearsRequestAndRejectsLateApproval() = runTest {
        val broker = WorkflowApprovalBroker()
        val run = WorkflowScheduler(setOf(ApprovalNodeExecutor(broker)), broker)
            .execute(definition("1"), emptyMap(), "/workspace", backgroundScope)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertNotNull(run.approvalRequest.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(WorkflowRunStatus.FAILED, run.state.value.status)
        assertEquals(124, run.state.value.nodeStates.getValue("approve").output?.exitCode)
        assertNull(run.approvalRequest.value)
        assertFalse(run.decide("approve", true))
        runCurrent()
        assertEquals(WorkflowRunStatus.FAILED, run.state.value.status)
        assertEquals(0, broker.retainedExecutionCount)
    }

    @Test fun invalidExpiryFailsBeforeExecutorAndApprovalRegistration() = runTest {
        val broker = WorkflowApprovalBroker()
        var calls = 0
        val executor = object : NodeExecutor {
            override val supportedTypes = setOf(WorkflowNodeType.HUMAN_APPROVAL)
            override suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext,
                onProgress: suspend (NodeRunStatus, String) -> Unit): NodeExecutionOutput {
                calls++
                return NodeExecutionOutput(NodeRunStatus.SUCCESS)
            }
        }
        val run = WorkflowScheduler(setOf(executor), broker).execute(definition("-1"), emptyMap(), "/workspace", backgroundScope)
        runCurrent()
        assertEquals(WorkflowRunStatus.FAILED, run.state.value.status)
        assertTrue(run.state.value.error.orEmpty().contains("审批等待"))
        assertEquals(0, calls)
        assertNull(run.approvalRequest.value)
        assertEquals(0, broker.retainedExecutionCount)
    }
}
