package top.tianyan.app.core.database

import kotlinx.serialization.json.Json

internal const val DEPENDENCY_BLOCK_REASON = "前置任务尚未完成，或方案版本/审议未通过；请核验依赖、审议规则及已批准版本后继续原任务。"

/** Snapshot checks belong to the same transaction as admission/claim, not just the model-facing tool. */
internal suspend fun BotAssignmentDao.hasCompletedDependencies(task: BotAssignmentEntity): Boolean {
    if (!governanceAllows(task)) return false
    val ids = runCatching { Json.decodeFromString<List<String>>(task.dependsOnJson) }.getOrElse { return false }
    return ids.all { id ->
        id != task.id && findById(id)?.let { it.groupId == task.groupId && it.status == "COMPLETED" } == true
    }
}
