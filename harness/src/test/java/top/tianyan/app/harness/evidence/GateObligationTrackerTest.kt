package top.tianyan.app.harness.evidence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 闸门义务账本：保证闸门每回合最多把交付延长一轮。 */
class GateObligationTrackerTest {

    @Test
    fun `shouldContinueOnce returns true once then false`() {
        val tracker = GateObligationTracker()

        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertFalse("第二次必须为 false，否则闸门会变成死循环", tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertFalse(tracker.shouldContinueOnce("s1", "delivery-gate"))
    }

    @Test
    fun `reset re-arms the obligation`() {
        val tracker = GateObligationTracker()
        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertFalse(tracker.shouldContinueOnce("s1", "delivery-gate"))

        tracker.reset("s1")

        assertTrue("新回合必须重新获得一次机会", tracker.shouldContinueOnce("s1", "delivery-gate"))
    }

    @Test
    fun `distinct keys get independent obligations`() {
        val tracker = GateObligationTracker()

        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        // 声称审计是另一个理由，不该被闸门的消耗牵连。
        assertTrue(tracker.shouldContinueOnce("s1", "claim-audit"))
        assertFalse(tracker.shouldContinueOnce("s1", "claim-audit"))
    }

    @Test
    fun `sessions do not share obligations`() {
        val tracker = GateObligationTracker()

        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertTrue(tracker.shouldContinueOnce("s2", "delivery-gate"))
        assertFalse(tracker.shouldContinueOnce("s1", "delivery-gate"))
    }

    @Test
    fun `reset only affects the target session`() {
        val tracker = GateObligationTracker()
        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertTrue(tracker.shouldContinueOnce("s2", "delivery-gate"))

        tracker.reset("s1")

        assertTrue(tracker.shouldContinueOnce("s1", "delivery-gate"))
        assertFalse("s2 的额度不应被 s1 的重置影响", tracker.shouldContinueOnce("s2", "delivery-gate"))
    }
}
