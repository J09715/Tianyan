package top.tianyan.app.harness.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.*
import top.tianyan.app.harness.compaction.CompactionManager
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionProjectionCancellationTest {
    private fun manager(repository: HarnessRuntimeRepository): CompactionManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return CompactionManager(repository, Json, SessionTreeStore(repository, Json, AppLogger(context, SensitiveDataRedactor { it })))
    }

    private fun unusedRepository(failure: Throwable = AssertionError("unexpected read")): HarnessRuntimeRepository = Proxy.newProxyInstance(
        HarnessRuntimeRepository::class.java.classLoader, arrayOf(HarnessRuntimeRepository::class.java),
    ) { _, _, _ -> throw failure } as HarnessRuntimeRepository

    @Test fun `explicit repository cancellation is preserved by context inspection and lightweight snapshot`() = runBlocking {
        val cancellation = CancellationException("cancel read")
        val manager = manager(unusedRepository(cancellation))
        for (read in listOf<suspend () -> Any?>(
            { manager.inspect("session") }, { manager.project("session") }, { manager.latestSnapshot("session") })) {
            assertSame(cancellation, runCatching { read() }.exceptionOrNull())
        }
    }

    @Test fun `repository failure cannot become an empty successful provider context`() = runBlocking {
        val failure = IOException("storage unavailable")
        val repository = object : HarnessRuntimeRepository by unusedRepository() {
            override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? = throw failure
        }
        val manager = manager(repository)
        assertSame(failure, runCatching { manager.inspect("session") }.exceptionOrNull())
        assertSame(failure, runCatching { manager.project("session") }.exceptionOrNull())
        // The optional UI banner remains best-effort; provider context reads are strict.
        assertNull(manager.latestSnapshot("session"))
    }

    @Test fun `cancelled non cancellable lane read cannot become an empty context or null snapshot`() = runBlocking {
        for (snapshot in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : HarnessRuntimeRepository by unusedRepository() {
                override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? = withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    null
                }
            }
            val manager = manager(repository)
            var returned = false
            val job = launch {
                if (snapshot) manager.latestSnapshot("session") else manager.inspect("session")
                returned = true
            }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertFalse(returned)
        }
    }

    @Test fun `cancelled non cancellable branch read cannot publish stale messages`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : HarnessRuntimeRepository by unusedRepository() {
            override suspend fun findLane(sessionId: String, laneName: String) = HarnessLaneEntity(sessionId, laneName, "leaf", updatedAt = 1)
            override suspend fun latestBranchEntryOfType(sessionId: String, leafId: String?, entryType: String): HarnessEntryEntity? = null
            override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                emptyList()
            }
        }
        var returned = false
        val job = launch { manager(repository).inspect("session"); returned = true }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(returned)
    }

    @Test fun `repository failure after cancellation cannot mask the cancelled read`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : HarnessRuntimeRepository by unusedRepository() {
            override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                throw IOException("late storage failure")
            }
        }
        var caught: Throwable? = null
        val job = launch {
            try { manager(repository).inspect("session") } catch (failure: Throwable) { caught = failure }
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(caught is CancellationException)
    }
}
