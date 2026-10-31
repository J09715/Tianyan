package top.tianyan.app.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServiceRecoveryPolicyTest {
    @Test fun requiresRepeatedFailedHealthChecksAndBacksOffEvenIfTheProcessImmediatelyCrashes() = runBlocking {
        var now = 0L
        val policy = ServiceRecoveryPolicy { now }
        var starts = 0
        suspend fun tick() = policy.check({ false }) { starts++ }
        assertFalse(tick()); assertFalse(tick()); assertTrue(tick())
        assertEquals(1, starts)
        repeat(10) { tick() }
        assertEquals(1, starts)
        now = 5_000_000_000
        tick()
        assertEquals(2, starts)
        now = 14_000_000_000
        repeat(3) { tick() }
        assertEquals(2, starts)
        now = 15_000_000_000
        tick()
        assertEquals(3, starts)
    }

    @Test fun stableHealthResetsBackoffButASingleHealthyPollDoesNot() = runBlocking {
        var now = 0L
        val policy = ServiceRecoveryPolicy { now }
        var starts = 0
        suspend fun failed() = policy.check({ false }) { starts++; error("startup failed") }
        repeat(3) { failed() }
        policy.check({ true }) { fail("healthy service was restarted") }
        repeat(3) { failed() }
        assertEquals(1, starts)
        repeat(6) { policy.check({ true }) { fail("healthy service was restarted") } }
        repeat(3) { failed() }
        assertEquals(2, starts)
        now = 5_000_000_000
        repeat(3) { failed() }
        assertEquals(3, starts)
    }

    @Test fun cancellationPropagatesToTheForegroundServiceOwner() = runBlocking {
        val policy = ServiceRecoveryPolicy { 0L }
        repeat(2) { policy.check({ false }) {} }
        try {
            policy.check({ false }) { throw CancellationException("stop requested") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun repeatedFailuresCapTheRetryDelayAtFiveMinutes() = runBlocking {
        var now = 0L
        val policy = ServiceRecoveryPolicy { now }
        var starts = 0
        repeat(20) {
            repeat(3) { policy.check({ false }) { starts++ } }
            now += 300_000_000_000
        }
        assertEquals(20, starts)
    }
}
