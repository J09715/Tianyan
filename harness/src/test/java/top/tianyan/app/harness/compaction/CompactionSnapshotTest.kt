package top.tianyan.app.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import kotlinx.serialization.builtins.ListSerializer
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.harness.session.SessionTreeStore

/** latestSnapshot 快照 API：UI 折叠透明度横幅的数据源契约（真实 Room）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactionSnapshotTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var compaction: CompactionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val store = SessionTreeStore(
            repository,
            Json,
            AppLogger(context, SensitiveDataRedactor { it }),
        )
        compaction = CompactionManager(repository, Json, store)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedUserMessages(sessionId: String, count: Int) {
        repository.ensureLane(sessionId, "main")
        repeat(count) { index ->
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = "$sessionId-m$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(
                        HarnessMessage.serializer(),
                        UserMessage(id = "$sessionId-m$index", createdAt = index.toLong(), text = "消息 $index"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `no compaction yields null snapshot`() = runBlocking {
        seedUserMessages("s-fresh", 3)
        assertNull(compaction.latestSnapshot("s-fresh"))
    }

    @Test
    fun `provider projection does not apply the live UI entry limit`() = runBlocking {
        seedUserMessages("s-long", 625)

        val projected = compaction.project("s-long")

        assertEquals(625, projected.messages.size)
        assertEquals("s-long-m0", projected.messages.first().id)
    }

    @Test
    fun `snapshot exposes folded count and summary after compact`() = runBlocking {
        val sessionId = "s-snap"
        seedUserMessages(sessionId, 6)
        val projected = compaction.project(sessionId)
        assertEquals(6, projected.messages.size)

        compaction.compact(sessionId, projected, keepFromIndex = 4)
        val snapshot = compaction.latestSnapshot(sessionId)!!
        assertEquals(4, snapshot.foldedMessageCount)
        assertTrue(snapshot.summary.isNotBlank())
        assertTrue(snapshot.createdAt > 0)
    }

    @Test
    fun `latest snapshot across rounds reports cumulative folded count`() = runBlocking {
        val sessionId = "s-cumulative"
        seedUserMessages(sessionId, 10)
        var context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 5)
        context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 2)

        val snapshot = compaction.latestSnapshot(sessionId)!!
        // 多轮压缩下 foldedMessageCount 为历次累计：第一轮折叠 5 条 + 第二轮折叠 2 条
        assertEquals(7, snapshot.foldedMessageCount)
        // 横幅展示的必须是“当前生效”的压缩状态：快照摘要与第二轮投影一致
        assertEquals(compaction.project(sessionId).summary, snapshot.summary)
    }

    @Test
    fun `rolling summary keeps newly folded facts after previous summary reaches cap`() = runBlocking {
        val sessionId = "s-rolling"
        repository.ensureLane(sessionId, "main")
        val latestMarker = "LATEST_DECISION_USE_SQL_QUERY"
        // 折叠段必须真实存在于 lane 上（compact 会按分支前缀对账，拒绝折叠 lane 外的消息）
        listOf(
            UserMessage(id = "latest", createdAt = 1, text = "关键决定：$latestMarker"),
            UserMessage(id = "retained", createdAt = 2, text = "continue"),
        ).forEach { message ->
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = message.id,
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = message.createdAt,
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(HarnessMessage.serializer(), message),
                ),
            )
        }
        val context = CompactedContext(
            summary = "old-context ".repeat(600),
            messages = compaction.project(sessionId).messages,
        )

        val compacted = compaction.compact(sessionId, context, keepFromIndex = 1)

        assertTrue(compacted.summary.orEmpty().contains(latestMarker))
        assertTrue(compacted.summary.orEmpty().length <= 16_000)
    }

    @Test
    fun `mechanical compaction accumulates programmatic file footprints`() = runBlocking {
        val sessionId = "s-file-footprints"
        repository.ensureLane(sessionId, "main")
        suspend fun append(message: HarnessMessage) {
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = message.id,
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = message.createdAt,
                    entryType = "message",
                    customType = null,
                    payloadJson = Json.encodeToString(HarnessMessage.serializer(), message),
                ),
            )
        }
        append(UserMessage("u1", 1L, "改一下网络层"))
        append(ToolCall("c-read", 2L, HarnessTool.READ, buildJsonObject { put("path", "core/network/A.kt") }, rawToolName = "read"))
        append(ToolResult("r-read", 3L, "c-read", success = true, output = "ok"))
        append(ToolCall("c-write", 4L, HarnessTool.WRITE, buildJsonObject { put("path", "core/network/B.kt") }, rawToolName = "write"))
        append(ToolResult("r-write", 5L, "c-write", success = true, output = "ok"))
        append(AssistantText("a1", 6L, "完成"))

        val context = compaction.project(sessionId)
        val compacted = compaction.compact(sessionId, context, keepFromIndex = context.messages.size - 1)

        // 无 summarizer → 机械摘要路径，也必须程序化携带累计文件足迹
        val summary = compacted.summary.orEmpty()
        assertTrue(summary.contains("<read-files>"))
        assertTrue(summary.contains("core/network/A.kt"))
        assertTrue(summary.contains("<modified-files>"))
        assertTrue(summary.contains("core/network/B.kt"))
    }

    @Test
    fun `compact creates structured retainedMessages without nested JSON string`() = runBlocking {
        val sessionId = "s-structured"
        seedUserMessages(sessionId, 5)
        val projected = compaction.project(sessionId)
        compaction.compact(sessionId, projected, keepFromIndex = 3)

        val lane = repository.ensureLane(sessionId, "main")
        val latestCompactionEntry = repository.latestBranchEntryOfType(sessionId, lane.leafId, CompactionManager.ENTRY_TYPE)!!
        val payload = Json.decodeFromString(CompactionPayload.serializer(), latestCompactionEntry.payloadJson)

        // 验证已直接持久化结构化列表，不再将所有保留消息压缩为内嵌转义 JSON 字符串
        assertNull(payload.retainedMessagesJson)
        assertEquals(2, payload.retainedMessages?.size)
        assertEquals("$sessionId-m3", payload.retainedMessages?.first()?.id)

        // 验证 project 正常恢复
        val reprojected = compaction.project(sessionId)
        assertEquals(listOf("$sessionId-m3", "$sessionId-m4"), reprojected.messages.map { it.id })
    }

    @Test
    fun `legacy retainedMessagesJson payload remains backwards compatible`() = runBlocking {
        val sessionId = "s-legacy-payload"
        repository.ensureLane(sessionId, "main")
        val retainedMsg = UserMessage("legacy-1", 10L, "旧格式保留消息")
        val legacyPayload = CompactionPayload(
            sourceLeafId = null,
            summary = "旧版摘要",
            retainedMessagesJson = Json.encodeToString(ListSerializer(HarnessMessage.serializer()), listOf(retainedMsg)),
            compactedMessageCount = 5,
            cumulativeCompactedMessageCount = 5,
            retainedMessageCount = 1,
            estimatedTokensBefore = 500,
            createdAt = 100L,
            sourceWatermarkSequence = 0L,
            retainedMessages = null,
        )
        repository.appendToLane(
            sessionId,
            "main",
            HarnessEntryEntity(
                id = "c-legacy",
                sessionId = sessionId,
                parentId = null,
                createdAt = 100L,
                entryType = CompactionManager.ENTRY_TYPE,
                customType = null,
                payloadJson = Json.encodeToString(CompactionPayload.serializer(), legacyPayload),
            ),
        )

        val projected = compaction.project(sessionId)
        assertEquals("旧版摘要", projected.summary)
        assertEquals(1, projected.messages.size)
        assertEquals("legacy-1", projected.messages.single().id)
        assertEquals("旧格式保留消息", (projected.messages.single() as UserMessage).text)
    }

    @Test
    fun `project degrades gracefully without crashing when compaction payload is corrupted`() = runBlocking {
        val sessionId = "s-corrupt"
        seedUserMessages(sessionId, 3)
        val lane = repository.findLane(sessionId, "main")!!
        repository.appendToLane(
            sessionId,
            "main",
            HarnessEntryEntity(
                id = "c-corrupt",
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = 999L,
                entryType = CompactionManager.ENTRY_TYPE,
                customType = null,
                payloadJson = "{ not-valid-json }",
            ),
        )

        // 损坏或无法解析时降级回退到分支直接投射，绝不抛出未捕获异常瘫痪 HarnessLoop
        val projected = compaction.project(sessionId)
        assertEquals(3, projected.messages.size)
    }
}
