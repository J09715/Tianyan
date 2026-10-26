package top.tianyan.app.harness.core

import org.junit.Assert.*
import org.junit.Test

class SessionProjectionTest {
    private fun message(id: String, sequence: Long) = SessionProjectionEntry(id, sequence, SessionProjectionContent.MessageValue(id))
    private fun summary(id: String, sequence: Long) = SessionProjectionEntry<String>(id, sequence, SessionProjectionContent.BranchSummary(id))
    private val checkpoint = SessionProjectionCheckpoint("compact", 5, "summary", listOf("kept"), 2L)

    @Test fun `fresh branch preserves message identity and state only entries stay outside context`() {
        val projection = SessionProjectionBuilder.project(listOf(message("user", 1),
            SessionProjectionEntry("state", 2, SessionProjectionContent.State("usage")), message("assistant", 3)))
        assertNull(projection.checkpointId)
        assertEquals(listOf("user", "assistant"), projection.messages.map { it.message })
        assertEquals(listOf("user", "assistant"), projection.messages.map { it.sourceEntryId })
        assertTrue(projection.messages.all { it.origin == ContextMessageOrigin.BRANCH })
        assertEquals(ProjectionContribution.STATE, projection.entries[1].contribution)
        assertEquals(3L, projection.sourceMaxSequence)
    }

    @Test fun `checkpoint recovery and appended messages have distinct provenance in chronological order`() {
        val projection = SessionProjectionBuilder.project(listOf(message("folded", 1), message("healed", 3),
            summary("healed-summary", 4), SessionProjectionEntry("compact", 5, SessionProjectionContent.State("compaction")),
            message("new", 6), summary("new-summary", 7)), checkpoint)
        assertEquals("summary", projection.summary)
        assertEquals(listOf("kept", "healed", "new"), projection.messages.map { it.message })
        assertEquals(listOf("compact", "healed", "new"), projection.messages.map { it.sourceEntryId })
        assertEquals(listOf(ContextMessageOrigin.RETAINED_SNAPSHOT, ContextMessageOrigin.WATERMARK_RECOVERY,
            ContextMessageOrigin.BRANCH), projection.messages.map { it.origin })
        assertEquals(listOf("healed-summary", "new-summary"), projection.branchSummaries.map { it.text })
        assertEquals(ProjectionContribution.FOLDED, projection.entries.first().contribution)
    }

    @Test fun `legacy checkpoint without a watermark cannot resurrect pre checkpoint messages`() {
        val projection = SessionProjectionBuilder.project(listOf(message("old", 3), summary("old-summary", 4), message("new", 6)),
            checkpoint.copy(sourceWatermarkSequence = null))
        assertEquals(listOf("kept", "new"), projection.messages.map { it.message })
        assertTrue(projection.branchSummaries.isEmpty())
    }

    @Test fun `recall entries survive folding and latest branch override owns the suffix`() {
        val projection = SessionProjectionBuilder.project(listOf(
            SessionProjectionEntry("recall-old", 1, SessionProjectionContent.Recall("kept", "first")),
            SessionProjectionEntry("recall-new", 6, SessionProjectionContent.Recall("kept", "second"))), checkpoint)
        assertEquals("second", projection.recallBlocks.getValue("kept").text)
        assertEquals("recall-new", projection.recallBlocks.getValue("kept").sourceEntryId)
        assertEquals(ProjectionContribution.SUPERSEDED_RECALL, projection.entries.first().contribution)
    }

    @Test fun `unreadable contributions produce bounded diagnostic codes instead of disappearing silently`() {
        val projection = SessionProjectionBuilder.project(listOf(message("user", 1),
            SessionProjectionEntry("broken", 2, SessionProjectionContent.Unreadable("message", ProjectionIssueCode.MALFORMED_MESSAGE))))
        assertEquals(listOf("user"), projection.messages.map { it.message })
        assertEquals(listOf(ProjectionIssue("broken", ProjectionIssueCode.MALFORMED_MESSAGE)), projection.issues)
        assertEquals(ProjectionContribution.UNREADABLE, projection.entries.last().contribution)
    }

    @Test fun `full branch and optimized window produce identical model content and watermarks`() {
        val entries = listOf(message("folded", 1),
            SessionProjectionEntry("recall", 2, SessionProjectionContent.Recall("kept", "frozen")),
            message("healed", 3), summary("recovered-summary", 4),
            SessionProjectionEntry("compact", 5, SessionProjectionContent.State("compaction")), message("new", 6))
        val full = SessionProjectionBuilder.project(entries, checkpoint)
        val window = SessionProjectionBuilder.project(entries.drop(1), checkpoint)
        assertEquals(full.messages, window.messages)
        assertEquals(full.branchSummaries, window.branchSummaries)
        assertEquals(full.recallBlocks, window.recallBlocks)
        assertEquals(full.sourceMaxSequence, window.sourceMaxSequence)
    }

    @Test fun `checkpoint only projection uses the checkpoint sequence as its snapshot watermark`() {
        val projection = SessionProjectionBuilder.project(emptyList(), checkpoint)
        assertEquals(5L, projection.sourceMaxSequence)
        assertEquals(listOf("kept"), projection.messages.map { it.message })
    }

    @Test fun `older checkpoint state cannot inject another summary`() {
        val projection = SessionProjectionBuilder.project(listOf(
            SessionProjectionEntry<String>("old-compact", 3, SessionProjectionContent.State("compaction")),
            SessionProjectionEntry<String>("compact", 5, SessionProjectionContent.State("compaction"))), checkpoint)
        assertEquals("summary", projection.summary)
        assertTrue(projection.branchSummaries.isEmpty())
        assertEquals(listOf("kept"), projection.messages.map { it.message })
    }
}
