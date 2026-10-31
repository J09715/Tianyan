package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy

/** Called inside admission's transaction; the first task owns the stage dispatch lease. */
@Dao
interface AssignmentWorkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDispatch(receipt: AssignmentDispatchReceipt)

    @Query("SELECT DISTINCT botId FROM assignment_dispatch_receipts WHERE groupId = :groupId AND (operationId = :operationId OR nativeTaskId = :nativeTaskId)")
    suspend fun dispatchedBots(groupId: String, operationId: String, nativeTaskId: String?): List<String>

    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId AND rootWorkId = :workId AND stageId = :stageId ORDER BY createdAt, id")
    suspend fun stageAssignments(groupId: String, workId: String, stageId: String): List<BotAssignmentEntity>

    suspend fun existingWorkTask(task: BotAssignmentEntity): String? {
        val work = task.rootWorkId ?: return null
        val stage = requireNotNull(task.stageId)
        val item = requireNotNull(task.workItemId)
        val rows = stageAssignments(requireNotNull(task.groupId), work, stage)
        val existing = rows.firstOrNull { it.workItemId == item }
        if (existing != null) {
            require(existing.teamBinding == task.teamBinding) { "原工作项属于其他团队版本，请先核验旧任务" }
            require(existing.proposalId == task.proposalId && existing.proposalVersion == task.proposalVersion) { "原工作项绑定的方案版本不一致" }
            require(existing.botId == task.botId) { "该工作项已交给 ${existing.botName}，请核对原任务 ${existing.id}" }
            return existing.id
        }
        require(rows.isEmpty() || rows.first().reportToBotId == task.reportToBotId) {
            "本阶段已由 ${rows.first().reportToBotId} 负责派单，请向原负责人汇报，不能另开工作项"
        }
        return null
    }
}
