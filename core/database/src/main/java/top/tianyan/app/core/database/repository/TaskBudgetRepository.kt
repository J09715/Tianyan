package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.database.TaskBudgetDao
import top.tianyan.app.core.model.bot.TaskBudgetState

interface TaskBudgetRepository {
    fun observe(groupId: String): Flow<List<TaskBudgetState>>
    suspend fun reserve(sessionId: String, requestId: String, tokens: Long): Boolean
    suspend fun settle(requestId: String, tokens: Long, known: Boolean)
    suspend fun setDefault(groupId: String, tokens: Long)
    suspend fun setLimit(groupId: String, id: String, tokens: Long)
    suspend fun forSession(sessionId: String): TaskBudgetState?
    suspend fun block(sessionId: String, reason: String)
}

class RoomTaskBudgetRepository(private val dao: TaskBudgetDao) : TaskBudgetRepository {
    override suspend fun block(sessionId: String, reason: String) = dao.blockForBudget(sessionId, "【任务预算】$reason")
    override fun observe(groupId: String) = dao.observeBudgets(groupId)
    override suspend fun reserve(sessionId: String, requestId: String, tokens: Long) = dao.reserve(sessionId, requestId, tokens)
    override suspend fun settle(requestId: String, tokens: Long, known: Boolean) {
        require(tokens >= 0)
        dao.settleBudgetRequest(requestId, if (known) "settled" else "unknown", tokens)
    }
    override suspend fun setDefault(groupId: String, tokens: Long) {
        require(tokens in 0..TaskBudgetState.MAX_LIMIT) { "预算超出允许范围" }
        dao.setDefaultBudget(groupId, tokens)
    }
    override suspend fun setLimit(groupId: String, id: String, tokens: Long) {
        require(tokens in 0..TaskBudgetState.MAX_LIMIT) { "预算超出允许范围" }
        check(dao.setBudgetLimit(id, groupId, tokens) == 1) { "预算不存在或不属于当前群" }
    }
    override suspend fun forSession(sessionId: String): TaskBudgetState? = dao.budgetTaskForSession(sessionId)?.let {
        dao.budgetState(dao.ensureBudget(it).id)
    }
}
