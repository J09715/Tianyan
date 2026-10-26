package top.tianyan.app.harness.groupchat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.harness.MentionExtractor

/** Only fully prepared input can become a recoverable round; serialize admission per group. */
internal class GroupRoundAdmission(
    private val groups: GroupChatRepository,
    private val rounds: GroupRoundCoordinator,
    private val prepare: suspend (List<GroupAttachment>) -> PreparedGroupAttachments,
    private val notify: suspend (String, String) -> Unit,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun send(groupId: String, text: String, files: List<GroupAttachment>) {
        locks.getOrPut(groupId) { Mutex() }.withLock {
            val group = groups.getGroup(groupId) ?: return@withLock
            val members = groups.getMembers(groupId)
            val mentions = MentionExtractor.parse(text, members.map { it.name })
            val responders = openingResponders(members, mentions, group.adminBotId, group.replyMode)
            val message = GroupMessage(
                UUID.randomUUID().toString(), groupId, "user_me", "我", GroupSenderType.USER,
                text, System.currentTimeMillis(), attachments = files,
            )
            if (responders.isEmpty()) {
                groups.stageMessage(message, emptyList())
                notify(groupId, "⚠️ 当前群聊暂无可响应的 Bot 成员")
                return@withLock
            }
            // Show the user message and its attachments before copying; a round is committed
            // only once its input is complete, so recovery never sees an unprepared round.
            groups.stageMessage(message, emptyList())
            val prepared = try {
                if (files.isEmpty()) PreparedGroupAttachments()
                else prepare(files)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notify(groupId, "附件读取失败：${failure.message}")
                return@withLock
            }
            val trigger = buildString {
                if (text.isNotBlank()) appendLine(text)
                if (prepared.description.isNotBlank()) appendLine("【附件】\n${prepared.description}")
            }.ifBlank { "（用户发送了附件，请查看并处理）" }.trimEnd()
            val round = rounds.newRound(group, responders, trigger, prepared.imageUrls) ?: return@withLock
            groups.insertRound(round)
            rounds.resetLateRounds(groupId)
            rounds.launchPrepared(round)
        }
    }
}
