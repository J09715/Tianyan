package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.harness.MentionExtractor

/** Select context by persisted task ownership, never by model-written report text or display names. */
class GroupContextScope(
    private val groups: GroupChatRepository,
    private val assignments: BotAssignmentRepository,
    private val nativeTasks: top.tianyan.app.core.database.task.AgentTaskRepository? = null,
) {
    internal suspend fun workForReceipt(id: String?): String? {
        if (id == null) return null
        return assignments.get(id)?.rootWorkId ?: assignments.batchAssignments(id).firstOrNull()?.rootWorkId
    }

    internal suspend fun delegatedTargets(groupId: String, nativeTaskId: String?): Set<String> {
        val task = nativeTaskId?.let { nativeTasks?.find(it) } ?: return emptySet()
        if (task.groupId != groupId) return emptySet()
        return task.operationId?.let { assignments.dispatchedBots(groupId, it, task.id) }.orEmpty()
    }

    internal suspend fun workForNativeTask(nativeTaskId: String?): String? =
        nativeTaskId?.let { nativeTasks?.find(it)?.groupWorkId }

    internal suspend fun select(group: GroupChat, botId: String, sessionId: String, rows: List<GroupMessage>): List<GroupMessage> {
        val members = groups.getMembers(group.id)
        if (members.none { it.id == botId }) return emptyList()
        val tasks = assignments.observeForGroup(group.id).first()
        val current = tasks.firstOrNull { it.independent && it.sessionId == sessionId && it.botId == botId }
        val team = groups.getTeam(group.id)
        if (current == null && team.supervisors.isEmpty() && tasks.none { it.parentAssignmentId != null }) return rows.filter { it.groupId == group.id }
        val byId = tasks.associateBy { it.id }
        val batches = tasks.mapNotNull { it.batchId }.toSet()
        val relevantBatches = rows.asSequence().filter { it.senderType == GroupSenderType.SYSTEM }
            .mapNotNull { it.delegatedTaskId }.filter { it in batches }.distinct().toList()
            .associateWith { assignments.getBatch(it) }
        val allowedUserTargets = originalAncestors(botId, current, byId)
        fun received(task: BotAssignment): Boolean = task.reportToBotId == botId &&
            task.parentAssignmentId == current?.id
        return rows.filter { row ->
            if (row.groupId != group.id) return@filter false
            when (row.senderType) {
                GroupSenderType.USER -> current == null || relevantCorrection(row.content, members, allowedUserTargets)
                GroupSenderType.BOT -> {
                    val taskId = row.delegatedTaskId
                    if (taskId == null) current == null || row.senderId in allowedUserTargets
                    else byId[taskId]?.let { task ->
                        row.senderId == task.botId && (task.id == current?.id || received(task))
                    } == true
                }
                GroupSenderType.SYSTEM -> {
                    val id = row.delegatedTaskId ?: return@filter false
                    byId[id]?.let(::received) ?: relevantBatches[id]?.let { batch ->
                        batch.groupId == group.id && batch.requesterBotId == botId &&
                            if (current != null) batch.requesterSessionId == sessionId
                            else tasks.none { it.independent && it.sessionId == batch.requesterSessionId }
                    } ?: false
                }
            }
        }
    }

    internal suspend fun history(group: GroupChat, botId: String, sessionId: String, round: Boolean): String {
        val rows = select(group, botId, sessionId, groups.observeMessages(group.id).first())
        return if (round) formatRoundHistory(group, rows, groups.getSeenSeq(group.id, botId))
        else formatGroupHistory(group, rows)
    }

    private fun originalAncestors(botId: String, task: BotAssignment?, byId: Map<String, BotAssignment>): Set<String> {
        val ids = mutableSetOf(botId)
        val seen = mutableSetOf<String>()
        var current = task
        while (current != null && seen.add(current.id)) {
            current.reportToBotId?.let(ids::add)
            current = current.parentAssignmentId?.let(byId::get)
        }
        return ids
    }

    private fun relevantCorrection(text: String, members: List<Bot>, allowedIds: Set<String>): Boolean {
        val mentions = MentionExtractor.parse(text, members.map { it.name })
        val addressed = members.filter { member ->
            MentionExtractor.parse("@${member.name}", listOf(member.name)).any { it in mentions }
        }.map { it.id }.toSet()
        return addressed.isEmpty() || addressed.any { it in allowedIds }
    }
}
