package top.tianyan.app.core.common.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryWatchdogTest {

    private val policy = MemoryWatchdog.WatermarkPolicy(
        fireRatio = 0.85,
        rearmRatio = 0.70,
        cooldownMs = 60_000L,
    )

    @Test
    fun armedAboveFireRatioFires() {
        val decision = policy.evaluate(armed = true, ratio = 0.90, lastFiredAt = 0, now = 120_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.FIRE, decision)
    }

    @Test
    fun armedAtExactFireRatioFires() {
        val decision = policy.evaluate(armed = true, ratio = 0.85, lastFiredAt = 0, now = 120_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.FIRE, decision)
    }

    @Test
    fun armedBelowFireRatioDoesNothing() {
        val decision = policy.evaluate(armed = true, ratio = 0.60, lastFiredAt = 0, now = 120_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.NONE, decision)
    }

    @Test
    fun cooldownSuppressesImmediateRefire() {
        val decision = policy.evaluate(armed = true, ratio = 0.92, lastFiredAt = 100_000, now = 130_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.NONE, decision)
    }

    @Test
    fun staysDisarmedBetweenRearmAndFireRatios() {
        val decision = policy.evaluate(armed = false, ratio = 0.75, lastFiredAt = 100_000, now = 200_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.NONE, decision)
    }

    @Test
    fun rearmsAfterFallingBelowRearmRatio() {
        val decision = policy.evaluate(armed = false, ratio = 0.50, lastFiredAt = 100_000, now = 200_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.REARM, decision)
    }

    @Test
    fun disarmedAboveFireRatioDoesNotFire() {
        val decision = policy.evaluate(armed = false, ratio = 0.95, lastFiredAt = 100_000, now = 200_000)
        assertEquals(MemoryWatchdog.WatermarkPolicy.Decision.NONE, decision)
    }

    @Test
    fun sameNameReleaserIsReplacedNotDuplicated() {
        val watchdog = MemoryWatchdog()
        val executed = mutableListOf<String>()
        watchdog.registerReleaser("dup") { executed.add("first") }
        watchdog.registerReleaser("dup") { executed.add("second") }
        watchdog.unregisterReleaser("not-registered")

        watchdog.fire(maxBytes = 512L * 1024 * 1024, usedBytes = 480L * 1024 * 1024, ratio = 0.94)

        assertEquals(listOf("second"), executed)
    }

    @Test
    fun releaserFailureDoesNotBlockOthers() {
        val watchdog = MemoryWatchdog()
        val executed = mutableListOf<String>()
        watchdog.registerReleaser("boom") { error("释放失败") }
        watchdog.registerReleaser("healthy") { executed.add("healthy") }

        watchdog.fire(maxBytes = 512L * 1024 * 1024, usedBytes = 480L * 1024 * 1024, ratio = 0.94)

        assertEquals(listOf("healthy"), executed)
    }
}
