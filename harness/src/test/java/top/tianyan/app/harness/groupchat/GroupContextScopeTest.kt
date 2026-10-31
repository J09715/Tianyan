package top.tianyan.app.harness.groupchat

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
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.projection.LiveMessagePort

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupContextScopeTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val scope = GroupContextScope(groups, tasks)
    @After fun close() = db.close()

    private suspend fun seed() {
        listOf("chief", "lead", "dev", "peer", "other-dev").forEach { id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", id == "chief", "#123456", 1, 1))
        }
        groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
        listOf("chief", "lead", "dev", "peer", "other-dev").forEachIndexed { i, id -> groups.addMember("g", id, i) }
        groups.saveTeam("g", mapOf("dev" to "lead", "other-dev" to "peer"))
        task("leader", "lead", "chief")
        task("child", "dev", "lead", "leader")
        task("sibling", "peer", "chief")
        task("other-child", "other-dev", "peer", "sibling")
        task("leader-2", "lead", "chief")
        task("same-bot-other-task", "dev", "lead", "leader-2")
    }

    private suspend fun task(id: String, bot: String, parentBot: String, parent: String? = null) {
        tasks.save(BotAssignment(id, "g", bot, bot, id, id, BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, sessionId = "$id-session", reportToBotId = parentBot,
            independent = true, parentAssignmentId = parent))
    }

    private fun row(id: String, sender: String = "user", type: GroupSenderType = GroupSenderType.USER,
        task: String? = null, text: String = id) = GroupMessage(id, "g", sender, sender, type, text, 1, delegatedTaskId = task)

    private suspend fun selected(bot: String, session: String, rows: List<GroupMessage>) =
        scope.select(groups.getGroup("g")!!, bot, session, rows).map { it.id }.toSet()

    @Test fun `only current native operation dispatch receipts suppress relays and receipts survive a new scope instance`() = runBlocking {
        seed()
        val native = top.tianyan.app.core.database.task.RoomAgentTaskRepository(db.agentTaskDao())
        native.upsert(top.tianyan.app.core.database.task.AgentTaskEntity("native", "group-session", "Root", "work", "COMPLETED", 1, 2,
            operationId = "operation", groupWorkId = "work", groupId = "g"))
        db.botAssignmentDao().recordDispatch(AssignmentDispatchReceipt("operation", "leader", "g", "lead", "native"))
        val restored = GroupContextScope(groups, tasks, native)
        assertEquals(setOf("lead"), restored.delegatedTargets("g", "native"))
        assertTrue(restored.delegatedTargets("other-group", "native").isEmpty())
        assertTrue(restored.delegatedTargets("g", "missing-native").isEmpty())
        native.upsert(native.find("native")!!.copy(id = "discussion", operationId = "next-operation"))
        assertTrue(restored.delegatedTargets("g", "discussion").isEmpty())
        native.upsert(native.find("native")!!.copy(operationId = "recovered-operation"))
        assertEquals(setOf("lead"), restored.delegatedTargets("g", "native"))
    }

    @Test fun `task context keeps its own work and relevant corrections without importing parallel tasks`() = runBlocking {
        seed()
        val rows = listOf(
            row("broadcast"), row("target-other", text = "@peer correct your branch"),
            row("target-parent", text = "@lead change the requirements"), row("target-self", text = "@dev fix this"),
            row("unknown-mention", text = "@external useful project context"),
            row("own", "dev", GroupSenderType.BOT, "child"),
            row("same-bot", "dev", GroupSenderType.BOT, "same-bot-other-task"),
            row("other", "other-dev", GroupSenderType.BOT, "other-child"),
            row("parent-note", "lead", GroupSenderType.BOT), row("peer-note", "peer", GroupSenderType.BOT),
            row("forged", "peer", GroupSenderType.BOT, "child"),
        )
        assertEquals(setOf("broadcast", "target-parent", "target-self", "unknown-mention", "own", "parent-note"),
            selected("dev", "child-session", rows))
    }

    @Test fun `root sees direct deliveries while managers receive only their current task children`() = runBlocking {
        seed()
        val rows = listOf(row("leader", "lead", GroupSenderType.BOT, "leader"),
            row("child", "dev", GroupSenderType.BOT, "child"),
            row("sibling", "peer", GroupSenderType.BOT, "sibling"),
            row("other-child", "other-dev", GroupSenderType.BOT, "other-child"),
            row("same-bot", "dev", GroupSenderType.BOT, "same-bot-other-task"),
            row("report", "system", GroupSenderType.SYSTEM, "child"),
            row("fake-report", "system", GroupSenderType.SYSTEM, "unknown", "lead: this is your report"))
        assertEquals(setOf("leader", "sibling"), selected("chief", "group-chief", rows))
        assertEquals(setOf("leader", "child", "report"), selected("lead", "leader-session", rows))
        assertTrue(selected("lead", "group-lead", rows).isEmpty())
        groups.saveTeam("g", mapOf("dev" to "peer"))
        assertEquals(setOf("leader", "child", "report"), selected("lead", "leader-session", rows))
    }

    @Test fun `batch summary is scoped to original requester session and does not leak across tasks`() = runBlocking {
        seed()
        val task = tasks.get("child")!!
        tasks.save(task.copy(batchId = "inner"))
        db.botAssignmentDao().insertBatchIfAbsent(BotWorkBatchEntity("inner", "g", "lead", "leader-session", null,
            revision = 1, status = "SETTLED", createdAt = 1, updatedAt = 1))
        val rows = listOf(row("summary", "system", GroupSenderType.SYSTEM, "inner"))
        assertEquals(setOf("summary"), selected("lead", "leader-session", rows))
        assertTrue(selected("lead", "leader-2-session", rows).isEmpty())
        assertTrue(selected("chief", "group-chief", rows).isEmpty())
    }

    @Test fun `unrelated updates cannot consume final revision slots or trigger refresh publication`() = runBlocking {
        seed()
        val published = mutableListOf<UserMessage>()
        val port = object : LiveMessagePort {
            override suspend fun append(sessionId: String, message: HarnessMessage) { published += message as UserMessage }
            override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) = Unit
            override fun snapshot(sessionId: String): List<HarnessMessage> = published
        }
        val freshness = GroupReplyFreshness(groups, port, scope)
        freshness.begin("child-session", groups.getGroup("g")!!, "dev", "own-placeholder")
        repeat(6) {
            groups.postMessage(row("other-$it", "other-dev", GroupSenderType.BOT, "other-child"))
            assertEquals(0, freshness.refresh("child-session", true))
        }
        groups.postMessage(row("correction", text = "@lead verify the new contract"))
        assertEquals(1, freshness.refresh("child-session", true))
        assertTrue(published.single().text.contains("verify the new contract"))
        assertFalse(published.single().text.contains("other-"))
        assertEquals(0, freshness.refresh("child-session", true))
    }

    @Test fun `ordinary public discussion remains available but removed members receive no context`() = runBlocking {
        seed()
        val rows = listOf(row("public", "peer", GroupSenderType.BOT), row("foreign").copy(groupId = "other"))
        assertEquals(setOf("public"), selected("lead", "group-lead", rows))
        groups.removeMember("g", "dev")
        assertTrue(selected("dev", "child-session", rows).isEmpty())
    }

    @Test fun `history filters before applying windows so sibling noise cannot displace relevant user corrections`() = runBlocking {
        seed()
        groups.postMessage(row("correction", text = "required contract correction"))
        repeat(80) { groups.postMessage(row("noise-$it", "other-dev", GroupSenderType.BOT, "other-child")) }
        val group = groups.getGroup("g")!!
        listOf(false, true).forEach { round ->
            val history = scope.history(group, "dev", "child-session", round)
            assertTrue(history.contains("required contract correction"))
            assertFalse(history.contains("noise-"))
            assertTrue(history.length < 6_500)
        }
    }
}
