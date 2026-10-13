package top.tianyan.app.harness.evidence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 证据账本：会话隔离、时间戳单调、验证条数上限。 */
class EvidenceTrackerTest {

    @Test
    fun `records are session scoped`() = runBlocking {
        val tracker = EvidenceTracker()
        tracker.recordModified("s1", "a.kt", at = 10L)
        tracker.recordRead("s1", "b.kt")

        val s1 = tracker.state("s1")
        assertEquals(setOf("a.kt"), s1.filesModified)
        assertEquals(setOf("b.kt"), s1.filesRead)
        assertEquals(10L, s1.lastMutationAt)

        assertEquals(EvidenceState(), tracker.state("s2"))
        Unit
    }

    @Test
    fun `lastMutationAt never moves backwards`() = runBlocking {
        // 并发工具轮次可能乱序完成；倒退的时间戳会让「验证晚于改动」的判据失效。
        val tracker = EvidenceTracker()
        tracker.recordModified("s1", "a.kt", at = 500L)
        tracker.recordModified("s1", "b.kt", at = 200L)

        assertEquals(500L, tracker.state("s1").lastMutationAt)
        Unit
    }

    @Test
    fun `blank session or path is ignored`() = runBlocking {
        val tracker = EvidenceTracker()
        tracker.recordModified("", "a.kt")
        tracker.recordRead("s1", "")

        assertEquals(EvidenceState(), tracker.state(""))
        assertEquals(EvidenceState().copy(filesRead = emptySet()), tracker.state("s1"))
        Unit
    }

    @Test
    fun `verifications are capped keeping the most recent`() = runBlocking {
        val tracker = EvidenceTracker()
        repeat(EvidenceTracker.MAX_VERIFICATIONS + 12) { index ->
            tracker.recordVerification(
                "s1",
                VerificationRecord(command = "cmd-$index", passed = true, at = index.toLong()),
            )
        }

        val stored = tracker.state("s1").verifications
        assertEquals(EvidenceTracker.MAX_VERIFICATIONS, stored.size)
        assertEquals("cmd-12", stored.first().command)
        assertEquals("cmd-61", stored.last().command)
        Unit
    }

    @Test
    fun `reset clears the whole session ledger`() = runBlocking {
        val tracker = EvidenceTracker()
        tracker.recordModified("s1", "a.kt", at = 10L)
        tracker.recordVerification("s1", VerificationRecord("./gradlew test", true, 20L))

        tracker.reset("s1")

        assertEquals(EvidenceState(), tracker.state("s1"))
        Unit
    }

    @Test
    fun `reset of one session leaves others intact`() = runBlocking {
        val tracker = EvidenceTracker()
        tracker.recordModified("s1", "a.kt", at = 10L)
        tracker.recordModified("s2", "b.kt", at = 10L)

        tracker.reset("s1")

        assertEquals(EvidenceState(), tracker.state("s1"))
        assertTrue(tracker.state("s2").filesModified.contains("b.kt"))
        assertFalse(tracker.state("s2").filesModified.isEmpty())
        Unit
    }
}
