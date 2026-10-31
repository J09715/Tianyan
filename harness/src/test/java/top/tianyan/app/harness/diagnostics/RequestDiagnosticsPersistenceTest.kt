package top.tianyan.app.harness.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.RequestDiagnosticsRepository
import top.tianyan.app.core.security.SecretRedactor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestDiagnosticsPersistenceTest {
    private class MemoryArchive : RequestDiagnosticsRepository {
        @Volatile var value: String? = null
        @Volatile var failWrites = false
        var onRead: (() -> Unit)? = null
        var onWrite: (() -> Unit)? = null
        override fun read(): String? { onRead?.invoke(); return value }
        override fun write(redactedArchive: String) {
            onWrite?.invoke()
            if (failWrites) throw IOException("failure")
            value = redactedArchive
        }
    }

    private fun record(store: RequestDiagnosticsStore, model: String = "m", session: String = "s", attempt: Int = 1) =
        store.record(session, "op", 1, attempt, "completions", """{"model":"$model","messages":[{"role":"user","content":"private-credential"}]}""", 100, listOf("private-credential"))

    private suspend fun saved(store: RequestDiagnosticsStore) = withTimeout(5_000) { store.awaitPersistence() }
    private fun CountDownLatch.awaitChecked() = check(await(5, TimeUnit.SECONDS)) { "Timed out waiting for archive worker" }

    @Test fun restoresRedactedSnapshotsAndDiffsAfterRestart() = runBlocking {
        val repository = MemoryArchive()
        val first = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            record(first, "before")
            record(first, "after", attempt = 2)
            saved(first)
            assertFalse(repository.value!!.contains("private-credential"))
            assertTrue(repository.value!!.contains("[REDACTED]"))
        } finally { first.close() }
        val restored = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            saved(restored)
            val requests = restored.snapshots.value.getValue("s")
            assertEquals(2, requests.size)
            assertEquals(listOf("model"), RequestContextDiff.between(requests[0], requests[1]).changed)
            assertEquals(RequestArchiveStatus.SAVED, restored.archiveStatus.value)
            restored.removeSessionDurably("s")
        } finally { restored.close() }
        val afterDelete = RequestDiagnosticsStore(SecretRedactor(), repository)
        try { saved(afterDelete); assertTrue(afterDelete.snapshots.value.isEmpty()) }
        finally { afterDelete.close() }
    }

    @Test fun loadMergesFreshRequestsButNeverResurrectsDeletedSessions() = runBlocking {
        val repository = MemoryArchive()
        val seed = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            record(seed, "old"); record(seed, session = "deleted"); saved(seed)
        } finally { seed.close() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        repository.onRead = { started.countDown(); release.awaitChecked() }
        val store = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            started.awaitChecked()
            record(store, "fresh", attempt = 2)
            store.removeSession("deleted")
            record(store, session = "deleted") // Late request callback after deletion.
            release.countDown()
            saved(store)
            assertEquals(listOf(1, 2), store.snapshots.value.getValue("s").map { it.attempt })
            assertFalse(store.snapshots.value.containsKey("deleted"))
            assertFalse(repository.value!!.contains("deleted"))
        } finally { release.countDown(); store.close() }
    }

    @Test fun queuedDeletionWaitsForInFlightWriteAndLatestBoundedState() = runBlocking {
        val repository = MemoryArchive()
        val store = RequestDiagnosticsStore(SecretRedactor(), repository)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            saved(store)
            repository.onWrite = { started.countDown(); release.awaitChecked() }
            record(store)
            started.awaitChecked()
            repeat(20) { record(store, session = "other$it") }
            repeat(20) { record(store, session = "other19", attempt = it) }
            val deleted = async(Dispatchers.Default) { store.removeSessionDurably("s") }
            withTimeout(5_000) { store.snapshots.first { !it.containsKey("s") } }
            assertFalse(deleted.isCompleted)
            release.countDown()
            deleted.await()
            assertEquals(8, store.snapshots.value.size)
            assertEquals(8, store.snapshots.value.getValue("other19").size)
            assertFalse(repository.value!!.contains("\"s\":"))
        } finally { release.countDown(); store.close() }
    }

    @Test fun writeFailureKeepsPreviewAndNextMutationRetries() = runBlocking {
        val repository = MemoryArchive()
        val store = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            saved(store)
            repository.failWrites = true
            record(store)
            try { saved(store); fail("Expected save failure") } catch (_: IllegalStateException) { }
            assertEquals(RequestArchiveStatus.ERROR, store.archiveStatus.value)
            assertTrue(store.snapshots.value.containsKey("s"))
            try { store.removeSessionDurably("s"); fail("Expected deletion failure") } catch (_: IllegalStateException) { }
            repository.failWrites = false
            store.removeSessionDurably("s")
            assertEquals(RequestArchiveStatus.SAVED, store.archiveStatus.value)
            assertFalse(repository.value!!.contains("\"s\":"))
        } finally { store.close() }
    }

    @Test fun corruptAndUnsupportedArchivesAreReportedWithoutRawErrors() = runBlocking {
        listOf("broken", """{"version":2,"sessions":{}}""", """{"version":1,"sessions":{"${"s".repeat(257)}":[]}}""").forEach { value ->
            val repository = MemoryArchive().apply { this.value = value }
            val store = RequestDiagnosticsStore(SecretRedactor(), repository)
            try {
                try { saved(store); fail("Expected restore failure") } catch (_: IllegalStateException) { }
                assertEquals(RequestArchiveStatus.ERROR, store.archiveStatus.value)
                assertTrue(store.snapshots.value.isEmpty())
                record(store)
                saved(store)
                assertEquals(RequestArchiveStatus.SAVED, store.archiveStatus.value)
            } finally { store.close() }
        }
    }

    @Test fun oversizedFieldLabelsAreExplicitlyOmittedAndRestorable() = runBlocking {
        val repository = MemoryArchive()
        val store = RequestDiagnosticsStore(SecretRedactor(), repository)
        try {
            store.record("s", "op", 1, 1, "completions", """{"${"x".repeat(300)}":"value","model":"m"}""", 350)
            saved(store)
            val request = store.snapshots.value.getValue("s").single()
            assertEquals(1, request.omittedSectionCount)
            assertTrue(request.previewTruncated)
            assertEquals(listOf("model"), request.sections.map { it.label })
        } finally { store.close() }
        val restored = RequestDiagnosticsStore(SecretRedactor(), repository)
        try { saved(restored); assertEquals(1, restored.snapshots.value.getValue("s").single().omittedSectionCount) }
        finally { restored.close() }
    }
}
