package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.GroupMessage

/** 衍生智能体当前视觉状态 */
internal fun computeMemberAvatarState(
    bot: Bot,
    streamingMessage: GroupMessage?,
    assignment: BotAssignment?,
): BotAvatarState = when {
    assignment?.status == BotAssignmentStatus.BLOCKED -> BotAvatarState.BLOCKED
    assignment?.status == BotAssignmentStatus.FAILED -> BotAvatarState.FAILED
    assignment?.status == BotAssignmentStatus.COMPLETED -> BotAvatarState.SUCCESS
    streamingMessage != null && streamingMessage.steps.any { it.success == null } -> BotAvatarState.EXECUTING
    streamingMessage != null && streamingMessage.reasoning.isNotBlank() && streamingMessage.content.isBlank() -> BotAvatarState.THINKING
    streamingMessage != null -> BotAvatarState.ACTIVE
    assignment?.status == BotAssignmentStatus.RUNNING -> BotAvatarState.EXECUTING
    else -> BotAvatarState.IDLE
}
