package top.tianyan.app.harness.task

import kotlinx.serialization.Serializable
import top.tianyan.app.harness.FileArtifactOrigin

/** Immutable references to committed observations. Tool success does not verify the user's goal. */
@Serializable
data class TaskDeliveryReport(
    val taskId: String,
    val sessionId: String,
    val operationId: String,
    val sourceStartEntryId: String?,
    val sourceLeafId: String?,
    val assistantClaimEntryId: String?,
    val observations: List<ToolObservation>,
    val artifacts: List<DeliveryArtifact>,
    val historyComplete: Boolean,
    val gaps: List<DeliveryGap>,
    val createdAt: Long,
)

@Serializable
enum class ObservationState { SUCCEEDED, FAILED, OUTCOME_UNKNOWN, WAITING_APPROVAL, UNSETTLED }

@Serializable
enum class DeliveryGap { GOAL_NOT_VERIFIED, NO_TOOL_EVIDENCE, HISTORY_INCOMPLETE, UNKNOWN_OUTCOME, PENDING_RESULT, FAILED_TOOL, INCOMPLETE_ARTIFACT, INVALID_ARTIFACT }

@Serializable
data class ToolObservation(val callEntryId: String, val resultEntryId: String?, val toolName: String, val state: ObservationState)

@Serializable
data class DeliveryArtifact(val resultEntryId: String, val path: String, val sha256: String, val bytes: Long, val complete: Boolean,
    val kind: DeliveryArtifactKind = DeliveryArtifactKind.OUTPUT_LOG, val origin: FileArtifactOrigin? = null, val observedAt: Long? = null)

@Serializable
enum class DeliveryArtifactKind { OUTPUT_LOG, APK, IMAGE, FILE }
