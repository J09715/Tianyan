package top.tianyan.app.harness.session

import java.lang.reflect.Proxy
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.HarnessRuntimeRepository
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.junit.Assert
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessLaneEntity
import top.tianyan.app.harness.projection.CurrentSessionTracker
import top.tianyan.app.harness.projection.SessionMessageProjector

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTreeCancellationTest {
    @Test
    fun cancelledRepositoryReadsDoNotBecomeEmptyHistory() = runBlocking {
        val cancellation = CancellationException("repository read cancelled")
        val repository = Proxy.newProxyInstance(
            HarnessRuntimeRepository::class.java.classLoader,
            arrayOf(HarnessRuntimeRepository::class.java),
        ) { _, _, _ -> throw cancellation } as HarnessRuntimeRepository
        val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
        val store = SessionTreeStore(repository, Json, logger)
        val reads: List<suspend () -> Any?> = listOf(
            { store.load("session") },
            { store.loadAt("session", "leaf") },
            { store.loadStrict("session") },
            { store.laneLeafId("session") },
            { store.appendRecallBlock("session", "user", "recall") },
        )
        for (read in reads) {
            try {
                read()
                fail("Cancellation was converted to a fallback result")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }
    }

    @Test fun strictHistoryPropagatesStorageFailureThroughTheMessageProjector() = runBlocking {
        val failure = IOException("storage read failed")
        for (failedRead in listOf("findLane", "branchTail")) {
            val unused = Proxy.newProxyInstance(HarnessRuntimeRepository::class.java.classLoader,
                arrayOf(HarnessRuntimeRepository::class.java)) { _, method, _ ->
                error("Unexpected storage call: ${method.name}")
            } as HarnessRuntimeRepository
            val repository = object : HarnessRuntimeRepository by unused {
                override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? {
                    if (failedRead == "findLane") throw failure
                    return HarnessLaneEntity("session", "main", "leaf", updatedAt = 1)
                }
                override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> =
                    throw failure
            }
            val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
            val store = SessionTreeStore(repository, Json, logger)
            val projector = SessionMessageProjector(store,
                CurrentSessionTracker())
            try { projector.loadPersistedHistory("session"); fail("must propagate") }
            catch (caught: IOException) { Assert.assertEquals(failure.message, caught.message) }
        }
    }

    @Test fun strictHistoryOfMissingSessionIsReadOnlyAndEmpty() = runBlocking {
        val repository = Proxy.newProxyInstance(HarnessRuntimeRepository::class.java.classLoader,
            arrayOf(HarnessRuntimeRepository::class.java)) { _, method, _ ->
            Assert.assertEquals("findLane", method.name)
            null
        } as HarnessRuntimeRepository
        val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
        Assert.assertTrue(SessionTreeStore(repository, Json, logger).loadStrict("missing").isEmpty())
    }

    @Test fun strictHistoryDoesNotDropCorruptOrUnreadableBlobMessages() = runBlocking {
        for (payload in listOf("invalid-json", "@@TIANYAN_BLOB@@:missing.json")) {
            val repository = Proxy.newProxyInstance(HarnessRuntimeRepository::class.java.classLoader,
                arrayOf(HarnessRuntimeRepository::class.java)) { _, method, _ -> when (method.name) {
                "findLane" -> HarnessLaneEntity("session", "main", "leaf", updatedAt = 1)
                "branchTail" -> listOf(
                    HarnessEntryEntity(id = "state", sessionId = "session", parentId = null,
                        createdAt = 1, entryType = "custom", payloadJson = "state-is-not-a-message"),
                    HarnessEntryEntity(id = "leaf", sessionId = "session", parentId = "state",
                        createdAt = 2, entryType = "message", payloadJson = payload))
                else -> error("Unexpected storage call: ${method.name}")
            } } as HarnessRuntimeRepository
            val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
            try { SessionTreeStore(repository, Json, logger).loadStrict("session"); fail("must not drop unreadable message") }
            catch (_: SerializationException) { }
        }
    }

    @Test fun strictHistoryCannotTurnLateStorageFailureIntoSuccessAfterCancellation() = runBlocking {
        val request = async {
            val job = currentCoroutineContext()[Job]!!
            val unused = Proxy.newProxyInstance(
                HarnessRuntimeRepository::class.java.classLoader, arrayOf(HarnessRuntimeRepository::class.java)) {
                    _, _, _ -> error("unused")
                } as HarnessRuntimeRepository
            val repository = object : HarnessRuntimeRepository by unused {
                override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? =
                    withContext(NonCancellable) {
                        job.cancel(); throw IOException("late failure")
                    }
            }
            val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
            SessionTreeStore(repository, Json, logger).loadStrict("session")
        }
        try { request.await(); fail("must cancel") } catch (_: CancellationException) { }
    }
}
