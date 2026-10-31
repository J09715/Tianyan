package top.tianyan.app.core.database.task

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentTaskObservationTest {
    private fun task(id: String) = AgentTaskEntity(id, "s", "title", "input", AgentTaskStatus.COMPLETED, 1, 1)
    private class Repository(private val rows: List<AgentTaskEntity>) : AgentTaskRepository {
        override fun observeAll() = flowOf(rows)
        override suspend fun find(id: String): AgentTaskEntity? = error("unused")
        override suspend fun listByStatus(statuses: List<String>): List<AgentTaskEntity> = error("unused")
        override suspend fun listForSession(sessionId: String, statuses: List<String>): List<AgentTaskEntity> = error("unused")
        override suspend fun upsert(task: AgentTaskEntity): Unit = error("unused")
        override suspend fun transition(transition: AgentTaskTransition): Boolean = error("unused")
        override suspend fun checkpoint(checkpoint: AgentTaskCheckpoint): Boolean = error("unused")
        override suspend fun deleteForSession(sessionId: String): Unit = error("unused")
        override suspend fun delete(id: String): Unit = error("unused")
    }
    @Test fun defaultObservationSelectsExactTaskAndPreservesTerminalState() = runBlocking {
        val selected = task("wanted")
        assertEquals(selected, Repository(listOf(task("other"), selected)).observeTask("wanted").first())
    }
    @Test fun defaultObservationReturnsNullForMissingTask() = runBlocking {
        assertNull(Repository(listOf(task("other"))).observeTask("missing").first())
    }
}
