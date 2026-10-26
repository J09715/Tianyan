package top.tianyan.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowApprovalBrokerTest {
    @Test fun parallelApprovalsAreQueuedAndNeverLost() = runBlocking {
        withTimeout(2000) {
            val broker = WorkflowApprovalBroker()
            val first = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "one", "one", "")) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "two", "two", "")) }
            assertEquals("one", broker.currentRequest.value?.nodeId)
            assertEquals("one", broker.currentRequestFor("run").value?.nodeId)
            assertTrue(broker.decide("run", "one", WorkflowApprovalDecision(true)))
            assertTrue(first.await().approved)
            broker.currentRequest.first { it?.nodeId == "two" }
            assertTrue(broker.decide("run", "two", WorkflowApprovalDecision(false)))
            assertFalse(second.await().approved)
            assertNull(broker.currentRequest.value)
        }
    }

    @Test fun cancellationUnblocksNextExecution() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val first = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("a", "one", "one", "")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("b", "two", "two", "")) }
        broker.cancelExecution("a")
        first.join()
        assertTrue(first.isCancelled)
        assertEquals("b", broker.currentRequest.value?.executionId)
        broker.decide("b", "two", WorkflowApprovalDecision(true))
        assertTrue(second.await().approved)
        assertNull(broker.currentRequest.value)
    }

    @Test fun completedHeadImmediatelyRevealsNextRequestAndDoesNotAcceptDuplicateDecision() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val flow = broker.currentRequestFor("run")
        val first = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "one", "one", "")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "two", "two", "")) }
        assertEquals("one", flow.value?.nodeId)
        assertTrue(broker.decide("run", "one", WorkflowApprovalDecision(true)))
        assertEquals("two", flow.value?.nodeId)
        assertFalse(broker.decide("run", "one", WorkflowApprovalDecision(true)))
        first.await()
        assertEquals("two", flow.value?.nodeId)
        broker.decide("run", "two", WorkflowApprovalDecision(false))
        assertFalse(second.await().approved)
    }

    @Test fun staleCancellationCleanupCannotEraseReplacementRequest() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val old = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "node", "old", "", reviewToken = "old")) }
        broker.cancelExecution("run")
        val flow = broker.currentRequestFor("run")
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "node", "new", "", reviewToken = "new")) }
        old.join()
        assertEquals("new", flow.value?.title)
        assertFalse(broker.decide("run", "node", WorkflowApprovalDecision(true, reviewToken = "old")))
        assertFalse(broker.decide("run", "node", WorkflowApprovalDecision(false, reviewToken = "old")))
        assertTrue(broker.decide("run", "node", WorkflowApprovalDecision(true, reviewToken = "new")))
        assertTrue(replacement.await().approved)
    }

    @Test fun compoundIdsCannotCollideAndQueuesRemainIsolated() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val one = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("a:b", "c", "one", "")) }
        val two = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("a", "b:c", "two", "")) }
        assertEquals("one", broker.currentRequestFor("a:b").value?.title)
        assertEquals("two", broker.currentRequestFor("a").value?.title)
        broker.decide("a:b", "c", WorkflowApprovalDecision(true))
        broker.decide("a", "b:c", WorkflowApprovalDecision(false))
        assertTrue(one.await().approved)
        assertFalse(two.await().approved)
    }

    @Test fun inlineCompletionCannotEraseNewRequestRegisteredBySameCoroutine() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val flow = broker.currentRequestFor("run")
        val work = async(Dispatchers.Unconfined) {
            broker.await(WorkflowApprovalRequest("run", "node", "old", ""))
            broker.await(WorkflowApprovalRequest("run", "node", "new", ""))
        }
        assertTrue(broker.decide("run", "node", WorkflowApprovalDecision(true)))
        assertEquals("new", flow.value?.title)
        assertTrue(broker.decide("run", "node", WorkflowApprovalDecision(true)))
        assertTrue(work.await().approved)
    }
}
