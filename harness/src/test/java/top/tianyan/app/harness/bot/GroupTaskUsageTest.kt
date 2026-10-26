package top.tianyan.app.harness.bot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupTaskUsageTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val assignments = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val runtime = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
    private val usage = GroupTaskUsage(assignments, runtime)
    @After fun close() = db.close()

    private suspend fun seed() {
        db.personaBotDao().upsert(BotEntity("bot", "Bot", "role", "", "", null, null, "dm", true, "#123456", 1, 1))
        db.groupChatDao().upsertGroup(GroupChatEntity("g", "Team", "bot", "", 1))
        task("root")
        task("child", "root")
        task("grandchild", "child")
        task("unrelated")
    }

    private suspend fun task(id: String, parent: String? = null) {
        assignments.save(BotAssignment(id, "g", "bot", "Bot", id, id, BotAssignmentStatus.COMPLETED,
            createdAt = 1, startedAt = 1, sessionId = "$id-session", independent = true, parentAssignmentId = parent))
    }

    private suspend fun record(id: String, session: String, input: Long, output: Long, adjustment: Boolean = false) {
        runtime.recordUsage(HarnessUsageEntity(id = id, sessionId = session, operationId = null, entryId = null,
            provider = "test", modelId = "fake", inputTokens = input, outputTokens = output,
            reasoningTokens = output / 2, cacheReadTokens = input / 2, adjustment = adjustment, createdAt = 1))
    }

    @Test fun `task tree accounting includes retries and descendants but excludes peers and adjustment rows`() = runBlocking {
        seed()
        record("root", "root-session", 100, 10)
        record("child", "child-session", 200, 20)
        record("retry", "child-session", 50, 6)
        record("grandchild", "grandchild-session", 300, 30)
        record("unrelated", "unrelated-session", 9999, 9999)
        record("adjustment", "child-session", 9999, 9999, true)
        val report = usage.describe("root")
        assertTrue(report.contains("当前任务：输入 100／输出 10"))
        assertTrue(report.contains("含后代 3 个任务／3 个独立会话：输入 650／输出 66／推理 33／缓存读 325"))
        assertTrue(report.contains("4 次已入账调用"))
        assertFalse(report.contains("9999"))
        assertFalse(report.contains("尚无账本记录"))
    }

    @Test fun `unreported usage and legacy shared sessions are not presented as zero cost`() = runBlocking {
        seed()
        val report = usage.describe("root")
        assertTrue(report.contains("3 个任务尚无账本记录"))
        assertTrue(report.contains("不能据此认定零消耗"))
        assignments.save(assignments.get("root")!!.copy(independent = false))
        assertTrue(usage.describe("root").contains("无法独立归因"))
    }
}
