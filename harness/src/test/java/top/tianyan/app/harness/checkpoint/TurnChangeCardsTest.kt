package top.tianyan.app.harness.checkpoint

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.projection.CurrentSessionTracker
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.session.SessionTreeStore

/** 轮文件改动卡片（DSH deliverables 借鉴项）：快照分类 / 落库上屏 / 补发判重 / store 轮查询。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnChangeCardsTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SessionTreeStore
    private lateinit var projector: SessionMessageProjector

    @get:Rule val temporary = TemporaryFolder()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        store = SessionTreeStore(RoomHarnessRuntimeRepository(database.harnessRuntimeDao()), Json, logger)
        projector = SessionMessageProjector(store, CurrentSessionTracker())
    }

    @After fun tearDown() { database.close() }

    // ---- classify ----

    @Test
    fun `classify sorts created modified and touched`() {
        val files = listOf(
            FileSnap("new.md", content = null, afterContent = "hello"),
            FileSnap("edited.kt", content = "old", afterContent = "new"),
            FileSnap("failed-write.txt", content = null, afterContent = null),
            FileSnap("big.bin", content = "x", afterContent = null),
            FileSnap("rewritten-same.kt", content = "same", afterContent = "same"),
        )
        val (created, modified, touched) = TurnChangeCards.classify(files)
        assertEquals(listOf("new.md"), created)
        assertEquals(listOf("edited.kt"), modified)
        // 内容完全一致（写相同内容）视为无变化；仅缺改动后凭据的才算触碰
        assertEquals(listOf("failed-write.txt", "big.bin"), touched)
    }

    @Test
    fun `classify of empty snapshot is all empty`() {
        val (created, modified, touched) = TurnChangeCards.classify(emptyList())
        assertTrue(created.isEmpty() && modified.isEmpty() && touched.isEmpty())
    }

    // ---- publish：落库 + 上屏 ----

    @Test
    fun `publish skips turns without any change`() = runBlocking {
        TurnChangeCards.publish("s", 0, listOf(FileSnap("same.txt", "a", "a")), store, projector)
        assertTrue(projector.messagesFlow("s").value.isEmpty())
        assertTrue(store.load("s").isEmpty())
    }

    @Test
    fun `publish persists and emits deterministic id`() = runBlocking {
        TurnChangeCards.publish(
            "s", 3,
            listOf(FileSnap("a.md", null, "x"), FileSnap("b.kt", "y", "z")),
            store, projector,
        )
        val event = projector.messagesFlow("s").value.single() as TurnChangesEvent
        assertEquals("turn_changes_s_3", event.id)
        assertEquals(3, event.turn)
        assertEquals(listOf("a.md"), event.created)
        assertEquals(listOf("b.kt"), event.modified)
        // 落库可查（判重依据），且重放投影可见
        assertEquals(1, store.load("s").filterIsInstance<TurnChangesEvent>().size)
    }

    // ---- publishClosedTurn 判重 ----

    @Test
    fun `closed turn republish is skipped when history already has the card`() = runBlocking {
        val checkpoint = Checkpoint(
            turn = 2, time = 1, prompt = "p",
            files = listOf(FileSnap("a.md", null, "x")),
        )
        TurnChangeCards.publishClosedTurn("s", checkpoint, store, projector)
        assertEquals(1, projector.messagesFlow("s").value.size)
        // 同一轮补发第二次（如已完成路径发布过、下一轮 beginTurn 又关出同一轮）→ 判重跳过
        TurnChangeCards.publishClosedTurn("s", checkpoint, store, projector)
        assertEquals(1, projector.messagesFlow("s").value.size)
    }

    @Test
    fun `closed turn publish ignores null checkpoint`() = runBlocking {
        TurnChangeCards.publishClosedTurn("s", null, store, projector)
        assertTrue(projector.messagesFlow("s").value.isEmpty())
    }

    // ---- publishActiveTurn ----

    @Test
    fun `active turn publish reflects open turn snapshot`() = runBlocking {
        val checkpointer = CheckpointStore()
        val root = java.io.File(temporary.newFolder(), "ws").apply { mkdirs() }
        val controller = RewindController(checkpointer, WorkspaceFileAccess(root))
        // 无活动轮：不发布
        assertFalse(TurnChangeCards.publishActiveTurn("s", controller, store, projector))
        // 开轮 + 捕获一次真实写入（轮初 null → 新建）
        checkpointer.beginTurn("s", "prompt")
        checkpointer.capture("s", "created.md", null)
        checkpointer.captureAfterImage("s", "created.md", "content")
        assertTrue(TurnChangeCards.publishActiveTurn("s", controller, store, projector))
        val event = projector.messagesFlow("s").value.single() as TurnChangesEvent
        assertEquals(0, event.turn)
        assertEquals(listOf("created.md"), event.created)
    }

    // ---- CheckpointStore 轮查询与 beginTurn 返回 ----

    @Test
    fun `beginTurn returns closed previous turn and active queries reflect open turn`() {
        val checkpointer = CheckpointStore()
        assertNull(checkpointer.activeTurnNumber("s"))
        assertTrue(checkpointer.activeTurnFiles("s").isEmpty())
        assertNull(checkpointer.beginTurn("s", "first"))
        checkpointer.capture("s", "a.txt", "before")
        checkpointer.captureAfterImage("s", "a.txt", "after")
        val closed = checkpointer.beginTurn("s", "second")
        assertEquals(0, closed?.turn)
        assertEquals(listOf("a.txt"), closed?.files?.map { it.path })
        assertEquals("before", closed?.files?.single()?.content)
        assertEquals("after", closed?.files?.single()?.afterContent)
        assertEquals(1, checkpointer.activeTurnNumber("s"))
        assertTrue(checkpointer.activeTurnFiles("s").isEmpty())
    }

    @Test
    fun `empty previous turn is dropped without a card`() {
        val checkpointer = CheckpointStore()
        checkpointer.beginTurn("s", "first")
        // 未触碰任何文件 → 关轮丢弃，返回 null
        assertNull(checkpointer.beginTurn("s", "second"))
    }
}
