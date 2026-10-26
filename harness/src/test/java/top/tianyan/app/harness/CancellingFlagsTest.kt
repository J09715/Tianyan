package top.tianyan.app.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消标记的泄漏修复与忙语义。
 *
 * 任务刚离开 sessionJobs、第二阶段尚未摘标时，[CancellingFlags.isCancelling] 必须仍为 true。
 * 立刻摘标会让 isSessionBusy 放行新 run，第二阶段再把那个 run 误标成已取消。
 */
class CancellingFlagsTest {

    private class Clock(var time: Long = 0L) : () -> Long {
        override fun invoke(): Long = time
    }

    private fun flags(clock: Clock, staleAfterMs: Long = 30_000L) =
        CancellingFlags(staleAfterMs = staleAfterMs, now = clock)

    /** 与 HarnessLoop.isSessionBusy 同一公式：存活任务/待批审批，或仍有效的取消标记。 */
    private fun busy(flags: CancellingFlags, sessionId: String, hold: Boolean): Boolean =
        hold || flags.isCancelling(sessionId, hold)

    @Test
    fun `guarded failure clears the flag and the session is not busy`() {
        val flags = flags(Clock())

        val failure = assertThrows(IllegalStateException::class.java) {
            flags.guarded("s") { throw IllegalStateException("db") }
        }

        assertEquals("db", failure.message)
        assertFalse(flags.isSet("s"))
        assertFalse(busy(flags, "s", hold = false))
    }

    @Test
    fun `guarded success keeps the flag so a session with no job stays busy`() {
        val flags = flags(Clock())

        assertEquals("ok", flags.guarded("s") { "ok" })

        assertTrue(flags.isSet("s"))
        assertTrue(busy(flags, "s", hold = false))
        assertTrue(busy(flags, "s", hold = true))
    }

    @Test
    fun `first idle observation stays cancelling even when the mark itself is old`() {
        val clock = Clock()
        val flags = flags(clock)
        flags.mark("s")
        clock.time = 120_000

        assertTrue(flags.isCancelling("s", hold = true))
        assertTrue("长时间 join 结束后第一次看到无任务，仍处于取消第二阶段", busy(flags, "s", hold = false))
        assertTrue(flags.isSet("s"))
    }

    @Test
    fun `flag idle past the stale window self-heals and the session is no longer busy`() {
        val clock = Clock()
        val flags = flags(clock, staleAfterMs = 30_000)
        flags.mark("s")
        assertTrue(flags.isCancelling("s", hold = false))

        clock.time = 29_999
        assertTrue(busy(flags, "s", hold = false))
        assertTrue(flags.isSet("s"))

        clock.time = 30_000
        assertFalse(busy(flags, "s", hold = false))
        assertFalse(flags.isSet("s"))
    }

    @Test
    fun `a live hold restarts the idle window`() {
        val clock = Clock()
        val flags = flags(clock, staleAfterMs = 30_000)
        flags.mark("s")
        assertTrue(flags.isCancelling("s", hold = false))
        clock.time = 20_000

        assertTrue(flags.isCancelling("s", hold = true))
        assertTrue(flags.isSet("s"))

        clock.time = 40_000
        assertTrue("hold 清掉闲置计时，不能按最初的闲置起点摘标", busy(flags, "s", hold = false))
        assertTrue(flags.isSet("s"))
    }

    @Test
    fun `fresh mark restarts the idle window`() {
        val clock = Clock()
        val flags = flags(clock, staleAfterMs = 30_000)
        flags.mark("s")
        assertTrue(flags.isCancelling("s", hold = false))
        clock.time = 30_000

        flags.mark("s")

        assertTrue(busy(flags, "s", hold = false))
        assertTrue(flags.isSet("s"))
    }

    @Test
    fun `duringCancel clears only after the block and still runs it when cancelled`() = runBlocking {
        val flags = flags(Clock())
        var phase2 = false
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            flags.duringCancel("s") {
                flags.mark("s")
                assertTrue(busy(flags, "s", hold = false))
                entered.complete(Unit)
                delay(30)
                phase2 = flags.isSet("s")
            }
        }
        withTimeout(2_000) { entered.await() }
        job.cancel()
        withTimeout(2_000) { job.join() }

        assertTrue("取消协程被取消时第二阶段仍要跑完", phase2)
        assertFalse(flags.isSet("s"))
        assertFalse(busy(flags, "s", hold = false))
    }

    @Test
    fun `successor started after the phase-two clear can drain before duringCancel returns`() = runBlocking {
        val flags = flags(Clock())
        val drained = mutableListOf<String>()
        flags.duringCancel("s") {
            flags.mark("s")
            // 被取消的 run 在第二阶段摘标前结束：finishRun 看到标记，不排空。
            if (!flags.isSet("s")) drained += "cancelled-job"
            // 与 HarnessLoop.cancel 相同：收尾后、启动后继 run 之前摘标。
            flags.clear("s")
            // 后继 run 在 duringCancel 的 finally 之前结束，此时标记必须已经不在。
            if (!flags.isSet("s")) drained += "successor"
        }
        assertEquals(listOf("successor"), drained)
        assertFalse(flags.isSet("s"))
    }

    @Test
    fun `duringCancel clears when the block fails after guarded already cleared`() = runBlocking {
        val flags = flags(Clock())

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                flags.duringCancel("s") {
                    flags.guarded("s") { throw IllegalStateException("db") }
                }
            }
        }

        assertEquals("db", failure.message)
        assertFalse(flags.isSet("s"))
        assertFalse(busy(flags, "s", hold = false))
    }

    @Test
    fun `default stale window is tens of seconds`() {
        assertTrue(CancellingFlags.STALE_AFTER_MS >= 10_000L)
        assertTrue(CancellingFlags.STALE_AFTER_MS <= 90_000L)
    }
}
