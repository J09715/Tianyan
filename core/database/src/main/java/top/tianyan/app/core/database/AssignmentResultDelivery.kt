package top.tianyan.app.core.database

internal fun childResultReceiptId(id: String, revision: Int, status: String) = "child-result:$id:$revision:$status"

/** Persist first: synchronous waiters acknowledge this receipt, later results resume the original task. */
internal suspend fun BotAssignmentDao.stageUnbatchedChildResult(child: BotAssignmentEntity, status: String, content: String, now: Long) {
    if (status == "QUEUED") return
    val parent = child.parentAssignmentId?.let { findById(it) } ?: return
    if (!parent.independent || parent.sessionId == null || parent.groupId != child.groupId || parent.botId != child.reportToBotId ||
        parent.status in setOf("CANCELLED", "INTERRUPTED", "FAILED") || !hasOpenTaskAncestry(parent) ||
        !deliveryMember(requireNotNull(parent.groupId), parent.botId)) return
    enqueueRequest(BotAssignmentRequestEntity(
        id = childResultReceiptId(child.id, child.revision, status), assignmentId = parent.id,
        instruction = content + "\n这是原任务的直属下级交付，请在原会话核验后再向上级交付。", createdAt = now,
    ))
}
