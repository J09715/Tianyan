package top.tianyan.app.harness.plugins

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.groupchat.GroupTurnOutcome

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class BotManagementExecutionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tracker = BotAssignmentTracker(RoomBotAssignmentRepository(db.botAssignmentDao(), Json))
    private val chief = BotEntity("chief", "阿策", "lead", "persona", "seed", null, null, "dm-chief", true, "#123456", 1, 1)

    @After fun close() = db.close()

    private suspend fun seed() {
        db.personaBotDao().upsert(chief)
        db.personaBotDao().upsert(chief.copy(id = "member", name = "小前", sessionId = "dm-member", isMainBot = false))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "俄罗斯方块开发", "chief", "", 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "chief", 0, 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "member", 1, 1))
        db.botGroupSessionDao().upsert(BotGroupSessionEntity("chief", "group", "group-chief", 1))
        db.botGroupSessionDao().upsert(BotGroupSessionEntity("member", "group", "group-member", 1))
    }

    private fun plugin() = botManagementPlugin(bots, groups, { error("Unexpected dispatch") },
        { error("Unexpected notification") }, tracker, AppLogger(context) { it })

    private suspend fun call(id: String, session: String, args: Map<String, String> = emptyMap()) =
        plugin().tools.single { it.id == id }.execute(
            PluginToolRequest(JsonObject(args.mapValues { JsonPrimitive(it.value) }), session, "/workspace", null),
        )

    @Test fun `management plugin registers every declared tool including continuation and reports`() {
        val registry = HarnessPluginRegistry(listOf(plugin()))
        val inspected = registry.inspect("plugin.tianyan.bot-management")
        assertTrue(inspected.first)
        assertTrue(inspected.second.contains("continue-assignment"))
        assertTrue(inspected.second.contains("report-assignment"))
    }

    @Test fun `chief manages team from group session while members and unknown sessions cannot`() = runBlocking {
        seed()
        assertTrue(call("list-bots", "group-chief").text.contains("小前"))
        assertTrue(call("list-assignments", "group-chief", mapOf("group_id" to "group")).success)
        assertTrue(call("list-bots", "dm-chief").success)
        assertFalse(call("list-bots", "group-member").success)
        assertFalse(call("list-bots", "unknown").success)
    }

    @Test fun `invalid explicit group cannot silently dispatch through dm`() = runBlocking {
        seed()
        val result = call("assign-task", "group-chief", mapOf("bot_name" to "小前", "task" to "test", "group_id" to "missing"))
        assertFalse(result.success)
        assertTrue(tracker.listRecent().isEmpty())
    }

    @Test fun `assignment dependencies survive repository round trip`() = runBlocking {
        seed()
        val repo = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
        val assignment = newAssignment(
            bots.getBot("member")!!,
            groups.getGroup("group"),
            "implement",
            emptyList(),
            dependsOn = listOf("pre-task"),
        )
        repo.save(assignment)
        assertEquals(listOf("pre-task"), repo.get(assignment.id)?.dependsOn)
    }

    @Test fun `background task is durable on acceptance and terminal before report`() = runTest {
        seed()
        val assignment = newAssignment(bots.getBot("member")!!, groups.getGroup("group"), "implement", emptyList())
        val reported = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        launchBotAssignment(tracker, assignment, { proceed.await(); GroupTurnOutcome("done", failed = false) }) { _, wake ->
            assertEquals(BotAssignmentStatus.COMPLETED, tracker.get(assignment.id)?.status)
            assertTrue(tracker.active.value.isEmpty())
            assertTrue(wake)
            reported.complete(Unit)
        }
        assertEquals(BotAssignmentStatus.RUNNING, tracker.get(assignment.id)?.status)
        proceed.complete(Unit)
        reported.await()
    }

    @Test fun `cancelling accepted job before execution persists cancellation`() = runTest {
        seed()
        val assignment = newAssignment(bots.getBot("member")!!, groups.getGroup("group"), "implement", emptyList())
        launchBotAssignment(tracker, assignment, { error("cancelled before execution") }) { _, _ -> }
        tracker.cancel(assignment.id)
        runCurrent()
        assertEquals(BotAssignmentStatus.CANCELLED, tracker.get(assignment.id)?.status)
        assertTrue(tracker.active.value.isEmpty())
    }

    @Test fun `execution failure is persisted before failure notification`() = runTest {
        seed()
        val assignment = newAssignment(bots.getBot("member")!!, groups.getGroup("group"), "implement", emptyList())
        val reported = CompletableDeferred<Unit>()
        launchBotAssignment(tracker, assignment, { error("offline") }) { text, _ ->
            assertEquals(BotAssignmentStatus.FAILED, tracker.get(assignment.id)?.status)
            assertTrue(text.contains("offline"))
            reported.complete(Unit)
        }
        reported.await()
        assertEquals("offline", tracker.get(assignment.id)?.error)
    }

    @Test fun `group cleanup drains result notification even after task status is completed`() = runTest {
        seed()
        val assignment = newAssignment(bots.getBot("member")!!, groups.getGroup("group"), "implement", emptyList())
        val notifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launchBotAssignment(tracker, assignment, { GroupTurnOutcome("done", failed = false) }) { _, _ ->
            notifying.complete(Unit)
            release.await()
        }
        notifying.await()
        assertEquals(BotAssignmentStatus.COMPLETED, tracker.get(assignment.id)?.status)
        val clearing = async { tracker.cancelGroup("group") }
        runCurrent()
        assertFalse(clearing.isCompleted)
        release.complete(Unit)
        clearing.await()
    }
}
