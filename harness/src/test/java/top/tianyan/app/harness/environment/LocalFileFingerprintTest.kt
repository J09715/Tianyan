package top.tianyan.app.harness.environment

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.harness.WorkspaceFileAccess

class LocalFileFingerprintTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun fingerprintsFilesLargerThanTheMultimodalReadLimit() = runBlocking {
        val root = temporary.newFolder()
        val file = File(root, "large.apk")
        val size = WorkspaceFileAccess.MAX_IMAGE_BYTES + 1
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        val files = WorkspaceFileAccess(root)
        assertTrue(files.readRawBytes("large.apk").isFailure)
        val fingerprint = files.fingerprint("large.apk", size).getOrNull()!!
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }
        assertEquals(size, fingerprint.bytes)
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, fingerprint.sha256)
    }

    @Test fun rejectsEscapesEmptyDirectoriesAndByteLimitExcess() = runBlocking {
        val root = temporary.newFolder()
        File(root, "data.apk").writeBytes(ByteArray(9))
        File(root, "empty.apk").createNewFile()
        val files = WorkspaceFileAccess(root)
        assertTrue(files.fingerprint("data.apk", 8).isFailure)
        assertTrue(files.fingerprint("empty.apk").isFailure)
        assertTrue(files.fingerprint(".").isFailure)
        assertTrue(files.fingerprint("../outside.apk").isFailure)
    }

    @Test fun rejectsAFileChangedDuringObservation() = runBlocking {
        val file = temporary.newFile()
        file.writeText("before")
        val failure = runCatching { fingerprintLocalFile(file, 1024) { file.writeText("changed-size") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun optionalInventoryTreatsMissingDirectoryAsEmpty() = runBlocking {
        val files = WorkspaceFileAccess(temporary.newFolder())
        assertTrue(files.listArtifactEntries("app/build/outputs/apk").getOrNull()!!.isEmpty())
        assertTrue(files.listArtifactEntries("../outside").isFailure)
    }

    @Test fun escapingSymlinksDoNotEnterArtifactInventoryOrFingerprint() = runBlocking {
        val root = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("private") }
        try { Files.createSymbolicLink(File(root, "escape.apk").toPath(), outside.toPath()) }
        catch (unsupported: Exception) { Assume.assumeNoException(unsupported) }
        val files = WorkspaceFileAccess(root)
        assertTrue(files.fingerprint("escape.apk").isFailure)
        assertTrue(files.listArtifactEntries(".").isFailure)
    }
}
