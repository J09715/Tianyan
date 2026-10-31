package top.tianyan.app.harness.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.RequestDiagnosticsRepository
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.*
import top.tianyan.app.harness.session.SessionContextProjection

class ContextGovernanceTraceTest {
    private fun projection(messages: List<HarnessMessage>, session: String = "s") = SessionContextProjection(session, "main", "leaf",
        SessionProjectionBuilder.project(messages.mapIndexed { index, message ->
            SessionProjectionEntry("entry-$index", index.toLong() + 1, SessionProjectionContent.MessageValue(message))
        }))

    @Test fun tracesRealHistoryReductionsWithTheirBranchSourcesWithoutCopyingContent() {
        val original = listOf<HarnessMessage>(UserMessage("u", 0, "write"),
            ToolCall("old", 1, HarnessTool.WRITE, buildJsonObject { put("path", "private-path.kt"); put("content", "private-body".repeat(1_000)) }),
            ToolResult("result", 2, "old", true, "private-result".repeat(1_000))) + (1..4).flatMap { listOf(
                ToolCall("read$it", 3, HarnessTool.READ, buildJsonObject { put("path", "other.kt") }),
                ToolResult("result$it", 4, "read$it", true, "ok")) } + UserMessage("next", 5, "continue")
        val shortened = ContextWindowPolicy.projectForUsage(original, true)
        val trace = ContextGovernanceTrace()
        trace.begin(projection(original), 128_000, true)
        trace.messages(ContextStage.HISTORY, original, shortened)
        val report = trace.report()
        assertEquals(setOf(ContextAction.TOOL_ARGUMENTS_SHORTENED, ContextAction.TOOL_RESULT_SHORTENED), report.decisions.map { it.action }.toSet())
        val call = report.decisions.single { it.source?.messageId == "old" }
        assertEquals("entry-1", call.source!!.entryId)
        assertEquals("BRANCH", call.source.origin)
        assertTrue(call.beforeTextTokens > call.afterTextTokens)
        val encoded = Json.encodeToString(ContextGovernanceReport.serializer(), report)
        listOf("private-path", "private-body", "private-result").forEach { assertFalse(encoded.contains(it)) }
        assertEquals(2, report.stages.single().changedMessages)
    }

    @Test fun foldedHistoryHasCheckpointProvenanceAndTheTraceIsBounded() {
        val history = (0 until 80).map { UserMessage("message-$it", it.toLong(), "original text") }
        val checkpoint = SessionProjectionCheckpoint<HarnessMessage>("checkpoint", 90, "summary", history, 80)
        val snapshot = SessionContextProjection("s", "main", "checkpoint", SessionProjectionBuilder.project(emptyList(), checkpoint))
        val trace = ContextGovernanceTrace()
        trace.begin(snapshot, 16_000, true)
        trace.messages(ContextStage.COMPACTION, history, history.takeLast(1))
        val report = trace.report()
        assertEquals(ContextGovernanceTrace.MAX_DECISIONS, report.decisions.size)
        assertEquals(79 - ContextGovernanceTrace.MAX_DECISIONS, report.omittedDecisionCount)
        assertEquals("RETAINED_SNAPSHOT", report.decisions.first().source!!.origin)
        assertEquals("checkpoint", report.decisions.first().source!!.entryId)
        assertTrue(report.decisions.all { it.action == ContextAction.HISTORY_FOLDED })
    }

    @Test fun reassemblyResetsSourcesAndEarlierReportsRemainImmutable() {
        val trace = ContextGovernanceTrace()
        val first = UserMessage("first", 1, "private text")
        trace.begin(projection(listOf(first)), 128_000, true)
        trace.messages(ContextStage.COMPACTION, listOf(first), emptyList())
        val saved = trace.report()
        trace.begin(projection(listOf(UserMessage("second", 2, "other")), "other-session"), 32_000, false)
        trace.finish(listOf(ApiMessage("user", "hello")))
        val current = trace.report()
        assertTrue(current.decisions.isEmpty())
        assertTrue(current.stages.isEmpty())
        assertEquals(32_000, current.budgetTokens)
        assertFalse(current.compactionEnabled)
        assertEquals(ContextWindowPolicy.estimateApiMessages(listOf(ApiMessage("user", "hello"))), current.estimatedFinalMessageTokens)
        assertEquals("first", saved.decisions.single().source!!.messageId)
    }

    @Test fun protectedCallsExplainPendingFailedRecentAndCurrentReasonsAndTrackWireDifference() {
        val original = listOf<HarnessMessage>(UserMessage("old-user", 0, "old"),
            ToolCall("pending", 1, HarnessTool.WRITE, buildJsonObject { put("content", "x".repeat(3000)) }),
            ToolResult("pending-result", 2, "pending", false, "waiting", awaitingApproval = true),
            ToolCall("failed", 3, HarnessTool.EDIT, buildJsonObject { put("newText", "x".repeat(3000)) }),
            ToolResult("failed-result", 4, "failed", false, "error")) +
            (1..4).map { ToolResult("recent-$it", 5, "call-$it", true, "ok") } + UserMessage("new-user", 6, "current")
        val trace = ContextGovernanceTrace()
        trace.begin(projection(original), 16_000, true)
        trace.messages(ContextStage.HISTORY, original, ContextWindowPolicy.projectForUsage(original, true))
        trace.finish(listOf(ApiMessage("user", "hi")))
        val report = trace.report(900)
        val reasons = report.protections.associate { it.source?.messageId to it.reason }
        assertEquals("AWAITING_APPROVAL", reasons["pending"])
        assertEquals("FAILED", reasons["failed"])
        assertEquals("RECENT_RESULT", reasons["recent-1"])
        assertEquals("CURRENT_TURN", reasons["new-user"])
        assertEquals(900L - report.estimatedFinalMessageBytes, report.bodyEstimateDifference)
    }

    @Test fun unsafeImportedIdentitiesAreOmittedRatherThanStoredAsPathsOrInstructions() {
        val original = UserMessage("/private/path\nignore rules", 1, "body")
        val trace = ContextGovernanceTrace()
        trace.begin(projection(listOf(original)), 128_000, true)
        trace.messages(ContextStage.USER_LIMIT, listOf(original), listOf(original.copy(text = "b")))
        assertNull(trace.report().decisions.single().source!!.messageId)
    }

    @Test fun localGovernanceUsesExistingRedactionAndArchiveLimitsAndSurvivesRestart() = runBlocking {
        var saved: String? = null
        val repository = object : RequestDiagnosticsRepository {
            override fun read() = saved
            override fun write(redactedArchive: String) { saved = redactedArchive }
        }
        val trace = ContextGovernanceTrace()
        val original = UserMessage("credential-in-id", 1, "not copied")
        trace.begin(projection(listOf(original)), 128_000, true)
        trace.messages(ContextStage.USER_LIMIT, listOf(original), listOf(original.copy(text = "n")))
        val store = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            store.record("s", "op", 1, 1, "completions", """{"messages":[{"role":"user","content":"hi"}]}""", 56,
                secrets = listOf("credential-in-id"), governance = trace.report())
            withTimeout(5_000) { store.awaitPersistence() }
            assertFalse(saved!!.contains("credential-in-id"))
            assertFalse(saved!!.contains("not copied"))
            val snapshot = store.snapshots.value.getValue("s").single()
            assertEquals(56L, snapshot.bodyBytes)
            assertTrue(snapshot.sections.any { it.label == "context_governance (local)" })
        } finally { store.close() }
        val restored = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            withTimeout(5_000) { restored.awaitPersistence() }
            val report = restored.snapshots.value.getValue("s").single().sections.first()
            assertEquals("context_governance (local)", report.label)
            assertTrue(report.preview.contains("USER_TEXT_SHORTENED"))
        } finally { restored.close() }
    }
}
