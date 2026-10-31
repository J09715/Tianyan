package top.tianyan.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.bot.TaskBudgetState

@Entity(tableName = "bot_task_budgets", foreignKeys = [ForeignKey(entity = GroupChatEntity::class,
    parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)], indices = [Index("groupId")])
data class TaskBudgetEntity(@PrimaryKey val id: String, val groupId: String, val title: String,
    val limitTokens: Long, val createdAt: Long)

@Entity(tableName = "bot_budget_requests", foreignKeys = [ForeignKey(entity = TaskBudgetEntity::class,
    parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)], indices = [Index("budgetId")])
data class BudgetRequestEntity(@PrimaryKey val id: String, val budgetId: String, val sessionId: String,
    val tokens: Long, val state: String, val createdAt: Long)

private const val BUDGET_PROJECTION = "SELECT b.*, COALESCE(SUM(CASE WHEN r.state = 'settled' THEN r.tokens ELSE 0 END),0) AS usedTokens, " +
    "COALESCE(SUM(CASE WHEN r.state != 'settled' THEN r.tokens ELSE 0 END),0) AS heldTokens, " +
    "CAST(COALESCE(SUM(CASE WHEN r.state = 'unknown' THEN 1 ELSE 0 END),0) AS INTEGER) AS unknownRequests " +
    "FROM bot_task_budgets b LEFT JOIN bot_budget_requests r ON b.id = r.budgetId "

@Dao
interface TaskBudgetDao {
    @Query(BUDGET_PROJECTION + "WHERE b.groupId = :groupId AND (b.id IN " +
        "(SELECT id FROM bot_task_budgets WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT 30) OR EXISTS " +
        "(SELECT 1 FROM bot_assignments a WHERE a.groupId = :groupId AND a.budgetId = b.id " +
        "AND a.status NOT IN ('COMPLETED','CANCELLED'))) GROUP BY b.id ORDER BY b.createdAt DESC")
    fun observeBudgets(groupId: String): Flow<List<TaskBudgetState>>
    @Query(BUDGET_PROJECTION + "WHERE b.id = :id GROUP BY b.id")
    suspend fun budgetState(id: String): TaskBudgetState?
    @Query("SELECT * FROM bot_task_budgets WHERE id = :id")
    suspend fun budget(id: String): TaskBudgetEntity?
    @Query("SELECT * FROM bot_assignments WHERE sessionId = :sessionId AND independent = 1 LIMIT 1")
    suspend fun budgetTaskForSession(sessionId: String): BotAssignmentEntity?
    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId AND independent = 1")
    suspend fun budgetTasks(groupId: String): List<BotAssignmentEntity>
    @Query("SELECT taskBudgetTokens FROM group_chats WHERE id = :groupId")
    suspend fun defaultBudget(groupId: String): Long?
    @Query("UPDATE group_chats SET taskBudgetTokens = :tokens WHERE id = :groupId")
    suspend fun setDefaultBudget(groupId: String, tokens: Long)
    @Query("UPDATE bot_task_budgets SET limitTokens = :tokens WHERE id = :id AND groupId = :groupId")
    suspend fun setBudgetLimit(id: String, groupId: String, tokens: Long): Int
    @Query("UPDATE bot_assignments SET budgetId = :budgetId WHERE id = :id AND budgetId IS NULL")
    suspend fun bindTaskBudget(id: String, budgetId: String)
    @Query("SELECT COALESCE(SUM(inputTokens + outputTokens),0) FROM harness_usage WHERE sessionId = :sessionId AND adjustment = 0")
    suspend fun baselineUsage(sessionId: String): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBudget(budget: TaskBudgetEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBudgetRequest(request: BudgetRequestEntity): Long
    @Query("UPDATE bot_budget_requests SET state = :state, tokens = :tokens WHERE id = :id AND state = 'reserved'")
    suspend fun settleBudgetRequest(id: String, state: String, tokens: Long): Int

    suspend fun rootBudget(task: BotAssignmentEntity): TaskBudgetEntity {
        val group = requireNotNull(task.groupId)
        val id = "$group:${task.rootWorkId ?: task.batchId ?: task.id}"
        insertBudget(TaskBudgetEntity(id, group, task.title, defaultBudget(group) ?: 0, System.currentTimeMillis()))
        return requireNotNull(budget(id))
    }

    @Transaction
    suspend fun ensureBudget(task: BotAssignmentEntity): TaskBudgetEntity {
        task.budgetId?.let { return requireNotNull(budget(it)) }
        val all = budgetTasks(requireNotNull(task.groupId))
        val byId = all.associateBy { it.id }
        var root = task
        val visited = mutableSetOf(task.id)
        while (root.parentAssignmentId != null) {
            val parent = byId[root.parentAssignmentId] ?: break
            check(visited.add(parent.id)) { "父任务关系存在循环" }
            root = parent
        }
        val owner = root.budgetId?.let { budget(it) } ?: rootBudget(root)
        val ids = all.filter { it.id == root.id || (root.batchId != null && it.batchId == root.batchId && it.parentAssignmentId == null) }
            .mapTo(mutableSetOf()) { it.id }.also { it.add(root.id) }
        var changed: Boolean
        do { changed = ids.addAll(all.filter { it.parentAssignmentId in ids }.map { it.id }) } while (changed)
        all.filter { it.id in ids && it.budgetId == null }.forEach { item ->
            bindTaskBudget(item.id, owner.id)
            item.sessionId?.let { session -> insertBudgetRequest(BudgetRequestEntity("baseline:${owner.id}:$session",
                owner.id, session, baselineUsage(session).coerceAtLeast(0), "settled", System.currentTimeMillis())) }
        }
        return owner
    }

    @Transaction
    suspend fun reserve(sessionId: String, requestId: String, tokens: Long): Boolean {
        require(tokens > 0)
        val task = budgetTaskForSession(sessionId) ?: return false
        val owner = ensureBudget(task)
        val state = requireNotNull(budgetState(owner.id))
        check(task.status == "RUNNING") { "任务已停止，不能继续请求模型" }
        if (state.limitTokens != 0L && tokens > state.availableTokens) throw top.tianyan.app.core.model.bot.TaskBudgetExceededException(
            "任务树预算不足：剩余 ${state.availableTokens}，本次需预留 $tokens tokens；请在群信息的任务预算中调整额度，再继续原任务。")
        check(insertBudgetRequest(BudgetRequestEntity(requestId, owner.id, sessionId, tokens, "reserved", System.currentTimeMillis())) != -1L)
        clearBudgetBlock(sessionId)
        return true
    }

    @Query("UPDATE bot_assignment_requests SET blockedReason = :reason WHERE id IN (SELECT activeRequestId FROM bot_assignments WHERE sessionId = :sessionId) AND status IN ('submitting','admitted')")
    suspend fun blockForBudget(sessionId: String, reason: String)
    @Query("UPDATE bot_assignment_requests SET blockedReason = NULL WHERE id IN (SELECT activeRequestId FROM bot_assignments WHERE sessionId = :sessionId) AND blockedReason LIKE '【任务预算】%'")
    suspend fun clearBudgetBlock(sessionId: String)
}
