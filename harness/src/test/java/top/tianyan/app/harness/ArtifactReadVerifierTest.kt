package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.runtime.environment.FileFingerprint

class ArtifactReadVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `read verifies historical version and fails closed after modification or deletion`() = runBlocking {
        val files = WorkspaceFileAccess(temporary.newFolder())
        val path = ToolOutputSpillStore.spill(files, "read", "first\nsecond")!!
        val artifact = ToolOutputArtifact.capture(path, "first\nsecond")
        val backend = WorkspaceToolBackend({ files })
        val request = WorkspaceToolRequest(HarnessTool.READ, buildJsonObject {
            put("path", path); put("expected_sha256", artifact.sha256); put("offset", 2); put("limit", 1)
        }, "s", "")
        val verified = backend.execute(request)
        assertTrue(verified.success)
        assertTrue(verified.output.contains("second"))
        assertFalse(verified.output.contains("first"))
        assertTrue(verified.output.contains("artifact_status=VERIFIED"))
        files.write(path, "replacement")
        val changed = backend.execute(request)
        assertFalse(changed.success)
        assertTrue(changed.output.contains("artifact_status=CHANGED"))
        assertFalse(changed.output.contains("replacement"))
        files.delete(path)
        assertTrue(backend.execute(request).output.contains("artifact_status=UNAVAILABLE"))
    }

    @Test fun `mutation during read discards payload`() = runBlocking {
        val files = WorkspaceFileAccess(temporary.newFolder())
        val expected = "a".repeat(64)
        var observations = 0
        val racing = object : WorkspaceToolOperations by files {
            override suspend fun fingerprint(path: String, maxBytes: Long): AppResult<FileFingerprint> =
                AppResult.Success(FileFingerprint(if (observations++ == 0) expected else "b".repeat(64), 10))
        }
        val args = buildJsonObject { put("expected_sha256", expected) }
        val changed = ArtifactReadVerifier.read(racing, "output", args) { WorkspaceToolOutcome(true, "private payload") }
        assertFalse(changed.success)
        assertFalse(changed.output.contains("private payload"))
    }

    @Test fun `unsupported fingerprint never reads an unverified substitute`() = runBlocking {
        val files = WorkspaceFileAccess(temporary.newFolder())
        files.write("output", "host-side substitute")
        val unsupported = object : WorkspaceToolOperations by files {
            override suspend fun fingerprint(path: String, maxBytes: Long): AppResult<FileFingerprint> =
                AppResult.Failure(top.tianyan.app.core.common.result.AppError(
                    top.tianyan.app.core.common.result.ErrorCode.IO, "unsupported"))
        }
        var reads = 0
        val result = ArtifactReadVerifier.read(unsupported, "output",
            buildJsonObject { put("expected_sha256", "a".repeat(64)) }) {
            reads++
            WorkspaceToolOutcome(true, "host-side substitute")
        }
        assertFalse(result.success)
        assertTrue(result.output.contains("artifact_status=UNAVAILABLE"))
        assertFalse(result.output.contains("host-side substitute"))
        assertEquals(0, reads)
    }

    @Test fun `history exposes bounded references and media limitation without copying inline data`() {
        val artifact = ToolOutputArtifact.capture(".tianyan-outputs/tool-a.txt", "archived output")
        val result = ToolResult("r", 1, "c", true, "preview", imageDataUrl = "data:image/png;base64,private",
            metadata = mapOf(ToolOutputArtifact.METADATA_KEY to Json.encodeToString(ToolOutputArtifact.serializer(), artifact)))
        val page = HistoryReadPage.render(listOf(result), 0, 8_000)
        assertTrue(page.contains("status=UNVERIFIED"))
        assertTrue(page.contains(artifact.sha256))
        assertTrue(page.contains("history_media=NOT_RESTORED"))
        assertFalse(page.contains("base64"))
        val user = HistoryReadPage.render(listOf(UserMessage("u", 0, "photo", imageUrls = listOf("private-uri"))), 0, 8_000)
        assertTrue(user.contains("history_media=NOT_RESTORED"))
        assertFalse(user.contains("private-uri"))
    }
}
