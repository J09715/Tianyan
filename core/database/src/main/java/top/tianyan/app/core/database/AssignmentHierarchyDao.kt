package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AssignmentHierarchyDao : GroupTeamDao {
    @Query("SELECT COUNT(*) FROM bot_work_batches WHERE requesterSessionId = :sessionId AND status IN ('WAITING_CHILDREN','WAITING_TURN')")
    suspend fun waitingChildBatches(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM bot_assignments WHERE parentAssignmentId = :parentId AND batchId IS NULL AND status IN ('QUEUED','RUNNING','WAITING_CHILDREN')")
    suspend fun waitingUnbatchedChildren(parentId: String): Int

    @Query("UPDATE bot_assignment_requests SET status = 'received', summary = '下级结果已由原任务的同步工具接收，仍待原任务验收；未另起执行轮次' WHERE id = :id AND status = 'pending'")
    suspend fun consumePendingChildResult(id: String)

    @Query("SELECT * FROM bot_assignments WHERE parentAssignmentId = :parentId")
    suspend fun childAssignments(parentId: String): List<BotAssignmentEntity>
}

/** Preserve original task/session ownership across organization changes. */
internal suspend fun BotAssignmentDao.resumeParent(batch: BotWorkBatchEntity, children: List<BotAssignmentEntity>, now: Long): Boolean {
    val parent = assignmentForSession(batch.requesterSessionId) ?: return false
    if (parent.groupId != batch.groupId || parent.botId != batch.requesterBotId) return false
    if (parent.status !in setOf("CANCELLED", "INTERRUPTED", "FAILED") && hasOpenTaskAncestry(parent)) {
        enqueueRequest(BotAssignmentRequestEntity(
            id = "child-batch:${batch.id}:${batch.revision}", assignmentId = parent.id,
            instruction = buildBatchReport(batch, children) +
                "\n这是原任务的受派成员交付。请核验产物与证据，处理失败与受阻；尚有子任务时继续等待，验收后向原上级简要交付，不能把成员声明直接当作完成。",
            createdAt = now,
        ))
    }
    return true
}

/** Original task ancestry is independent of later organization edits. Called inside owner transactions. */
internal suspend fun BotAssignmentDao.hasOpenTaskAncestry(task: BotAssignmentEntity): Boolean {
    val visited = mutableSetOf(task.id)
    var current = task
    while (current.parentAssignmentId != null) {
        val parent = findById(requireNotNull(current.parentAssignmentId)) ?: return false
        if (!visited.add(parent.id) || parent.status == "CANCELLED" || parent.groupId != task.groupId ||
            parent.botId != current.reportToBotId) return false
        current = parent
    }
    return true
}

/** Traverse terminal ancestors too: their descendants may still be running after a failed turn. */
internal suspend fun BotAssignmentDao.cancelLiveDescendants(task: BotAssignmentEntity, now: Long) {
    val seen = mutableSetOf(task.id)
    val descendants = mutableListOf<BotAssignmentEntity>()
    val pending = ArrayDeque<String>().apply { add(task.id) }
    while (pending.isNotEmpty()) {
        childAssignments(pending.removeFirst()).forEach { child ->
            if (seen.add(child.id)) { descendants += child; pending.add(child.id) }
        }
    }
    descendants.asReversed().forEach { child ->
        if (child.status in setOf("QUEUED", "RUNNING", "WAITING_CHILDREN", "BLOCKED")) cancelTaskNode(child, now)
        else cancelAssignmentRequests(child.id)
    }
}

internal suspend fun BotAssignmentDao.cancelTaskNode(task: BotAssignmentEntity, now: Long) {
    if (!task.independent) {
        finish(task.id, "CANCELLED", now, null, "用户取消")
        return
    }
    cancelAssignmentRequests(task.id)
    if (task.status == "CANCELLED") return
    settleAssignment(task.id, "CANCELLED", now, task.summary, "用户取消")
    if (task.batchId != null) settleBatchIfIdle(task.batchId, now)
    else {
        val lastRequest = assignmentRequests(task.id).firstOrNull()?.id.orEmpty()
        stageAssignmentReport(task.copy(activeRequestId = "cancel:$lastRequest"), "CANCELLED", now, null, "用户取消")
    }
}
