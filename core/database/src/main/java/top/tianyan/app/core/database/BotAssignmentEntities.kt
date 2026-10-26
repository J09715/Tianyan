package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import top.tianyan.app.core.model.bot.BotWorkBatch

@Entity(
    tableName = "bot_assignments",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["status"]),
        Index(value = ["sessionId"]),
        Index(value = ["batchId", "status"]),
        Index(value = ["groupId", "rootWorkId", "stageId", "workItemId"], unique = true),
    ],
)
data class BotAssignmentEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val botId: String,
    val botName: String,
    val title: String,
    val task: String,
    val status: String,
    val writePathsJson: String,
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val summary: String?,
    val error: String?,
    val sessionId: String?,
    val laneName: String?,
    @ColumnInfo(defaultValue = "NULL") val reportToBotId: String? = null,
    @ColumnInfo(defaultValue = "0") val independent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val revision: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val activeRequestId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val dependsOnJson: String = "[]",
    @ColumnInfo(defaultValue = "NULL") val batchId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val parentAssignmentId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val budgetId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val proposalId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val proposalVersion: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val teamBinding: String? = null,
    @ColumnInfo(defaultValue = "NULL") val rootWorkId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val stageId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val workItemId: String? = null,
)

@Dao
interface BotAssignmentDao : GroupDeliveryDao, BotAssignmentRequestDao, AssignmentHierarchyDao, TaskBudgetDao, GroupGovernanceDao, AssignmentWorkDao {
    @Query("SELECT * FROM bot_assignments WHERE botId = :botId AND independent = 1")
    suspend fun assignmentsForBot(botId: String): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments WHERE sessionId = :sessionId AND independent = 1 LIMIT 1")
    suspend fun assignmentForSession(sessionId: String): BotAssignmentEntity?

    @Transaction
    suspend fun createIndependent(
        task: BotAssignmentEntity,
        session: HarnessSessionEntity,
        request: BotAssignmentRequestEntity,
        batch: BotWorkBatchEntity? = null,
        dispatchOperationId: String? = null,
        nativeTaskId: String? = null,
    ): String {
        require(task.independent && task.sessionId == session.id && request.assignmentId == task.id)
        val group = requireNotNull(task.groupId)
        require(deliveryMember(group, task.botId)) { "执行成员已移出群聊" }
        require(task.reportToBotId != null && deliveryMember(group, task.reportToBotId)) { "请求者已移出群聊" }
        val team = effectiveTeam(group)
        require(team.canAssign(task.reportToBotId, task.botId) || governance(group).canRoute(task.reportToBotId, task.botId)) { "只能向本群直属下级派发新任务" }
        val boundWork = task.proposalId?.let { proposal(it) }?.rootWorkId
        require(boundWork == null || task.rootWorkId == boundWork) { "方案所属业务任务不匹配" }
        existingWorkTask(task.copy(teamBinding = linkedBinding(group)))?.let {
            if (dispatchOperationId != null) recordDispatch(AssignmentDispatchReceipt(dispatchOperationId, it, group, task.botId, nativeTaskId))
            return it
        }
        task.parentAssignmentId?.let { id ->
            val parent = requireNotNull(findById(id)) { "父任务不存在" }
            require(parent.status == "RUNNING" && parent.botId == task.reportToBotId && parent.groupId == group) { "父任务已停止或不属于当前负责人" }
        }
        require(hasOpenTaskAncestry(task)) { "原上级任务已取消或归属无法核对，不能继续派发" }
        require(hasCompletedDependencies(task.copy(teamBinding = linkedBinding(group)))) { DEPENDENCY_BLOCK_REASON }
        if (batch == null) {
            require(task.batchId == null) { "批次实体缺失" }
        } else {
            require(task.batchId == batch.id) { "任务与批次不一致" }
            require(batch.groupId == group && batch.requesterBotId == task.reportToBotId) { "批次来源群或负责人不匹配" }
            require(assignmentForSession(batch.requesterSessionId)?.id == task.parentAssignmentId) { "批次来源会话与原父任务不匹配" }
            val existing = findBatch(batch.id)
            require(existing == null || (existing.groupId == batch.groupId && existing.requesterBotId == batch.requesterBotId &&
                existing.requesterSessionId == batch.requesterSessionId && existing.originOperationId == batch.originOperationId)) {
                "批次 ID 已属于其他群聊、负责人、会话或轮次"
            }
            require(batch.revision >= 1) { "批次 revision 必须从 1 开始" }
            require(batchTaskCount(batch.id) < BotWorkBatch.MAX_TASKS) {
                "本批任务已达上限（${BotWorkBatch.MAX_TASKS}）"
            }
            if (insertBatchIfAbsent(batch) == -1L) bumpBatchRevision(batch.id, batch.updatedAt)
        }
        val parent = task.parentAssignmentId?.let { findById(it) }
        val owner = parent?.let { ensureBudget(it) } ?: rootBudget(task)
        val state = requireNotNull(budgetState(owner.id))
        if (state.limitTokens > 0 && state.availableTokens == 0L) throw top.tianyan.app.core.model.bot.TaskBudgetExceededException("任务树预算不足，不能继续分派；请由用户调整原预算。")
        insertIndependentSession(session)
        insertIndependentAssignment(task.copy(budgetId = owner.id, teamBinding = linkedBinding(group)))
        insertAssignmentRequest(request)
        if (dispatchOperationId != null) recordDispatch(AssignmentDispatchReceipt(dispatchOperationId, task.id, group, task.botId, nativeTaskId))
        return task.id
    }

    @Transaction
    suspend fun enqueueRequest(request: BotAssignmentRequestEntity) {
        val task = requireNotNull(findById(request.assignmentId)) { "任务不存在" }
        require(task.independent) { "旧版任务没有独立会话，请重新派发" }
        require(task.teamBinding == task.groupId?.let { linkedBinding(it) }) { "团队版本已变化，请在新版下创建新任务" }
        require(deliveryMember(requireNotNull(task.groupId), task.botId)) { "执行成员已移出群聊" }
        val existing = findAssignmentRequest(request.id)
        if (existing != null) {
            require(existing.assignmentId == request.assignmentId && existing.instruction == request.instruction) { "补充要求 ID 已被其他内容使用" }
            return
        }
        require(hasOpenTaskAncestry(task)) { "原上级任务已取消或归属无法核对，请先核验并恢复原上级任务" }
        if (task.status == "INTERRUPTED") cancelAssignmentRequests(task.id)
        task.batchId?.let { bumpBatchRevision(it, System.currentTimeMillis()) }
        insertAssignmentRequest(request)
        queueAssignment(task.id)
    }

    @Transaction
    suspend fun claimRequest(id: String, now: Long): Boolean {
        val request = findAssignmentRequest(id)?.takeIf { it.status == "pending" } ?: return false
        val task = findById(request.assignmentId) ?: return false
        if (task.status != "QUEUED" || !task.independent) return false
        if (!hasOpenTaskAncestry(task) || !deliveryMember(requireNotNull(task.groupId), task.botId) ||
            task.reportToBotId?.let { !deliveryMember(task.groupId, it) } == true) {
            cancelAssignment(task.id, now)
            return false
        }
        if (activateAssignment(task.id, id, now) != 1) return false
        check(claimAssignmentRequest(id) == 1)
        if (!hasCompletedDependencies(task)) {
            declareAssignmentBlock(id, DEPENDENCY_BLOCK_REASON, "[]")
            finishRequest(id, false, false, null, DEPENDENCY_BLOCK_REASON, now)
            return false
        }
        return true
    }

    @Transaction
    suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?, now: Long) {
        val request = findAssignmentRequest(id) ?: return
        val task = findById(request.assignmentId) ?: return
        if (task.activeRequestId != id || task.status != "RUNNING") return
        val requestStatus = if (uncertain) "unknown" else if (success) "completed" else "failed"
        if (settleAssignmentRequest(id, requestStatus, summary, error) != 1) return
        val outcome = when {
            uncertain -> "INTERRUPTED"
            !request.blockedReason.isNullOrBlank() -> "BLOCKED"
            !success -> "FAILED"
            else -> "COMPLETED"
        }
        // Uncertain native effects require explicit resumption, even if a later instruction was queued.
        val waiting = !uncertain && success && (waitingUnbatchedChildren(task.id) > 0 ||
            task.sessionId?.let { waitingChildBatches(it) > 0 } == true)
        val status = if (!uncertain && queuedAssignmentRequests(task.id) > 0) "QUEUED" else if (waiting) "WAITING_CHILDREN" else outcome
        if (uncertain) settleInterruptedAssignmentMessages(task.id, "⚠️ 原执行结果待核对，未自动重跑")
        val detail = if (uncertain) error else request.blockedReason ?: if (!success) error else if (waiting) "等待直属下级交付后在原任务验收" else null
        settleAssignment(task.id, status, now, summary, detail)
        if (waiting || (status == "QUEUED" && childAssignments(task.id).isNotEmpty())) return
        if (task.batchId == null) {
            stageAssignmentReport(task, status, now, summary, detail)
        } else if (status != "QUEUED") {
            settleBatchIfIdle(task.batchId, now)
        }
    }

    @Transaction
    suspend fun recoverRequests(now: Long) {
        interruptedAssignmentRequests().forEach { request ->
            finishRequest(request.id, false, true, null, "进程退出，原执行结果待核对；继续前先核验原会话和产物", now)
        }
    }

    @Transaction
    suspend fun cancelAssignment(id: String, now: Long) {
        val task = findById(id) ?: return
        cancelTaskNode(task, now)
        cancelLiveDescendants(task, now)
    }

    @Transaction
    suspend fun acknowledgeSynchronousResult(id: String, revision: Int, status: String, sourceSessionId: String) {
        val child = findById(id) ?: return
        val parent = child.parentAssignmentId?.let { findById(it) } ?: return
        if (child.batchId != null || parent.sessionId != sourceSessionId || parent.status != "RUNNING" ||
            parent.groupId != child.groupId || parent.botId != child.reportToBotId) return
        consumePendingChildResult(childResultReceiptId(id, revision, status))
    }

    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeForGroup(groupId: String): Flow<List<BotAssignmentEntity>>

    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId AND status = 'RUNNING' ORDER BY createdAt ASC")
    suspend fun listRunning(groupId: String): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BotAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: BotAssignmentEntity)

    @Query(
        "UPDATE bot_assignments SET status = :status, finishedAt = :finishedAt, " +
            "summary = :summary, error = :error WHERE id = :id",
    )
    suspend fun settleAssignment(id: String, status: String, finishedAt: Long, summary: String?, error: String?)

    @Transaction
    suspend fun finish(id: String, status: String, finishedAt: Long, summary: String?, error: String?) {
        val previous = findById(id) ?: return
        if (previous.status != "RUNNING") return
        settleAssignment(id, status, finishedAt, summary, error)
        stageAssignmentReport(previous, status, finishedAt, summary, error)
    }

    suspend fun stageAssignmentReport(assignment: BotAssignmentEntity, status: String, now: Long, summary: String?, error: String?) {
        val recipient = assignment.reportToBotId ?: return
        val groupId = assignment.groupId ?: return
        val label = when (status) {
            "COMPLETED" -> "已完成"
            "CANCELLED" -> "已取消"
            "BLOCKED" -> "受阻"
            "INTERRUPTED" -> "执行结果待核对"
            "QUEUED" -> "本轮结束，仍有补充要求待处理"
            else -> "执行未完成"
        }
        val content = "【成员委派结果】${assignment.botName} · $label（任务 ${assignment.id}）\n" +
            (summary ?: "无结果摘要").take(4_000) + (error?.let { "\n原因：$it" } ?: "") +
            "\n请核对原任务与产物；结果未知时先只读核验，勿重复执行。"
        if (assignment.parentAssignmentId != null && assignment.batchId == null) {
            stageUnbatchedChildResult(assignment, status, content, now)
            return
        }
        stageGroupDelivery(GroupMessageEntity(
            id = "assignment-report:${assignment.id}" + (assignment.activeRequestId?.let { ":$it" } ?: ""), groupId = groupId, senderId = "system", senderName = "系统通知",
            senderType = "SYSTEM", content = content, timestamp = now, isStreaming = false, delegatedTaskId = assignment.id,
        ), if (status == "CANCELLED") emptyList() else listOf(recipient))
    }

    @Query("DELETE FROM bot_assignments WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query(
        "UPDATE bot_assignments SET status = 'FAILED', finishedAt = :now, error = :error " +
            "WHERE status = 'RUNNING' AND independent = 0",
    )
    suspend fun markRunningInterrupted(now: Long, error: String)

    @Query("SELECT * FROM bot_assignments WHERE status = 'RUNNING' AND independent = 0")
    suspend fun allRunning(): List<BotAssignmentEntity>

    @Transaction
    suspend fun failRunning(now: Long, error: String) {
        val interrupted = allRunning()
        markRunningInterrupted(now, error)
        interrupted.forEach { stageAssignmentReport(it, "FAILED", now, null, error) }
    }

    @Query("SELECT * FROM bot_work_batches WHERE id = :id LIMIT 1")
    suspend fun findBatch(id: String): BotWorkBatchEntity?

    @Query("SELECT * FROM bot_work_batches WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listBatches(groupId: String, limit: Int): List<BotWorkBatchEntity>

    @Query("SELECT * FROM bot_work_batches WHERE status IN ('WAITING_CHILDREN','WAITING_TURN') ORDER BY updatedAt ASC LIMIT 64")
    suspend fun waitingBatches(): List<BotWorkBatchEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatchIfAbsent(batch: BotWorkBatchEntity): Long

    @Query(
        "UPDATE bot_work_batches SET revision = revision + 1, status = 'WAITING_CHILDREN', " +
            "updatedAt = :now, settledAt = NULL WHERE id = :id",
    )
    suspend fun bumpBatchRevision(id: String, now: Long)

    @Query("UPDATE bot_work_batches SET status = :status, updatedAt = :now, settledAt = :settledAt WHERE id = :id")
    suspend fun setBatchStatus(id: String, status: String, now: Long, settledAt: Long?)

    @Query(
        "UPDATE bot_work_batches SET status = :status, reportRevision = :revision, " +
            "updatedAt = :now, settledAt = :now WHERE id = :id",
    )
    suspend fun settleBatch(id: String, status: String, revision: Int, now: Long)

    @Query("SELECT COUNT(*) FROM bot_assignments WHERE batchId = :batchId AND status IN ('QUEUED','RUNNING','WAITING_CHILDREN')")
    suspend fun activeBatchTaskCount(batchId: String): Int

    @Query("SELECT COUNT(*) FROM bot_assignments WHERE batchId = :batchId")
    suspend fun batchTaskCount(batchId: String): Int

    @Query("SELECT * FROM bot_assignments WHERE batchId = :batchId ORDER BY createdAt ASC, id ASC")
    suspend fun batchAssignments(batchId: String): List<BotAssignmentEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM harness_lanes WHERE sessionId = :sessionId " +
            "AND currentOperationId = :operationId AND updatedAt >= :staleBefore)",
    )
    suspend fun isOperationActive(sessionId: String, operationId: String, staleBefore: Long): Boolean

    @Transaction
    suspend fun reconcileWaitingBatches(now: Long) {
        waitingBatches().forEach { settleBatchIfIdle(it.id, now) }
    }

    suspend fun settleBatchIfIdle(batchId: String, now: Long) {
        val batch = findBatch(batchId) ?: return
        if (batch.reportRevision == batch.revision && batch.status in setOf("SETTLED", "CANCELLED")) return
        if (activeBatchTaskCount(batchId) > 0) {
            setBatchStatus(batchId, "WAITING_CHILDREN", now, null)
            return
        }
        val operationActive = batch.originOperationId != null &&
            isOperationActive(batch.requesterSessionId, batch.originOperationId, now - BATCH_OPERATION_STALE_MS)
        if (operationActive) {
            setBatchStatus(batchId, "WAITING_TURN", now, null)
            return
        }
        if (!deliveryMember(batch.groupId, batch.requesterBotId)) {
            settleBatch(batchId, "CANCELLED", batch.revision, now)
            return
        }
        val children = batchAssignments(batchId)
        if (children.isEmpty()) {
            settleBatch(batchId, "CANCELLED", batch.revision, now)
            return
        }
        if (resumeParent(batch, children, now)) {
            settleBatch(batchId, "SETTLED", batch.revision, now)
            return
        }
        stageGroupDelivery(
            GroupMessageEntity(
                id = "assignment-batch-report:${batch.id}:${batch.revision}",
                groupId = batch.groupId,
                senderId = "system",
                senderName = "系统通知",
                senderType = "SYSTEM",
                content = buildBatchReport(batch, children),
                timestamp = now,
                isStreaming = false,
                delegatedTaskId = batch.id,
            ),
            listOf(batch.requesterBotId),
        )
        val status = if (children.all { it.status == "CANCELLED" }) "CANCELLED" else "SETTLED"
        settleBatch(batchId, status, batch.revision, now)
    }
}
