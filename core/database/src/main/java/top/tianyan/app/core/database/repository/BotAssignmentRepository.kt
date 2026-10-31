package top.tianyan.app.core.database.repository

import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotWorkBatch

interface BotAssignmentRepository : IndependentAssignmentRepository {
    fun observeForGroup(groupId: String): Flow<List<BotAssignment>>
    suspend fun listRunning(groupId: String): List<BotAssignment>
    suspend fun listRecent(limit: Int = 20): List<BotAssignment>
    suspend fun get(id: String): BotAssignment?
    suspend fun save(assignment: BotAssignment)
    suspend fun finish(
        id: String,
        status: BotAssignmentStatus,
        summary: String? = null,
        error: String? = null,
        finishedAt: Long = System.currentTimeMillis(),
    )

    /** 进程重启后把遗留的 RUNNING 记录收敛为失败，避免工作面板永久显示“执行中”。 */
    suspend fun failRunning(now: Long = System.currentTimeMillis(), error: String = "进程已退出，原执行结果待核对；请检查原会话和产物，不要重复执行")

    suspend fun getBatch(id: String): BotWorkBatch?

    suspend fun listBatches(groupId: String, limit: Int = 20): List<BotWorkBatch>

    suspend fun batchAssignments(batchId: String): List<BotAssignment>

    suspend fun batchTaskCount(batchId: String): Int

    /** 收敛没有活跃子任务、且主控回合已结束的批次。 */
    suspend fun reconcileWaitingBatches(now: Long = System.currentTimeMillis())
}
