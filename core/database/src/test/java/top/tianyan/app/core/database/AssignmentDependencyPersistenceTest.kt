package top.tianyan.app.core.database

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
import top.tianyan.app.core.model.bot.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignmentDependencyPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "dev").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", "", null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        listOf("g", "other").forEach { group ->
            db.groupChatDao().upsertGroup(GroupChatEntity(group, group, "chief", "", 1))
            listOf("chief", "dev").forEachIndexed { i, bot -> db.groupChatDao().addMember(GroupMemberEntity(group, bot, i, 1)) }
        }
    }

    private suspend fun predecessor(status: BotAssignmentStatus, group: String = "g") {
        tasks.save(BotAssignment("pre", group, "dev", "dev", "pre", "pre", status,
            createdAt = 1, startedAt = 1, reportToBotId = "chief"))
    }

    private suspend fun create(id: String = "dependent", dependencies: List<String> = listOf("pre")) {
        tasks.createIndependent(BotAssignment(id, "g", "dev", "dev", id, id, BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "session-$id", independent = true, reportToBotId = "chief",
            batchId = "work", dependsOn = dependencies), HarnessSessionEntity("session-$id", id, 1, 1, null),
            BotAssignmentRequest("initial:$id", id, id, 1),
            BotWorkBatch("work", "g", "chief", "group-chief", null, 1,
                status = BotWorkBatchStatus.WAITING_CHILDREN, createdAt = 1, updatedAt = 1))
    }

    @Test fun `only completed same group dependencies admit a task without partial writes`() = runBlocking {
        seed()
        BotAssignmentStatus.entries.filter { it != BotAssignmentStatus.COMPLETED }.forEach { status ->
            predecessor(status)
            assertTrue(runCatching { create() }.isFailure)
        }
        predecessor(BotAssignmentStatus.COMPLETED, "other")
        assertTrue(runCatching { create() }.isFailure)
        assertTrue(runCatching { create(dependencies = listOf("missing")) }.isFailure)
        assertTrue(runCatching { create(dependencies = listOf("dependent")) }.isFailure)
        assertNull(tasks.get("dependent"))
        assertNull(tasks.getRequest("initial:dependent"))
        assertNull(db.harnessSessionDao().findById("session-dependent"))
        assertNull(tasks.getBatch("work"))
        predecessor(BotAssignmentStatus.COMPLETED)
        create()
        assertEquals(listOf("pre"), tasks.get("dependent")!!.dependsOn)
        assertTrue(tasks.claimRequest("initial:dependent", 2))
    }

    @Test fun `reopened predecessor blocks execution and resumes the same task after verification`() = runBlocking {
        seed()
        predecessor(BotAssignmentStatus.COMPLETED)
        create()
        val original = tasks.get("dependent")!!
        predecessor(BotAssignmentStatus.RUNNING)
        assertFalse(tasks.claimRequest("initial:dependent", 2))
        val blocked = tasks.get("dependent")!!
        assertEquals(BotAssignmentStatus.BLOCKED, blocked.status)
        assertTrue(blocked.error!!.contains("前置任务"))
        assertEquals("failed", tasks.getRequest("initial:dependent")!!.status)
        assertEquals(DEPENDENCY_BLOCK_REASON, tasks.getRequest("initial:dependent")!!.blockedReason)
        predecessor(BotAssignmentStatus.COMPLETED)
        tasks.enqueueRequest(BotAssignmentRequest("resume", "dependent", "verify and continue", 3))
        assertTrue(tasks.claimRequest("resume", 4))
        assertEquals(original.sessionId, tasks.get("dependent")!!.sessionId)
        assertEquals(original.budgetId, tasks.get("dependent")!!.budgetId)
    }

    @Test fun `unreadable persisted dependencies cannot start a model turn`() = runBlocking {
        seed()
        create(dependencies = emptyList())
        db.openHelper.writableDatabase.execSQL("UPDATE bot_assignments SET dependsOnJson = ? WHERE id = ?",
            arrayOf("not-json", "dependent"))
        assertFalse(tasks.claimRequest("initial:dependent", 2))
        assertEquals("BLOCKED", db.botAssignmentDao().findById("dependent")!!.status)
        assertEquals("failed", tasks.getRequest("initial:dependent")!!.status)
    }
}
