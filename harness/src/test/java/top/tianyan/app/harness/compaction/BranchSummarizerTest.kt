package top.tianyan.app.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.session.SessionTreeStore

/** 分支摘要（pi BranchSummaryEntry 等价物）：注入、投影与压缩折叠的真实 Room 集成。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BranchSummarizerTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var store: SessionTreeStore
    private lateinit var compaction: CompactionManager
    private lateinit var branchSummarizer: BranchSummarizer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val json = Json { ignoreUnknownKeys = true }
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        store = SessionTreeStore(repository, json, logger)
        compaction = CompactionManager(repository, json, store)
        branchSummarizer = BranchSummarizer(repository, json, logger)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 主干：u1-a1-u2-a2-u3-a3；从 a1 后分叉出备选分支 u2b-a2b。 */
    private suspend fun seedForkedSession(sessionId: String): Pair<String, String> {
        store.ensureMainLane(sessionId)
        store.append(sessionId, UserMessage("u1", 1, "任务：重构网络层"))
        store.append(sessionId, AssistantText("a1", 2, "好的，我先看现状"))
        store.append(sessionId, UserMessage("u2", 3, "方案A：使用 Retrofit"))
        store.append(sessionId, AssistantText("a2", 4, "方案A完成了一半，遇到循环依赖"))
        store.append(sessionId, UserMessage("u3", 5, "继续方案A"))
        store.append(sessionId, AssistantText("a3", 6, "仍然受阻"))
        // 分叉：回到 u2 之前，走方案B
        store.rewindBefore(sessionId, "u2")
        store.append(sessionId, UserMessage("u2b", 7, "方案B：使用 Ktor"))
        store.append(sessionId, AssistantText("a2b", 8, "方案B可行"))
        return "a3" to "a2b"
    }

    @Test
    fun `switching branch summarizes the abandoned segment into the new position`() = runBlocking {
        val (oldLeaf, newLeaf) = seedForkedSession("s-branch")

        val created = branchSummarizer.summarizeAbandonedBranch("s-branch", oldLeaf, newLeaf)

        assertTrue(created)
        val projected = compaction.project("s-branch")
        assertEquals(1, projected.branchSummaries.size)
        assertTrue(projected.branchSummaries.single().contains("分支摘要"))
        assertTrue(projected.branchSummaries.single().contains("方案A"))
        // 摘要层包含分支摘要，供 provider 请求注入
        assertTrue(projected.summaryLayer.contains("分支摘要"))
    }

    @Test
    fun `same abandoned leaf is summarized only once`() = runBlocking {
        val (oldLeaf, newLeaf) = seedForkedSession("s-dedupe")

        assertTrue(branchSummarizer.summarizeAbandonedBranch("s-dedupe", oldLeaf, newLeaf))
        assertFalse(branchSummarizer.summarizeAbandonedBranch("s-dedupe", oldLeaf, newLeaf))

        assertEquals(1, compaction.project("s-dedupe").branchSummaries.size)
    }

    @Test
    fun `short abandoned segment is skipped`() = runBlocking {
        store.ensureMainLane("s-short")
        store.append("s-short", UserMessage("u1", 1, "hi"))
        store.append("s-short", AssistantText("a1", 2, "hello"))
        store.append("s-short", UserMessage("u2", 3, "bye"))
        store.append("s-short", AssistantText("a2", 4, "bye"))
        store.rewindBefore("s-short", "u2")
        store.append("s-short", UserMessage("u2b", 5, "stay"))
        store.append("s-short", AssistantText("a2b", 6, "ok"))

        // 被放弃段只有 u2/a2 共 2 条消息，低于阈值
        assertFalse(branchSummarizer.summarizeAbandonedBranch("s-short", "a2", "a2b"))
        assertTrue(compaction.project("s-short").branchSummaries.isEmpty())
    }

    @Test
    fun `compaction folds branch summaries into the compaction summary`() = runBlocking {
        val (oldLeaf, newLeaf) = seedForkedSession("s-fold")
        assertTrue(branchSummarizer.summarizeAbandonedBranch("s-fold", oldLeaf, newLeaf))

        val context = compaction.project("s-fold")
        val compacted = compaction.compact("s-fold", context, keepFromIndex = 1)

        // 分支摘要被折叠进新的压缩摘要，不再单独注入
        assertTrue(compacted.summary.orEmpty().contains("分支摘要"))
        val reprojected = compaction.project("s-fold")
        assertTrue(reprojected.branchSummaries.isEmpty())
        assertTrue(reprojected.summary.orEmpty().contains("分支摘要"))
    }

    @Test
    fun `branch summary survives on the branch after new messages are appended`() = runBlocking {
        val (oldLeaf, newLeaf) = seedForkedSession("s-append")
        assertTrue(branchSummarizer.summarizeAbandonedBranch("s-append", oldLeaf, newLeaf))

        store.append("s-append", UserMessage("u-next", 9, "继续"))
        store.append("s-append", AssistantText("a-next", 10, "收到"))

        val projected = compaction.project("s-append")
        assertEquals(1, projected.branchSummaries.size)
        // 分支摘要不是消息：消息投影仍为完整分支（u1,a1,u2b,a2b,u-next,a-next）
        assertEquals(6, projected.messages.size)
    }
}
