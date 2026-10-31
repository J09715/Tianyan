package top.tianyan.app.harness.bot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.bot.market.GroupTeamTemplates
import top.tianyan.app.harness.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupGovernanceGuardTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val bots = RoomPersonaBotRepository(db.personaBotDao(), db.botGroupSessionDao())
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val guard = GroupGovernanceGuard(groups, bots, tasks)
    private val rule = GroupReviewRule("rule", "Review", "author", "reviewer", "executor")
    @After fun close() = db.close()
    private suspend fun seed() {
        listOf("chief", "author", "reviewer", "executor", "worker").forEachIndexed { i, id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", i == 0, "#123456", 1, 1))
            if (i == 0) groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
            groups.addMember("g", id, i)
            db.botGroupSessionDao().upsert(BotGroupSessionEntity(id, "g", "group-$id", 1))
        }
        groups.saveTeam("g", mapOf("worker" to "executor"))
        groups.saveGovernance("g", GroupGovernance(listOf(rule)))
    }
    private fun call(tool: HarnessTool, raw: String? = null, args: JsonObject = JsonObject(emptyMap())) =
        ToolCall("call", 1, tool, args, rawToolName = raw)
    private fun capability(action: String = "call", server: String = "plugin.tianyan.bot-management", tool: String = "inspect-governance") =
        call(HarnessTool.MCP, "use_capability", buildJsonObject { put("action", action); put("server", server); put("tool", tool) })
    private suspend fun tool(id: String, bot: String, arguments: Map<String, String>) =
        groupReviewTools(bots, groups).single { it.id == id }.execute(
            PluginToolRequest(JsonObject(arguments.mapValues { JsonPrimitive(it.value) }), "group-$bot", "/workspace", null))
    private suspend fun approvedTask(bot: String = "executor") {
        groups.submitProposal("g", "author", "rule", "p", 0, "Implement the approved change")
        groups.decideProposal("g", "reviewer", "p", 1, ProposalDecision.APPROVED, "verified")
        tasks.save(BotAssignment("task", "g", bot, bot, "Build", "Build", BotAssignmentStatus.RUNNING,
            createdAt = 1, startedAt = 1, sessionId = "task-session", independent = true, reportToBotId = "chief",
            proposalId = "p", proposalVersion = 1))
    }

    @Test fun `ordinary group roles cannot mutate or escape through shell subagents and arbitrary plugins`() = runBlocking {
        seed()
        listOf("chief", "author", "reviewer", "executor").forEach { bot ->
            listOf(HarnessTool.WRITE, HarnessTool.EDIT, HarnessTool.BASE, HarnessTool.PROCESS, HarnessTool.HOST,
                HarnessTool.DOWNLOAD, HarnessTool.SUBAGENT, HarnessTool.BUILD_SCRIPT).forEach {
                assertNotNull("$bot $it", guard.blockReason("group-$bot", call(it)))
            }
            assertNotNull(guard.blockReason("group-$bot", capability(server = "plugin.other")))
            assertNotNull(guard.blockReason("group-$bot", capability(action = "script")))
            assertNotNull(guard.blockReason("group-$bot", capability(tool = "create-bot")))
            assertNull(guard.blockReason("group-$bot", call(HarnessTool.READ)))
            assertNull(guard.blockReason("group-$bot", capability()))
            assertNull(guard.blockReason("group-$bot", capability(tool = "assign-task")))
        }
    }

    @Test fun `approved execution allows tools but changed version blocks further tools immediately`() = runBlocking {
        seed(); approvedTask()
        assertNull(guard.blockReason("task-session", call(HarnessTool.WRITE)))
        assertNull(guard.blockReason("task-session", call(HarnessTool.BASE)))
        groups.submitProposal("g", "author", "rule", "p", 1, "changed implementation")
        assertNotNull(guard.blockReason("task-session", call(HarnessTool.WRITE)))
        assertNull(guard.blockReason("task-session", call(HarnessTool.READ)))
    }

    @Test fun `approved proposal cannot authorize author or reviewer and stopped tasks`() = runBlocking {
        seed(); approvedTask("reviewer")
        assertNotNull(guard.blockReason("task-session", call(HarnessTool.BASE)))
        val task = requireNotNull(tasks.get("task"))
        tasks.save(task.copy(botId = "worker"))
        assertNull(guard.blockReason("task-session", call(HarnessTool.BASE)))
        tasks.save(task.copy(botId = "worker", status = BotAssignmentStatus.CANCELLED))
        assertNotNull(guard.blockReason("task-session", call(HarnessTool.BASE)))
    }

    @Test fun `review tools verify identity version group and retain rejection`() = runBlocking {
        seed()
        val submitted = tool("submit-proposal", "author", mapOf("rule_id" to "rule", "version" to "0", "proposal_id" to "p", "content" to "plan"))
        assertTrue(submitted.text, submitted.success)
        val args = mapOf("proposal_id" to "p", "version" to "1", "decision" to "APPROVED", "reason" to "verified")
        assertFalse(tool("review-proposal", "author", args).success)
        assertFalse(tool("review-proposal", "reviewer", args + ("version" to "2")).success)
        assertFalse(tool("review-proposal", "reviewer", args + ("group_id" to "other")).success)
        assertTrue(tool("review-proposal", "reviewer", args + ("decision" to "REJECTED")).success)
        assertEquals(ProposalDecision.REJECTED, groups.getProposal("p")!!.decision)
        assertFalse(tool("review-proposal", "reviewer", args).success)
        assertTrue(tool("inspect-governance", "chief", emptyMap()).text.contains("REJECTED"))
    }

    @Test fun `templates carry complete valid reporting trees and independent review authorities`() {
        GroupTeamTemplates.all.forEach { template ->
            template.validate()
            val ids = template.members.map { it.key }.toSet()
            assertTrue(ids.contains(template.adminKey))
            assertTrue(template.rules.isNotEmpty())
            template.rules.forEach { assertEquals(3, setOf(it.authorId, it.reviewerId, it.executorId).size) }
        }
        val imperial = GroupTeamTemplates.all.single { it.id == "sansheng" }
        assertEquals(6, imperial.members.count { it.supervisorKey == "shangshu" })
    }

    @Test fun `unconfigured groups and private sessions keep existing tool behavior`() = runBlocking {
        seed()
        assertNull(guard.blockReason("dm-author", call(HarnessTool.BASE)))
        groups.saveGovernance("g", GroupGovernance())
        assertNull(guard.blockReason("group-author", call(HarnessTool.BASE)))
    }
}
