package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.harness.bot.ActiveBotAssignment
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton

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

@Composable
internal fun GroupWorkSummaryBar(summary: GroupWorkSummary, onOpen: () -> Unit) {
    if (summary.active == 0 && summary.attention == 0) return
    val labels = buildList {
        if (summary.running > 0) add(stringResource(R.string.fbot_summary_running, summary.running))
        if (summary.queued > 0) add(stringResource(R.string.fbot_summary_queued, summary.queued))
        if (summary.attention > 0) add(stringResource(R.string.fbot_summary_attention, summary.attention))
    }
    RuntimeTextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeIcon(if (summary.attention > 0) RuntimeIconName.Alert else RuntimeIconName.Checklist,
                Modifier.size(18.dp), if (summary.attention > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(labels.joinToString(" · "), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp))
        }
    }
}
