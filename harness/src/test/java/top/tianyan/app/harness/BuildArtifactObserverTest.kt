package top.tianyan.app.harness

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.common.result.*
import top.tianyan.app.runtime.environment.*

class BuildArtifactObserverTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun differentiatesNewChangedAndUnchangedVersionsUsingRealBytes() = runBlocking {
        val root = temporary.newFolder()
        val directory = File(root, "app/build/outputs/apk/debug").apply { mkdirs() }
        File(directory, "old.apk").writeText("old-version")
        File(directory, "change.apk").writeText("before")
        val observer = BuildArtifactObserver(WorkspaceFileAccess(root), "")
        val before = observer.scan()
        File(directory, "change.apk").writeText("after")
        File(directory, "new.apk").writeText("new-version")
        val batch = observer.after(before, observer.scan(), 10)
        assertTrue(batch.complete)
        val byPath = batch.files.associateBy { it.path.substringAfterLast('/') }
        assertEquals(FileArtifactOrigin.UNCHANGED_AFTER_COMMAND, byPath["old.apk"]!!.origin)
        assertEquals(FileArtifactOrigin.CHANGED_AFTER_COMMAND, byPath["change.apk"]!!.origin)
        assertEquals(FileArtifactOrigin.NEW_AFTER_COMMAND, byPath["new.apk"]!!.origin)
        assertTrue(batch.files.all { it.kind == FileArtifactKind.APK })
    }

    @Test fun incompleteBeforeScanNeverPromotesAnObservedFileToNew() = runBlocking {
        val root = temporary.newFolder()
        val file = File(root, "app/build/outputs/apk/app.apk").apply { parentFile.mkdirs(); writeText("existing") }
        val observer = BuildArtifactObserver(WorkspaceFileAccess(root), "")
        val after = observer.scan()
        val batch = observer.after(BuildArtifactObserver.Inventory(emptyMap(), emptySet(), false), after, 10)
        assertFalse(batch.complete)
        assertEquals(FileArtifactOrigin.OBSERVED_AFTER_COMMAND, batch.files.single().origin)
        assertEquals(file.length(), batch.files.single().bytes)
    }

    @Test fun candidateLimitMarksTheInventoryIncomplete() = runBlocking {
        val root = temporary.newFolder()
        val directory = File(root, "build/outputs/apk").apply { mkdirs() }
        repeat(20) { File(directory, "app-$it.apk").writeText("data-$it") }
        val inventory = BuildArtifactObserver(WorkspaceFileAccess(root), "").scan()
        assertFalse(inventory.complete)
        assertEquals(ToolFileArtifacts.MAX_FILES, inventory.versions.size)
    }

    @Test fun unavailableFingerprintDoesNotCreateAPathOnlyArtifact() = runBlocking {
        val root = temporary.newFolder()
        File(root, "build/outputs/apk/app.apk").apply { parentFile.mkdirs(); writeText("data") }
        val files = object : ExecutionFiles by WorkspaceFileAccess(root) {
            override suspend fun fingerprint(path: String, maxBytes: Long): AppResult<FileFingerprint> = AppResult.Failure(AppError(ErrorCode.IO, "unavailable"))
        }
        val observed = BuildArtifactObserver(files, "").scan()
        assertFalse(observed.complete)
        assertTrue(observed.versions.isEmpty())
        assertTrue("build/outputs/apk/app.apk" in observed.present)
    }

    @Test fun byteBudgetAlsoBoundsFailedFingerprintAttempts() = runBlocking {
        val root = temporary.newFolder()
        val directory = File(root, "build/outputs/apk").apply { mkdirs() }
        repeat(5) { File(directory, "app-$it.apk").writeText("data") }
        val attempts = mutableListOf<Long>()
        val files = object : ExecutionFiles by WorkspaceFileAccess(root) {
            override suspend fun fingerprint(path: String, maxBytes: Long): AppResult<FileFingerprint> {
                attempts += maxBytes; return AppResult.Failure(AppError(ErrorCode.IO, "failed after reading"))
            }
        }
        assertFalse(BuildArtifactObserver(files, "").scan().complete)
        assertEquals(2, attempts.size)
        assertEquals(256L * 1024 * 1024, attempts.sum())
    }

    @Test fun cancelledInventoryPropagatesAndNeverBecomesAnEmptyCompleteScan() = runBlocking {
        val root = temporary.newFolder()
        val files = object : ExecutionFiles by WorkspaceFileAccess(root) {
            override suspend fun listArtifactEntries(path: String): AppResult<List<ExecutionFileEntry>> = throw CancellationException("cancelled")
        }
        assertTrue(runCatching { BuildArtifactObserver(files, "").scan() }.exceptionOrNull() is CancellationException)
    }
}
