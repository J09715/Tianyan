package top.tianyan.app.core.database.repository

import top.tianyan.app.core.database.GroupChatEntity
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupReplyMode
import top.tianyan.app.core.database.GroupMemberEntity
import top.tianyan.app.core.model.bot.GroupTeam

internal fun teamFromRows(adminId: String, rows: List<GroupMemberEntity>) = GroupTeam(
    adminId,
    rows.map { it.botId }.toSet(),
    rows.mapNotNull { row -> row.supervisorBotId?.takeIf { id -> rows.any { it.botId == id } }?.let { row.botId to it } }.toMap(),
)

internal fun GroupChatEntity.toModel() = GroupChat(
    id = id,
    name = name,
    adminBotId = adminBotId,
    description = description,
    createdAt = createdAt,
    background = background, taskBudgetTokens = taskBudgetTokens, teamGroupId = teamGroupId, teamVersion = teamVersion,
    replyMode = runCatching { GroupReplyMode.valueOf(replyMode) }.getOrDefault(GroupReplyMode.ADMIN_FIRST),
    unifiedApprovalMode = unifiedApprovalMode,
    executionLimits = executionLimitsJson?.let { runCatching {
        kotlinx.serialization.json.Json.decodeFromString<top.tianyan.app.core.model.bot.GroupExecutionLimits>(it)
    }.getOrNull() } ?: top.tianyan.app.core.model.bot.GroupExecutionLimits(),
)

internal fun GroupChat.toEntity() = GroupChatEntity(
    id = id,
    name = name,
    adminBotId = adminBotId,
    description = description,
    createdAt = createdAt,
    background = background, taskBudgetTokens = taskBudgetTokens, teamGroupId = teamGroupId, teamVersion = teamVersion,
    replyMode = replyMode.name,
    unifiedApprovalMode = unifiedApprovalMode,
    executionLimitsJson = kotlinx.serialization.json.Json.encodeToString(executionLimits),
)
