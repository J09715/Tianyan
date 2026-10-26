package top.tianyan.app.harness.plugins

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat

/** 从 BotManagementPlugin 抽出以控制文件尺寸：群解析、取色归一、Bot 状态行。 */

internal suspend fun resolveGroup(
    caller: Bot,
    requestedGroupId: String?,
    groupRepository: GroupChatRepository,
): GroupChat? {
    if (requestedGroupId.isNullOrBlank()) {
        return groupRepository.observeGroupsForBot(caller.id).first().firstOrNull()
    }
    val group = groupRepository.getGroup(requestedGroupId) ?: return null
    val member = groupRepository.getMembers(group.id).any { it.id == caller.id }
    return group.takeIf { member }
}

internal fun normalizeColor(raw: String?): String =
    raw?.trim()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#3D5AFE"

/**
 * list-bots 状态行（DS Bot 语义）：按「等用户批准 > 在干活 > 有排队/执行中任务 > 空闲」
 * 取最高优先级状态。状态只进工具输出，不写系统提示（保持提示字节稳定以命中前缀缓存）。
 */
internal fun botStatusLine(
    sessionStates: Map<String, SessionRunState>,
    sessionIds: List<String>,
    activeTaskBotNames: Set<String>,
    botName: String,
): String {
    val states = sessionIds.mapNotNull { sessionStates[it] }
    return "状态=" + when {
        states.any { it == SessionRunState.WAITING_APPROVAL } -> "等用户批准"
        states.any { it == SessionRunState.RUNNING } -> "在干活"
        botName in activeTaskBotNames -> "有排队任务"
        else -> "空闲"
    }
}
