package top.tianyan.app.ui.bot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.feature.bot.R

@Composable
internal fun liveStatusText(message: GroupMessage?, assignment: BotAssignment?): String? {
    if (message == null) {
        val task = assignment ?: return null
        return when (task.status) {
            BotAssignmentStatus.COMPLETED ->
                task.summary?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fbot_work_status_completed)
            BotAssignmentStatus.FAILED ->
                task.error?.takeIf { it.isNotBlank() }
                    ?: task.summary?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.fbot_work_status_failed)
            BotAssignmentStatus.BLOCKED ->
                task.error?.takeIf { it.isNotBlank() }
                    ?: task.summary?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.fbot_assignment_blocked)
            BotAssignmentStatus.CANCELLED -> stringResource(R.string.fbot_work_status_cancelled)
            BotAssignmentStatus.INTERRUPTED -> stringResource(R.string.fbot_assignment_interrupted)
            BotAssignmentStatus.WAITING_CHILDREN -> stringResource(R.string.fbot_assignment_waiting_children)
            BotAssignmentStatus.QUEUED -> stringResource(R.string.fbot_assignment_queued)
            BotAssignmentStatus.RUNNING -> stringResource(R.string.fbot_member_waiting_start)
        }
    }
    val runningStep = message.steps.lastOrNull { it.success == null }
    if (runningStep != null) return officeStepAction(runningStep)
    outputSnippet(message.contentText.takeLast(512).toString())?.let { return it }
    reasoningSnippet(message.reasoning)?.let { return it }
    return stringResource(R.string.fbot_office_thinking_fallback)
}

@Composable
internal fun statusLabel(status: BotAssignmentStatus): String = when (status) {
    BotAssignmentStatus.WAITING_CHILDREN -> stringResource(R.string.fbot_assignment_waiting_children)
    BotAssignmentStatus.QUEUED -> stringResource(R.string.fbot_assignment_queued)
    BotAssignmentStatus.BLOCKED -> stringResource(R.string.fbot_assignment_blocked)
    BotAssignmentStatus.INTERRUPTED -> stringResource(R.string.fbot_assignment_interrupted)
    BotAssignmentStatus.RUNNING -> stringResource(R.string.fbot_work_status_running)
    BotAssignmentStatus.COMPLETED -> stringResource(R.string.fbot_work_status_completed)
    BotAssignmentStatus.FAILED -> stringResource(R.string.fbot_work_status_failed)
    BotAssignmentStatus.CANCELLED -> stringResource(R.string.fbot_work_status_cancelled)
}
