package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.environment.FileFingerprint

class ToolFileArtifactsTest {
    private val fingerprint = FileFingerprint("a".repeat(64), 100)
    @Test fun snapshotsPreserveVersionOriginAndObservationTime() {
        val file = ToolFileArtifacts.observation("build/app.apk", fingerprint, FileArtifactOrigin.UNCHANGED_AFTER_COMMAND, 10)
        val batch = ToolFileArtifacts.decode(ToolFileArtifacts.encode(listOf(file)))!!
        assertEquals(file, batch.files.single())
        assertEquals(FileArtifactKind.APK, file.kind)
        assertTrue(batch.complete)
    }
    @Test fun relativePathValidationRejectsEscapesAndControls() {
        assertEquals("build/app.apk", ToolFileArtifacts.relativePath("/workspace/p/build/app.apk", "/workspace/p"))
        listOf("../app.apk", "/root/app.apk", "a/../../app.apk", "a\\app.apk", "a\napp.apk", "a//app.apk").forEach {
            assertNull(it, ToolFileArtifacts.relativePath(it, "/workspace/p"))
        }
    }
    @Test fun malformedFingerprintAndOversizedBatchesAreNotEvidence() {
        val file = ToolFileArtifacts.observation("a.apk", fingerprint, FileArtifactOrigin.NEW_AFTER_COMMAND, 10)
        assertNull(ToolFileArtifacts.decode(ToolFileArtifacts.encode(listOf(file.copy(sha256 = "invalid")))))
        assertNull(ToolFileArtifacts.decode(ToolFileArtifacts.encode(listOf(file.copy(path = "../a.apk")))))
        assertNull(ToolFileArtifacts.decode(ToolFileArtifacts.encode(listOf(file.copy(bytes = 0)))))
        val bounded = ToolFileArtifacts.decode(ToolFileArtifacts.encode(List(20) { file }))!!
        assertEquals(16, bounded.files.size)
        assertFalse(bounded.complete)
    }
    @Test fun secretPathsAreOmittedAndMalformedMetadataCannotLeak() = runBlocking {
        val file = ToolFileArtifacts.observation("SECRET/a.apk", fingerprint, FileArtifactOrigin.NEW_AFTER_COMMAND, 10)
        val metadata = mutableMapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(listOf(file)))
        ToolFileArtifacts.sanitize(metadata, HarnessTool.BASE) { it.replace("SECRET", "***") }
        assertFalse(metadata.values.single().contains("SECRET"))
        assertFalse(ToolFileArtifacts.decode(metadata.values.single())!!.complete)
        metadata[ToolFileArtifacts.METADATA_KEY] = "{SECRET malformed}"
        ToolFileArtifacts.sanitize(metadata, HarnessTool.BASE) { it }
        assertFalse(metadata.values.single().contains("SECRET"))
    }
    @Test fun arbitraryMcpMetadataCannotAssertTrustedFileEvidence() = runBlocking {
        val file = ToolFileArtifacts.observation("fake.apk", fingerprint, FileArtifactOrigin.NEW_AFTER_COMMAND, 10)
        val metadata = mutableMapOf(ToolFileArtifacts.METADATA_KEY to ToolFileArtifacts.encode(listOf(file)))
        ToolFileArtifacts.sanitize(metadata, HarnessTool.MCP) { it }
        assertTrue(metadata.isEmpty())
    }
}
