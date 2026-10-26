package top.tianyan.app.harness.subagent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.queue.PromptQueueManager
import top.tianyan.app.harness.session.SessionTreeStore

/** 后台子代理注册表（DSH continuable 借鉴项）：完成投递 / 保活查询 / 会话取消。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundSubagentRegistryTest {

    private lateinit var database: AppDatabase
    private lateinit var queue: PromptQueueManager
    private lateinit var registry: BackgroundSubagentRegistry

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repository: HarnessRuntimeRepository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        queue = PromptQueueManager(repository, Json, SessionTreeStore(repository, Json, logger))
        registry = BackgroundSubagentRegistry(queue, logger)
    }

    @After fun tearDown() { database.close() }

    @Test
    fun `completed batch enqueues steer summary into parent session`() = runBlocking {
        val done = CompletableDeferred<Unit>()
        registry.launch("s", 2) {
            done.await()
            true to "汇总内容"
        }
        assertTrue(registry.hasActive)
        done.complete(Unit)
        withRetry { !registry.hasActive && steered("s").isNotEmpty() }
        assertTrue(steered("s").contains("【后台子任务已完成】"))
        assertTrue(steered("s").contains("汇总内容"))
    }

    @Test
    fun `failing batch degrades to failure summary instead of crashing`() = runBlocking {
        registry.launch("s", 1) { error("批次崩溃") }
        withRetry { !registry.hasActive }
        withRetry { steered("s").contains("批次崩溃") }
        assertTrue(steered("s").contains("【后台子任务已完成】"))
    }

    @Test
    fun `cancelForSession cancels running batch and skips delivery`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        registry.launch("s", 1) {
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        started.await()
        assertTrue(registry.hasActive)
        registry.cancelForSession("s")
        withRetry { !registry.hasActive }
        withRetry(500) { steered("s").isEmpty() }
    }

    @Test
    fun `activeCountForSession tracks per session`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        registry.launch("a", 1) { gate.await(); true to "x" }
        registry.launch("b", 1) { gate.await(); true to "x" }
        withRetry { registry.activeCountForSession("a") == 1 && registry.activeCountForSession("b") == 1 }
        gate.complete(Unit)
        withRetry { !registry.hasActive }
        assertEquals(0, registry.activeCountForSession("a"))
        assertEquals(0, registry.activeCountForSession("b"))
    }

    // ---- helpers ----

    /** STEER 队列内容（durable queue 落库后经 list 读回，验证真实投递路径）。 */
    private suspend fun steered(sessionId: String): String =
        queue.list(sessionId, PromptQueue.STEER).joinToString("\n---\n") { it.second.text }

    private suspend fun withRetry(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in $timeoutMs ms")
            kotlinx.coroutines.delay(25)
        }
    }
}
