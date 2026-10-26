package top.tianyan.app.harness.core

/** Storage-independent contributions from one captured branch, in ancestor-to-leaf order. */
data class SessionProjectionEntry<out Message>(
    val id: String,
    val sequence: Long,
    val content: SessionProjectionContent<Message>,
)

sealed interface SessionProjectionContent<out Message> {
    data class MessageValue<Message>(val message: Message) : SessionProjectionContent<Message>
    data class BranchSummary(val text: String) : SessionProjectionContent<Nothing>
    data class Recall(val userMessageId: String, val text: String) : SessionProjectionContent<Nothing>
    data class State(val entryType: String) : SessionProjectionContent<Nothing>
    data class Unreadable(val entryType: String, val code: ProjectionIssueCode) : SessionProjectionContent<Nothing>
}

data class SessionProjectionCheckpoint<Message>(
    val entryId: String,
    val sequence: Long,
    val summary: String,
    val retained: List<Message>,
    val sourceWatermarkSequence: Long?,
)

enum class ContextMessageOrigin { BRANCH, RETAINED_SNAPSHOT, WATERMARK_RECOVERY }
data class ProjectedContextMessage<Message>(
    val message: Message,
    /** Snapshot-retained messages belong to the checkpoint; their original raw entry is not loaded. */
    val sourceEntryId: String,
    val sourceSequence: Long,
    val origin: ContextMessageOrigin,
)
data class ProjectedContextSummary(val sourceEntryId: String, val sourceSequence: Long, val text: String)
data class ProjectedContextRecall(val sourceEntryId: String, val sourceSequence: Long, val text: String)

enum class ProjectionContribution { MESSAGE, RECOVERED_MESSAGE, BRANCH_SUMMARY, RECOVERED_BRANCH_SUMMARY, RECALL, SUPERSEDED_RECALL, FOLDED, STATE, UNREADABLE }
data class ProjectionEntryInspection(val entryId: String, val sequence: Long, val contribution: ProjectionContribution)
enum class ProjectionIssueCode { MALFORMED_MESSAGE, MALFORMED_BRANCH_SUMMARY, MALFORMED_CHECKPOINT, MALFORMED_RETAINED_MESSAGES, INVALID_WATERMARK }
data class ProjectionIssue(val entryId: String, val code: ProjectionIssueCode)

/** Canonical context plus provenance. Does not include prompts, protocol serialization or byte truncation. */
data class SessionProjection<Message>(
    val checkpointId: String?,
    val summary: String?,
    val messages: List<ProjectedContextMessage<Message>>,
    val branchSummaries: List<ProjectedContextSummary>,
    val recallBlocks: Map<String, ProjectedContextRecall>,
    /** Only entries fetched for this projection; a compacted read need not materialize folded history. */
    val entries: List<ProjectionEntryInspection>,
    val issues: List<ProjectionIssue>,
    val sourceMaxSequence: Long,
)

/** Shared rules for full-branch and optimized-window repositories. The reader selects the branch. */
object SessionProjectionBuilder {
    fun <Message> project(
        entries: List<SessionProjectionEntry<Message>>,
        checkpoint: SessionProjectionCheckpoint<Message>? = null,
        issues: List<ProjectionIssue> = emptyList(),
    ): SessionProjection<Message> {
        val messages = mutableListOf<ProjectedContextMessage<Message>>()
        val summaries = mutableListOf<ProjectedContextSummary>()
        val recalls = linkedMapOf<String, ProjectedContextRecall>()
        val inspections = mutableListOf<ProjectionEntryInspection>()
        val problems = issues.toMutableList()
        checkpoint?.retained?.forEach {
            messages += ProjectedContextMessage(it, checkpoint.entryId, checkpoint.sequence, ContextMessageOrigin.RETAINED_SNAPSHOT)
        }
        val watermark = checkpoint?.sourceWatermarkSequence ?: checkpoint?.sequence
        for (entry in entries) {
            val recovered = checkpoint != null && entry.sequence > watermark!! && entry.sequence < checkpoint.sequence
            val live = checkpoint == null || entry.sequence > checkpoint.sequence
            val contribution = when (val content = entry.content) {
                is SessionProjectionContent.MessageValue -> when {
                    recovered || live -> {
                        messages += ProjectedContextMessage(content.message, entry.id, entry.sequence,
                            if (recovered) ContextMessageOrigin.WATERMARK_RECOVERY else ContextMessageOrigin.BRANCH)
                        if (recovered) ProjectionContribution.RECOVERED_MESSAGE else ProjectionContribution.MESSAGE
                    }
                    else -> ProjectionContribution.FOLDED
                }
                is SessionProjectionContent.BranchSummary -> when {
                    (recovered || live) && content.text.isNotBlank() -> {
                        summaries += ProjectedContextSummary(entry.id, entry.sequence, content.text)
                        if (recovered) ProjectionContribution.RECOVERED_BRANCH_SUMMARY else ProjectionContribution.BRANCH_SUMMARY
                    }
                    else -> ProjectionContribution.FOLDED
                }
                is SessionProjectionContent.Recall -> {
                    if (content.userMessageId.isNotBlank()) recalls[content.userMessageId] =
                        ProjectedContextRecall(entry.id, entry.sequence, content.text)
                    ProjectionContribution.RECALL
                }
                is SessionProjectionContent.State -> ProjectionContribution.STATE
                is SessionProjectionContent.Unreadable -> {
                    problems += ProjectionIssue(entry.id, content.code)
                    ProjectionContribution.UNREADABLE
                }
            }
            inspections += ProjectionEntryInspection(entry.id, entry.sequence, contribution)
        }
        val currentRecallIds = recalls.values.mapTo(hashSetOf()) { it.sourceEntryId }
        return SessionProjection(
            checkpoint?.entryId, checkpoint?.summary, messages.toList(), summaries.toList(), recalls.toMap(),
            inspections.map { if (it.contribution == ProjectionContribution.RECALL && it.entryId !in currentRecallIds)
                it.copy(contribution = ProjectionContribution.SUPERSEDED_RECALL) else it },
            problems.toList(), maxOf(checkpoint?.sourceWatermarkSequence ?: 0L,
                entries.maxOfOrNull { it.sequence } ?: checkpoint?.sequence ?: 0L),
        )
    }
}
