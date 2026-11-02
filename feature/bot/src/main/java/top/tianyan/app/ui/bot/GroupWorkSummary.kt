package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.size
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.harness.bot.ActiveBotAssignment

internal data class GroupWorkSummary(val running: Int, val queued: Int, val attention: Int) {
    val active: Int get() = running + queued
}

/** Persistent task status is authoritative; live task/bubble projections are deduplicated by task ID. */
internal fun summarizeGroupWork(tasks: List<BotAssignment>, live: List<ActiveBotAssignment>, messages: List<GroupMessage>): GroupWorkSummary {
    val byId = tasks.associateBy { it.id }
    val running = tasks.filter { it.status == BotAssignmentStatus.RUNNING }.mapTo(mutableSetOf()) { "task:${it.id}" }
    live.filter { byId[it.taskId]?.status?.let { state -> state.isActive } != false }
        .forEach { running += "task:${it.taskId}" }
    messages.filter { it.isStreaming }.forEach {
        val taskId = it.delegatedTaskId
        if (taskId == null || byId[taskId]?.status?.isActive != false) {
            running += taskId?.let { id -> "task:$id" } ?: "bot:${it.senderId}"
        }
    }
    val queued = tasks.filter { it.status == BotAssignmentStatus.QUEUED }
        .map { "task:${it.id}" }.toSet() - running
    val attention = tasks.filter { it.status in setOf(BotAssignmentStatus.BLOCKED, BotAssignmentStatus.INTERRUPTED, BotAssignmentStatus.FAILED) }
        .map { "task:${it.id}" }.toSet() - running
    return GroupWorkSummary(running.size, queued.size, attention.size)
}
