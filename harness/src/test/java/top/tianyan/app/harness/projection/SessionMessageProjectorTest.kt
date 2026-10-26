package top.tianyan.app.harness.projection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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
import top.tianyan.app.core.database.HarnessQueueItemEntity
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.queue.PromptQueueManager

/**
 * 会话消息投影器集成测试：真实 Room 持久化 + SessionTreeStore 全链路，
 * 验证实时流发布、历史合并、前台镜像与流式上屏语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionMessageProjectorTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore
    private lateinit var tracker: CurrentSessionTracker
    private lateinit var projector: SessionMessageProjector

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SessionTreeStore(
            repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
            json = Json { ignoreUnknownKeys = true },
            logger = AppLogger(context, SensitiveDataRedactor { it }),
        )
        tracker = CurrentSessionTracker()
        projector = SessionMessageProjector(store, tracker)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun user(id: String, text: String) = UserMessage(id = id, createdAt = 10L, text = text)
    private fun assistant(id: String, text: String) = AssistantText(id = id, createdAt = 11L, text = text)

    @Test
    fun `append persists and publishes into live flow`() = runBlocking {
        projector.seedEmpty("s1")
        val msg = user("m1", "你好")
        projector.append("s1", msg)

        assertEquals(listOf(msg), projector.snapshot("s1"))
        // 持久化后可从历史读回（异步合并路径之外的基础保障）
        assertEquals(1, store.load("s1").size)
    }

    @Test
    fun `preparedForLoad reuses existing flow without reload`() = runBlocking {
        projector.seedEmpty("s1")
        val same = projector.preparedForLoad("s1")
        assertTrue(same === projector.messagesFlow("s1"))
    }

    @Test
    fun `foreground mirroring only tracks current session`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")

        val msg = assistant("a1", "回复")
        projector.publishPersisted("s1", msg)
        assertEquals(listOf(msg), projector.foregroundMessages.value)

        // 切走前台后再追加，不应污染新前台镜像
        tracker.setCurrent("s2")
        projector.append("s1", user("m2", "第二条"))
        assertEquals(listOf(msg), projector.foregroundMessages.value)
    }

    @Test
    fun `replaceAll swaps live list for regen and rewind paths`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.append("s1", user("m1", "问题"))
        projector.append("s1", assistant("a1", "旧答案"))

        projector.replaceAll("s1", listOf(user("m1", "问题")))
        assertEquals(listOf(user("m1", "问题")), projector.snapshot("s1"))
        assertEquals(listOf(user("m1", "问题")), projector.foregroundMessages.value)
    }

    @Test
    fun `streamText preserves prior reasoning of the same bubble`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.streamReasoning("s1", "b1", 5L, "思考中…")
        projector.streamText("s1", "b1", 5L, "答案草稿")

        val bubble = projector.snapshot("s1").single() as AssistantText
        assertEquals("答案草稿", bubble.text)
        assertEquals("思考中…", bubble.reasoning)

        projector.remove("s1", "b1")
        assertTrue(projector.snapshot("s1").isEmpty())
    }

    @Test
    fun `publishPersisted updates in place when id exists`() = runBlocking {
        tracker.setCurrent("s1")
        projector.seedEmpty("s1")
        projector.append("s1", assistant("a1", "v1"))
        val updated = AssistantText(id = "a1", createdAt = 11L, text = "v2", totalMs = 8L)
        projector.publishPersisted("s1", updated)

        assertEquals(listOf(updated), projector.snapshot("s1"))
    }

    @Test
    fun `live projection stays bounded while durable history keeps growing`() = runBlocking {
        tracker.setCurrent("long")
        projector.seedEmpty("long")
        repeat(SessionTreeStore.MAX_LIVE_ENTRIES + 25) { index ->
            projector.append("long", UserMessage(id = "m$index", createdAt = index.toLong(), text = "message $index"))
        }

        val live = projector.snapshot("long")
        assertEquals(SessionTreeStore.MAX_LIVE_ENTRIES, live.size)
        assertEquals("m25", live.first().id)
        assertEquals(SessionTreeStore.MAX_LIVE_ENTRIES + 25, database.harnessRuntimeDao().listEntries("long").size)
    }

    @Test
    fun `live window also honors a byte budget for heavy messages`() = runBlocking {
        tracker.setCurrent("heavy")
        projector.seedEmpty("heavy")

        // 12 条各 1M 字符的 tool_result：条数远未到 MAX_LIVE_ENTRIES，但字符总量 12M 超过预算。
        val big = "x".repeat(1024 * 1024)
        val messages = (0 until 12).map { index ->
            ToolResult(
                id = "r$index",
                createdAt = index.toLong(),
                toolCallId = "c$index",
                success = true,
                output = big,
            )
        }
        projector.replaceAll("heavy", messages)

        val live = projector.snapshot("heavy")
        assertTrue(live.size < messages.size)
        // 永远保留最新一条，且窗口总量不超预算。
        assertEquals("r11", live.last().id)
        val retainedChars = live.sumOf { (it as ToolResult).output.length }
        assertTrue(retainedChars <= SessionMessageProjector.MAX_LIVE_CHARS)
    }

    @Test
    fun `history search matches unordered terms across a tool exchange`() = runBlocking {
        store.append("history", ToolCall(
            id = "call-install",
            createdAt = 1L,
            tool = HarnessTool.BASE,
            args = buildJsonObject { put("command", "adb install RelayG.apk") },
        ))
        store.append("history", ToolResult(
            id = "result-install",
            createdAt = 2L,
            toolCallId = "call-install",
            success = true,
            output = "Performing Streamed Install\nSuccess",
        ))

        val matches = store.search("history", "Success RelayG", limit = 8)

        assertTrue(matches.any { it.id == "call-install" })
        assertTrue(matches.any { it.id == "result-install" })
    }

    @Test
    fun `history search indexes read the same messages including tool entries`() = runBlocking {
        store.append("history-index", UserMessage(id = "first", createdAt = 1L, text = "needle first"))
        store.append("history-index", AssistantText(id = "reply", createdAt = 2L, text = "other"))
        store.append("history-index", ToolCall(
            id = "call", createdAt = 3L, tool = HarnessTool.BASE,
            args = buildJsonObject { put("command", "echo other") },
        ))
        store.append("history-index", ToolResult(
            id = "result", createdAt = 4L, toolCallId = "call", success = true, output = "other",
        ))
        store.append("history-index", UserMessage(id = "last", createdAt = 5L, text = "needle last"))

        val hits = store.searchIndexed("history-index", "needle")

        assertEquals(listOf(4, 0), hits.map { it.index })
        hits.forEach { hit ->
            assertEquals(hit.message.id, store.read("history-index", index = hit.index)?.id)
        }
    }

    @Test
    fun `history read of a tool call also returns its result`() = runBlocking {
        store.append("read-related", ToolCall(
            id = "call",
            createdAt = 1L,
            tool = HarnessTool.BASE,
            args = buildJsonObject { put("command", "adb install app.apk") },
        ))
        store.append("read-related", ToolResult(
            id = "result",
            createdAt = 2L,
            toolCallId = "call",
            success = true,
            output = "Success",
        ))

        assertEquals(listOf("call", "result"), store.readWithRelated("read-related", messageId = "call").map { it.id })
    }

    @Test
    fun `history read returns the same call-rooted group for call and result anchors`() = runBlocking {
        store.append("read-related-groups", ToolCall(
            id = "call",
            createdAt = 1L,
            tool = HarnessTool.WRITE,
            args = buildJsonObject { put("path", "a.txt") },
        ))
        store.append("read-related-groups", ToolResult(
            id = "pending",
            createdAt = 2L,
            toolCallId = "call",
            success = false,
            output = "等待用户批准",
            awaitingApproval = true,
        ))
        store.append("read-related-groups", ToolResult(
            id = "result",
            createdAt = 3L,
            toolCallId = "call",
            success = true,
            output = "ok",
        ))

        val expected = listOf("call", "pending", "result")
        assertEquals(expected, store.readWithRelated("read-related-groups", messageId = "call").map { it.id })
        assertEquals(expected, store.readWithRelated("read-related-groups", messageId = "pending").map { it.id })
        assertEquals(expected, store.readWithRelated("read-related-groups", messageId = "result").map { it.id })
    }

    @Test
    fun `invalid queued prompt is removed when listed or consumed`() = runBlocking {
        val repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val queue = PromptQueueManager(repository, Json, store)
        repository.enqueue(HarnessQueueItemEntity(
            id = "bad", sessionId = "queue", laneName = "main", operationId = null,
            queueType = PromptQueue.FOLLOW_UP.id, createdAt = 1L, payloadJson = "{broken",
        ))
        assertTrue(queue.list("queue", PromptQueue.FOLLOW_UP).isEmpty())
        assertTrue(repository.listQueue("queue", "main", PromptQueue.FOLLOW_UP.id).isEmpty())

        repository.enqueue(HarnessQueueItemEntity(
            id = "bad2", sessionId = "queue", laneName = "main", operationId = null,
            queueType = PromptQueue.FOLLOW_UP.id, createdAt = 2L, payloadJson = "{broken",
        ))
        assertTrue(queue.consume("queue", PromptQueue.FOLLOW_UP).isEmpty())
        assertTrue(repository.listQueue("queue", "main", PromptQueue.FOLLOW_UP.id).isEmpty())
    }
}
