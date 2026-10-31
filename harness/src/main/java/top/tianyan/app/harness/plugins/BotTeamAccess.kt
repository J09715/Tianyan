package top.tianyan.app.harness.plugins

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.GroupChat

internal const val DIRECT_REPORT_ONLY = "只能向本群直属下级派发新任务；已有任务仅原派发负责人可续办或取消"

internal suspend fun assignmentInSessionGroup(sessionId: String, task: BotAssignment, bots: PersonaBotRepository): Boolean {
    val groupId = bots.observeGroupSessionRefs().first()[sessionId]?.groupId
    return groupId == null || groupId == task.groupId
}

/** A group-bound session must not silently fall back to another team. */
internal suspend fun resolveTeamGroup(caller: Bot, sessionId: String, requested: String?, groups: GroupChatRepository,
    bots: PersonaBotRepository): GroupChat? {
    val bound = bots.observeGroupSessionRefs().first()[sessionId]?.groupId
    if (bound != null && !requested.isNullOrBlank() && requested != bound) return null
    return resolveGroup(caller, bound ?: requested, groups)
}

internal suspend fun canManageAssignment(caller: Bot, task: BotAssignment, groups: GroupChatRepository): Boolean {
    val groupId = task.groupId ?: return caller.isMainBot
    if (groups.getMembers(groupId).none { it.id == caller.id }) return false
    return task.reportToBotId == caller.id || (task.reportToBotId == null && groups.getGroup(groupId)?.adminBotId == caller.id)
}

internal suspend fun canInspectAssignment(caller: Bot, task: BotAssignment, groups: GroupChatRepository): Boolean {
    val groupId = task.groupId ?: return caller.isMainBot
    return canManageAssignment(caller, task, groups) || groups.getTeam(groupId).canInspect(caller.id, task.botId)
}
