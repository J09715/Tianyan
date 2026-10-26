package top.tianyan.app.harness.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.observeStreamActivity

class ProviderActivityTest {
    @Test fun `SSE heartbeat updates transport but never claims model output or execution progress`() = runBlocking {
        var now = 0L
        val tracker = ProviderActivityTracker { now }
        tracker.begin("s", "operation", 1)
        now = 100
        withContext(ProviderActivityContext(tracker, "s")) {
            observeStreamActivity(": heartbeat", AtomicInteger(ProviderClient.FIRST_EVENT_WAITING), Job())
        }
        assertEquals(100L, tracker.state.value["s"]!!.lastTransportAt)
        assertNull(tracker.state.value["s"]!!.lastOutputAt)
        assertEquals("waiting_model", tracker.state.value["s"]!!.phase)
        now = 200
        tracker.output("s", "tool_arguments")
        assertEquals(200L, tracker.state.value["s"]!!.lastOutputAt)
        assertEquals("tool_arguments", tracker.state.value["s"]!!.phase)
    }

    @Test fun `output recovery records a new request attempt and reason without carrying a false fresh output`() {
        var now = 0L
        val tracker = ProviderActivityTracker { now }
        tracker.begin("s", "operation", 1)
        tracker.output("s", "thinking")
        tracker.retry("s", "output_limit")
        now = 20
        tracker.begin("s", "operation", 2)
        assertEquals(20L, tracker.state.value["s"]!!.requestStartedAt)
        assertEquals(2, tracker.state.value["s"]!!.attempt)
        assertEquals("output_limit", tracker.state.value["s"]!!.retryReason)
        assertNull(tracker.state.value["s"]!!.lastOutputAt)
        tracker.begin("s", "new-operation", 1)
        assertNull(tracker.state.value["s"]!!.retryReason)
    }
}
