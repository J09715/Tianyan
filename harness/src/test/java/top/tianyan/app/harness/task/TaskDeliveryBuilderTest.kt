package top.tianyan.app.harness.task

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.compaction.ConversationText
import top.tianyan.app.harness.effects.ToolRecoveryNotice
import top.tianyan.app.harness.session.ApiMessageProjector

class TaskDeliveryBuilderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun entry(message: HarnessMessage, sequence: Long = message.createdAt) = HarnessEntryEntity(
        sequence, message.id, "s", null, message.createdAt, "message", payloadJson = json.encodeToString(HarnessMessage.serializer(), message))
    private fun build(entries: List<HarnessEntryEntity>, start: String? = null): TaskDelivery {
        val operation = HarnessOperationEntity("op", "s", "main", "run", "running", "provider_settled", 1, 1, start, "{}")
        return TaskDeliveryBuilder(json).build("task", operation, HarnessLaneEntity("s", "main", entries.lastOrNull()?.id, "op", updatedAt = 1), entries, 10)
    }
    private fun call(id: String = "call", at: Long = 2) = ToolCall(id, at, HarnessTool.BASE, buildJsonObject { })

    @Test fun observedToolSuccessIsNeverPromotedToGoalVerification() {
        val messages = listOf(UserMessage("input", 1, "SECRET-GOAL"), call(), ToolResult("result", 3, "call", true, "SECRET-OUTPUT"),
            AssistantText("final", 4, "I verified everything SECRET-CLAIM"))
        val delivery = build(messages.map(::entry))
        assertTrue(delivery.report.historyComplete)
        assertEquals(ObservationState.SUCCEEDED, delivery.report.observations.single().state)
        assertEquals("final", delivery.report.assistantClaimEntryId)
        assertEquals(listOf(DeliveryGap.GOAL_NOT_VERIFIED), delivery.report.gaps)
        val encoded = json.encodeToString(HarnessMessage.serializer(), delivery)
        assertFalse(encoded.contains("SECRET-"))
        assertEquals(delivery, json.decodeFromString(HarnessMessage.serializer(), encoded))
    }

    @Test fun approvalReplayUsesFinalCommittedResultInsteadOfOldWaitingRecord() {
        val waiting = ToolResult("waiting", 3, "call", false, "wait", awaitingApproval = true)
        val final = ToolResult("result", 4, "call", true, "done")
        val report = build(listOf(entry(call()), entry(waiting), entry(final))).report
        assertEquals("result", report.observations.single().resultEntryId)
        assertEquals(ObservationState.SUCCEEDED, report.observations.single().state)
        assertFalse(DeliveryGap.PENDING_RESULT in report.gaps)
    }

    @Test fun unknownAndMissingResultsStayExplicit() {
        val report = build(listOf(entry(call()), entry(ToolRecoveryNotice.unknown().result("unknown", 3, "call")),
            entry(call("missing", 4)))).report
        assertEquals(listOf(ObservationState.OUTCOME_UNKNOWN, ObservationState.UNSETTLED), report.observations.map { it.state })
        assertTrue(DeliveryGap.UNKNOWN_OUTCOME in report.gaps)
        assertTrue(DeliveryGap.PENDING_RESULT in report.gaps)
        assertNull(report.assistantClaimEntryId)
    }

    @Test fun reportWindowStartsAfterOperationAnchorAndNeverMixesEarlierTask() {
        val old = entry(call("older", 1))
        val anchor = entry(UserMessage("anchor", 2, "earlier"))
        val current = entry(call("current", 3))
        val report = build(listOf(old, anchor, current), start = "anchor").report
        assertEquals(listOf("current"), report.observations.map { it.callEntryId })
        assertTrue(report.historyComplete)
    }

    @Test fun longSessionWithShortCurrentRunIsStillComplete() {
        val earlier = (1..255).map { entry(UserMessage("old$it", it.toLong(), "old")) }
        val report = build(earlier + entry(call("current", 256)) + entry(ToolResult("result", 257, "current", true, "ok")), "old255").report
        assertTrue(report.historyComplete)
        assertEquals(1, report.observations.size)
    }

    @Test fun cappedHistoryAndOrphanedResultsCannotLookLikeCompleteEvidence() {
        val entries = (1..257).map { entry(call("call$it", it.toLong())) }
        val report = build(entries).report
        assertFalse(report.historyComplete)
        assertEquals(TaskDeliveryBuilder.MAX_OBSERVATIONS, report.observations.size)
        assertTrue(DeliveryGap.HISTORY_INCOMPLETE in report.gaps)
        val orphan = build(listOf(entry(ToolResult("orphan", 1, "missing-call", true, "ok")))).report
        assertFalse(orphan.historyComplete)
    }

    @Test fun outputFingerprintMatchesExactUtf8IncludingChunkBoundarySurrogates() {
        val content = "a".repeat(8191) + "🙂中文\n" + "z".repeat(9000)
        val artifact = ToolOutputArtifact.capture(".tianyan-outputs/tool-test.txt", content)
        val bytes = content.toByteArray(Charsets.UTF_8)
        assertEquals(bytes.size.toLong(), artifact.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, artifact.sha256)
        assertTrue(artifact.complete)
        val result = ToolResult("result", 3, "call", true, "inline", metadata = mapOf(ToolOutputArtifact.METADATA_KEY to
            json.encodeToString(ToolOutputArtifact.serializer(), artifact)))
        assertEquals(artifact.sha256, build(listOf(entry(call()), entry(result))).report.artifacts.single().sha256)
    }

    @Test fun truncatedAndInvalidArtifactsNeverCountAsCompleteOutput() {
        val artifact = ToolOutputArtifact.capture(".tianyan-outputs/tool-test.txt", "[输出保存达到上限：中段缺失]\n")
        val truncated = ToolResult("result", 3, "call", true, "inline", metadata = mapOf(ToolOutputArtifact.METADATA_KEY to
            json.encodeToString(ToolOutputArtifact.serializer(), artifact)))
        assertTrue(DeliveryGap.INCOMPLETE_ARTIFACT in build(listOf(entry(call()), entry(truncated))).report.gaps)
        val invalid = truncated.copy(metadata = mapOf(ToolOutputArtifact.METADATA_KEY to "{}"))
        val report = build(listOf(entry(call()), entry(invalid))).report
        assertTrue(report.artifacts.isEmpty())
        assertTrue(DeliveryGap.INVALID_ARTIFACT in report.gaps)
        assertNull(ToolOutputArtifact.decode(json.encodeToString(ToolOutputArtifact.serializer(), artifact.copy(path = "../../private/key"))))
    }

    @Test fun corruptCommittedMessageAbortsRatherThanManufacturingEmptyEvidence() {
        val source = entry(call()).copy(payloadJson = "{broken")
        assertEquals("Task delivery source message is corrupt", runCatching { build(listOf(source)) }.exceptionOrNull()!!.message)
    }

    @Test fun fileVersionsRemainBoundToResultAndUnchangedFilesStayExplicit() {
        val file = ToolFileArtifact("build/app.apk", "a".repeat(64), 100, FileArtifactKind.APK, FileArtifactOrigin.UNCHANGED_AFTER_COMMAND, 10)
        val result = ToolResult("result", 3, "call", true, "done", metadata = mapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(listOf(file))))
        val report = build(listOf(entry(call()), entry(result))).report
        val artifact = report.artifacts.single()
        assertEquals("result", artifact.resultEntryId)
        assertEquals(DeliveryArtifactKind.APK, artifact.kind)
        assertEquals(FileArtifactOrigin.UNCHANGED_AFTER_COMMAND, artifact.origin)
        assertEquals(10L, artifact.observedAt)
        assertEquals(listOf(DeliveryGap.GOAL_NOT_VERIFIED), report.gaps)
    }

    @Test fun fileInventoryFailuresAndInvalidMetadataRemainVisibleAsGaps() {
        val result = ToolResult("result", 3, "call", true, "done", metadata = mapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(emptyList(), false)))
        assertTrue(DeliveryGap.INCOMPLETE_ARTIFACT in build(listOf(entry(call()), entry(result))).report.gaps)
        val invalid = result.copy(metadata = mapOf(ToolFileArtifacts.METADATA_KEY to "{}"))
        assertTrue(DeliveryGap.INVALID_ARTIFACT in build(listOf(entry(call()), entry(invalid))).report.gaps)
    }

    @Test fun identicalVersionsDeduplicateButSamePathWithDifferentBytesIsPreserved() {
        val first = ToolFileArtifact("build/app.apk", "a".repeat(64), 100, FileArtifactKind.APK, FileArtifactOrigin.NEW_AFTER_COMMAND, 10)
        val changed = first.copy(sha256 = "b".repeat(64), origin = FileArtifactOrigin.CHANGED_AFTER_COMMAND, observedAt = 20)
        val messages = listOf(call("call1"), ToolResult("result1", 3, "call1", true, "done", metadata = mapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(listOf(first)))),
            call("call2"), ToolResult("result2", 4, "call2", true, "done", metadata = mapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(listOf(first, changed)))))
        val report = build(messages.map(::entry)).report
        assertEquals(2, report.artifacts.size)
        assertEquals(listOf("result1", "result2"), report.artifacts.map { it.resultEntryId })
        assertEquals(listOf(first.sha256, changed.sha256), report.artifacts.map { it.sha256 })
    }

    @Test fun deliveryRecordNeverEntersProviderContextOrCompactionPrompt() {
        val input = UserMessage("input", 1, "hello")
        val delivery = build(listOf(entry(input)))
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            assertEquals(ApiMessageProjector.project(listOf(input), mode, false), ApiMessageProjector.project(listOf(input, delivery), mode, false))
        }
        assertEquals(ConversationText.serialize(listOf(input)), ConversationText.serialize(listOf(input, delivery)))
        assertEquals(0, ContextWindowPolicy.estimateApiMessages(ApiMessageProjector.project(listOf(delivery), ToolCallMode.NATIVE, false)))
    }
}
