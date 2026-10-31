package top.tianyan.app.harness.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.task.AgentTaskCheckpoint
import top.tianyan.app.core.database.task.AgentTaskEntity
import top.tianyan.app.core.database.task.AgentTaskRepository
import top.tianyan.app.core.database.task.AgentTaskTransition
import top.tianyan.app.harness.PendingMessage
import top.tianyan.app.harness.task.AgentStateMachine

class DefaultSessionTitleTest {

    @Test
    fun `instruction title is the first non-blank line`() {
        val text = "\n  帮我   修登录崩溃  \n\n复现：打开应用就闪退\n[附件：已复制并挂载到 Linux 沙箱]"
        assertEquals("帮我 修登录崩溃", DefaultSessionTitle.fromInstruction(text))
    }

    @Test
    fun `instruction title is capped at the task title length`() {
        val title = DefaultSessionTitle.fromInstruction("修".repeat(120))
        assertEquals(DefaultSessionTitle.MAX_CHARS, title.length)
        assertEquals("修".repeat(DefaultSessionTitle.MAX_CHARS), title)
    }

    @Test
    fun `default session names are placeholders in both locales`() {
        assertTrue(DefaultSessionTitle.isPlaceholder("新建会话"))
        assertTrue(DefaultSessionTitle.isPlaceholder(" 新会话 "))
        assertTrue(DefaultSessionTitle.isPlaceholder("New session"))
        assertFalse(DefaultSessionTitle.isPlaceholder("修登录崩溃"))
    }

    @Test
    fun `first instruction renames a default session`() = runBlocking {
        val sessions = FakeSessions()
        sessions.upsert(session("s1", "新建会话"))
        val recorder = recorder(sessions, hasUserMessage = false)

        recorder.record("s1", pending("帮我修登录崩溃\n看一下栈"))

        assertEquals("帮我修登录崩溃", sessions.sessions.getValue("s1").title)
    }

    @Test
    fun `custom title and existing conversation stay unchanged`() = runBlocking {
        val custom = FakeSessions().also { it.upsert(session("s1", "登录排查")) }
        recorder(custom, hasUserMessage = false).record("s1", pending("另一件事"))
        assertEquals("登录排查", custom.sessions.getValue("s1").title)

        val talked = FakeSessions().also { it.upsert(session("s2", "新建会话")) }
        recorder(talked, hasUserMessage = true).record("s2", pending("继续修"))
        assertEquals("新建会话", talked.sessions.getValue("s2").title)
    }

    @Test
    fun `a second instruction does not overwrite the first title before history lands`() = runBlocking {
        val sessions = FakeSessions()
        sessions.upsert(session("s1", "New session"))
        val recorder = recorder(sessions, hasUserMessage = false)

        recorder.record("s1", pending("先看崩溃日志"))
        recorder.record("s1", pending("再补一个复现"))

        assertEquals("先看崩溃日志", sessions.sessions.getValue("s1").title)
    }

    private fun recorder(sessions: FakeSessions, hasUserMessage: Boolean) = QueuedInstructionRecorder(
        tasks = AgentStateMachine(FakeTasks()),
        sessions = sessions,
        log = { _, _, _ -> },
        nowMs = { 42L },
        hasUserMessage = { hasUserMessage },
    )

    private fun session(id: String, title: String) = HarnessSessionEntity(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
        modelId = null,
    )

    private fun pending(text: String) = PendingMessage(text = text, createdAt = 42L, taskId = "task-$text")

    private class FakeSessions : HarnessSessionRepository {
        val sessions = LinkedHashMap<String, HarnessSessionEntity>()

        override fun observeAll(): Flow<List<HarnessSessionEntity>> = flowOf(sessions.values.toList())
        override suspend fun findById(id: String) = sessions[id]
        override suspend fun upsert(session: HarnessSessionEntity) { sessions[session.id] = session }
        override suspend fun touch(id: String, updatedAt: Long) = Unit
        override suspend fun rename(id: String, title: String, updatedAt: Long) {
            val current = sessions[id] ?: return
            sessions[id] = current.copy(title = title, updatedAt = updatedAt)
        }
        override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setRunMode(id: String, runMode: String, updatedAt: Long) = Unit
        override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) = Unit
        override suspend fun deleteSession(id: String) { sessions.remove(id) }
        override suspend fun countInRange(start: Long?, end: Long?) = 0
        override suspend fun listAll() = sessions.values.toList()
    }

    private class FakeTasks : AgentTaskRepository {
        override fun observeAll(): Flow<List<AgentTaskEntity>> = flowOf(emptyList())
        override suspend fun find(id: String): AgentTaskEntity? = null
        override suspend fun listByStatus(statuses: List<String>) = emptyList<AgentTaskEntity>()
        override suspend fun listForSession(sessionId: String, statuses: List<String>) = emptyList<AgentTaskEntity>()
        override suspend fun upsert(task: AgentTaskEntity) = Unit
        override suspend fun transition(transition: AgentTaskTransition) = false
        override suspend fun checkpoint(checkpoint: AgentTaskCheckpoint) = false
        override suspend fun deleteForSession(sessionId: String) = Unit
        override suspend fun delete(id: String) = Unit
    }
}
