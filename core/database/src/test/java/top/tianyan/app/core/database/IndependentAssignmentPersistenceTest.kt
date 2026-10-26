package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class IndependentAssignmentPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val groups = db.groupChatDao()
    @After fun close() = db.close()

    private suspend fun create(): BotAssignment {
        val bot = BotEntity("chief", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1)
        db.personaBotDao().upsert(bot)
        db.personaBotDao().upsert(bot.copy(id = "member", sessionId = "member-dm", isMainBot = false))
        groups.upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        groups.addMember(GroupMemberEntity("group", "chief", 0, 1))
        groups.addMember(GroupMemberEntity("group", "member", 1, 1))
        val task = BotAssignment("task", "group", "member", "Member", "Build", "original", BotAssignmentStatus.QUEUED,
            createdAt = 1, startedAt = 1, sessionId = "independent", reportToBotId = "chief", independent = true)
        tasks.createIndependent(task, HarnessSessionEntity("independent", "Task", 1, 1, null, workspace = "/workspace/project"),
            BotAssignmentRequest("z-initial", "task", "original", 1))
        return task
    }

    @Test fun `task admission creates session ownership and request atomically`() = runBlocking {
        create()
        assertEquals("member", db.personaBotDao().findBySessionId("independent")!!.id)
        assertEquals("/workspace/project", db.harnessSessionDao().findById("independent")!!.workspace)
        assertEquals(listOf("z-initial"), tasks.pendingRequests().first().map { it.id })
        assertEquals(0, tasks.get("task")!!.revision)
    }

    @Test fun `queued turns are addressed idempotently and reports preserve each completed round`() = runBlocking {
        create()
        assertTrue(tasks.claimRequest("z-initial", 2))
        tasks.enqueueRequest(BotAssignmentRequest("a-next", "task", "new requirement", 1))
        tasks.enqueueRequest(BotAssignmentRequest("a-next", "task", "new requirement", 1))
        assertTrue(tasks.pendingRequests().first().isEmpty())
        assertEquals(BotAssignmentStatus.RUNNING, tasks.get("task")!!.status)
        assertFalse(tasks.claimRequest("a-next", 3))
        assertTrue(tasks.admitRequest("z-initial", "native-task"))
        tasks.finishRequest("z-initial", true, false, "first result", null)
        assertEquals(BotAssignmentStatus.QUEUED, tasks.get("task")!!.status)
        assertEquals(listOf("a-next"), tasks.pendingRequests().first().map { it.id })
        assertTrue(tasks.claimRequest("a-next", 4))
        tasks.finishRequest("a-next", true, false, "second result", null)
        tasks.finishRequest("z-initial", false, true, null, "late callback")
        assertEquals("second result", tasks.get("task")!!.summary)
        assertEquals("independent", tasks.get("task")!!.sessionId)
        assertEquals(2, tasks.get("task")!!.revision)
        assertEquals(2, groups.observeMessages("group").first().size)
        assertEquals(2, groups.pendingGroupDeliveries().first().size)
        assertEquals("native-task", tasks.getRequest("z-initial")!!.nativeTaskId)
    }

    @Test fun `blockers and artifacts are scoped to a round and continuation clears old blockers`() = runBlocking {
        create()
        tasks.claimRequest("z-initial", 2)
        assertTrue(tasks.reportBlock("independent", "missing input", listOf("/workspace/result.txt")))
        assertFalse(tasks.reportBlock("member-dm", "spoofed", emptyList()))
        tasks.finishRequest("z-initial", true, false, "partial result", null)
        assertEquals(BotAssignmentStatus.BLOCKED, tasks.get("task")!!.status)
        assertEquals("missing input", tasks.get("task")!!.error)
        assertTrue(groups.observeMessages("group").first().single().content.contains("missing input"))
        tasks.enqueueRequest(BotAssignmentRequest("fix", "task", "here is the missing input", 3))
        tasks.claimRequest("fix", 4)
        tasks.finishRequest("fix", true, false, "verified result", null)
        assertEquals(BotAssignmentStatus.COMPLETED, tasks.get("task")!!.status)
        assertNull(tasks.get("task")!!.error)
        assertEquals(listOf("/workspace/result.txt"), tasks.getRequest("z-initial")!!.artifacts)
    }

    @Test fun `restart preserves uncertain effects and freezes pending continuation until an explicit request`() = runBlocking {
        create()
        tasks.claimRequest("z-initial", 2)
        tasks.admitRequest("z-initial", "native-task")
        tasks.enqueueRequest(BotAssignmentRequest("queued-before-crash", "task", "later", 3))
        tasks.recoverRequests()
        tasks.recoverRequests()
        assertEquals(BotAssignmentStatus.INTERRUPTED, tasks.get("task")!!.status)
        assertEquals("unknown", tasks.getRequest("z-initial")!!.status)
        assertTrue(tasks.pendingRequests().first().isEmpty())
        assertFalse(tasks.claimRequest("z-initial", 4))
        assertFalse(tasks.claimRequest("queued-before-crash", 4))
        tasks.enqueueRequest(BotAssignmentRequest("explicit", "task", "first inspect the existing output", 5))
        assertEquals(listOf("explicit"), tasks.pendingRequests().first().map { it.id })
        assertEquals("cancelled", tasks.getRequest("queued-before-crash")!!.status)
    }

    @Test fun `stop and cleanup reject late callbacks and do not resurrect a newer turn`() = runBlocking {
        create()
        tasks.claimRequest("z-initial", 2)
        tasks.enqueueRequest(BotAssignmentRequest("queued", "task", "later", 3))
        tasks.cancelAssignment("task")
        tasks.finishRequest("z-initial", true, false, "late", null)
        assertEquals(BotAssignmentStatus.CANCELLED, tasks.get("task")!!.status)
        assertEquals("cancelled", tasks.getRequest("queued")!!.status)
        tasks.enqueueRequest(BotAssignmentRequest("resume", "task", "continue explicitly", 4))
        tasks.claimRequest("resume", 5)
        tasks.finishRequest("z-initial", true, false, "stale result", null)
        assertEquals("resume", tasks.get("task")!!.activeRequestId)
        groups.clearMessages("group")
        tasks.finishRequest("resume", true, false, "late", null)
        assertNull(tasks.getRequest("resume"))
        assertTrue(groups.observeMessages("group").first().isEmpty())
    }

    @Test fun `failed request admission leaves neither a native session nor a task orphan`() = runBlocking {
        create()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_request BEFORE INSERT ON bot_assignment_requests
            BEGIN SELECT RAISE(ABORT, 'write failed'); END""")
        val task = tasks.get("task")!!.copy(id = "failed", sessionId = "failed-session")
        assertTrue(runCatching { tasks.createIndependent(task, HarnessSessionEntity("failed-session", "T", 1, 1, null),
            BotAssignmentRequest("failed-request", "failed", "work", 1)) }.isFailure)
        assertNull(tasks.get("failed"))
        assertNull(db.harnessSessionDao().findById("failed-session"))
    }
}
