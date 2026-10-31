package top.tianyan.app.harness.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessLaneEntity
import top.tianyan.app.core.database.HarnessOperationEntity
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*
import top.tianyan.app.harness.effects.ToolRecoveryNotice

/** Uses a fixed leaf and a bounded committed history window, never model-authored verification claims. */
internal class TaskDeliveryBuilder(private val json: Json) {
    fun build(taskId: String, operation: HarnessOperationEntity, lane: HarnessLaneEntity,
        entries: List<HarnessEntryEntity>, now: Long): TaskDelivery {
        require(operation.sessionId == lane.sessionId && operation.id == lane.currentOperationId)
        require(entries.all { it.sessionId == lane.sessionId })
        check(lane.leafId == null || entries.lastOrNull()?.id == lane.leafId) { "Delivery source leaf is missing" }
        val anchor = operation.startLeafId?.let { id -> entries.indexOfFirst { it.id == id } }
        val completeWindow = if (operation.startLeafId == null) entries.size <= MAX_SOURCE_ENTRIES else (anchor ?: -1) >= 0
        val window = if (anchor != null && anchor >= 0) entries.drop(anchor + 1) else entries.takeLast(MAX_SOURCE_ENTRIES)
        // Malformed committed messages must fail the completion transaction, not disappear as missing evidence.
        val messages = try { window.filter { it.entryType == "message" }.map { json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) } }
        catch (failure: SerializationException) { throw IllegalStateException("Task delivery source message is corrupt", failure) }
        val calls = messages.filterIsInstance<ToolCall>()
        val results = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
        val callsById = calls.associateBy { it.id }
        val observedIds = calls.map { it.id } + results.keys.filter { it !in callsById }
        val observations = observedIds.takeLast(MAX_OBSERVATIONS).map { id ->
            val candidates = results[id].orEmpty()
            val result = candidates.lastOrNull { !it.awaitingApproval && !it.approvalDeferred } ?: candidates.lastOrNull()
            ToolObservation(id, result?.id, callsById[id]?.let(::safeName) ?: "tool", state(result))
        }
        val selectedResults = observations.mapNotNull { observation -> results[observation.callEntryId]?.find { it.id == observation.resultEntryId } }
        val allArtifacts = selectedResults.flatMap { result -> buildList {
            ToolOutputArtifact.decode(result.metadata[ToolOutputArtifact.METADATA_KEY])?.let { add(DeliveryArtifact(result.id, it.path, it.sha256, it.bytes, it.complete)) }
            ToolFileArtifacts.decode(result.metadata[ToolFileArtifacts.METADATA_KEY])?.files?.forEach {
                add(DeliveryArtifact(result.id, it.path, it.sha256, it.bytes, true, DeliveryArtifactKind.valueOf(it.kind.name), it.origin, it.observedAt))
            }
        } }.distinctBy { Triple(it.kind, it.path, it.sha256) }
        val artifacts = allArtifacts.takeLast(MAX_ARTIFACTS)
        val historyComplete = completeWindow && observedIds.size <= MAX_OBSERVATIONS &&
            results.keys.all { it in callsById } && allArtifacts.size <= MAX_ARTIFACTS
        val invalidArtifact = selectedResults.any { result ->
            val metadata = result.metadata
            metadata.containsKey(ToolOutputArtifact.METADATA_KEY) && ToolOutputArtifact.decode(metadata[ToolOutputArtifact.METADATA_KEY]) == null ||
                metadata.containsKey(ToolFileArtifacts.METADATA_KEY) && ToolFileArtifacts.decode(metadata[ToolFileArtifacts.METADATA_KEY]) == null
        }
        val inventoryIncomplete = selectedResults.any { ToolFileArtifacts.decode(it.metadata[ToolFileArtifacts.METADATA_KEY])?.complete == false }
        val gaps = buildList {
            add(DeliveryGap.GOAL_NOT_VERIFIED)
            if (observations.isEmpty()) add(DeliveryGap.NO_TOOL_EVIDENCE)
            if (!historyComplete) add(DeliveryGap.HISTORY_INCOMPLETE)
            if (observations.any { it.state == ObservationState.OUTCOME_UNKNOWN }) add(DeliveryGap.UNKNOWN_OUTCOME)
            if (observations.any { it.state == ObservationState.UNSETTLED || it.state == ObservationState.WAITING_APPROVAL }) add(DeliveryGap.PENDING_RESULT)
            if (observations.any { it.state == ObservationState.FAILED }) add(DeliveryGap.FAILED_TOOL)
            if (artifacts.any { !it.complete } || inventoryIncomplete) add(DeliveryGap.INCOMPLETE_ARTIFACT)
            if (invalidArtifact) add(DeliveryGap.INVALID_ARTIFACT)
        }
        val lastAction = messages.indexOfLast { it is ToolCall || it is ToolResult }
        val claim = messages.drop(lastAction + 1).filterIsInstance<AssistantText>().lastOrNull { it.text.isNotBlank() }?.id
        return TaskDelivery("delivery:${operation.id}", now, TaskDeliveryReport(taskId, lane.sessionId, operation.id,
            operation.startLeafId, lane.leafId, claim, observations, artifacts, historyComplete, gaps, now))
    }

    private fun safeName(call: ToolCall): String = SecretRedactor().redact(call.rawToolName ?: HarnessApiMapper.apiName(call.tool))
        .replace(Regex("[^A-Za-z0-9_.:*-]"), "_").take(96)
    private fun state(result: ToolResult?): ObservationState = when {
        result == null -> ObservationState.UNSETTLED
        result.errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN -> ObservationState.OUTCOME_UNKNOWN
        result.awaitingApproval || result.approvalDeferred -> ObservationState.WAITING_APPROVAL
        result.success -> ObservationState.SUCCEEDED
        else -> ObservationState.FAILED
    }
    companion object {
        const val MAX_SOURCE_ENTRIES = 256
        const val MAX_OBSERVATIONS = 96
        const val MAX_ARTIFACTS = 32
    }
}
