package top.tianyan.app.harness.groupchat

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.GroupToolStep

class GroupExecutionWatchdogTest {
    private val policy = GroupExecutionPolicy(120, 300)

    @Test fun `continuous reasoning output and regeneration cannot reset execution deadline`() {
        val watch = GroupExecutionWatchdog(0, policy)
        for (time in 0L..299L) watch.update(time, emptyList(), false)
        assertTrue(watch.shouldWarn(120))
        watch.markWarned()
        assertFalse(watch.shouldWarn(200))
        assertFalse(watch.expired(299))
        assertTrue(watch.expired(300))
        assertEquals(0, watch.lastProgressAt)
    }

    @Test fun `long tools and approvals pause the progress clock and tool results restart it`() {
        val watch = GroupExecutionWatchdog(0, policy)
        val running = GroupToolStep("call", "build")
        watch.update(50, listOf(running), false)
        watch.update(5_000, listOf(running), false)
        assertFalse(watch.expired(5_000))
        watch.update(5_100, listOf(running.copy(success = true)), false)
        assertEquals(5_100, watch.lastProgressAt)
        assertFalse(watch.expired(5_399))
        watch.update(5_200, listOf(running.copy(success = true)), true)
        watch.update(9_000, listOf(running.copy(success = true)), false)
        assertFalse(watch.expired(9_000))
        assertTrue(watch.expired(9_200))
    }

    @Test fun `output retry replaying old completed tool rows cannot refresh the clock`() {
        val watch = GroupExecutionWatchdog(0, policy)
        val result = GroupToolStep("read", "read", success = true)
        watch.update(10, listOf(result), false)
        watch.update(150, emptyList(), false)
        watch.update(200, listOf(result), false)
        assertEquals(10, watch.lastProgressAt)
        assertTrue(watch.expired(310))
    }
}
