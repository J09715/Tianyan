package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningTimingTrackerTest {

    @Test
    fun `measures from first reasoning chunk to first content chunk`() {
        var now = 1_000L
        val tracker = ReasoningTimingTracker { now }
        tracker.onReasoningChunk()
        now = 1_200L
        tracker.onReasoningChunk() // 重复增量不重置起点
        now = 4_800L
        tracker.onContentChunk()

        assertEquals(3_800L, tracker.finish())
    }

    @Test
    fun `no reasoning yields null duration`() {
        val tracker = ReasoningTimingTracker { 0L }
        tracker.onContentChunk()
        assertNull(tracker.finish())
    }

    @Test
    fun `finish closes reasoning that produced no content`() {
        var now = 100L
        val tracker = ReasoningTimingTracker { now }
        tracker.onReasoningChunk()
        now = 900L

        assertEquals(800L, tracker.finish())
    }

    @Test
    fun `duration never goes negative`() {
        var now = 5_000L
        val tracker = ReasoningTimingTracker { now }
        tracker.onReasoningChunk()
        now = 4_000L
        tracker.onContentChunk()

        assertEquals(0L, tracker.finish())
    }
}