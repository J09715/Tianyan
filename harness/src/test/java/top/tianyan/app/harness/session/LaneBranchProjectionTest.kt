package top.tianyan.app.harness.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaneBranchProjectionTest {
    @Test
    fun sharedAncestorsPreserveNearestNamedLaneAndBranchPreviews() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
            val store = SessionTreeStore(repository, Json, AppLogger(context, SensitiveDataRedactor { it }))
            val manager = LaneManager(repository, store)
            store.append("session", UserMessage("root", 1L, "common context"))
            manager.create("session", "branch:outer:outer", "root")
            store.append("session", AssistantText("middle", 2L, "shared reply"), "branch:outer:outer")
            manager.create("session", "branch:inner:inner", "middle")
            store.append("session", AssistantText("inner-leaf", 3L, "inner preview"), "branch:inner:inner")
            manager.create("session", "subagent:research:one", "middle")
            store.append("session", UserMessage("task", 4L, "任务目标：调查问题"), "subagent:research:one")
            store.append("session", AssistantText("child-leaf", 5L, "child preview"), "subagent:research:one")

            val branches = manager.branches("session")
            assertTrue(branches.first().isCurrent)
            val inner = branches.single { it.leafId == "inner-leaf" }
            assertEquals("branch:inner:inner", inner.laneName)
            assertEquals("inner preview", inner.preview)
            assertEquals(3, inner.depth)
            val child = branches.single { it.leafId == "child-leaf" }
            assertEquals(ConversationBranchKind.SUBAGENT, child.kind)
            assertEquals("调查问题 · research", child.name)
            assertEquals("child preview", child.preview)
            assertEquals(2, child.depth)
        } finally {
            database.close()
        }
    }
}
