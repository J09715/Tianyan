package top.tianyan.app.harness.plugins

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.bot.BotAssignmentTracker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotTeamAccessTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val tracker = BotAssignmentTracker(RoomBotAssignmentRepository(db.botAssignmentDao(), Json))
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "lead", "dev", "peer").forEachIndexed { i, id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", id == "chief", "#123456", 1, 1))
            if (i == 0) groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
            groups.addMember("g", id, i)
            db.botGroupSessionDao().upsert(BotGroupSessionEntity(id, "g", "group-$id", 1))
        }
        groups.saveTeam("g", mapOf("dev" to "lead"))
    }

    private fun task() = BotAssignment("task", "g", "dev", "dev", "Build", "Build", BotAssignmentStatus.RUNNING,
        createdAt = 1, startedAt = 1, reportToBotId = "lead")

    private suspend fun call(id: String, session: String, arguments: Map<String, String>) =
        botManagementPlugin(bots, groups, { error("unexpected orchestrator") }, { error("unexpected dispatcher") },
            tracker, AppLogger(context) { it }).tools.single { it.id == id }.execute(
            PluginToolRequest(JsonObject(arguments.mapValues { JsonPrimitive(it.value) }), session, "/workspace", null))

    @Test fun `root and peers cannot skip reporting lines and leaf cannot create a team`() = runBlocking {
        seed()
        assertFalse(call("assign-task", "group-chief", mapOf("group_id" to "g", "bot_name" to "dev", "task" to "work")).success)
        assertFalse(call("assign-task", "group-peer", mapOf("group_id" to "g", "bot_name" to "dev", "task" to "work")).success)
        assertFalse(call("create-bot", "group-lead", emptyMap()).success)
        assertTrue(tracker.listRecent().isEmpty())
    }

    @Test fun `manager roster contains only direct reports`() = runBlocking {
        seed()
        val root = call("list-bots", "group-chief", mapOf("group_id" to "g"))
        assertTrue(root.success)
        assertFalse(root.text.contains("bot_name=\"dev\""))
        val lead = call("list-bots", "group-lead", mapOf("group_id" to "g"))
        assertTrue(lead.success)
        assertTrue(lead.text.contains("dev"))
        assertFalse(call("list-bots", "group-dev", mapOf("group_id" to "g")).success)
    }

    @Test fun `ancestor can inspect evidence but cannot cancel or continue a grandchild task`() = runBlocking {
        seed()
        tracker.start(task(), null)
        assertTrue(call("inspect-assignment", "group-chief", mapOf("assignment_id" to "task")).success)
        assertFalse(call("cancel-assignment", "group-chief", mapOf("assignment_id" to "task")).success)
        assertFalse(call("continue-assignment", "group-chief", mapOf("assignment_id" to "task", "instruction" to "redo")).success)
        assertFalse(call("inspect-assignment", "group-peer", mapOf("assignment_id" to "task")).success)
        assertTrue(call("cancel-assignment", "group-lead", mapOf("assignment_id" to "task")).success)
    }

    @Test fun `inspecting own assignment does not expose unauthorized batch siblings`() = runBlocking {
        seed()
        tracker.start(task().copy(batchId = "batch"), null)
        tracker.start(task().copy(id = "peer-task", botId = "peer", botName = "peer",
            title = "private-peer-title", task = "private-peer-instruction", reportToBotId = "chief", batchId = "batch"), null)
        val result = call("inspect-assignment", "group-dev", mapOf("assignment_id" to "task"))
        assertTrue(result.success)
        assertTrue(result.text.contains("同批：1 个任务"))
        assertFalse(result.text.contains("peer-task"))
        assertFalse(result.text.contains("private-peer"))
    }

    @Test fun `organization change does not transfer an existing task control`() = runBlocking {
        seed()
        val original = task()
        groups.saveTeam("g", emptyMap())
        assertTrue(canManageAssignment(bots.getBot("lead")!!, original, groups))
        assertFalse(canManageAssignment(bots.getBot("chief")!!, original, groups))
        groups.removeMember("g", "lead")
        assertFalse(canManageAssignment(bots.getBot("lead")!!, original, groups))
    }
}
