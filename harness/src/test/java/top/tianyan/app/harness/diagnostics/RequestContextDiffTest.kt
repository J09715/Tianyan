package top.tianyan.app.harness.diagnostics

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.security.SecretRedactor

class RequestContextDiffTest {
    private val store = RequestDiagnosticsStore(SecretRedactor())

    private fun capture(body: String, operation: String = "op", secrets: List<String> = emptyList()): RequestContextSnapshot {
        store.record("s", operation, 0, 1, "completions", body, body.toByteArray().size.toLong(), secrets)
        return store.snapshots.value.getValue("s").last()
    }

    @Test fun comparesModelToolsMessagesAndRequestOptionsAcrossRuns() {
        val previous = capture("""{"model":"a","messages":[{"role":"user","content":"hello"}],"tools":[{"name":"read"}],"stream_options":{}}""")
        val current = capture("""{"model":"b","messages":[{"role":"user","content":"hello"},{"role":"assistant","content":"hi"}],"tools":[{"name":"write"}],"max_tokens":100}""", "next-op")
        val diff = RequestContextDiff.between(previous, current)
        assertEquals(listOf("messages[2] assistant", "max_tokens"), diff.added)
        assertEquals(listOf("stream_options"), diff.removed)
        assertEquals(listOf("model", "tools"), diff.changed)
        assertEquals(1, diff.unchangedCount)
        assertTrue(diff.complete)
    }

    @Test fun detectsChangesBeyondDisplayedSectionPrefix() {
        val prefix = "x".repeat(RequestDiagnosticsStore.MAX_SECTION_CHARS + 100)
        val previous = capture("""{"messages":[{"role":"user","content":"${prefix}before"}]}""")
        val current = capture("""{"messages":[{"role":"user","content":"${prefix}after"}]}""")
        assertEquals(previous.sections.single().preview, current.sections.single().preview)
        assertTrue(current.sections.single().previewTruncated)
        val diff = RequestContextDiff.between(previous, current)
        assertEquals(listOf("messages[1] user"), diff.changed)
        assertTrue(diff.complete)
    }

    @Test fun detectsChangesAfterEntirePreviewBudgetIsUsed() {
        val messages = (1..8).joinToString(",") { """{"role":"user","content":"${"x".repeat(9_000)}"}""" }
        val previous = capture("""{"messages":[$messages],"model":"before"}""")
        val current = capture("""{"messages":[$messages],"model":"after"}""")
        assertEquals("", current.sections.last().preview)
        assertTrue(current.sections.last().previewTruncated)
        assertEquals(listOf("model"), RequestContextDiff.between(previous, current).changed)
        assertTrue(current.sections.sumOf { it.preview.length } <= RequestDiagnosticsStore.MAX_PREVIEW_CHARS)
    }

    @Test fun credentialAndOpaqueMediaChangesAreExcludedBeforeFingerprinting() {
        val previous = capture("""{"input":[{"role":"user","content":"credential-first"},{"type":"input_image","image_url":"data:image/png;base64,AAAA"}],"encrypted_content":"opaque-first"}""",
            secrets = listOf("credential-first"))
        val current = capture("""{"input":[{"role":"user","content":"credential-second"},{"type":"input_image","image_url":"data:image/png;base64,BBBB"}],"encrypted_content":"opaque-second"}""",
            secrets = listOf("credential-second"))
        assertTrue(RequestContextDiff.between(previous, current).changed.isEmpty())
        assertFalse(current.toString().contains("credential-second"))
        assertFalse(current.toString().contains("opaque-second"))
        assertFalse(current.toString().contains("BBBB"))
    }

    @Test fun marksComparisonIncompleteWhenFieldsAreOmitted() {
        val body = "{" + (0..RequestDiagnosticsStore.MAX_SECTIONS).joinToString(",") { "\"field$it\":\"value\"" } + "}"
        val previous = capture(body)
        val current = capture(body)
        assertEquals(1, current.omittedSectionCount)
        assertEquals(RequestDiagnosticsStore.MAX_SECTIONS, current.sections.size)
        assertFalse(RequestContextDiff.between(previous, current).complete)
    }

    @Test fun rejectsComparisonAcrossSessions() {
        val snapshot = capture("{}")
        assertTrue(runCatching { RequestContextDiff.between(snapshot, snapshot.copy(sessionId = "other")) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
