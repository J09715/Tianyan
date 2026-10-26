package top.tianyan.app.harness.bot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.*
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.groupchat.GroupTurnOutcome
import top.tianyan.app.harness.queue.PromptQueue
import top.tianyan.app.harness.session.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupGovernanceRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val tasks = RoomBotAssignmentRepository(db.botAssignmentDao(), Json)
    private val sessions = RoomHarnessSessionRepository(db.harnessSessionDao())
    private val control = FakeControl()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }

    @Test fun `draft reject revise approve execute and nested delivery use persistent version gates`() = runBlocking {
        listOf("chief", "author", "reviewer", "executor", "worker").forEachIndexed { index, id ->
            db.personaBotDao().upsert(BotEntity(id, id, "role", "", id, null, null, "dm-$id", index == 0, "#123456", 1, 1))
            if (index == 0) groups.saveGroup(GroupChat("g", "Team", "chief", createdAt = 1))
            groups.addMember("g", id, index)
        }
        groups.saveTeam("g", mapOf("worker" to "executor"))
        groups.saveGovernance("g", GroupGovernance(listOf(GroupReviewRule("r", "Review", "author", "reviewer", "executor"))))
        sessions.upsert(HarnessSessionEntity("parent", "Root", 1, 1, null, workspace = "/workspace/project"))
        val guard = GroupGovernanceGuard(groups, bots, tasks)
        val nativeTasks = top.tianyan.app.core.database.task.RoomAgentTaskRepository(db.agentTaskDao())
        nativeTasks.upsert(top.tianyan.app.core.database.task.AgentTaskEntity("native-root", "parent", "Root", "draft",
            "RUNNING", 1, 1, operationId = "root-operation", groupWorkId = "root-work", groupId = "g"))
        val executions = mutableListOf<String>()
        lateinit var engine: BotAssignmentRuntime
        engine = BotAssignmentRuntime(tasks, groups, bots, sessions, control, BotAssignmentTracker(tasks),
            BotAssignmentExecutor { task, request, admitted ->
                val sessionId = requireNotNull(task.sessionId)
                val accepted = control.submit(sessionId, request.instruction) as PromptSubmission.Accepted
                admitted(accepted.taskId)
                when (task.botId) {
                    "author" -> groups.submitProposal("g", "author", "r", "p", if (request.instruction == "draft") 0 else 1, request.instruction, task.rootWorkId)
                    "reviewer" -> groups.decideProposal("g", "reviewer", "p", requireNotNull(task.proposalVersion),
                        if (task.proposalVersion == 1) ProposalDecision.REJECTED else ProposalDecision.APPROVED, "review evidence")
                    "executor" -> {
                        executions += "executor"
                        assertNull(guard.blockReason(sessionId, ToolCall("check", 1, HarnessTool.BASE, kotlinx.serialization.json.JsonObject(emptyMap()))))
                        val child = engine.create("worker", "g", "executor", sessionId, "implement")
                        val result = withTimeout(10_000) { engine.awaitSynchronousTask(child.id, sessionId) }
                        assertEquals(BotAssignmentStatus.COMPLETED, result.status)
                        assertEquals(2, child.proposalVersion)
                        assertEquals("p", child.proposalId)
                        assertEquals("root-work", child.rootWorkId)
                    }
                    "worker" -> {
                        executions += "worker"
                        assertNull(guard.blockReason(sessionId, ToolCall("write", 1, HarnessTool.WRITE, kotlinx.serialization.json.JsonObject(emptyMap()))))
                    }
                }
                control.setState(sessionId, SessionRunState.COMPLETED)
                GroupTurnOutcome("verified ${task.botId}", false)
            }, AppLogger(context) { it }, scope, nativeTasks = nativeTasks)
        val draft = engine.create("author", "g", "chief", "parent", "draft", operationId = "root-operation")
        assertEquals("root-work", draft.rootWorkId)
        assertEquals(BotAssignmentStatus.COMPLETED, withTimeout(10_000) { engine.awaitTask(draft.id) }.status)
        val review = engine.create("reviewer", "g", "chief", "parent", "review", proposalId = "p")
        assertEquals(BotAssignmentStatus.COMPLETED, withTimeout(10_000) { engine.awaitTask(review.id) }.status)
        assertEquals(ProposalDecision.REJECTED, groups.getProposal("p")!!.decision)
        assertTrue(runCatching { engine.create("executor", "g", "chief", "parent", "must not run", proposalId = "p") }.isFailure)
        assertTrue(executions.isEmpty())
        val followup = engine.continueTask(draft.id, "g", "revise", "revision")
        assertEquals(BotAssignmentRequest.COMPLETED, withTimeout(10_000) { engine.awaitRequest(followup) }.status)
        val reReview = engine.create("reviewer", "g", "chief", "parent", "review again", proposalId = "p")
        assertEquals(BotAssignmentStatus.COMPLETED, withTimeout(10_000) { engine.awaitTask(reReview.id) }.status)
        val execution = engine.create("executor", "g", "chief", "parent", "execute", proposalId = "p")
        assertEquals(BotAssignmentStatus.COMPLETED, withTimeout(10_000) { engine.awaitTask(execution.id) }.status)
        assertEquals(listOf("executor", "worker"), executions)
        assertEquals(draft.sessionId, tasks.get(draft.id)!!.sessionId)
        assertEquals(2, execution.proposalVersion)
        val repeated = engine.create("executor", "g", "author", "parent", "execute again", proposalId = "p")
        assertEquals(execution.id, repeated.id)
        assertEquals(execution.sessionId, repeated.sessionId)
        assertEquals("chief", repeated.reportToBotId)
        assertEquals(listOf("executor", "worker"), executions)
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("parent")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        private val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        fun setState(id: String, state: SessionRunState) { synchronized(this) { sessionRunStates.value += id to state } }
        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue): PromptSubmission {
            setState(sessionId, SessionRunState.RUNNING)
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-$sessionId")
        }
        override fun cancel(targetSessionId: String?) { targetSessionId?.let { setState(it, SessionRunState.IDLE) } }
        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}
